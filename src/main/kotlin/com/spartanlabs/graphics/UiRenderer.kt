package com.spartanlabs.graphics

import com.spartanlabs.graphics.ui.Color
import com.spartanlabs.graphics.ui.Element
import com.spartanlabs.graphics.ui.Label
import com.spartanlabs.graphics.ui.PositionedElement
import com.spartanlabs.graphics.ui.Scene
import com.spartanlabs.graphics.ui.flatten
import com.spartanlabs.networking.NdcConverter
import org.lwjgl.opengl.GL33.*
import org.lwjgl.stb.STBEasyFont.stb_easy_font_print
import org.lwjgl.system.MemoryUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

/** Shared slf4j logger for all [UiRenderer] instances. */
private val uiLog: Logger = LoggerFactory.getLogger(UiRenderer::class.java)

/**
 * Draws a [Scene] of UI [Element]s on top of the rendered world.
 *
 * UI lives in fixed screen space (window pixels, origin top-left) - it never
 * pans or zooms with the camera - so it owns its own quad geometry and
 * shaders rather than sharing [Window]'s actor pipeline:
 *
 * - a **quad shader** that fills each element with its [Color], optionally
 *   multiplied by a texture (so a texture tints toward its element's colour);
 * - a **text shader** plus [org.lwjgl.stb.STBEasyFont] for [Label] glyphs,
 *   which needs no font file or glyph atlas - the geometry is generated on
 *   the CPU each frame.
 *
 * All GL calls assume a current context on the calling thread, same as
 * [Window]; [Window] owns this object's lifecycle and calls [initialize]
 * during setup and [close] on teardown.
 */
internal class UiRenderer {

    // Quad pipeline (element backgrounds / textures)
    private var quadVao = 0
    private var quadVbo = 0
    private var quadEbo = 0
    private var quadShader = 0

    // Text pipeline (Label glyphs)
    private var textVao = 0
    private var textVbo = 0
    private var textShader = 0

    // Element textures, keyed by Element.texture's name; an untextured element
    // (null) gets the cache's white fallback so the shader can always sample
    // (white * uColor == uColor).
    private val textures = TextureCache()

    // Off-heap scratch buffer STBEasyFont fills with glyph quads. Allocated
    // once and reused every frame; freed in close().
    private var glyphQuads: ByteBuffer? = null

    private val quadVertexShaderSource = """
        #version 330 core
        layout (location = 0) in vec2 aPos;
        layout (location = 1) in vec2 aTexCoord;

        out vec2 vTexCoord;

        uniform vec2 uOffset;
        uniform vec2 uHalfSize;

        void main() {
            gl_Position = vec4(aPos * uHalfSize + uOffset, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val quadFragmentShaderSource = """
        #version 330 core
        in vec2 vTexCoord;
        out vec4 FragColor;

        uniform sampler2D uTexture;
        uniform vec4 uColor;

        void main() {
            FragColor = uColor * texture(uTexture, vTexCoord);
        }
    """.trimIndent()

    private val textVertexShaderSource = """
        #version 330 core
        layout (location = 0) in vec2 aPos; // already in NDC

