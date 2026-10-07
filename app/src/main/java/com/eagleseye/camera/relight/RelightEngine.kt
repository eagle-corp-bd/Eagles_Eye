package com.eagleseye.camera.relight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import com.eagleseye.camera.R
import com.eagleseye.camera.engine.GpuDebugLog

/**
 * Wraps the AGSL relight RuntimeShader: owns the frame/depth input bitmaps and
 * exposes uniform setters that the light orb drives every frame.
 *
 * Mirrors the app's existing AGSL pattern (LiquidGlassimpl): shader source
 * lives in res/raw and is compiled with `RuntimeShader(code)`. The shader is
 * drawn through a Compose ShaderBrush; replacing the input bitmaps and
 * uniforms is cheap CPU work, all pixel math stays on the GPU.
 */
class RelightEngine(private val context: Context) {

    // RuntimeShader requires Android 13+. Below that, `shader` stays null and
    // the UI shows the plain frame with a hint instead of relighting.
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // Built lazily: compiling an AGSL program in the constructor of a
    // remember{} can throw during composition, and a single failed attempt
    // permanently disabled relight for the whole session. Deferring the build
    // and surfacing the real reason (instead of blaming the OS level) keeps the
    // feature working on Android 13-16 and visible/fixable when it isn't.
    private var built = false
    private var _shader: RuntimeShader? = null
    val shader: RuntimeShader?
        get() {
            if (!built) { built = true; _shader = buildShader() }
            return _shader
        }

    /** null during normal operation; set when AGSL is genuinely unavailable. */
    var failReason: String? = null
        private set

    private fun buildShader(): RuntimeShader? {
        if (!supported) return null
        failReason = null
        val src = try {
            context.resources.openRawResource(R.raw.relight_effect)
                .bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e("RelightEngine", "read relight_effect.agsl failed", e)
            failReason = "shader source unreadable"
            return null
        }
        val s = try {
            RuntimeShader(src)
        } catch (e: Throwable) {
            Log.e("RelightEngine", "AGSL compile failed", e)
            // Helper diagnosis: if even a trivially small AGSL program fails to
            // compile, the device/driver really can't run AGSL shaders at all.
            val minOk = runCatching {
                RuntimeShader("uniform float2 uZ; half4 main(float2 c) { return half4(0,0,0,1); }")
            }.isSuccess
            failReason = if (minOk) "AGSL compile failed: ${e.message ?: e.javaClass.simpleName}"
                         else "AGSL unavailable on this device"
            GpuDebugLog.agslFail("relight: $failReason")
            return null
        }
        try {
            applyDefaults(s)
        } catch (e: Throwable) {
            Log.e("RelightEngine", "uniform setup failed", e)
            failReason = "AGSL uniform setup failed: ${e.message}"
            GpuDebugLog.agslFail("relight: $failReason")
            return null
        }
        GpuDebugLog.log("AGSL", "relight compiled OK")
        return s
    }

    @Volatile private var frameBmp: Bitmap? = null
    @Volatile private var depthBmp: Bitmap? = null

    // Surface (draw-space) size in px. AGSL `main(coord)` receives coordinates in
    // the same space the shader is drawn in (Canvas px), and `uniform shader`
    // inputs are sampled in that same space, so the BitmapShader local matrix maps
    // surface px -> bitmap px. The model frame/depth are a SQUARE center-crop of
    // the camera, but mapping them onto their exact crop rect via a hand-built
    // matrix proved driver-fragile (stretched / "stached" on some devices), so we
    // simply stretch the square across the full surface — the original behavior
    // before the gray-background bug — which at least displays the live frame
    // undistorted in orientation. glReadPixels returns BOTTOM-UP rows, so the
    // matrix also flips Y (preTranslate handles the flip origin). setInputShader +
    // BitmapShader is the only AGSL input path at compileSdk 34+.
    private var surfWpx = 0f
    private var surfHpx = 0f

