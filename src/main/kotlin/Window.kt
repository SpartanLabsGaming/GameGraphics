import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33.*
import org.lwjgl.stb.STBImage.*
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.NULL

/**
 * Owns the GLFW window, the OpenGL context, and everything needed to render
 * quads to the screen. Has no knowledge of networking or where quad data
 * comes from — callers simply pass a list of [Quad]s into [render] each frame.
 *
 * [onMouseAction] is invoked on every mouse move/press/release, letting the
 * caller (e.g. Main, wiring it to a NetworkClient) decide what to do with
 * mouse input without Window needing any knowledge of networking.
 */
class Window(
    private val title: String,
    private val texturePath: String,
    private val onMouseAction: (MouseAction) -> Unit
) {
    private var handle: Long = 0

    // Detected from the monitor at open()
    private var width = 0
    private var height = 0

    // GPU handles
    private var vao = 0
    private var vbo = 0
    private var ebo = 0
    private var shaderProgram = 0
    private var textureId = 0

    private val vertexShaderSource = """
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

    private val fragmentShaderSource = """
        #version 330 core
        in vec2 vTexCoord;
        out vec4 FragColor;

        uniform sampler2D uTexture;

        void main() {
            FragColor = texture(uTexture, vTexCoord);
        }
    """.trimIndent()

    /** Creates the window, OpenGL context, shaders, quad geometry, and texture. */
    fun open() {
        GLFWErrorCallback.createPrint(System.err).set()

        if (!glfwInit()) {
            throw IllegalStateException("Unable to initialize GLFW")
        }

        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)

        // Detect the primary monitor's current resolution and size the
        // window to match it (borderless, fills the screen exactly).
        val primaryMonitor = glfwGetPrimaryMonitor()
        val vidmode = glfwGetVideoMode(primaryMonitor)
            ?: throw RuntimeException("Unable to detect monitor video mode")

        width = vidmode.width()
        height = vidmode.height()

        glfwWindowHint(GLFW_REFRESH_RATE, vidmode.refreshRate())
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE)

        handle = glfwCreateWindow(width, height, title, NULL, NULL)
        if (handle == NULL) {
            throw RuntimeException("Failed to create the GLFW window")
        }

        stackPush().use { stack ->
            val monX = stack.mallocInt(1)
            val monY = stack.mallocInt(1)
            glfwGetMonitorPos(primaryMonitor, monX, monY)
            glfwSetWindowPos(handle, monX.get(0), monY.get(0))
        }

        glfwSetKeyCallback(handle) { win, key, _, action, _ ->
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(win, true)
            }
        }

        glfwSetFramebufferSizeCallback(handle) { _, w, h ->
            glViewport(0, 0, w, h)
            width = w
            height = h
        }

        // Cursor movement -> MOVE events, throttled to at most 60 per second.
        // GLFW can fire this callback far more often than that on a fast
        // mouse/high-poll-rate device; there's no benefit sending more MOVE
        // events than the display (or server tick rate) can use.
        var lastMoveSentTime = 0.0
        val moveIntervalSeconds = 1.0 / 60.0

        glfwSetCursorPosCallback(handle) { _, xpos, ypos ->
            val now = glfwGetTime()
            if (now - lastMoveSentTime >= moveIntervalSeconds) {
                lastMoveSentTime = now
                onMouseAction(MouseAction(MouseActionType.MOVE, button = -1, x = xpos, y = ypos))
            }
        }

        // Mouse button press/release -> PRESS/RELEASE events, tagged with the
        // cursor position at the time of the click. Never throttled — these
        // are discrete, low-frequency events that must not be dropped.
        glfwSetMouseButtonCallback(handle) { win, button, action, _ ->
            stackPush().use { stack ->
                val xBuf = stack.mallocDouble(1)
                val yBuf = stack.mallocDouble(1)
                glfwGetCursorPos(win, xBuf, yBuf)

                val type = when (action) {
                    GLFW_PRESS -> MouseActionType.PRESS
                    GLFW_RELEASE -> MouseActionType.RELEASE
                    else -> return@glfwSetMouseButtonCallback // ignore GLFW_REPEAT, etc.
                }

                onMouseAction(MouseAction(type, button, xBuf.get(0), yBuf.get(0)))
            }
        }

        glfwMakeContextCurrent(handle)
        glfwSwapInterval(1) // v-sync
        glfwShowWindow(handle)

        GL.createCapabilities()

        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glClearColor(0.1f, 0.1f, 0.15f, 1.0f)

        createShaderProgram()
        createQuadGeometry()
        textureId = loadTexture(texturePath)
    }

    /** True once the user has requested the window close (e.g. pressed Escape). */
    fun shouldClose(): Boolean = glfwWindowShouldClose(handle)

    /** Pumps the GLFW event queue. Call once per frame. */
    fun pollEvents() = glfwPollEvents()

    /** Clears the screen, draws every quad in [quads] at its given position, and presents the frame. */
    fun render(quads: List<Quad>) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        for (quad in quads) {
            drawQuad(quad.x, quad.y, quad.width, quad.height)
        }

        glfwSwapBuffers(handle)
    }

    /** Releases all GPU and GLFW resources. Call once before exiting. */
    fun close() {
        glDeleteVertexArrays(vao)
        glDeleteBuffers(vbo)
        glDeleteBuffers(ebo)
        glDeleteTextures(textureId)
        glDeleteProgram(shaderProgram)

        org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(handle)
        glfwDestroyWindow(handle)

        glfwTerminate()
        glfwSetErrorCallback(null)?.free()
    }

    // ---------------------------------------------------------------
    // Internal rendering details
    // ---------------------------------------------------------------

    private fun drawQuad(x: Double, y: Double, widthPx: Double, heightPx: Double) {
        glUseProgram(shaderProgram)

        // Convert the quad's pixel-space position into an NDC offset.
        // NDC Y is up, while y increases downward, hence the negation.
        val offsetX = (2.0 * x / width).toFloat()
        val offsetY = (-2.0 * y / height).toFloat()
        glUniform2f(glGetUniformLocation(shaderProgram, "uOffset"), offsetX, offsetY)

        // Convert this quad's pixel size into an NDC half-extent, based on
        // the current window size, so it renders at the exact requested
        // pixel dimensions regardless of resolution.
        val halfSizeX = (widthPx / width).toFloat()
        val halfSizeY = (heightPx / height).toFloat()
        glUniform2f(glGetUniformLocation(shaderProgram, "uHalfSize"), halfSizeX, halfSizeY)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, textureId)
        glUniform1i(glGetUniformLocation(shaderProgram, "uTexture"), 0)

        glBindVertexArray(vao)
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L)
        glBindVertexArray(0)
    }

    private fun createShaderProgram() {
        val vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource)

        shaderProgram = glCreateProgram()
        glAttachShader(shaderProgram, vertexShader)
        glAttachShader(shaderProgram, fragmentShader)
        glLinkProgram(shaderProgram)

        if (glGetProgrami(shaderProgram, GL_LINK_STATUS) == GL_FALSE) {
            throw RuntimeException(
                "Shader program link failed:\n" + glGetProgramInfoLog(shaderProgram)
            )
        }

        glDeleteShader(vertexShader)
        glDeleteShader(fragmentShader)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw RuntimeException(
                "Shader compile failed:\n" + glGetShaderInfoLog(shader)
            )
        }
        return shader
    }

    private fun createQuadGeometry() {
        // A unit square using +/-1 corner directions. The vertex shader
        // scales this by uHalfSize (set per quad, per draw call) to reach
        // the actual on-screen size, so this buffer is created once and
        // reused for every quad regardless of its individual width/height.
        val vertices = floatArrayOf(
            // positions   // texcoords
            -1f,  1f,      0.0f, 1.0f, // top-left
            1f,  1f,      1.0f, 1.0f, // top-right
            1f, -1f,      1.0f, 0.0f, // bottom-right
            -1f, -1f,      0.0f, 0.0f  // bottom-left
        )

        val indices = intArrayOf(
            0, 1, 2,
            2, 3, 0
        )

        vao = glGenVertexArrays()
        vbo = glGenBuffers()
        ebo = glGenBuffers()

        glBindVertexArray(vao)

        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW)

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW)

        val stride = 4 * java.lang.Float.BYTES

        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L)
        glEnableVertexAttribArray(0)

        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * java.lang.Float.BYTES)
        glEnableVertexAttribArray(1)

        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)
    }

    private fun loadTexture(path: String): Int {
        stackPush().use { stack ->
            val imgWidth = stack.mallocInt(1)
            val imgHeight = stack.mallocInt(1)
            val channels = stack.mallocInt(1)

            stbi_set_flip_vertically_on_load(true)
            val image = stbi_load(path, imgWidth, imgHeight, channels, 4)
                ?: throw RuntimeException("Failed to load texture: $path (${stbi_failure_reason()})")

            val texId = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, texId)

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

            glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGBA8,
                imgWidth.get(0), imgHeight.get(0), 0,
                GL_RGBA, GL_UNSIGNED_BYTE, image
            )
            glGenerateMipmap(GL_TEXTURE_2D)

            stbi_image_free(image)
            glBindTexture(GL_TEXTURE_2D, 0)

            return texId
        }
    }
}