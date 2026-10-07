package com.eagleseye.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.view.Surface
import android.net.Uri
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import com.eagleseye.camera.capture.GLVideoRecorder
import com.eagleseye.camera.engine.CameraGLRenderer
import com.eagleseye.camera.engine.FusionPipelineEngine
import com.eagleseye.camera.presets.FilmPreset
import com.eagleseye.camera.sensor.MotionEnergyTracker

class CameraGLView(context: Context) : GLSurfaceView(context), Preview.SurfaceProvider {
    val engine = CameraGLRenderer(context.applicationContext)
    private val motionTracker = MotionEnergyTracker(context)
    @Volatile private var pending: SurfaceRequest? = null

    init {
        setEGLContextClientVersion(3)
        setPreserveEGLContextOnPause(true)
        engine.motionTracker = motionTracker
        engine.onSurfaceTexReady = { st ->
            pending?.let {
                provide(it, st)
                pending = null
            }
        }
        setRenderer(engine)
        renderMode = RENDERMODE_CONTINUOUSLY
        motionTracker.start()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); motionTracker.start() }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        motionTracker.stop()
        // Stop the GL render loop and depth inference so nothing burns battery
        // (and no GL work runs) while this view is detached (gallery/editor open).
        try { onPause() } catch (e: Exception) {}
    }

    val depthEngine: FusionPipelineEngine?
        get() = engine.depthEngine
    val captureProgress: Float
        get() = engine.captureProgress
    var currentPreset: FilmPreset?
        get() = engine.currentPreset
        set(v) { engine.currentPreset = v }
    var rotDeg: Int
        get() = engine.rotationDeg
        set(v) { engine.rotationDeg = v }
    var isFront: Boolean
        get() = engine.isFront
        set(v) { engine.isFront = v }
    var frameType: FrameType
        get() = engine.frameType
        set(v) { engine.frameType = v }
    var frameConfig: FrameConfig
        get() = engine.frameConfig
        set(v) { engine.frameConfig = v }
    var showFrame: Boolean
        get() = engine.showFrame
        set(v) { engine.showFrame = v }
    var showTimestamp: Boolean
        get() = engine.showTimestamp
        set(v) { engine.showTimestamp = v }
    var dateStyle: DateStampStyle
        get() = engine.dateStyle
        set(v) { engine.dateStyle = v }
    var dateFmt: DateFormatType
        get() = engine.dateFmt
        set(v) { engine.dateFmt = v }
    var dateShowTime: Boolean
        get() = engine.dateShowTime
        set(v) { engine.dateShowTime = v }
    var datePos: Int
        get() = engine.datePos
        set(v) { engine.datePos = v }
    var dateCustom: String?
        get() = engine.dateCustom
        set(v) { engine.dateCustom = v }
    var wbTempK: Int
        get() = engine.wbTempK
        set(v) { engine.wbTempK = v }
    var histogramEnabled: Boolean
        get() = engine.histogramEnabled
        set(v) { engine.histogramEnabled = v }
    var histogramCallback: ((IntArray) -> Unit)?
        get() = engine.histogramCallback
        set(v) { engine.histogramCallback = v }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        val st = engine.surfaceTexture
        if (st != null) {
            queueEvent { provide(request, st) }
        } else {
            pending = request
        }
    }

    private fun provide(request: SurfaceRequest, st: SurfaceTexture) {
        val r = request.resolution
        st.setDefaultBufferSize(r.width, r.height)
        queueEvent { engine.camW = r.width; engine.camH = r.height }
        val aspect = r.width.toFloat() / r.height.toFloat()
        streamAspect = aspect
        onStreamAspect?.invoke(aspect)
        request.provideSurface(Surface(st), ContextCompat.getMainExecutor(context)) {}
    }

    @Volatile var streamAspect = 0f
    var onStreamAspect: ((Float) -> Unit)? = null

    fun captureCurrentFrame(callback: (Bitmap) -> Unit) {
        queueEvent {
            engine.capturePending = true
            engine.captureCallback = callback
        }
    }

    fun processBitmap(input: Bitmap, callback: (Bitmap) -> Unit) {
        queueEvent { engine.processBitmap(input, callback) }
    }

    // One-off GL preview of a single effect pass on a test chart, used by the
    // effects sheet tiles. null = GL not ready / shader unavailable yet.
    // Callback lands on the main thread.
    fun renderTilePreview(name: String, w: Int, h: Int, callback: (Bitmap?) -> Unit) {
        queueEvent {
            engine.renderTilePreview(name, w, h) { bmp ->
                ContextCompat.getMainExecutor(context).execute { callback(bmp) }
            }
        }
    }

    // Film composite live preview (leak/grain/vignette/tint/…) — applied on
    // the GL thread every frame, and baked into effect-video recordings.
    var glFilmStyle: GlFilmStyle?
        get() = engine.filmStyle
        set(v) { engine.filmStyle = v }

    // Effect-video: the GL renderer pushes processed frames into the encoder.
    fun startVideoRecording(r: GLVideoRecorder, onStarted: (Boolean) -> Unit) {
        queueEvent { engine.startRecording(r, onStarted) }
    }

    fun stopVideoRecording(r: GLVideoRecorder?, onDone: (Uri?) -> Unit) {
        queueEvent { engine.stopRecording(r, onDone) }
    }
}
