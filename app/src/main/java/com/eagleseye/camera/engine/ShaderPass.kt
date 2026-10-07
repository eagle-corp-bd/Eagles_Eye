package com.eagleseye.camera.engine

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class ShaderPass(
    vertexSrc: String,
    fragmentSrc: String,
    val passName: String = "unnamed"
) {
    val program: Int
    val quadVao: Int
    val isValid: Boolean
    private val uniformCache = mutableMapOf<String, Int>()

    init {
        val p = IntArray(1)
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* drain stale errors */ }
        program = compileProgram(normalizeGlsl(vertexSrc), normalizeGlsl(fragmentSrc))
        if (program != 0) {
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, p, 0)
        }
        isValid = program != 0 && p[0] != 0
        if (!isValid) {
            // Detailed VERT/FRAG/LINK reason (if any) is already in lastError —
            // only append this summary when no richer message was captured.
            val msg = "ShaderPass invalid: name=$passName prog=$program link=${p[0]}"
            android.util.Log.e("GL", msg)
            if (GpuDebugLog.lastError.isEmpty() || GpuDebugLog.lastError.startsWith("ShaderPass invalid")) {
                GpuDebugLog.logError("SHADER", msg)
            } else {
                GpuDebugLog.log("SHADER", msg)
            }
        }
        quadVao = createFullscreenQuad()
    }

    /**
     * Issues the draw. Returns true only when the program was valid and the
     * draw was actually submitted — callers that ping-pong must swap only on
     * true so a failed pass never advances onto an uninitialized texture.
     */
    fun draw(
        inputTex: Int,
        inputTex2: Int = -1,
        inputTex3: Int = -1,
        outputFbo: Int = 0,
        width: Int,
        height: Int,
        texTarget: Int = GLES30.GL_TEXTURE_2D,
        block: ShaderPass.() -> Unit
    ): Boolean {
        if (!isValid) {
            GpuDebugLog.logOnce("draw-skip-$passName", "SHADER", "draw skipped (invalid): $passName")
            return false
        }
        GLES30.glUseProgram(program)
        if (outputFbo != 0) GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        else GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        if (inputTex >= 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(texTarget, inputTex)
            setInt("uTexture", 0)
        }
        if (inputTex2 >= 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTex2)
            setInt("uTexture2", 1)
        }
        if (inputTex3 >= 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTex3)
            setInt("uDepthMap", 2)
        }
        setFloat2("uResolution", width.toFloat(), height.toFloat())
        block()
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        return true
    }

    fun setFloat(n: String, v: Float) { GLES30.glUniform1f(loc(n), v) }
    fun setFloat2(n: String, x: Float, y: Float) { GLES30.glUniform2f(loc(n), x, y) }
    fun setFloat3(n: String, x: Float, y: Float, z: Float) { GLES30.glUniform3f(loc(n), x, y, z) }
    fun setFloat4(n: String, x: Float, y: Float, z: Float, w: Float) { GLES30.glUniform4f(loc(n), x, y, z, w) }
    fun setInt(n: String, v: Int) { GLES30.glUniform1i(loc(n), v) }
    fun setMat4(n: String, m: FloatArray) { GLES30.glUniformMatrix4fv(loc(n), 1, false, m, 0) }
    fun setTexture(name: String, tex: Int, unit: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        setInt(name, unit)
    }

    private fun loc(n: String) = uniformCache.getOrPut(n) { GLES30.glGetUniformLocation(program, n) }

    /**
     * Kotlin raw strings often start with a newline before #version. GLES requires
     * #version as the first token (only whitespace/comments may precede it); some
     * Mali drivers reject a leading blank line outright.
     */
    private fun normalizeGlsl(src: String): String {
        var s = src.trimStart('\u0000', '\uFEFF')
        // Strip everything before the first #version line, then keep from #version on.
        val idx = s.indexOf("#version")
        if (idx > 0) s = s.substring(idx)
        else if (idx < 0) s = s.trimStart()
        // Collapse any remaining leading whitespace so #version is column 0.
        while (s.isNotEmpty() && s[0].isWhitespace()) s = s.substring(1)
        return s
    }

    private fun compileProgram(vs: String, fs: String): Int {
        val v = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER)
        if (v == 0) { logFail("create-vertex", "glCreateShader returned 0 glErr=${GLES30.glGetError()}"); return 0 }
        GLES30.glShaderSource(v, vs); GLES30.glCompileShader(v)
        val f = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER)
        if (f == 0) { GLES30.glDeleteShader(v); logFail("create-fragment", "glCreateShader returned 0 glErr=${GLES30.glGetError()}"); return 0 }
        GLES30.glShaderSource(f, fs); GLES30.glCompileShader(f)
        val cv = IntArray(1); GLES30.glGetShaderiv(v, GLES30.GL_COMPILE_STATUS, cv, 0)
        val cf = IntArray(1); GLES30.glGetShaderiv(f, GLES30.GL_COMPILE_STATUS, cf, 0)
        if (cv[0] == 0) {
            val info = GLES30.glGetShaderInfoLog(v) ?: ""
            android.util.Log.e("GL", "VERT($passName): $info")
            GpuDebugLog.logError("SHADER", "VERT compile fail name=$passName\n$info")
            GLES30.glDeleteShader(v); GLES30.glDeleteShader(f); return 0
        }
        if (cf[0] == 0) {
            val info = GLES30.glGetShaderInfoLog(f) ?: ""
            android.util.Log.e("GL", "FRAG($passName): $info")
            GpuDebugLog.logError("SHADER", "FRAG compile fail name=$passName\n$info")
            GLES30.glDeleteShader(v); GLES30.glDeleteShader(f); return 0
        }
        val prog = GLES30.glCreateProgram()
        if (prog == 0) { GLES30.glDeleteShader(v); GLES30.glDeleteShader(f); logFail("create-program", "glCreateProgram returned 0 glErr=${GLES30.glGetError()}"); return 0 }
        GLES30.glAttachShader(prog, v); GLES30.glAttachShader(prog, f)
        // Fullscreen quad VAO always feeds aPosition at location 0 — bind it
        // explicitly so link succeeds and the VAO pointer matches on every GPU.
        GLES30.glBindAttribLocation(prog, 0, "aPosition")
        GLES30.glLinkProgram(prog)
        val lp = IntArray(1); GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, lp, 0)
        if (lp[0] == 0) {
            val info = GLES30.glGetProgramInfoLog(prog) ?: ""
            android.util.Log.e("GL", "LINK($passName): $info")
            GpuDebugLog.logError("SHADER", "LINK fail name=$passName\n$info")
            GLES30.glDeleteProgram(prog); GLES30.glDeleteShader(v); GLES30.glDeleteShader(f); return 0
        }
        GLES30.glDeleteShader(v); GLES30.glDeleteShader(f)
        GpuDebugLog.logOnce("ok-$passName", "SHADER", "compiled OK: $passName prog=$prog")
        return prog
    }

    private fun logFail(stage: String, detail: String) {
        GpuDebugLog.logError("SHADER", "$stage fail name=$passName: $detail")
    }

    private fun createFullscreenQuad(): Int {
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val vao = IntArray(1); val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0); GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        val buf: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).also { it.position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        return vao[0]
    }

    fun destroy() { GLES30.glDeleteProgram(program) }
}