        void main() {
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    private val textFragmentShaderSource = """
        #version 330 core
        out vec4 FragColor;

        uniform vec4 uColor;

        void main() {
            FragColor = uColor;
        }
    """.trimIndent()

    /**
     * Compiles both shaders and builds the reusable quad geometry, white
     * fallback texture, and glyph scratch buffer.
     * @return [Result.success] once the renderer is ready to [render], or the
     * failure that prevented it (a shader that would not compile/link)
     */
    fun initialize(): Result<Unit> = runCatching {
        quadShader = Shaders.link(quadVertexShaderSource, quadFragmentShaderSource)
        textShader = Shaders.link(textVertexShaderSource, textFragmentShaderSource)

        createQuadGeometry()
        createTextGeometry()
        textures.initialize()
        glyphQuads = MemoryUtil.memAlloc(GLYPH_BUFFER_BYTES)
    }.onFailure { cause -> uiLog.error("Could not initialize the UI renderer", cause) }

    /**
     * Draws every element of [scene] back-to-front, resolving panel-relative
     * child positions first (see [flatten]). Assumes alpha blending is
     * already enabled by the caller.
     */
    fun render(scene: Scene, windowWidth: Int, windowHeight: Int) {
        if (scene.isEmpty()) return
        uiLog.trace("Rendering {} UI element(s)", scene.size)

        for (positioned in scene.flatten(windowWidth, windowHeight)) {
            drawElementQuad(positioned, windowWidth, windowHeight)
            val element = positioned.element
            if (element is Label && element.text.isNotEmpty()) {
                drawLabelText(element, positioned, windowWidth, windowHeight)
            }
        }
    }

    /**
     * Releases every GL resource this renderer owns, including all cached
     * element textures and the off-heap glyph buffer. Safe to call once.
     */
    fun close() {
        glDeleteVertexArrays(quadVao)
        glDeleteBuffers(quadVbo)
        glDeleteBuffers(quadEbo)
        glDeleteProgram(quadShader)

        glDeleteVertexArrays(textVao)
        glDeleteBuffers(textVbo)
        glDeleteProgram(textShader)

        textures.close()

        glyphQuads?.let(MemoryUtil::memFree)
        glyphQuads = null
    }

    // ---------------------------------------------------------------
    // Element quad
    // ---------------------------------------------------------------

    private fun drawElementQuad(positioned: PositionedElement, windowWidth: Int, windowHeight: Int) {
        val element = positioned.element

        // A zero-size element (the default Square) has nothing to draw - skip
        // it so a bare container Panel doesn't paint a degenerate quad.
        if (positioned.width <= 0.0 || positioned.height <= 0.0) return
        if (element.color == Color.TRANSPARENT && element.texture == null) return

        glUseProgram(quadShader)

        val (offsetX, offsetY) = NdcConverter.topLeftOffset(
            positioned.x, positioned.y, positioned.width, positioned.height, windowWidth, windowHeight
        )
        glUniform2f(glGetUniformLocation(quadShader, "uOffset"), offsetX, offsetY)

        val (halfW, halfH) = NdcConverter.halfSize(
            positioned.width, positioned.height, windowWidth, windowHeight
        )
        glUniform2f(glGetUniformLocation(quadShader, "uHalfSize"), halfW, halfH)

        val (r, g, b, a) = element.color.normalized()
        glUniform4f(glGetUniformLocation(quadShader, "uColor"), r, g, b, a)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, textures.handleFor(element.texture))
        glUniform1i(glGetUniformLocation(quadShader, "uTexture"), 0)

        glBindVertexArray(quadVao)
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L)
        glBindVertexArray(0)
    }

    // ---------------------------------------------------------------
    // Label text (STBEasyFont)
    // ---------------------------------------------------------------

    private fun drawLabelText(
        label: Label,
        positioned: PositionedElement,
        windowWidth: Int,
        windowHeight: Int
    ) {
        val buffer = glyphQuads ?: return
        buffer.clear()

        val quadCount = stb_easy_font_print(0f, 0f, label.text, null, buffer)
        if (quadCount <= 0) return

        val triangles = glyphTrianglesToNdc(
            buffer, quadCount,
            originXPx = positioned.x + TEXT_PADDING_PX,
            originYPx = positioned.y + TEXT_PADDING_PX,
            windowWidth, windowHeight
        )

        glUseProgram(textShader)
        val (r, g, b, a) = label.textColor.normalized()
        glUniform4f(glGetUniformLocation(textShader, "uColor"), r, g, b, a)

        glBindVertexArray(textVao)
        glBindBuffer(GL_ARRAY_BUFFER, textVbo)
        glBufferData(GL_ARRAY_BUFFER, triangles, GL_DYNAMIC_DRAW)
        glDrawArrays(GL_TRIANGLES, 0, triangles.size / 2)
        glBindVertexArray(0)
    }

    /**
     * Converts STBEasyFont's packed quad output into a flat NDC triangle-list
     * (two triangles per quad). Each source vertex is 16 bytes - `x, y, z`
     * floats then a packed RGBA int - laid out 4 per quad; only `x, y` (in
     * font pixels, Y down, relative to the print origin) are used here.
     */
    private fun glyphTrianglesToNdc(
        buffer: ByteBuffer,
        quadCount: Int,
        originXPx: Double,
        originYPx: Double,
        windowWidth: Int,
        windowHeight: Int
    ): FloatArray {
        val out = FloatArray(quadCount * TRIANGLE_VERTS_PER_QUAD * 2)
        var w = 0

        fun emit(vertexByteOffset: Int) {
            val fontX = buffer.getFloat(vertexByteOffset)
            val fontY = buffer.getFloat(vertexByteOffset + 4)
            val screenX = (originXPx + fontX * TEXT_SCALE).toFloat()
            val screenY = (originYPx + fontY * TEXT_SCALE).toFloat()
            val (ndcX, ndcY) = NdcConverter.pixelToNdc(screenX, screenY, windowWidth, windowHeight)
            out[w++] = ndcX
            out[w++] = ndcY
        }

        for (quad in 0 until quadCount) {
            val base = quad * BYTES_PER_QUAD
            val v0 = base
            val v1 = base + BYTES_PER_GLYPH_VERTEX
            val v2 = base + BYTES_PER_GLYPH_VERTEX * 2
            val v3 = base + BYTES_PER_GLYPH_VERTEX * 3
            // quad (v0,v1,v2,v3) -> triangles (v0,v1,v2) + (v0,v2,v3)
            emit(v0); emit(v1); emit(v2)
            emit(v0); emit(v2); emit(v3)
        }
        return out
    }

    // ---------------------------------------------------------------
    // GL resource setup
    // ---------------------------------------------------------------

    private fun createQuadGeometry() {
        val vertices = floatArrayOf(
            // positions   // texcoords
            -1f, 1f, 0.0f, 1.0f,
            1f, 1f, 1.0f, 1.0f,
            1f, -1f, 1.0f, 0.0f,
            -1f, -1f, 0.0f, 0.0f
        )
        val indices = intArrayOf(0, 1, 2, 2, 3, 0)

        quadVao = glGenVertexArrays()
        quadVbo = glGenBuffers()
        quadEbo = glGenBuffers()

        glBindVertexArray(quadVao)

        glBindBuffer(GL_ARRAY_BUFFER, quadVbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, quadEbo)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW)

        val stride = 4 * java.lang.Float.BYTES
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * java.lang.Float.BYTES)
        glEnableVertexAttribArray(1)

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    private fun createTextGeometry() {
        textVao = glGenVertexArrays()
        textVbo = glGenBuffers()

        glBindVertexArray(textVao)
        glBindBuffer(GL_ARRAY_BUFFER, textVbo)
        // No glBufferData yet - drawLabelText() re-specs it every frame.
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * java.lang.Float.BYTES, 0L)
        glEnableVertexAttribArray(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    private companion object {
        /** Screen pixels per STBEasyFont pixel - the font is tiny at 1:1. */
        const val TEXT_SCALE = 2.0

        /** Inset of a label's text from its top-left corner, in screen pixels. */
        const val TEXT_PADDING_PX = 4.0

        const val BYTES_PER_GLYPH_VERTEX = 16
        const val BYTES_PER_QUAD = BYTES_PER_GLYPH_VERTEX * 4
        const val TRIANGLE_VERTS_PER_QUAD = 6

        /** ~1500 glyph quads of scratch space - far more than any one label needs. */
        const val GLYPH_BUFFER_BYTES = 1500 * BYTES_PER_QUAD
    }
}
