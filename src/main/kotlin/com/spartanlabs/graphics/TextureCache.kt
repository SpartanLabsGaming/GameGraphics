package com.spartanlabs.graphics

import org.lwjgl.opengl.GL33.*
import org.lwjgl.stb.STBImage.*
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

private val log: Logger = LoggerFactory.getLogger(TextureCache::class.java)

/** Classpath directory every texture name is looked up under. */
private const val TEXTURE_DIR = "textures"

/**
 * Loads texture images onto the GPU once and hands back the GL handle on
 * every later request for the same name. Shared by [Window] (actor textures,
 * whose names come from the server) and [UiRenderer] (element textures).
 *
 * A name is a bare file name resolved against the `/[TEXTURE_DIR]` classpath
 * folder - `"checkerboard.png"` loads `/textures/checkerboard.png`, packaged
 * from `src/main/resources/textures/`. A name with no such resource, or one
 * that will not decode, falls back to a 1x1 opaque-white texture (logged
 * once, not once per frame) so the quad shader can always sample and
 * `white * uColor` just shows the colour.
 *
 * All calls assume a current GL context on the calling thread; the owner
 * calls [initialize] during setup and [close] on teardown.
 */
internal class TextureCache {

    private val byName = HashMap<String, Int>()
    private var white = 0

    /** Builds the white fallback texture. Call once, before [handleFor]. */
    fun initialize() {
        white = createWhiteTexture()
    }

    /**
     * The GL texture handle for [name] - uploaded and cached on first use,
     * the white fallback (logged once) if it cannot be found or decoded. A
     * null [name] is the fallback with no warning.
     */
    fun handleFor(name: String?): Int {
        if (name == null) return white
        return byName.getOrPut(name) {
            runCatching { load(name) }
                .onFailure { cause -> log.warn("Texture '{}' unavailable, drawing it blank: {}", name, cause.message) }
                .getOrDefault(white)
        }
    }

    /** Deletes every uploaded texture and the white fallback. Safe to call once. */
    fun close() {
        (byName.values.toSet() + white).forEach(::glDeleteTextures)
        byName.clear()
        white = 0
    }

    /** @return a 1x1 opaque-white texture's GL handle */
    private fun createWhiteTexture(): Int {
        val texId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, texId)
        stackPush().use { stack ->
            val pixel = stack.malloc(4).put(byteArrayOf(-1, -1, -1, -1)).flip()
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel)
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glBindTexture(GL_TEXTURE_2D, 0)
        return texId
    }

    /**
     * Reads `/[TEXTURE_DIR]/[name]` off the classpath, decodes it, and uploads
     * it as a mipmapped RGBA texture.
     * @return the new texture's GL handle
     * @throws RuntimeException if the resource is missing or will not decode
     */
    private fun load(name: String): Int {
        val fileBytes = readClasspathResource("/$TEXTURE_DIR/$name")
        try {
            stackPush().use { stack ->
                val imgWidth = stack.mallocInt(1)
                val imgHeight = stack.mallocInt(1)
                val channels = stack.mallocInt(1)

                stbi_set_flip_vertically_on_load(true)
                val image = stbi_load_from_memory(fileBytes, imgWidth, imgHeight, channels, 4)
                    ?: throw RuntimeException("could not decode image (${stbi_failure_reason()})")

                val texId = glGenTextures()
                glBindTexture(GL_TEXTURE_2D, texId)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
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
        } finally {
            MemoryUtil.memFree(fileBytes)
        }
    }

    /** Reads a classpath resource into a freshly allocated native buffer the caller must free. */
    private fun readClasspathResource(path: String): ByteBuffer {
        val bytes = TextureCache::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            ?: throw RuntimeException("no classpath resource '$path'")
        return MemoryUtil.memAlloc(bytes.size).put(bytes).flip()
    }
}
