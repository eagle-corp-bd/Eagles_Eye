package com.eagleseye.camera.editor

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import com.eagleseye.camera.engine.GlPixel
import com.eagleseye.camera.engine.ShaderPass
import com.eagleseye.camera.engine.Shaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/** Headless GL editor: renders a photo through an [EditorOp] chain on a
 *  dedicated thread with its own EGL pbuffer context — no view, no surface
 *  lifecycle, no races. This is the exact render path export uses, so the
 *  preview and the saved file always match. Context loss (sleep/wake) is
 *  detected per job and the context is rebuilt. Any failure surfaces as
 *  `null`, never as a black frame. */
object OffscreenEditor {

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "eagles-offscreen-gl") }

    private var disp: EGLDisplay? = null
    private var ctx: EGLContext? = null
    private var surf: EGLSurface? = null

    private var passCache = HashMap<String, ShaderPass>()
    private var lutTex = 0
    private var depthTex = 0

    private val FRAG_BLEND = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uMix;
in vec2 vUV;out vec4 fragColor;
void main(){fragColor=vec4(texture(uTexture,vUV).rgb*uMix,1.);}
"""

    private val FRAG_FRAME = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uTexture2;uniform float uMix;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);vec4 f=texture(uTexture2,vUV);
 vec3 o=mix(c.rgb,f.rgb,f.a*uMix);
 fragColor=vec4(o,c.a);
}
"""

    /** Renders `ops` over `src` at full resolution. `curves` are the four
     *  per-channel control points (sampled into a LUT here); `depth` feeds the
     *  depthblur pass. Returns null on any failure (never a black frame). */
    fun render(src: Bitmap, ops: List<EditorOp>, curves: List<List<FloatArray>>?, depth: Bitmap?): Bitmap? {
        val fut = exec.submit<Bitmap?> {
            try {
                if (!makeCurrent()) return@submit null
                doRender(src, ops, curves, depth)
            } catch (t: Throwable) {
                android.util.Log.w("EAGLES_EDIT", "offscreen render failed: ${t.message}")
                teardown()
                null
            }
        }
        return try { fut.get() } catch (e: Throwable) { null }
    }

    private fun makeCurrent(): Boolean {
        val d = disp; val s = surf; val c = ctx
        if (d != null && s != null && c != null) {
            if (EGL14.eglGetCurrentDisplay() == d && EGL14.eglGetCurrentContext() == c) return true
            if (EGL14.eglMakeCurrent(d, s, s, c)) return true
        }
        teardown()
        return initContext()
    }

    private fun teardown() {
        try {
            if (disp != null && ctx != null) EGL14.eglDestroyContext(disp, ctx)
            if (disp != null && surf != null) EGL14.eglDestroySurface(disp, surf)
            if (disp != null) EGL14.eglTerminate(disp)
        } catch (_: Throwable) {}
        disp = null; ctx = null; surf = null
        passCache = HashMap(); lutTex = 0; depthTex = 0
    }

    private fun initContext(): Boolean {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (d == EGL14.EGL_NO_DISPLAY) return false
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(d, ver, 0, ver, 1)) return false
        val cfgAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT or 0x0040, // EGL_OPENGL_ES3_BIT
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val cfg = arrayOfNulls<android.opengl.EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(d, cfgAttr, 0, cfg, 0, 1, n, 0) || n[0] < 1) return false
        val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val c = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0) ?: return false
        val sAttr = intArrayOf(EGL14.EGL_WIDTH, 4, EGL14.EGL_HEIGHT, 4, EGL14.EGL_NONE)
        val s = EGL14.eglCreatePbufferSurface(d, cfg[0], sAttr, 0) ?: return false
        if (!EGL14.eglMakeCurrent(d, s, s, c)) return false
        disp = d; ctx = c; surf = s
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {} // clear any stale errors
        return true
    }

    private fun doRender(src: Bitmap, ops: List<EditorOp>, curves: List<List<FloatArray>>?, depth: Bitmap?): Bitmap? {
        if (curves != null) uploadLut(curves)
        val w = src.width; val h = src.height
        if (w <= 0 || h <= 0) return null
        val maxTex = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTex, 0)
        if (w > maxTex[0] || h > maxTex[0]) return null // silently-incomplete texture -> black frame; let the CPU fallback render
        val photoTex = upload(src)
        var dTex = -1
        if (ops.any { it.name == "depthblur" || it.name == "depthblurf" } && depth != null) {
            if (depthTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(depthTex), 0); depthTex = 0 }
            depthTex = uploadDepth(depth)
            dTex = depthTex
        }
        val fa = makeTarget(w, h); val fb = makeTarget(w, h)
        if (fa.first == 0 || fb.first == 0) return null // incomplete FBO -> black frames; let the CPU fallback render
        var input = photoTex
        var target = 0
        var lastOutFbo = fa.first
        var drew = false
        for (op in ops) {
            val outFbo = if (target == 0) fa.first else fb.first
            val outTex = if (target == 0) fa.second else fb.second
            if (op.name == "depthblurf") {
                val amount = op.params["amount"] ?: 0.5f
                val focusDepth = op.params["focusDepth"] ?: 0.5f
                val range = op.params["range"] ?: 0.5f
                // The composite is written into the SAME ping-pong pair as every
                // other op, so the chain always ends on a complete, rendered FBO.
                // Deleting the previous ping-pong texture mid-chain (the old code)
                // detached it from an FBO that is still in use, making every later
                // draw a silent no-op -> black frame / lost edits.
                if (renderFastDepthBlur(input, dTex, outFbo, w, h, amount, focusDepth, range)) {
                    drew = true
                    if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                        android.util.Log.w("EAGLES_EDIT", "GL error after depthblurf; falling back to CPU")
                        return null
                    }
                    input = outTex
                    target = 1 - target
                    lastOutFbo = outFbo
                    continue
                }
            }
            val p = pass(op.name) ?: continue
            val second = when {
                op.name == "curves" && lutTex != 0 -> lutTex
                op.overlay != null -> upload(op.overlay!!)
                else -> -1
            }
            val third = if (op.name == "depthblur") dTex else -1
            p.draw(input, inputTex2 = second, inputTex3 = third, outputFbo = outFbo, width = w, height = h) {
                applyOp(op, p, w.toFloat(), h.toFloat())
            }
            drew = true
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                android.util.Log.w("EAGLES_EDIT", "GL error after ${op.name}; falling back to CPU")
                return null
            }
            if (op.overlay != null) GLES30.glDeleteTextures(1, intArrayOf(second), 0)
            input = outTex
            target = 1 - target
            lastOutFbo = outFbo
        }
        if (!drew) return null // every pass was unknown/invalid — nothing rendered, readback would be garbage
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lastOutFbo)
        GLES30.glViewport(0, 0, w, h)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            android.util.Log.w("EAGLES_EDIT", "readback FBO incomplete; falling back to CPU")
            return null
        }
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        // glReadPixels returns tightly-packed RGBA bytes; GlPixel.fromRgbaBuffer packs
        // them into a defined 0xAARRGGBB bitmap, removing the platform-dependent channel
        // swap that caused the magenta / multi-coloured-static artifact.
        val bmp = GlPixel.fromRgbaBuffer(buf, w, h)
        flipVertical(bmp)
        GLES30.glDeleteTextures(1, intArrayOf(photoTex), 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fa.first), 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fb.first), 0)
        GLES30.glDeleteTextures(1, intArrayOf(fa.second), 0)
        GLES30.glDeleteTextures(1, intArrayOf(fb.second), 0)
        return bmp
    }

    private var whiteTex = 0
    private fun ensureWhiteTex(): Int {
        if (whiteTex != 0) return whiteTex
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); whiteTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTex)
        val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        px.put(0xFF.toByte()); px.put(0xFF.toByte()); px.put(0xFF.toByte()); px.put(0xFF.toByte()); px.rewind()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        return whiteTex
    }

    // Fast separable half-res depth bokeh (native-Android style). Renders the H and
    // V passes into fresh half-res targets (deleted right after) and composites
    // into the caller's [outFbo] — the same ping-pong pair the rest of the chain
    // writes to, so the readback always lands on a complete, rendered FBO.
    // NOTE: the COC shaders name their samplers uColor / uDepth / uAlpha (and
    // uBlur on the composite), which differ from ShaderPass' default uTexture /
    // uTexture2 / uDepthMap — the unit assignment must be set explicitly or all
    // samplers fall back to unit 0 (the source photo) and the depth blur reads
    // photo pixels as depth, producing garbage instead of bokeh.
    private fun renderFastDepthBlur(srcTex: Int, depthTex: Int, outFbo: Int, w: Int, h: Int, amount: Float, focusDepth: Float, range: Float): Boolean {
        if (depthTex < 0 || w <= 0 || h <= 0 || outFbo == 0) return false
        val hw = (w / 2).coerceAtLeast(2); val hh = (h / 2).coerceAtLeast(2)
        val hT = makeTarget(hw, hh); val vT = makeTarget(hw, hh)
        val maxCoC = amount.coerceIn(0f, 1f) * 0.9f
        val wt = ensureWhiteTex()
        ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_DBLUR_COC_H).draw(
            srcTex, inputTex2 = depthTex, inputTex3 = wt, outputFbo = hT.first, width = hw, height = hh) {
            setInt("uColor", 0); setInt("uDepth", 1); setInt("uAlpha", 2)
            setFloat("uFocusDepth", focusDepth); setFloat("uRange", range)
            setFloat("uMaxCoC", maxCoC); setFloat("uTexelW", 1f / hw); setFloat("uEdgeK", 8f)
        }
        ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_DBLUR_COC_V).draw(
            hT.second, inputTex2 = depthTex, inputTex3 = wt, outputFbo = vT.first, width = hw, height = hh) {
            setInt("uColor", 0); setInt("uDepth", 1); setInt("uAlpha", 2)
            setFloat("uFocusDepth", focusDepth); setFloat("uRange", range)
            setFloat("uMaxCoC", maxCoC); setFloat("uTexelH", 1f / hh); setFloat("uEdgeK", 8f)
        }
        ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_DBLUR_COMP).draw(
            srcTex, inputTex2 = vT.second, inputTex3 = wt, outputFbo = outFbo, width = w, height = h) {
            setInt("uColor", 0); setInt("uBlur", 1); setInt("uAlpha", 2)
            setFloat("uMaxCoC", maxCoC); setFloat("uSegMix", 0f)
        }
        GLES30.glDeleteFramebuffers(1, intArrayOf(hT.first), 0); GLES30.glDeleteTextures(1, intArrayOf(hT.second), 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(vT.first), 0); GLES30.glDeleteTextures(1, intArrayOf(vT.second), 0)
        return true
    }

    private fun makeTarget(w: Int, h: Int): Pair<Int, Int> {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (!complete) {
            GLES30.glDeleteTextures(1, t, 0); GLES30.glDeleteFramebuffers(1, f, 0)
            return 0 to 0
        }
        return f[0] to t[0]
    }

    private fun upload(bmp: Bitmap): Int {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, bmp.width, bmp.height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, asBuffer(bmp))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun uploadDepth(bmp: Bitmap): Int {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, bmp.width, bmp.height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, asBuffer(bmp))
        // LINEAR (not NEAREST): the editor depth map is only ~256px, so nearest
        // sampling makes the depth-guided blur fall into hard blocks ("weird
        // texture" on blur edges). Linear interpolation smooths the bokeh ramp.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun asBuffer(bmp: Bitmap): ByteBuffer = GlPixel.toRgbaBuffer(bmp)

    private fun uploadLut(curves: List<List<FloatArray>>) {
        val lut = ByteArray(256 * 4 * 4)
        for (chan in 0 until 4) {
            val row = if (chan < curves.size) EditorCurves.sample(curves[chan]) else EditorCurves.linear()
            for (x in 0 until 256) {
                val v = (row[x].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                val idx = (chan * 256 + x) * 4
                lut[idx] = v; lut[idx + 1] = v; lut[idx + 2] = v; lut[idx + 3] = v
            }
        }
        if (lutTex == 0) {
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); lutTex = t[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lutTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 256, 4, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(lut))
    }

    private fun pass(name: String): ShaderPass? {
        passCache[name]?.let { return it }
        val frag = when (name) {
            "tune" -> Shaders.FRAG_EDIT_TUNE
            "wb" -> Shaders.FRAG_EDIT_WB
            "film_grade" -> Shaders.FRAG_EDIT_FILM_GRADE
            "curves" -> Shaders.FRAG_EDIT_CURVES
            "select" -> Shaders.FRAG_EDIT_SELECT
            "heal" -> Shaders.FRAG_EDIT_HEAL
            "mask" -> Shaders.FRAG_EDIT_MASK
            "hsl" -> Shaders.FRAG_EDIT_HSL
            "split" -> Shaders.FRAG_EDIT_SPLIT
            "structure" -> Shaders.FRAG_EDIT_STRUCTURE
            "geom" -> Shaders.FRAG_EDIT_GEOM
            "lensblur" -> Shaders.FRAG_EDIT_LENSBLUR
            "depthblur" -> Shaders.FRAG_EDIT_DEPTHBLUR
            "depthblurf" -> Shaders.FRAG_EDIT_DEPTHBLUR
            "sharpen" -> Shaders.FRAG_SHARPEN
            "detail" -> Shaders.FRAG_EDIT_DETAIL
            "effects" -> Shaders.FRAG_EDIT_EFFECTS
            "optics" -> Shaders.FRAG_EDIT_OPTICS
            "markup" -> Shaders.FRAG_MARKUP
            "vignette" -> Shaders.FRAG_VIGNETTE
            "grain" -> Shaders.FRAG_GRAIN
            "tilt" -> Shaders.FRAG_TILT_SHIFT
            "streak" -> Shaders.FRAG_STREAK
            "blend" -> FRAG_BLEND
            "frame" -> FRAG_FRAME
            else -> effectFrag(name)
        } ?: return null
        val p = ShaderPass(Shaders.VERTEX_FULLSCREEN, frag)
        passCache[name] = p
        return p
    }

    private fun effectFrag(name: String): String? =
        Shaders.ALL_PHASE_1[name] ?: Shaders.ALL_PHASE_2[name]
            ?: Shaders.ALL_PHASE_4[name] ?: Shaders.ALL_PHASE_5[name]
            ?: Shaders.ALL_PHASE_6[name]

    private fun isEffect(name: String) =
        name in Shaders.ALL_PHASE_1 || name in Shaders.ALL_PHASE_2 ||
            name in Shaders.ALL_PHASE_4 || name in Shaders.ALL_PHASE_5 ||
            name in Shaders.ALL_PHASE_6

    private fun uKey(k: String) = if (k.startsWith("u")) k else "u" + k.replaceFirstChar { it.uppercaseChar() }

    private fun applyOp(op: EditorOp, p: ShaderPass, fxW: Float, fxH: Float) {
        when (op.name) {
            "blend" -> { p.setFloat("uMix", op.params["mix"] ?: 1f); return }
            "frame" -> { p.setFloat("uMix", op.params["mix"] ?: 1f); return }
            "curves" -> {
                p.setFloat("uChannel", op.params["channel"] ?: 0f)
                p.setFloat("uMix", op.params["mix"] ?: 1f)
                return
            }
            "select" -> {
                var n = 0
                for (i in 0 until 6) {
                    val x = op.params["p${i}x"] ?: return
                    p.setFloat4("uSpots[$i]", x, op.params["p${i}y"] ?: 0f,
                        op.params["p${i}r"] ?: 0.1f, op.params["p${i}w"] ?: 1f)
                    p.setFloat3("uAdj[$i]", op.params["p${i}b"] ?: 0f,
                        op.params["p${i}s"] ?: 1f, op.params["p${i}t"] ?: 0f)
                    n = i + 1
                }
                p.setFloat("uCount", n.toFloat())
                return
            }
            "heal" -> {
                var n = 0
                for (i in 0 until 6) {
                    val x = op.params["p${i}x"] ?: return
                    p.setFloat4("uSpots[$i]", x, op.params["p${i}y"] ?: 0f,
                        op.params["p${i}r"] ?: 0.1f, op.params["p${i}w"] ?: 0.5f)
                    n = i + 1
                }
                p.setFloat("uCount", n.toFloat())
                return
            }
        }
        if (op.name == "tilt") {
            p.setFloat("uFocusY", op.params["focusY"] ?: 0.5f)
            p.setFloat("uFocusWidth", op.params["width"] ?: 0.25f)
            p.setFloat("uFeather", op.params["feather"] ?: 0.2f)
            return
        }
        if (op.name == "duotone") {
            p.setFloat3("uShadowColor", 0.08f, 0.04f, 0.14f)
            p.setFloat3("uHighlightColor", 1.0f, 0.88f, 0.62f)
            p.setFloat("uIntensity", op.params["intensity"] ?: 1f)
            return
        }
        if (op.name == "bw_grain") {
            p.setFloat3("uWeights", 0.299f, 0.587f, 0.114f)
        }
        if (op.name == "streak") {
            p.setFloat("uStretch", 0.02f + (op.params["stretch"] ?: 0.5f) * 0.10f)
        }
        op.params.forEach { (k, v) -> p.setFloat(uKey(k), v) }
        if (isEffect(op.name)) {
            if (op.name in setOf("vhs", "crt", "glitch", "starburst", "terminal", "redshift", "dominionCamera", "quantum")) {
                p.setFloat("uTime", 2.013f)
            }
            if (op.name == "grain" || op.name == "iso_grain") p.setFloat("uFrameSeed", 7f)
            if (op.name == "dust") p.setFloat("uSeed", 13f)
            if (op.name == "crt") {
                p.setFloat("uResolutionX", fxW); p.setFloat("uResolutionY", fxH)
            }
            if (op.name == "vhs") {
                val i = op.params["intensity"] ?: 0f
                p.setFloat("uTracking", op.params["tracking"] ?: i * 0.7f)
                p.setFloat("uChromaBleed", op.params["chromaBleed"] ?: i * 0.8f)
                p.setFloat("uNoise", op.params["noise"] ?: i * 0.6f)
                p.setFloat("uScanline", op.params["scanline"] ?: i * 0.5f)
                p.setFloat("uWobble", op.params["wobble"] ?: i * 0.4f)
                p.setFloat("uVignette", op.params["vignette"] ?: i * 0.6f)
                p.setFloat("uColorShift", op.params["colorShift"] ?: i * 0.5f)
                p.setFloat("uHeadSwitch", op.params["headSwitch"] ?: i * 0.8f)
                p.setFloat("uBlur", op.params["blur"] ?: i * 0.3f)
            }
        }
    }

    private fun flipVertical(bmp: Bitmap) {
        val rows = bmp.height / 2
        for (y in 0 until rows) {
            val top = IntArray(bmp.width); val bot = IntArray(bmp.width)
            bmp.getPixels(top, 0, bmp.width, 0, y, bmp.width, 1)
            bmp.getPixels(bot, 0, bmp.width, 0, bmp.height - 1 - y, bmp.width, 1)
            bmp.setPixels(bot, 0, bmp.width, 0, y, bmp.width, 1)
            bmp.setPixels(top, 0, bmp.width, 0, bmp.height - 1 - y, bmp.width, 1)
        }
    }
}