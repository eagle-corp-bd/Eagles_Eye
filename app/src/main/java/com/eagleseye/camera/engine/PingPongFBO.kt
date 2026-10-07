package com.eagleseye.camera.engine

import android.opengl.GLES30

class PingPongFBO(val width: Int, val height: Int, val label: String = "pp") {
    val fbo = IntArray(2); val tex = IntArray(2)
    private var readIdx = 0
    var isComplete = false
        private set
    val readTex: Int get() = tex[readIdx]
    val writeFbo: Int get() = fbo[1 - readIdx]

    init {
        GLES30.glGenFramebuffers(2, fbo, 0); GLES30.glGenTextures(2, tex, 0)
        var ok = width > 0 && height > 0
        if (!ok) GpuDebugLog.logError("FBO", "$label invalid size ${width}x$height")
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
            if (!GpuDebugLog.checkFbo("$label[$i] ${width}x$height")) ok = false
            // Deterministic initial content so a missed write never samples garbage.
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        isComplete = ok
        if (ok) GpuDebugLog.logOnce("fbo-ok-$label-${width}x$height", "FBO", "$label complete ${width}x$height")
    }

    fun swap() { readIdx = 1 - readIdx }
    fun destroy() { GLES30.glDeleteFramebuffers(2, fbo, 0); GLES30.glDeleteTextures(2, tex, 0) }
}
