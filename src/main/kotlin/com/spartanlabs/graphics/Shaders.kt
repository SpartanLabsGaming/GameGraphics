package com.spartanlabs.graphics

import org.lwjgl.opengl.GL33.*

/**
 * Compiles and links GLSL shader programs. Shared by [Window] (the actor
 * shader) and [UiRenderer] (the UI quad and text shaders) so the
 * compile/link/error-check boilerplate lives in exactly one place.
 *
 * Every function here assumes a current GL context on the calling thread and
 * throws [RuntimeException] with the driver's info log on any compile or link
 * failure, so callers can wrap a whole setup step in a single `runCatching`.
 */
internal object Shaders {

    /**
     * Compiles [vertexSource] and [fragmentSource] and links them into a
     * program, deleting the intermediate shader objects once linked.
     * @return the linked program's GL handle
     */
    fun link(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compile(GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compile(GL_FRAGMENT_SHADER, fragmentSource)

        val program = glCreateProgram()
        glAttachShader(program, vertexShader)
        glAttachShader(program, fragmentShader)
        glLinkProgram(program)

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            throw RuntimeException("Shader program link failed:\n" + glGetProgramInfoLog(program))
        }

        glDeleteShader(vertexShader)
        glDeleteShader(fragmentShader)
        return program
    }

    /** @return the compiled shader's GL handle */
    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw RuntimeException("Shader compile failed:\n" + glGetShaderInfoLog(shader))
        }
        return shader
    }
}