    private fun bindInput(name: String, bmp: Bitmap) {
        val s = shader ?: return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0f || bh <= 0f) return
        // Full-surface stretch (square frame -> surface), no flip: the frame is
        // already flipped to top-down in rgbBytesToBitmap (glReadPixels is
        // bottom-up) so it matches the top-down depth matte. Until the surface is
        // measured, fall back to a 1:1 mapping (re-bound once known).
        val sx = if (surfWpx > 0f) surfWpx / bw else 1f
        val sy = if (surfHpx > 0f) surfHpx / bh else 1f
        try {
            val bs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            bs.setLocalMatrix(Matrix().apply { setScale(sx, sy) })
            s.setInputShader(name, bs)
        } catch (e: Throwable) {
            Log.e("RelightEngine", "bind input '$name' failed (${e.javaClass.simpleName}: ${e.message})", e)
        }
    }

    private val posF = FloatArray(2)
    private val sizeF = FloatArray(2)

    private fun applyDefaults(s: RuntimeShader) {
        sizeF[0] = 1f; sizeF[1] = 1f
        s.setFloatUniform("uSize", sizeF)
        s.setFloatUniform("uAmbient", 0.55f)
        s.setFloatUniform("uIntensity", 0.9f)
        s.setFloatUniform("uShininess", 48f)
        s.setFloatUniform("uSpecular", 0.35f)
        s.setFloatUniform("uLightHeight", 0.55f)
        s.setFloatUniform("uSmoothMix", 0.55f)
        s.setFloatUniform("uHeightGain", 3.0f)
        s.setFloatUniform("uSlopeLimit", 6.0f)
        s.setFloatUniform("uEdgeFeather", 0.6f)
        s.setFloatUniform("uDebug", 0f)
        setLightColor(1f, 0.94f, 0.82f)
        setLight(0.68f, 0.32f)
    }

    fun setSurfaceSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        surfWpx = w.toFloat(); surfHpx = h.toFloat()
        sizeF[0] = w.toFloat(); sizeF[1] = h.toFloat()
        shader?.setFloatUniform("uSize", sizeF)
        // Re-bind any inputs we already have so they pick up the corrected
        // surface->bitmap matrix (the frame can arrive before first measure).
        val f = frameBmp
        val d = depthBmp
        if (f != null) bindInput("frame", f)
        if (d != null) bindInput("depthMap", d)
    }

    /** New camera frame input. The previous input bitmap is recycled once the
     *  shader has taken ownership of the new one. */
    fun setFrame(bmp: Bitmap) {
        if (bmp === frameBmp) return
        bindInput("frame", bmp)
        frameBmp?.recycle()
        frameBmp = bmp
    }

    /** New depth matte input (normalized 0..1 gray, 1 = nearest). */
    fun setDepth(bmp: Bitmap) {
        if (bmp === depthBmp) return
        bindInput("depthMap", bmp)
        depthBmp?.recycle()
        depthBmp = bmp
    }

    /** Light orb position in UV space (0..1) — updated live during drags. */
    fun setLight(uvx: Float, uvy: Float) {
        posF[0] = uvx.coerceIn(0f, 1f); posF[1] = uvy.coerceIn(0f, 1f)
        shader?.setFloatUniform("uLightPos", posF)
    }

    fun setLightColor(r: Float, g: Float, b: Float) {
        shader?.setFloatUniform("uLightColorR", r)
        shader?.setFloatUniform("uLightColorG", g)
        shader?.setFloatUniform("uLightColorB", b)
    }

    fun setIntensity(v: Float) = shader?.setFloatUniform("uIntensity", v)
    fun setShininess(v: Float) = shader?.setFloatUniform("uShininess", v)
    fun setSpecular(v: Float) = shader?.setFloatUniform("uSpecular", v)
    fun setHeight(v: Float) = shader?.setFloatUniform("uLightHeight", v)
    fun setAmbient(v: Float) = shader?.setFloatUniform("uAmbient", v)
    fun setSmoothing(v: Float) = shader?.setFloatUniform("uSmoothMix", v)
    fun setDebug(mode: Int) = shader?.setFloatUniform("uDebug", mode.toFloat())

    fun close() {
        frameBmp?.recycle(); frameBmp = null
        depthBmp?.recycle(); depthBmp = null
    }
}