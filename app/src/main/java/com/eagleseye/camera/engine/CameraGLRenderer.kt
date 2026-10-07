package com.eagleseye.camera.engine

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.eagleseye.camera.DateFormatType
import com.eagleseye.camera.DateStampStyle
import com.eagleseye.camera.FrameConfig
import com.eagleseye.camera.FrameType
import com.eagleseye.camera.GlFilmStyle
import com.eagleseye.camera.AppSettings
import com.eagleseye.camera.capture.GLVideoRecorder
import com.eagleseye.camera.presets.FilmPreset
import com.eagleseye.camera.sensor.MotionEnergyTracker
import java.text.SimpleDateFormat
import java.util.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraGLRenderer(private val appContext: android.content.Context? = null) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    var cameraTexId = -1
    var surfaceTexture: SurfaceTexture? = null
    @Volatile var onSurfaceTexReady: ((SurfaceTexture) -> Unit)? = null
    @Volatile var camW = 640; @Volatile var camH = 480
    var motionTracker: MotionEnergyTracker? = null
    @Volatile var liveIso = 400
    @Volatile var wbTempK = 0 // 0 = auto/identity, else Kelvin for GL white-balance tint fallback

    private fun wbGain(): FloatArray {
        val k = wbTempK
        if (k <= 0) return IDENTITY_WB
        val f = (k.coerceIn(2000, 8000) - 2000f) / 6000f
        return floatArrayOf(
            (0.8f + f * 1.2f).coerceIn(0.5f, 2.5f), 1f,
            (2.3f - f * 1.8f).coerceIn(0.5f, 2.5f))
    }

    private companion object {
        val IDENTITY_WB = floatArrayOf(1f, 1f, 1f)
    }

    private lateinit var mainFBO: PingPongFBO
    private lateinit var blurFBO: PingPongFBO
    private lateinit var brightFBO: PingPongFBO

    private lateinit var oesDirect: ShaderPass
    private lateinit var oesTo2D: ShaderPass
    private lateinit var brightPass: ShaderPass
    private lateinit var bloomComp: ShaderPass
    private lateinit var halationComp: ShaderPass
    private lateinit var mistComp: ShaderPass
    private lateinit var tiltShift: ShaderPass
    private lateinit var anamorphic: ShaderPass
    private lateinit var blitShader: ShaderPass
    private var blitOk = false
    private val effectPasses = mutableMapOf<String, ShaderPass>()
    private val bokehRealPass: ShaderPass by lazy {
        ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_EDIT_LENSBLUR, "edit_lensblur")
    }
    // Real per-pixel depth bokeh for still capture (same shader the editor uses).
    private val depthBokehPass: ShaderPass by lazy {
        // Capture-path bokeh: samples the SQUARE model depth texture, so it maps
        // the frame UV through the center-crop inverse (uDS/uDO) to stay scene-
        // aligned — same mapping the live engine uses (FusionPipelineEngine).
        ShaderPass(Shaders.VERTEX_FULLSCREEN, """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uDepthMap;
uniform float uAmount,uFocusDepth,uRange,uBlades;
uniform vec2 uResolution;
uniform vec2 uDS;uniform vec2 uDO;
in vec2 vUV;out vec4 fragColor;
float polyRad(float ang){
 float n=floor(uBlades+0.5);
 if(n<3.)return 1.;
 float a=mod(ang,6.2831853/n);
 return cos(3.1415926/n)/max(cos(a-3.1415926/n),0.2);
}
void main(){
 vec4 c=texture(uTexture,vUV);
 float d=texture(uDepthMap,vUV*uDS+uDO).r;
 float bl=clamp(abs(d-uFocusDepth)/max(uRange,0.02),0.,1.)*clamp(uAmount,0.,1.);
 float brad=bl*0.024*uResolution.y*0.5;
 vec4 acc=c;float wsum=1.;
 if(brad>0.5){
  for(int r=1;r<=3;r++){
   float rr=float(r)/3.;
   for(int i=0;i<12;i++){
    float a=(float(i)+0.5)/12.*6.2831853+0.26;
    vec2 dir=vec2(cos(a),sin(a))*polyRad(a);
    vec2 p=vUV+dir*(brad*rr)/uResolution;
    if(p.x<0.||p.y<0.||p.x>1.||p.y>1.)continue;
    acc+=texture(uTexture,p);wsum+=1.;
   }
  }
 }
 fragColor=vec4(acc.rgb/wsum,c.a);
}""")
    }
    private lateinit var phase2Passes: Map<String, ShaderPass>
    private lateinit var separableBlur: SeparableBlur
    private lateinit var frameGenerator: FrameTextureGenerator
    private lateinit var frameOverlayPass: ShaderPass
    private val dateFormat = SimpleDateFormat("yy.MM.dd  HH:mm", Locale.US)
    @Volatile var frameType = FrameType.NONE
    @Volatile var frameConfig = FrameConfig()
    @Volatile var showFrame = false
    @Volatile var showTimestamp = false
    @Volatile var dateStyle = DateStampStyle.ORANGE_FILM
    @Volatile var dateFmt = DateFormatType.DD_MM_YY
    @Volatile var dateShowTime = false
    @Volatile var datePos = 0
    @Volatile var dateCustom: String? = null
    private var fallbackDepthTex = -1
    private var syntheticDepthTex = -1
    private var depthFbo = -1
    private lateinit var depthGenPass: ShaderPass
    private var captureFbo = -1
    private var captureTex = -1
    @Volatile var capturePending = false
    @Volatile var captureCallback: ((Bitmap) -> Unit)? = null

    @Volatile var activeEffects = mutableSetOf<String>()
    @Volatile var effectParams = mutableMapOf<String, MutableMap<String, Float>>()
    @Volatile var rotationDeg = 0
    @Volatile var isFront = false

    @Volatile var currentPreset: FilmPreset? = null
        set(v) { field = v; applyPreset(v) }

    // stamp text: custom date wins, else today in the chosen format (+ time).
    private fun buildStamp(): String {
        dateCustom?.takeIf { it.isNotBlank() }?.let { return it }
        val pat = when (dateFmt) {
            DateFormatType.DD_MM_YY -> "yy.MM.dd"
            DateFormatType.MM_DD_YY -> "MM.dd.yy"
            DateFormatType.YYYY_MM_DD -> "yyyy.MM.dd"
            DateFormatType.MM_YY -> "MM.yy"
        } + if (dateShowTime) "  HH:mm" else ""
        return SimpleDateFormat(pat, Locale.US).format(Date())
    }

    @Volatile var histogramEnabled = false
    @Volatile var histogramCallback: ((IntArray) -> Unit)? = null
    private var histBuf: java.nio.ByteBuffer? = null
    private var histFrameCounter = 0
    @Volatile var depthEngine: FusionPipelineEngine? = null

    // Capture inference progress (0..1), written on the GL thread while the
    // all-engines portrait pass runs, polled by the UI progress bar.
    val captureProgress: Float get() = depthEngine?.captureProgress ?: 0f

    private var viewW = 1; private var viewH = 1
    private var frameCount = 0
    private var frame = false
    private var texId = -1
    private var initialized = false
    private var oesShaderOk = false

    // ── Scene analysis: brightest-region centroid + color drives light leak,
    //    lens flare and god rays (adaptive, wanders smoothly). ──
    private var scenePass: ShaderPass? = null
    private var sceneFbo = -1; private var sceneTex = -1
    private val sceneBuf = java.nio.ByteBuffer.allocateDirect(16 * 16 * 4).order(java.nio.ByteOrder.nativeOrder())
    private var memFbo = -1; private var memTex = -1
    @Volatile var sceneHotX = 0.5f; @Volatile var sceneHotY = 0.15f
    @Volatile var sceneR = 1f; @Volatile var sceneG = 0.45f; @Volatile var sceneB = 0.1f
    @Volatile private var scenePeak = 0f

    // Snap-to-brightest hotspot state (see analyzeScene): the SUN flare locks
    // onto the single brightest 16x16 cell and holds it with hysteresis.
    private val sceneLums = FloatArray(256)
    private val sceneCellR = FloatArray(256)
    private val sceneCellG = FloatArray(256)
    private val sceneCellB = FloatArray(256)
    @Volatile private var sceneLockCX = -1
    @Volatile private var sceneLockCY = -1
    @Volatile private var sceneLockLum = 0f
    private var sceneCountdown = 0

    // ── Film composite parity with the CPU FilmOverlay ──
    @Volatile var filmStyle: GlFilmStyle? = null
    private var filmStylePass: ShaderPass? = null

    // ── KinoStreak (MIT, Keijiro Takahashi): prefilter/down/up/composite ──
    private var streakPrePass: ShaderPass? = null
    private var streakDownPass: ShaderPass? = null
    private var streakUpPass: ShaderPass? = null
    private var streakCompPass: ShaderPass? = null
    private var streakGainPass: ShaderPass? = null
    private var streakMonitorPass: ShaderPass? = null
    private var streakPreSqrtPass: ShaderPass? = null
    private var streakCompSqrtPass: ShaderPass? = null
    private var sunPrePass: ShaderPass? = null
    private var sunPreSqrtPass: ShaderPass? = null
    private var sunCompWarpPass: ShaderPass? = null
    private var sunCompWarpSqrtPass: ShaderPass? = null

    // ── Effect-video recording (surface-input H.264 capture of the processed view) ──
    @Volatile var recorder: GLVideoRecorder? = null
    private var recEglDisplay: android.opengl.EGLDisplay? = null
    private var recEglContext: android.opengl.EGLContext? = null
    private var recEglSurface: android.opengl.EGLSurface? = null
    private var recW = 0; private var recH = 0
    private var recStartNs = 0L
    @Volatile var isRecording = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            val ac = appContext ?: return
            depthEngine = FusionPipelineEngine(ac)
            // Every surface recreation swaps in a fresh engine — restore the
            // user's depth selection from persisted settings immediately, or the
            // new engine sits at mode=OFF (raw camera) until a tile is re-tapped.
            depthEngine?.let { e ->
                val dm = AppSettings.depthMode(ac)
                val bo = AppSettings.bokehOn(ac)
                val effMode = if (dm != 0) dm else if (bo) 3 else 0
                e.enabled = effMode != 0
                e.mode = when (effMode) {
                    1 -> FusionPipelineEngine.Mode.DEPTH_VIEW
                    2 -> FusionPipelineEngine.Mode.STUDIO_LIGHT
                    3 -> FusionPipelineEngine.Mode.BOKEH
                    else -> FusionPipelineEngine.Mode.OFF
                }
                e.blurStrength = AppSettings.depthBlur(ac)
                e.setDepthModel(AppSettings.useMiDasDepth(ac))
                e.depthRange = AppSettings.bokehRange(ac)
                e.saveToPhoto = AppSettings.bakeBokeh(ac)
            }
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            appContext?.let { GpuDebugLog.init(it) }
            GpuDebugLog.captureGlStrings()
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { }

            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); texId = t[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            cameraTexId = texId
            val st = SurfaceTexture(texId)
            st.setOnFrameAvailableListener(this)
            surfaceTexture = st
            onSurfaceTexReady?.invoke(st)

            Log.i("EaglesEye", "OES exts: " + (GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: "null"))
            val oesVert = buildOesVertex()
            val oesFrag = buildOesShader()
            Log.i("EaglesEye", "OES vert:\n$oesVert\nOES frag:\n$oesFrag")
            oesDirect = ShaderPass(oesVert, oesFrag, "oes_direct")
            oesShaderOk = oesDirect.isValid
            oesTo2D = oesDirect
            Log.i("EaglesEye", "OES shader compiled: $oesShaderOk")
            GpuDebugLog.log("SHADER", "OES compiled ok=$oesShaderOk")

            brightPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_BRIGHT_PASS, "bright")
            bloomComp = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_BLOOM_COMP, "bloom")
            halationComp = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_HALATION_COMP, "halation")
            mistComp = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_MIST, "mist")
            tiltShift = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_TILT_SHIFT, "tilt_shift")
            anamorphic = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_ANAMORPHIC, "anamorphic")
            blitShader = ShaderPass(Shaders.VERTEX_FULLSCREEN, """
#version 300 es
precision mediump float;uniform sampler2D uTexture;in vec2 vUV;out vec4 fragColor;
void main(){fragColor=texture(uTexture,vUV);}""", "blit")
            blitOk = blitShader.isValid
            GpuDebugLog.log("SHADER", "blit ok=$blitOk")

            Shaders.ALL.forEach { (name, src) ->
                try { effectPasses[name] = ShaderPass(Shaders.VERTEX_FULLSCREEN, src, name) }
                catch (e: Exception) {
                    Log.e("EaglesEye", "shader fail: $name: ${e.message}")
                    GpuDebugLog.logError("SHADER", "ctor throw $name: ${e.message}")
                }
            }
            val invalid = effectPasses.filterValues { !it.isValid }.keys.sorted()
            val total = effectPasses.size
            val badCount = invalid.size
            GpuDebugLog.setPassSummary(
                "passes=$total invalid=$badCount",
                invalid
            )
            GpuDebugLog.log(
                "SHADER",
                "effectPasses built total=$total invalid=$badCount" +
                    if (invalid.isEmpty()) "" else " names=$invalid"
            )

            // Minimal init first: even if later optional setup throws, the surface
            // can still show OES preview instead of a permanent black clear.
            phase2Passes = emptyMap()
            initialized = true

            val ft = IntArray(1); GLES30.glGenTextures(1, ft, 0); fallbackDepthTex = ft[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fallbackDepthTex)
            val depthBuf = java.nio.ByteBuffer.allocateDirect(1).order(java.nio.ByteOrder.nativeOrder()).put(0xFF.toByte()).also { it.position(0) }
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, 0x8229, 1, 1, 0, 0x1903, GLES30.GL_UNSIGNED_BYTE, depthBuf)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)

            val dt = IntArray(1); GLES30.glGenTextures(1, dt, 0); syntheticDepthTex = dt[0]

            depthGenPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_DEPTH_GEN, "depth_gen")

            frameGenerator = FrameTextureGenerator()
            frameOverlayPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_FRAME_OVERLAY, "frame_overlay")

            scenePass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_SCENE_AVG, "scene_avg")
            filmStylePass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_FILM_STYLE, "film_style")

            streakPrePass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_PREFILTER, "streak_pre")
            streakDownPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_DOWN, "streak_down")
            streakUpPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_UP, "streak_up")
            streakCompPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_COMPOSITE, "streak_comp")
            streakGainPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_GAIN, "streak_gain")
            streakMonitorPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_MONITOR, "streak_monitor")
            streakPreSqrtPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_PREFILTER_SQRT, "streak_pre_sqrt")
            streakCompSqrtPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_COMPOSITE_SQRT, "streak_comp_sqrt")
            sunPrePass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_PREFILTER_SUN, "sun_pre")
            sunPreSqrtPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_PREFILTER_SUN_SQRT, "sun_pre_sqrt")
            sunCompWarpPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_COMPOSITE_WARP, "sun_comp_warp")
            sunCompWarpSqrtPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_KINO_COMPOSITE_WARP_SQRT, "sun_comp_warp_sqrt")

            streakFp16 = testHalfFloatRender()
            Log.i("EaglesEye", "kinostreak fp16 buffers: $streakFp16")

            phase2Passes = mapOf(
                "bloom" to bloomComp, "halation" to halationComp,
                "mist" to mistComp, "tilt_shift" to tiltShift,
                "dreamy" to (effectPasses["dreamy"] ?: ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_DREAMY, "dreamy_fb")),
                "anamorphic" to anamorphic,
                "streak" to (effectPasses["streak"] ?: ShaderPass(Shaders.VERTEX_FULLSCREEN, Shaders.FRAG_STREAK, "streak_fb"))
            )
            GpuDebugLog.log("INIT", "onSurfaceCreated complete oes=$oesShaderOk blit=${if (::blitShader.isInitialized) blitShader.isValid else false} passes=${effectPasses.size}")
            initialized = true
        } catch (e: Throwable) {
            Log.e("EaglesEye", "onSurfaceCreated", e)
            GpuDebugLog.logError("INIT", "onSurfaceCreated threw: ${e.message ?: e}")
            if (::oesDirect.isInitialized && oesDirect.isValid) oesShaderOk = true
            if (!::phase2Passes.isInitialized) {
                try { phase2Passes = emptyMap() } catch (_: Throwable) {}
            }
            initialized = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        viewW = w; viewH = h
        GLES30.glViewport(0, 0, w, h)
            mainFBO = PingPongFBO(w, h, "main")
            blurFBO = PingPongFBO(w, h, "blur")
            brightFBO = PingPongFBO(w / 2, h / 2, "bright")
            separableBlur = SeparableBlur(w, h)
            GpuDebugLog.log("FBO", "surfaceChanged ${w}x$h main=${mainFBO.isComplete} blur=${blurFBO.isComplete} bright=${brightFBO.isComplete}")

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, syntheticDepthTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, 0x8229, w, h, 0, 0x1903, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val dfbo = IntArray(1); GLES30.glGenFramebuffers(1, dfbo, 0); depthFbo = dfbo[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, depthFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, syntheticDepthTex, 0)
            GpuDebugLog.checkFbo("depthFbo")

        val ct = IntArray(1); GLES30.glGenTextures(1, ct, 0); captureTex = ct[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, captureTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            val cfbo = IntArray(1); GLES30.glGenFramebuffers(1, cfbo, 0); captureFbo = cfbo[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, captureFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, captureTex, 0)
            GpuDebugLog.checkFbo("captureFbo")

        // Scene-analysis 16x16 FBO
        val st = IntArray(1); GLES30.glGenTextures(1, st, 0); sceneTex = st[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 16, 16, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            val sfbo = IntArray(1); GLES30.glGenFramebuffers(1, sfbo, 0); sceneFbo = sfbo[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, sceneTex, 0)
            GpuDebugLog.checkFbo("sceneFbo")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!initialized) { GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return }
        try {
            val st = surfaceTexture
            synchronized(this) { if (frame) { st?.updateTexImage(); frame = false } }

            if (!oesShaderOk) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, viewW, viewH)
                GLES30.glClearColor(1f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GpuDebugLog.logOnce("oes-bad", "SHADER", "OES shader invalid — red fallback (oesShaderOk=false)")
                return
            }

            // Get the transform matrix from SurfaceTexture (encodes rotation/mirror/crop)
            val stMatrix = FloatArray(16)
            st?.getTransformMatrix(stMatrix)

            frameCount++

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewW, viewH)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            val mirror = if (isFront) 1f else 0f
            val wbg = wbGain()

            if (activeEffects.isEmpty() && depthEngine?.enabled != true && !(showFrame && frameType != FrameType.NONE)) {
                if (frameCount % 60 == 0) Log.i("EaglesEye", "OES direct STMatrix rot=$rotationDeg mirror=$mirror")
                val fit = oesMapping()
                val drewDirect = oesDirect.draw(cameraTexId, outputFbo = 0, width = viewW, height = viewH,
                    texTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
                    setMat4("uSTMatrix", stMatrix)
                    setFloat2("uFit", fit[0], fit[1])
                    setFloat3("uWB", wbg[0], wbg[1], wbg[2])
                }
                if (!drewDirect) {
                    GLES30.glClearColor(1f, 0f, 0f, 1f)
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                    GLES30.glClearColor(0f, 0f, 0f, 1f)
                }
                return
            }
            if (frameCount % 60 == 0) Log.i("EaglesEye", "activeEffects=$activeEffects params=$effectParams")

            val fit = oesMapping()
            val oesDrew = oesTo2D.draw(cameraTexId, outputFbo = mainFBO.writeFbo, width = viewW, height = viewH,
                texTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
                setMat4("uSTMatrix", stMatrix)
                setFloat2("uFit", fit[0], fit[1])
                setFloat3("uWB", wbg[0], wbg[1], wbg[2])
            }
            // Only advance the ping-pong when the OES pass actually wrote —
            // an unconditional swap here would sample an uninitialized texture.
            if (oesDrew) mainFBO.swap()

            // Depth pipeline pass (post-processes mainFBO.readTex, writes mainFBO.writeFbo)
            val de = depthEngine
            if (de?.enabled == true) {
                val stex = try {
                    de.render(mainFBO.readTex, viewW, viewH)
                } catch (e: Throwable) {
                    Log.e("EaglesEye", "depth render threw", e)
                    -1
                }
                if (stex >= 0) {
                    if (blitShader.draw(stex, outputFbo = mainFBO.writeFbo, width = viewW, height = viewH) {}) {
                        mainFBO.swap()
                    }
                } else if (frameCount % 60 == 0) {
                    Log.w("EaglesEye", "depth deferred: ${de.lastDeferReason} errs=${de.glErrCount} ${de.lastErrLabel}")
                }
            }

            val hasDepthPhase = activeEffects.any { it in Shaders.ALL_PHASE_3 }
            val needsBlur = activeEffects.any { it in setOf("bloom", "halation", "mist", "tilt_shift", "dreamy") } || hasDepthPhase
            if (needsBlur) {
                val brightDrew = brightPass.draw(mainFBO.readTex, outputFbo = brightFBO.writeFbo,
                    width = brightFBO.width, height = brightFBO.height) {
                    setFloat("uThreshold", effectParams["bloom"]?.get("threshold") ?: 0.7f)
                }
                if (brightDrew) {
                    brightFBO.swap()
                    if (separableBlur.blur(brightFBO.readTex, blurFBO.writeFbo, viewW, viewH)) {
                        blurFBO.swap()
                    }
                }
            }

            for (name in Shaders.ALL_PHASE_1.keys) {
                if (name !in activeEffects || name !in effectPasses) continue
                val drew = effectPasses[name]!!.draw(mainFBO.readTex, outputFbo = mainFBO.writeFbo,
                    width = viewW, height = viewH) {
                    (effectParams[name] ?: emptyMap()).forEach { (k, v) -> setFloat(if (k.startsWith("u")) k else "u${k.replaceFirstChar { it.uppercaseChar() }}", v) }
                    if (name == "grain") setFloat("uFrameSeed", (frameCount % 10000).toFloat())
                    if (name == "dust") setFloat("uSeed", (frameCount % 1000).toFloat())
                    if (name == "crt") { setFloat("uTime", frameCount / 60f); setFloat("uResolutionX", viewW.toFloat()); setFloat("uResolutionY", viewH.toFloat()) }
                    if (name == "vhs") {
                        setFloat("uTime", frameCount / 60f)
                        setFloat("uTracking", effectParams["vhs"]?.get("tracking") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.7f)
                        setFloat("uChromaBleed", effectParams["vhs"]?.get("chromaBleed") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.8f)
                        setFloat("uNoise", effectParams["vhs"]?.get("noise") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.6f)
                        setFloat("uScanline", effectParams["vhs"]?.get("scanline") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.5f)
                        setFloat("uWobble", effectParams["vhs"]?.get("wobble") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.4f)
                        setFloat("uVignette", effectParams["vhs"]?.get("vignette") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.6f)
                        setFloat("uColorShift", effectParams["vhs"]?.get("colorShift") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.5f)
                        setFloat("uHeadSwitch", effectParams["vhs"]?.get("headSwitch") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.8f)
                        setFloat("uBlur", effectParams["vhs"]?.get("blur") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.3f)
                    }
                if (name == "duotone" && currentPreset != null) { setFloat3("uShadowColor", currentPreset!!.duotoneShadow[0], currentPreset!!.duotoneShadow[1], currentPreset!!.duotoneShadow[2]); setFloat3("uHighlightColor", currentPreset!!.duotoneHighlight[0], currentPreset!!.duotoneHighlight[1], currentPreset!!.duotoneHighlight[2]) }
                if (name == "prism" && currentPreset != null) { setFloat3("uTint1", currentPreset!!.prismTint1[0], currentPreset!!.prismTint1[1], currentPreset!!.prismTint1[2]); setFloat3("uTint2", currentPreset!!.prismTint2[0], currentPreset!!.prismTint2[1], currentPreset!!.prismTint2[2]) }
                }
                // Swap only after a real draw — invalid pass leaves readTex valid.
                if (drew) mainFBO.swap()
            }

            for ((name, pass) in phase2Passes) {
                if (name !in activeEffects) continue
                val p = effectParams[name] ?: emptyMap()
                var drew = false
                when (name) {
                    "bloom" -> drew = pass.draw(mainFBO.readTex, inputTex2 = blurFBO.readTex,
                        outputFbo = mainFBO.writeFbo, width = viewW, height = viewH) {
                        setFloat("uIntensity", p["intensity"] ?: 0.5f)
                    }
                    "halation" -> drew = pass.draw(mainFBO.readTex, inputTex2 = blurFBO.readTex,
                        outputFbo = mainFBO.writeFbo, width = viewW, height = viewH) {
                        setFloat3("uTint", 1f, 0.35f, 0.15f)
                        setFloat("uIntensity", p["intensity"] ?: 0.6f)
                    }
                    "mist" -> drew = pass.draw(mainFBO.readTex, inputTex2 = blurFBO.readTex,
                        outputFbo = mainFBO.writeFbo, width = viewW, height = viewH) {
                        setFloat3("uMistColor", 0.85f, 0.87f, 0.9f)
                        setFloat("uHorizonY", 0.5f)
                        setFloat("uIntensity", p["intensity"] ?: 0.7f)
                    }
                    "tilt_shift" -> drew = pass.draw(mainFBO.readTex, inputTex2 = blurFBO.readTex,
                        outputFbo = mainFBO.writeFbo, width = viewW, height = viewH) {
                        setFloat("uFocusY", p["focusY"] ?: 0.5f)
                        setFloat("uFocusWidth", p["focusWidth"] ?: 0.2f)
                        setFloat("uFeather", p["feather"] ?: 0.15f)
                    }
                    "anamorphic" -> drew = pass.draw(mainFBO.readTex, outputFbo = mainFBO.writeFbo,
                        width = viewW, height = viewH) {
                        setFloat("uStreakLength", p["streakLength"] ?: 0.03f)
                        setFloat3("uTint", 1f, 1f, 1f)
                    }
                    "streak" -> drew = renderKinoStreak(mainFBO.readTex, mainFBO.writeFbo, viewW, viewH,
                        p["threshold"] ?: 1.0f, p["intensity"] ?: 0.43f, p["stretch"] ?: 0.75f,
                        0.55f, 0.55f, 1f, debug = true)
                    "sunstreak" -> if (scenePeak > 0.15f) {
                        drew = renderKinoStreak(mainFBO.readTex, mainFBO.writeFbo, viewW, viewH,
                            p["threshold"] ?: 1.0f, p["intensity"] ?: 0.46f, p["stretch"] ?: 0.75f,
                            1f, 0.8f, 0.55f,
                            sunX = sceneHotX, sunY = sceneHotY,
                            sunSize = p["sunSize"] ?: 0.075f, warp = (p["warp"] ?: 0.4f) * 0.12f)
                    }
                    "dreamy" -> drew = pass.draw(mainFBO.readTex, inputTex2 = blurFBO.readTex,
                        outputFbo = mainFBO.writeFbo, width = viewW, height = viewH) {
                        setFloat("uIntensity", p["intensity"] ?: 0.5f)
                    }
                }
                // Swap only after a real draw — invalid pass leaves readTex valid.
                if (drew) mainFBO.swap()
            }

            if (hasDepthPhase && needsBlur) {
                val focusDepth = effectParams["depth_halation"]?.get("focusPlane") ?: effectParams["depth_tilt"]?.get("focusDist") ?: 0.5f
                val depthBand = effectParams["depth_halation"]?.get("depthBand") ?: effectParams["depth_tilt"]?.get("focalLength") ?: 0.3f
                depthGenPass.draw(mainFBO.readTex, inputTex2 = blurFBO.readTex, outputFbo = depthFbo, width = viewW, height = viewH) {
                    setFloat("uFocusDepth", focusDepth)
                    setFloat("uDepthBand", depthBand)
                }
            }

            for (name in Shaders.ALL_PHASE_3.keys) {
                if (name !in activeEffects || name !in effectPasses) continue
                val depthTex = if (hasDepthPhase) syntheticDepthTex else fallbackDepthTex
                val blurTex = if (needsBlur) blurFBO.readTex else -1
                val drew = effectPasses[name]!!.draw(
                    inputTex = mainFBO.readTex,
                    inputTex2 = blurTex,
                    inputTex3 = depthTex,
                    outputFbo = mainFBO.writeFbo,
                    width = viewW, height = viewH
                ) {
                    (effectParams[name] ?: emptyMap()).forEach { (k, v) -> setFloat(if (k.startsWith("u")) k else "u${k.replaceFirstChar { it.uppercaseChar() }}", v) }
                }
                if (drew) mainFBO.swap()
            }

            val motionEnergy = motionTracker?.energy() ?: 0f
            val gyroVec = floatArrayOf(0.5f, 0.3f)
            motionTracker?.let {
                val deg = it.energy() * 360f
                val rad = deg * 0.0174533f
                gyroVec[0] = kotlin.math.cos(rad) * 0.5f + 0.5f
                gyroVec[1] = kotlin.math.sin(rad) * 0.5f + 0.5f
            }
            for (name in Shaders.ALL_PHASE_4.keys) {
                if (name !in activeEffects || name !in effectPasses) continue
                val drew = effectPasses[name]!!.draw(mainFBO.readTex, outputFbo = mainFBO.writeFbo,
                    width = viewW, height = viewH) {
                    (effectParams[name] ?: emptyMap()).forEach { (k, v) -> setFloat(if (k.startsWith("u")) k else "u${k.replaceFirstChar { it.uppercaseChar() }}", v) }
                    setFloat("uResolutionX", viewW.toFloat())
                    setFloat("uResolutionY", viewH.toFloat())
                    setFloat("uTime", frameCount / 60f)
                    setFloat2("uGyroDir", gyroVec[0], gyroVec[1])
                    setFloat("uIsoLevel", liveIso.toFloat())
                    setFloat("uMotionIntensity", (effectParams["motion_blur"]?.get("intensity") ?: 0f) + motionEnergy)
                }
                if (drew) mainFBO.swap()
            }

            for (name in Shaders.ALL_PHASE_5.keys) {
                if (name !in activeEffects || name !in effectPasses) continue
                // Real MiDaS depth bokeh (FusionPipelineEngine) already replaces the
                // fake tilt-shift when the BOKEH depth mode is live — skip the fake.
                if (name == "bokeh" && depthEngine?.enabled == true && depthEngine?.mode == FusionPipelineEngine.Mode.BOKEH) continue
                val drew = effectPasses[name]!!.draw(mainFBO.readTex, inputTex2 = if (name == "double_exposure") memTex else -1, outputFbo = mainFBO.writeFbo,
                    width = viewW, height = viewH) {
                    (effectParams[name] ?: emptyMap()).forEach { (k, v) -> setFloat(if (k.startsWith("u")) k else "u${k.replaceFirstChar { it.uppercaseChar() }}", v) }
                    if (name == "glitch" || name == "starburst" || name == "fat_pixel" || name == "terminal") setFloat("uTime", frameCount / 60f)
                    if (name == "light_leak") {
                        setFloat("uTime", frameCount / 60f)
                        setFloat2("uEntryPoint", sceneHotX, sceneHotY)
                        val lc = currentPreset?.lightLeakColor ?: floatArrayOf(1f, 0.4f, 0f)
                        val lpk = (1f + scenePeak * 1.5f).coerceIn(1f, 2.2f)
                        setFloat3("uLeakColor", (lc[0] * lpk).coerceIn(0f, 1f), (lc[1] * lpk).coerceIn(0f, 1f), (lc[2] * lpk).coerceIn(0f, 1f))
                    }
                    if (name == "lens_flare") {
                        setFloat("uTime", frameCount / 60f)
                        setFloat2("uSourcePos", sceneHotX, sceneHotY)
                        setFloat3("uSourceColor", sceneR, sceneG, sceneB)
                        setFloat("uAnamorphic", effectParams[name]?.get("anamorphic") ?: 0.4f)
                    }
                    if (name == "light_rays") {
                        setFloat("uTime", frameCount / 60f)
                        setFloat2("uSourcePos", sceneHotX, sceneHotY)
                        setFloat3("uSourceColor", sceneR, sceneG, sceneB)
                        setFloat2("uResolution", viewW.toFloat(), viewH.toFloat())
                    }
                    if (name == "bw_grain") setFloat3("uWeights", 0.299f, 0.587f, 0.114f)
                    if (name == "focus_peak" || name == "fat_pixel" || name == "halftone" || name == "cmyk_dots" || name == "teletext" || name == "terminal" || name == "bokeh") setFloat2("uResolution", viewW.toFloat(), viewH.toFloat())
                    if (name == "sharpen") setFloat2("uResolution", viewW.toFloat(), viewH.toFloat())
                    // terminal color handled via effectParams
                }
                if (drew) mainFBO.swap()
            }

            for (name in Shaders.ALL_PHASE_6.keys) {
                if (name !in activeEffects || name !in effectPasses) continue
                val drew = effectPasses[name]!!.draw(mainFBO.readTex, outputFbo = mainFBO.writeFbo,
                    width = viewW, height = viewH) {
                    (effectParams[name] ?: emptyMap()).forEach { (k, v) -> setFloat(if (k.startsWith("u")) k else "u${k.replaceFirstChar { it.uppercaseChar() }}", v) }
                    if (name == "retro") setFloat("uTime", frameCount / 60f)
                }
                if (drew) mainFBO.swap()
            }

            // ── Scene analysis: find the brightest region — the origin for the
            //    adaptive light, lens flare and god rays. Runs every other
            //    frame, only when one of those look elements is active. ──
            if (needsSceneAnalysis()) runSceneAnalysis()

            // ── Film composite (leak/grain/vignette/tint/warmth/fade) ──
            val fs = filmStyle
            if (fs != null && fs.active && filmStylePass != null && filmStylePass!!.isValid) {
                runFilmComposite(fs)
            }

            if (showFrame && frameType != FrameType.NONE && frameOverlayPass.isValid) {
                val stamp = buildStamp()
                val frameTex = frameGenerator.getFrameTexture(frameType, viewW, viewH, frameConfig, dateFormat.format(Date()),
                    showDate = showTimestamp, stamp = stamp, stampStyle = dateStyle, stampPos = datePos)
                val drew = frameOverlayPass.draw(mainFBO.readTex, inputTex2 = frameTex, outputFbo = mainFBO.writeFbo,
                    width = viewW, height = viewH) {
                    setFloat("uFrameIntensity", 1.0f)
                    setTexture("uImage", mainFBO.readTex, 0)
                    setTexture("uFrame", frameTex, 1)
                }
                if (drew) mainFBO.swap()
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, viewW, viewH)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            // Double exposure: keep the last fully processed frame around.
            if (activeEffects.contains("double_exposure")) {
                if (memFbo < 0 && viewW > 0 && viewH > 0) {
                    val mt = IntArray(1); GLES30.glGenTextures(1, mt, 0); memTex = mt[0]
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, memTex)
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, viewW, viewH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    val mf = IntArray(1); GLES30.glGenFramebuffers(1, mf, 0); memFbo = mf[0]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, memFbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, memTex, 0)
                GpuDebugLog.checkFbo("memFbo")
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                }
                if (memFbo >= 0 && blitShader.isValid) {
                    blitShader.draw(mainFBO.readTex, outputFbo = memFbo, width = viewW, height = viewH) {}
                }
            }

            if (!::blitShader.isInitialized || !blitShader.draw(mainFBO.readTex, outputFbo = 0, width = viewW, height = viewH) {}) {
                GpuDebugLog.logOnce("blit-invalid", "SHADER", "final blit skipped — blitShader invalid")
                if (oesShaderOk && ::oesDirect.isInitialized) {
                    val fit2 = oesMapping()
                    oesDirect.draw(cameraTexId, outputFbo = 0, width = viewW, height = viewH,
                        texTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
                        setMat4("uSTMatrix", stMatrix)
                        setFloat2("uFit", fit2[0], fit2[1])
                        setFloat3("uWB", wbg[0], wbg[1], wbg[2])
                    }
                }
            }

            // Effect-video: composite the exact same processed frame to the encoder
            val rec = recorder
            if (rec != null && isRecording) recordFrame(rec)

            if (histogramEnabled && histogramCallback != null && viewW > 0 && viewH > 0) {
                histFrameCounter++
                if (histFrameCounter % 4 == 0) {
                    val hw = (viewW / 4).coerceAtLeast(1)
                    val hh = (viewH / 4).coerceAtLeast(1)
                    val buf = histBuf ?: java.nio.ByteBuffer.allocateDirect(hw * hh * 4)
                        .order(java.nio.ByteOrder.nativeOrder()).also { histBuf = it }
                    GLES30.glReadPixels(0, 0, hw, hh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                    val bins = IntArray(64)
                    buf.position(0)
                    val n = hw * hh
                    for (i in 0 until n) {
                        val r = (buf.get().toInt()) and 0xFF
                        val g = (buf.get().toInt()) and 0xFF
                        val b = (buf.get().toInt()) and 0xFF
                        buf.get()
                        val luma = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
                        bins[(luma * 64 / 256).coerceIn(0, 63)]++
                    }
                    histogramCallback?.invoke(bins)
                }
            }

            if (capturePending) {
                capturePending = false
                val cb = captureCallback
                captureCallback = null
                if (cb == null) return
                val buf = java.nio.ByteBuffer.allocateDirect(viewW * viewH * 4).order(java.nio.ByteOrder.nativeOrder())
                var readFbo = captureFbo
                if (!fboReady(readFbo)) readFbo = 0
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
                GLES30.glViewport(0, 0, viewW, viewH)
                if (readFbo != 0) {
                    blitShader.draw(mainFBO.readTex, outputFbo = captureFbo, width = viewW, height = viewH) {}
                }
                GLES30.glReadPixels(0, 0, viewW, viewH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                // fromRgbaBuffer packs GL's RGBA bytes into a defined-order bitmap (no
                // platform-specific ARGB_8888 swap) — fixes the magenta/static capture.
                val bmp = GlPixel.fromRgbaBuffer(buf, viewW, viewH)
                cb(bmp)
            }
        } catch (e: Exception) { Log.e("EaglesEye", "onDrawFrame: ${e.message}") }
    }

    override fun onFrameAvailable(st: SurfaceTexture?) { synchronized(this) { frame = true } }

    // Scene-driven effects (or recording) activate the 16x16 hotspot analysis.
    private fun needsSceneAnalysis(): Boolean {
        if (isRecording) return true
        if (activeEffects.any { it == "light_leak" || it == "lens_flare" || it == "light_rays" || it == "sunstreak" }) return true
        val fs = filmStyle ?: return false
        return fs.leakAmt > 0.001f
    }

    // Snap-to-brightest hotspot: the flare locks onto the single brightest
    // 16x16 cell (re-acquires only when another cell beats it by 1.25x or the
    // held cell fades to half its lock brightness) instead of hovering between
    // lights like a weighted centroid. Inside the locked cell a 3x3 weighted
    // centroid is clamped to +/-0.35 cell so the pivot tracks the sun smoothly
    // without ever drifting across a cell boundary.
    private fun analyzeScene(blend: Float = 0.30f, snap: Boolean = false) {
        val b = sceneBuf; b.position(0)
        val lums = sceneLums
        var peak = 0f
        var bestL = 0f; var bestI = -1
        for (i in 0 until 256) {
            val r = (b.get().toInt() and 0xFF) / 255f
            val g = (b.get().toInt() and 0xFF) / 255f
            val bl = (b.get().toInt() and 0xFF) / 255f
            b.get()
            val l = 0.299f * r + 0.587f * g + 0.114f * bl
            lums[i] = l
            sceneCellR[i] = r; sceneCellG[i] = g; sceneCellB[i] = bl
            if (l > peak) peak = l
            if (l > bestL) { bestL = l; bestI = i }
        }
        scenePeak = peak
        if (bestI < 0 || bestL < 0.12f) return
        val ax = bestI % 16; val ay = bestI / 16
        var cx: Int; var cy: Int
        if (snap || sceneLockCX < 0) {
            cx = ax; cy = ay; sceneLockLum = bestL
        } else {
            val heldL = lums[sceneLockCY * 16 + sceneLockCX]
            if (bestL > sceneLockLum * 1.25f || heldL < sceneLockLum * 0.5f) {
                cx = ax; cy = ay; sceneLockLum = bestL
            } else {
                cx = sceneLockCX; cy = sceneLockCY
            }
        }
        sceneLockCX = cx; sceneLockCY = cy
        var ws = 0f; var sxn = 0f; var syn = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val nx = (cx + dx).coerceIn(0, 15); val ny = (cy + dy).coerceIn(0, 15)
            val w = (lums[ny * 16 + nx] - 0.10f).coerceAtLeast(0f)
            ws += w
            sxn += w * (nx + 0.5f) / 16f
            syn += w * (15 - ny + 0.5f) / 16f
        }
        val ccx = (cx + 0.5f) / 16f; val ccy = (15 - cy + 0.5f) / 16f
        val tx: Float; val ty: Float
        if (ws > 0.001f) {
            tx = (sxn / ws).coerceIn(ccx - 0.35f / 16f, ccx + 0.35f / 16f)
            ty = (syn / ws).coerceIn(ccy - 0.35f / 16f, ccy + 0.35f / 16f)
        } else { tx = ccx; ty = ccy }
        sceneHotX += (tx - sceneHotX) * blend
        sceneHotY += (ty - sceneHotY) * blend
        val li = cy * 16 + cx
        val cr = sceneCellR[li]; val cg = sceneCellG[li]; val cb = sceneCellB[li]
        val mx = maxOf(cr, cg, cb, 0.001f)
        val boost = 1.25f
        sceneR = ((cr / mx) * boost).coerceIn(0.08f, 1f)
        sceneG = ((cg / mx) * boost).coerceIn(0.08f, 1f)
        sceneB = ((cb / mx) * boost).coerceIn(0.08f, 1f)
    }

    // True when the FBO we're about to read from is complete. Reads from an
    // incomplete FBO on some drivers are undocumented territory (native crashes
    // rather than clean GL errors), so every readback goes through here.
    private fun fboReady(fbo: Int): Boolean {
        try {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                Log.e("EaglesEye", "FBO $fbo incomplete status=$st")
                GpuDebugLog.logError("FBO", "fboReady($fbo) status=0x${Integer.toHexString(st)}")
                return false
            }
        } catch (e: Throwable) { return false }
        return true
    }

    // ── Scene analysis readback. Kept away from onDrawFrame so that a driver
    //    hiccup on one device can never take down the render loop: three bad
    //    reads in a row disable adaptive scene effects for the rest of the run
    //    (light leaks fall back to their static look). ──
    private var sceneFaults = 0

    private fun runSceneAnalysis() {
        if (sceneFaults >= 3 || sceneFbo < 0 || sceneTex < 0) return
        val sp = scenePass
        if (sp == null || !sp.isValid) return
        sceneCountdown++
        if (sceneCountdown % 2 != 0) return
        try {
            sp.draw(mainFBO.readTex, outputFbo = sceneFbo, width = 16, height = 16) {
                setFloat2("uCellSize", 1f / 16f, 1f / 16f)
            }
            if (!fboReady(sceneFbo)) { sceneFaults++; return }
            sceneBuf.position(0)
            GLES30.glReadPixels(0, 0, 16, 16, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, sceneBuf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            analyzeScene()
        } catch (e: Throwable) {
            sceneFaults++
            Log.e("EaglesEye", "scene analysis: ${e.message}")
        }
    }

    // One-shot hotspot analysis of an arbitrary texture (used by the still
    // capture path, where the live camera loop may not have run recently).
    // Strong blend: a freshly captured photo defines its own flare origin.
    private fun analyzeTextureForHotspot(tex: Int) {
        if (sceneFaults >= 3 || sceneFbo < 0 || sceneTex < 0) return
        val sp = scenePass
        if (sp == null || !sp.isValid) return
        try {
            sp.draw(tex, outputFbo = sceneFbo, width = 16, height = 16) {
                setFloat2("uCellSize", 1f / 16f, 1f / 16f)
            }
            if (!fboReady(sceneFbo)) { sceneFaults++; return }
            sceneBuf.position(0)
            GLES30.glReadPixels(0, 0, 16, 16, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, sceneBuf)
GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            analyzeScene(0.9f, snap = true)
        } catch (e: Throwable) {
            Log.e("EaglesEye", "capture hotspot: ${e.message}")
        }
    }

    // Film composite pass — extracted from onDrawFrame to keep the render loop
    // method small (small methods are also where JIT on Samsung devices has
    // historically crashed on huge generated code) and to isolate this pass.
    private fun runFilmComposite(fs: GlFilmStyle) {
        try {
            val drew = filmStylePass!!.draw(mainFBO.readTex, outputFbo = mainFBO.writeFbo, width = viewW, height = viewH) {
                setFloat("uGrain", fs.grain)
                setFloat("uVignette", fs.vignette)
                setFloat("uFade", fs.fade)
                setFloat("uWarmth", if (fs.warmth > 0f) 1f else 0f)
                setFloat("uMono", if (fs.mono) 1f else 0f)
                setFloat("uFSat", fs.filmSat)
                setFloat("uFContrast", fs.filmContrast)
                setFloat("uFLift", fs.filmLift)
                setFloat("uFRolloff", fs.filmRolloff)
                setFloat("uTintAlpha", fs.tintAlpha)
                setFloat3("uTint", fs.tintR, fs.tintG, fs.tintB)
                setFloat("uLeak", fs.leakAmt * (0.5f + scenePeak))
                setFloat("uAdaptive", if (fs.adaptive) 1f else 0f)
                setFloat2("uEntryPoint", sceneHotX, sceneHotY)
                setFloat3("uLeakColor", fs.leakR, fs.leakG, fs.leakB)
                setFloat("uTime", frameCount / 60f)
                setFloat("uFrame", (frameCount % 100000).toFloat())
            }
            if (drew) mainFBO.swap()
        } catch (e: Throwable) {
            Log.e("EaglesEye", "film composite: ${e.message}")
            GpuDebugLog.logError("FILM", "composite threw: ${e.message}")
        }
    }

    // KinoStreak pyramid — mirrors Streak.cs OnRenderImage exactly:
    // prefilter into a half-height buffer (vscale 1.5, threshold), then halve
    // the width each level with the 6-tap horizontal box filter (hscale 1.25)
    // until width <= 16, then upsample-combine coarse→fine with lerp(_,_,stretch)
    // level by level, and finally add streak * color * intensity * 5 onto source.
    private var streakFp16 = false

    // KinoStreak runs in HDR (ARGBHalf) in Unity; test for FP16 render targets
    // (EXT_color_buffer_float) so the faint streak tails survive in 8-bit-free precision.
    private fun testHalfFloatRender(): Boolean {
        return try {
            val t = IntArray(1); val f = IntArray(1)
            GLES30.glGenTextures(1, t, 0); GLES30.glGenFramebuffers(1, f, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, 32, 32, 0,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, t[0], 0)
            val ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
            GLES30.glDeleteTextures(1, t, 0); GLES30.glDeleteFramebuffers(1, f, 0)
            ok
        } catch (e: Throwable) { false }
    }

    private fun renderKinoStreak(
        srcTex: Int, outFbo: Int, w: Int, h: Int,
        threshold: Float, intensity: Float, stretch: Float,
        tr: Float, tg: Float, tb: Float, debug: Boolean = false,
        sunX: Float = -1f, sunY: Float = -1f, sunSize: Float = 0f, warp: Float = 0f
    ): Boolean {
        try {
            val sunMode = sunX >= 0f
            val pre = when {
                sunMode && streakFp16 -> sunPrePass
                sunMode -> sunPreSqrtPass ?: sunPrePass
                streakFp16 -> streakPrePass
                else -> streakPreSqrtPass ?: streakPrePass
            }
            val down = streakDownPass ?: return false
            val up = streakUpPass ?: return false
            val comp = when {
                sunMode && streakFp16 -> sunCompWarpPass
                sunMode -> sunCompWarpSqrtPass ?: sunCompWarpPass
                streakFp16 -> streakCompPass
                else -> streakCompSqrtPass ?: streakCompPass
            }
            if (pre == null || down == null || up == null || comp == null) return false
            if (!pre.isValid || !down.isValid || !up.isValid || !comp.isValid) return false
            val hh = (h / 2).coerceAtLeast(2)
            val widths = ArrayList<Int>()
            var lw = w
            widths.add(lw)
            while (lw > 16) { lw /= 2; widths.add(lw) }
            val n = widths.size
            val tex = IntArray(n); val fbo = IntArray(n)
            val uTex = IntArray(n); val uFbo = IntArray(n)
            GLES30.glGenTextures(n, tex, 0); GLES30.glGenFramebuffers(n, fbo, 0)
            GLES30.glGenTextures(n, uTex, 0); GLES30.glGenFramebuffers(n, uFbo, 0)
            for (i in 0 until n) {
                for (arr in intArrayOf(tex[i], uTex[i])) {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, arr)
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0,
                        if (streakFp16) GLES30.GL_RGBA16F else GLES30.GL_RGBA, widths[i], hh, 0,
                        GLES30.GL_RGBA, if (streakFp16) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE, null)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                }
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, uFbo[i])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, uTex[i], 0)
            }
            // Pass 0 — HDR headroom: boost brights 4x (repo input is HDR ~9x).
            // In FP16 this really goes above 1.0; on 8-bit fallback it clips at 1.0
            // (still ~3x more energy than a plain LDR prefilter).
            val gainTex = IntArray(1); val gainFbo = IntArray(1)
            GLES30.glGenTextures(1, gainTex, 0); GLES30.glGenFramebuffers(1, gainFbo, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gainTex[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0,
                if (streakFp16) GLES30.GL_RGBA16F else GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, if (streakFp16) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, gainFbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, gainTex[0], 0)
            streakGainPass?.draw(srcTex, outputFbo = gainFbo[0], width = w, height = h) {}
            // Pass 1 — prefilter + threshold, half height. On 8-bit fallback the
            // sqrt-companded prefilter/composite pair preserves faint tails.
            pre?.draw(gainTex[0], outputFbo = fbo[0], width = widths[0], height = hh) {
                setFloat("uThreshold", if (streakFp16) threshold else 0.6f)
                setFloat2("uResolution", w.toFloat(), h.toFloat())
                if (sunMode) {
                    setFloat2("uSunPos", sunX, sunY)
                    setFloat("uSunSize", sunSize)
                }
            }
            // Pass 2 — 6-tap horizontal box downsample, width halved per level
            for (i in 1 until n) {
                down.draw(tex[i - 1], outputFbo = fbo[i], width = widths[i], height = hh) {
                    setFloat2("uResolution", widths[i - 1].toFloat(), hh.toFloat())
                }
            }
            // Pass 3 — upsample-combine: coarse accumulator lerp'd into each finer level
            for (i in n - 2 downTo 0) {
                val coarse = if (i == n - 2) tex[n - 1] else uTex[i + 1]
                up.draw(coarse, inputTex2 = tex[i], outputFbo = uFbo[i], width = widths[i], height = hh) {
                    setFloat("uStretch", stretch)
                }
            }
            // Pass 4 — additive composite onto the source
            comp.draw(uTex[0], inputTex2 = srcTex, outputFbo = outFbo, width = w, height = h) {
                setFloat3("uColor", tr, tg, tb)
                setFloat("uIntensity", intensity)
                if (sunMode) setFloat("uWarp", warp)
            }
            // Debug: raw streak accumulator miniature in the bottom-left corner
            // of the live viewfinder (red channel) — verifies the pyramid output.
            if (debug) {
                streakMonitorPass?.draw(uTex[0], outputFbo = outFbo, width = 240, height = 120) {}
            }
            GLES30.glDeleteTextures(n, tex, 0); GLES30.glDeleteFramebuffers(n, fbo, 0)
            GLES30.glDeleteTextures(n, uTex, 0); GLES30.glDeleteFramebuffers(n, uFbo, 0)
            GLES30.glDeleteTextures(1, gainTex, 0); GLES30.glDeleteFramebuffers(1, gainFbo, 0)
            return true
        } catch (e: Throwable) {
            Log.e("EaglesEye", "kinostreak: ${e.message}")
            GpuDebugLog.logError("STREAK", "renderKinoStreak threw: ${e.message}")
            return false
        }
    }

    // ── Effect-video recording: processed frames → H.264 via surface input ──
    fun startRecording(r: GLVideoRecorder, onStarted: (Boolean) -> Unit) {
        try {
            if (!initialized) { onStarted(false); return }
            recorder = r
            recW = r.width; recH = r.height
            recStartNs = System.nanoTime()
            setupRecEgl()
            isRecording = true
            onStarted(true)
        } catch (e: Exception) {
            Log.e("EaglesEye", "startRecording: ${e.message}")
            recorder = null; isRecording = false
            onStarted(false)
        }
    }

    fun stopRecording(r: GLVideoRecorder?, onDone: (Uri?) -> Unit) {
        if (r != null) { r.stop(); onDone(null) }
        recorder = null
        isRecording = false
        try {
            val disp = EGL14.eglGetCurrentDisplay()
            recEglSurface?.let { EGL14.eglDestroySurface(disp, it) }
            recEglContext?.let { EGL14.eglDestroyContext(disp, it) }
        } catch (e: Exception) {}
        recEglSurface = null; recEglContext = null; recEglDisplay = null
    }

    private fun setupRecEgl() {
        val inputSurf = recorder?.inputSurface ?: return
        val disp = EGL14.eglGetCurrentDisplay()
        val shared = EGL14.eglGetCurrentContext()
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        var found = false
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
            found = EGL14.eglChooseConfig(disp, attrs, 0, configs, 1, 1, num, 0) && num[0] > 0
            GpuDebugLog.log("EGL", "recorder chooseConfig es3+recordable found=$found n=${num[0]} err=0x${Integer.toHexString(EGL14.eglGetError())}")
            if (!found) {
                // Keep ES3 bit: the shared recording context below requests CLIENT_VERSION 3.
                val attrs2 = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_NONE)
                found = EGL14.eglChooseConfig(disp, attrs2, 0, configs, 1, 1, num, 0) && num[0] > 0
                GpuDebugLog.log("EGL", "recorder fallback es3 found=$found n=${num[0]} err=0x${Integer.toHexString(EGL14.eglGetError())}")
            }
            if (!found || num[0] <= 0) { GpuDebugLog.logError("EGL", "recorder no config"); return }
            val cfg = configs[0] ?: return
            val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            val ctx = EGL14.eglCreateContext(disp, cfg, shared, ctxAttrs, 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) { GpuDebugLog.logError("EGL", "recorder eglCreateContext failed err=0x${Integer.toHexString(EGL14.eglGetError())}"); return }
            val eglSurf = EGL14.eglCreateWindowSurface(disp, cfg, inputSurf, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurf == EGL14.EGL_NO_SURFACE) { GpuDebugLog.logError("EGL", "recorder eglCreateWindowSurface failed err=0x${Integer.toHexString(EGL14.eglGetError())}"); return }
            GpuDebugLog.log("EGL", "recorder surface ready")
            recEglDisplay = disp; recEglContext = ctx; recEglSurface = eglSurf
        }

    // Composite the final processed frame into the encoder surface, called right
    // after the screen blit, so recorded video == what the viewfinder shows.
    private fun recordFrame(r: GLVideoRecorder) {
        val eglSurf = recEglSurface ?: return
        val ctx = recEglContext ?: return
        val disp = EGL14.eglGetCurrentDisplay()
        val prevCtx = EGL14.eglGetCurrentContext()
        val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(disp, eglSurf, eglSurf, ctx)) return
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val sA = if (viewH > 0) viewW.toFloat() / viewH.toFloat() else 1f
        val rA = if (recH > 0) recW.toFloat() / recH.toFloat() else 1f
        if (sA > 0f && rA > 0f && sA != rA) {
            if (sA > rA) {
                val h = (recH * (rA / sA)).toInt().coerceAtLeast(1)
                GLES30.glViewport(0, (recH - h) / 2, recW, h)
            } else {
                val w = (recW * (sA / rA)).toInt().coerceAtLeast(1)
                GLES30.glViewport((recW - w) / 2, 0, w, recH)
            }
        } else {
            GLES30.glViewport(0, 0, recW, recH)
        }
        blitShader.draw(mainFBO.readTex, outputFbo = 0, width = recW, height = recH) {}
        val tNs = System.nanoTime() - recStartNs
        EGLExt.eglPresentationTimeANDROID(disp, eglSurf, tNs / 1000L)
        EGL14.eglSwapBuffers(disp, eglSurf)
        EGL14.eglMakeCurrent(disp, prevDraw, prevRead, prevCtx)
    }

    private fun buildOesShader(): String = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform vec3 uWB;
in vec2 vUV;
layout(location = 0) out vec4 fragColor;
void main(){
    fragColor = vec4(texture(uTexture, vUV).rgb * uWB, 1.0);
}"""

    // STMatrix vertex shader: applies SurfaceTexture transform (rotation/mirror),
// then letterboxes (FIT) so the ENTIRE stream is visible with black bars.
    private fun buildOesVertex(): String = """#version 300 es
layout(location = 0) in vec2 aPosition;
uniform mat4 uSTMatrix;
uniform vec2 uFit;
out vec2 vUV;
void main(){
    vec4 uv4 = uSTMatrix * vec4(aPosition * 0.5 + 0.5, 0.0, 1.0);
    vec2 uv = uv4.xy / uv4.w;
    uv = (uv - 0.5) * uFit + 0.5;
    vUV = uv;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}"""

    // CPU-side FIT (letterbox): stream effective aspect scaled to FIT inside viewport.
    // Returns (scaleX, scaleY) where one is 1.0, the other ≤1.0 (black bars on that axis).
    private fun oesMapping(): FloatArray {
        val qtr = (rotationDeg / 90).toInt() and 3
        var sEff = if (camW > 0 && camH > 0) camW.toFloat() / camH.toFloat() else 1f
        if (qtr % 2 == 1) sEff = 1f / sEff
        val vA = viewW.toFloat() / viewH.toFloat().coerceAtLeast(1f)
        // LETTERBOX: stream fits entirely, black bars on the longer axis.
        // If stream taller (sEff < vA): black bars left/right → scaleX = sEff/vA, scaleY = 1
        // If stream wider (sEff > vA): black bars top/bottom → scaleX = 1, scaleY = vA/sEff
        val scaleX = if (sEff < vA) sEff / vA else 1f
        val scaleY = if (sEff > vA) vA / sEff else 1f
        return floatArrayOf(scaleX, scaleY)
    }

    private var processInputTex = -1
    private var processFbo = -1
    fun processBitmap(input: Bitmap, callback: (Bitmap) -> Unit) {
        val w = input.width; val h = input.height
        if (processInputTex < 0) {
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); processInputTex = t[0]
            val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); processFbo = f[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, processInputTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        // Unambiguous ARGB_8888 -> RGBA byte pack. Avoids the platform-dependent
        // copyPixelsToBuffer channel/stride swap that caused magenta / static artifacts.
        val buf = GlPixel.toRgbaBuffer(input)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, processFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, processInputTex, 0)
        var read = processInputTex
        val tmpKeepTex = mutableListOf<Int>()
        val tmpKeepFbo = mutableListOf<Int>()
        var tmpBlurTex = -1
        var tmpBlurFbo = -1
        fun ensureTmpBlur() {
            if (tmpBlurTex >= 0) return
            val bt = IntArray(1); GLES30.glGenTextures(1, bt, 0); tmpBlurTex = bt[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tmpBlurTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            val bf = IntArray(1); GLES30.glGenFramebuffers(1, bf, 0); tmpBlurFbo = bf[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tmpBlurFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tmpBlurTex, 0)
            GpuDebugLog.checkFbo("tileBlurFbo")
        }
        for (name in Shaders.ALL_PHASE_1.keys) {
            if (name !in activeEffects || name !in effectPasses) continue
            val tmpTex = IntArray(1); GLES30.glGenTextures(1, tmpTex, 0)
            val tmpFbo = IntArray(1); GLES30.glGenFramebuffers(1, tmpFbo, 0)
            tmpKeepTex += tmpTex[0]; tmpKeepFbo += tmpFbo[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tmpTex[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tmpFbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tmpTex[0], 0)
            GpuDebugLog.checkFbo("tilePhase1/$name")
            val drew = effectPasses[name]!!.draw(read, outputFbo = tmpFbo[0], width = w, height = h) {
                (effectParams[name] ?: emptyMap()).forEach { (k, v) -> setFloat(if (k.startsWith("u")) k else "u${k.replaceFirstChar { it.uppercaseChar() }}", v) }
                if (name == "grain") setFloat("uFrameSeed", (frameCount % 10000).toFloat())
                if (name == "dust") setFloat("uSeed", (frameCount % 1000).toFloat())
                if (name == "vhs") {
                    setFloat("uTime", frameCount / 60f)
                    setFloat("uTracking", effectParams["vhs"]?.get("tracking") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.7f)
                    setFloat("uChromaBleed", effectParams["vhs"]?.get("chromaBleed") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.8f)
                    setFloat("uNoise", effectParams["vhs"]?.get("noise") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.6f)
                    setFloat("uScanline", effectParams["vhs"]?.get("scanline") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.5f)
                    setFloat("uWobble", effectParams["vhs"]?.get("wobble") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.4f)
                    setFloat("uVignette", effectParams["vhs"]?.get("vignette") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.6f)
                    setFloat("uColorShift", effectParams["vhs"]?.get("colorShift") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.5f)
                    setFloat("uHeadSwitch", effectParams["vhs"]?.get("headSwitch") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.8f)
                    setFloat("uBlur", effectParams["vhs"]?.get("blur") ?: (effectParams["vhs"]?.get("intensity") ?: 0f) * 0.3f)
                }
                if (name == "duotone" && currentPreset != null) { setFloat3("uShadowColor", currentPreset!!.duotoneShadow[0], currentPreset!!.duotoneShadow[1], currentPreset!!.duotoneShadow[2]); setFloat3("uHighlightColor", currentPreset!!.duotoneHighlight[0], currentPreset!!.duotoneHighlight[1], currentPreset!!.duotoneHighlight[2]) }
                if (name == "prism" && currentPreset != null) { setFloat3("uTint1", currentPreset!!.prismTint1[0], currentPreset!!.prismTint1[1], currentPreset!!.prismTint1[2]); setFloat3("uTint2", currentPreset!!.prismTint2[0], currentPreset!!.prismTint2[1], currentPreset!!.prismTint2[2]) }
            }
            // Keep the tile chain on the previous valid texture if the pass failed.
            if (drew) read = tmpTex[0]
            else { GLES30.glDeleteTextures(1, tmpTex, 0); GLES30.glDeleteFramebuffers(1, tmpFbo, 0); tmpKeepTex.removeLast(); tmpKeepFbo.removeLast() }
        }
        for ((name, pass) in phase2Passes) {
            if (name !in activeEffects) continue
            val p = effectParams[name] ?: emptyMap()
            val tmpTex = IntArray(1); GLES30.glGenTextures(1, tmpTex, 0)
            val tmpFbo = IntArray(1); GLES30.glGenFramebuffers(1, tmpFbo, 0)
            tmpKeepTex += tmpTex[0]; tmpKeepFbo += tmpFbo[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tmpTex[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tmpFbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tmpTex[0], 0)
            GpuDebugLog.checkFbo("tilePhase2/$name")
            var drew = false
            when (name) {
                "bloom", "halation", "mist" -> drew = pass.draw(read, inputTex2 = tmpBlurTex, outputFbo = tmpFbo[0], width = w, height = h) { setFloat("uIntensity", p["intensity"] ?: 0.5f) }
                "tilt_shift" -> drew = pass.draw(read, inputTex2 = tmpBlurTex, outputFbo = tmpFbo[0], width = w, height = h) { setFloat("uFocusY", p["focusY"] ?: 0.5f); setFloat("uFocusWidth", p["focusWidth"] ?: 0.2f); setFloat("uFeather", p["feather"] ?: 0.15f) }
                "dreamy" -> drew = pass.draw(read, inputTex2 = tmpBlurTex, outputFbo = tmpFbo[0], width = w, height = h) { setFloat("uIntensity", p["intensity"] ?: 0.5f) }
                "anamorphic" -> drew = pass.draw(read, outputFbo = tmpFbo[0], width = w, height = h) { setFloat("uStreakLength", p["streakLength"] ?: 0.03f); setFloat3("uTint", 1f, 1f, 1f) }
                "streak" -> {
                    drew = renderKinoStreak(read, tmpFbo[0], w, h,
                        p["threshold"] ?: 1.0f, p["intensity"] ?: 0.43f, p["stretch"] ?: 0.75f,
                        0.55f, 0.55f, 1f)
                }
                "sunstreak" -> if (scenePeak > 0.15f) {
                    drew = renderKinoStreak(read, tmpFbo[0], w, h,
                        p["threshold"] ?: 1.0f, p["intensity"] ?: 0.46f, p["stretch"] ?: 0.75f,
                        1f, 0.8f, 0.55f,
                        sunX = sceneHotX, sunY = sceneHotY,
                        sunSize = p["sunSize"] ?: 0.075f, warp = (p["warp"] ?: 0.4f) * 0.12f)
                }
            }
            if (drew) read = tmpTex[0]
            else { GLES30.glDeleteTextures(1, tmpTex, 0); GLES30.glDeleteFramebuffers(1, tmpFbo, 0); tmpKeepTex.removeLast(); tmpKeepFbo.removeLast() }
        }
        // Scene-driven effects must know this exact still's bright region:
        // the live-loop hotspot may be stale (camera sleeps while editing).
        if (activeEffects.any { it == "light_leak" || it == "lens_flare" || it == "light_rays" || it == "sunstreak" }) {
            analyzeTextureForHotspot(read)
        }
        // Phase 5 effects
        for (name in Shaders.ALL_PHASE_5.keys) {
            if (name !in activeEffects || name !in effectPasses) continue
            val tmpTex = IntArray(1); GLES30.glGenTextures(1, tmpTex, 0)
            val tmpFbo = IntArray(1); GLES30.glGenFramebuffers(1, tmpFbo, 0)
            tmpKeepTex += tmpTex[0]; tmpKeepFbo += tmpFbo[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tmpTex[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tmpFbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tmpTex[0], 0)
            if (name == "bokeh") {
                val deR = depthEngine
                val realB = deR?.enabled == true && deR?.mode == FusionPipelineEngine.Mode.BOKEH
                // With the real depth engine active the still gets the true depth bokeh
                // below — the cheap fake pass is never baked (double-blur). With "bake
                // bokeh" off, neither fake nor real is saved; the photo stays clean.
                if (realB || deR?.saveToPhoto == false) continue
                // Real lens blur in the still-capture path — the live fake FRAG_BOKEH
                // is only a cheap preview; the saved photo gets the true circle blur.
                val sz = effectParams[name]?.get("size") ?: 24f
                bokehRealPass.draw(read, outputFbo = tmpFbo[0], width = w, height = h) {
                    setFloat("uAmount", (sz / 48f).coerceIn(0f, 1f) * 0.9f)
                    setFloat("uFocusY", 0.5f)
                    setFloat("uFocusW", 0.14f)
                    setFloat("uFeather", 0.3f)
                    setFloat2("uResolution", w.toFloat(), h.toFloat())
                }
                read = tmpTex[0]
                continue
            }
            effectPasses[name]!!.draw(read, inputTex2 = if (name == "double_exposure") memTex else -1, outputFbo = tmpFbo[0], width = w, height = h) {
                (effectParams[name] ?: emptyMap()).forEach { (k, v) -> setFloat(if (k.startsWith("u")) k else "u${k.replaceFirstChar { it.uppercaseChar() }}", v) }
                if (name == "glitch" || name == "starburst" || name == "fat_pixel" || name == "terminal") setFloat("uTime", frameCount / 60f)
                if (name == "light_leak") {
                    setFloat("uTime", frameCount / 60f)
                    setFloat2("uEntryPoint", sceneHotX, sceneHotY)
                    val lc = currentPreset?.lightLeakColor ?: floatArrayOf(1f, 0.4f, 0f)
                    setFloat3("uLeakColor", lc[0], lc[1], lc[2])
                }
                if (name == "lens_flare") {
                    setFloat("uTime", frameCount / 60f)
                    setFloat2("uSourcePos", sceneHotX, sceneHotY)
                    setFloat3("uSourceColor", sceneR, sceneG, sceneB)
                    setFloat("uAnamorphic", effectParams[name]?.get("anamorphic") ?: 0.4f)
                }
                if (name == "light_rays") {
                    setFloat("uTime", frameCount / 60f)
                    setFloat2("uSourcePos", sceneHotX, sceneHotY)
                    setFloat3("uSourceColor", sceneR, sceneG, sceneB)
                    setFloat2("uResolution", w.toFloat(), h.toFloat())
                }
                if (name == "bw_grain") setFloat3("uWeights", 0.299f, 0.587f, 0.114f)
                if (name == "focus_peak" || name == "fat_pixel" || name == "halftone" || name == "cmyk_dots" || name == "teletext" || name == "terminal" || name == "bokeh") setFloat2("uResolution", w.toFloat(), h.toFloat())
                if (name == "sharpen") setFloat2("uResolution", w.toFloat(), h.toFloat())
            }
            read = tmpTex[0]
        }
        // Phase 6 (instant/retro)
        for (name in Shaders.ALL_PHASE_6.keys) {
            if (name !in activeEffects || name !in effectPasses) continue
            val tmpTex = IntArray(1); GLES30.glGenTextures(1, tmpTex, 0)
            val tmpFbo = IntArray(1); GLES30.glGenFramebuffers(1, tmpFbo, 0)
            tmpKeepTex += tmpTex[0]; tmpKeepFbo += tmpFbo[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tmpTex[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tmpFbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tmpTex[0], 0)
            effectPasses[name]!!.draw(read, outputFbo = tmpFbo[0], width = w, height = h) {
                (effectParams[name] ?: emptyMap()).forEach { (k, v) -> setFloat(if (k.startsWith("u")) k else "u${k.replaceFirstChar { it.uppercaseChar() }}", v) }
                if (name == "retro") setFloat("uTime", frameCount / 60f)
            }
            read = tmpTex[0]
        }
        // NOTE: frames + date stamps are intentionally NOT baked here — the capture
        // pipeline applies them once on the CPU (FilmEngine.applyToCapture), so the
        // saved photo matches the live view without double frames/stamps.

        // ── Real depth bokeh for still capture ────────────────────────────────────
        // When the live camera was in depth-BOKEH mode, bake the same portrait blur the
        // user saw into the saved photo. Runs a synchronous depth inference on the final
        // processed still, then applies the depth-driven blur shader. Any failure is
        // swallowed so the capture always saves (just without bokeh).
        val de = depthEngine
        val ctx = appContext
        // Only bake when the user wants it ("Bake blur into photos") and the live
        // camera was actually in depth-BOKEH mode.
        if (ctx != null && de?.enabled == true && de.mode == FusionPipelineEngine.Mode.BOKEH && AppSettings.bakeBokeh(ctx)) {
            try {
                val di = de.inferDepth(read)
                val dt = de.depthTexture
                if (di != null && dt != 0) {
                    val tmpTex = IntArray(1); GLES30.glGenTextures(1, tmpTex, 0)
                    val tmpFbo = IntArray(1); GLES30.glGenFramebuffers(1, tmpFbo, 0)
                    tmpKeepTex += tmpTex[0]; tmpKeepFbo += tmpFbo[0]
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tmpTex[0])
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tmpFbo[0])
                    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tmpTex[0], 0)
                    val strength = (de.blurStrength).coerceIn(0.15f, 1f)
                    depthBokehPass.draw(read, inputTex3 = dt, outputFbo = tmpFbo[0], width = w, height = h) {
                        setFloat("uFocusDepth", di.focusDepth)
                        setFloat("uRange", AppSettings.bokehRange(ctx))
                        setFloat("uAmount", strength)
                        setFloat("uBlades", 7f)
                        setFloat2("uResolution", w.toFloat(), h.toFloat())
                        val aspect = if (h > 0) w.toFloat() / h.toFloat() else 1f
                        val (dsx, dox) = if (aspect >= 1f) aspect to (0.5f - 0.5f * aspect) else 1f to 0f
                        val (dsy, doy) = if (aspect < 1f) (1f / aspect) to (0.5f - 0.5f / aspect) else 1f to 0f
                        setFloat2("uDS", dsx, dsy)
                        setFloat2("uDO", dox, doy)
                    }
                    read = tmpTex[0]
                }
            } catch (e: Exception) {
                android.util.Log.e("CameraGLRenderer", "capture depth bokeh failed", e)
            }
        }
        var out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val readBuf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, processFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, read, 0)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, readBuf)
        out = GlPixel.fromRgbaBuffer(readBuf, w, h)
        if (tmpKeepTex.isNotEmpty()) {
            GLES30.glDeleteTextures(tmpKeepTex.size, tmpKeepTex.toIntArray(), 0)
            GLES30.glDeleteFramebuffers(tmpKeepFbo.size, tmpKeepFbo.toIntArray(), 0)
        }
        if (tmpBlurTex >= 0) { GLES30.glDeleteTextures(1, intArrayOf(tmpBlurTex), 0); GLES30.glDeleteFramebuffers(1, intArrayOf(tmpBlurFbo), 0) }
        if (activeEffects.contains("double_exposure") && memFbo >= 0 && blitShader.isValid) {
            blitShader.draw(read, outputFbo = memFbo, width = w, height = h) {}
        }
        callback(out)
    }

    private fun applyPreset(p: FilmPreset?) {
        try {
            if (p == null) { activeEffects = mutableSetOf(); effectParams = mutableMapOf(); return }
            val active = mutableSetOf<String>()
            val params = mutableMapOf<String, MutableMap<String, Float>>()

            fun add(name: String, intensity: Float, vararg extra: Pair<String, Float>) {
                if (intensity <= 0f) return
                active.add(name)
                params[name] = mutableMapOf("intensity" to intensity, *extra)
            }

            add("crt", p.crtIntensity)
            add("fisheye", p.fisheye, "strength" to p.fisheye)
            add("vignette", p.vignetteIntensity, "radius" to p.vignetteRadius, "softness" to p.vignetteSoftness)
            add("ca", p.chromaticIntensity, "strength" to p.chromaticIntensity)
            add("grain", p.grainIntensity, "size" to p.grainSize)
            add("dust", p.dustIntensity, "seed" to 0f)
            add("vhs", p.vhsIntensity, "tracking" to (p.vhsIntensity * p.vhsTracking), "chromaBleed" to (p.vhsIntensity * 0.8f), "noise" to (p.vhsIntensity * 0.6f), "scanline" to (p.vhsIntensity * 0.5f), "wobble" to (p.vhsIntensity * 0.4f), "vignette" to (p.vhsIntensity * 0.6f), "colorShift" to (p.vhsIntensity * 0.5f), "headSwitch" to (p.vhsIntensity * 0.8f), "blur" to (p.vhsIntensity * 0.3f))
            add("prism", p.prismIntensity, "angle" to p.prismAngle, "offset" to p.prismOffset, "opacity" to p.prismIntensity)
            add("duotone", p.duotoneIntensity)
            add("bleach", p.bleachIntensity)
            add("cross", p.crossProcessIntensity)
            add("warp", p.warpIntensity, "uZoom" to p.warpIntensity, "uRotation" to (p.warpIntensity * 3.14159f))
            add("bloom", p.bloomIntensity)
            add("halation", p.halationIntensity)
            add("mist", p.mistIntensity)
            add("tilt_shift", p.tiltShiftIntensity)
            add("sharpen", p.sharpenIntensity)
            add("streak", p.streakIntensity, "threshold" to 1.0f, "intensity" to (0.2f + p.streakIntensity * 0.35f), "stretch" to (0.6f + p.streakSpread * 0.3f))
            add("sunstreak", p.sunStreakIntensity, "intensity" to (0.25f + p.sunStreakIntensity * 0.35f), "warp" to p.sunStreakWarp, "sunSize" to 0.075f)
            add("light_rays", p.lightRaysIntensity)
            add("split_tone", p.splitToneIntensity)
            add("faded_film", p.fadedFilmIntensity)
            add("wide_angle", p.wideAngleIntensity, "strength" to p.wideAngleIntensity)
            add("double_exposure", p.doubleExposureIntensity, "mix" to p.doubleExposureIntensity)
            add("glitch", p.glitchIntensity)
            add("light_leak", p.lightLeakIntensity)
            add("bw_grain", p.bwIntensity)
            add("color_shift", p.colorShift)
            add("soft_focus", p.softFocus)
            add("focus_peak", p.focusPeakIntensity)
            add("false_color", p.falseColorIntensity)
            add("kaleido", p.kaleidoIntensity, "slices" to p.kaleidoSlices)
            if (p.instantIntensity > 0f) { active.add("instant"); params["instant"] = mutableMapOf("intensity" to p.instantIntensity) }
            if (p.retroIntensity > 0f) { active.add("retro"); params["retro"] = mutableMapOf("intensity" to p.retroIntensity) }

            add("in_color", p.inColorIntensity, "warmth" to p.inColorWarmth)
            add("1bit", p.oneBitIntensity, "scale" to p.oneBitScale)
            add("fat_pixel", p.fatPixelIntensity, "pixelSize" to p.fatPixelSize, "glitch" to p.fatPixelGlitch, "jitter" to p.fatPixelGlitch, "rgbSplit" to (p.fatPixelGlitch * 0.05f))
            add("halftone", p.halftoneIntensity, "dotSize" to p.halftoneSize, "angle" to p.halftoneAngle)
            add("cmyk_dots", p.cmykIntensity, "dotSize" to p.cmykSize)
            add("teletext", p.teletextIntensity)
            add("terminal", p.terminalIntensity, "colorHue" to p.terminalColor)
            add("bokeh", p.bokehIntensity, "size" to p.bokehSize, "threshold" to p.bokehThreshold)

            add("motion_blur", p.motionBlurIntensity)
            add("iso_grain", p.isoGrainIntensity)
            add("gyro_leak", p.gyroLeakIntensity)
            add("velvia", p.velviaIntensity)
            add("portra800", p.portraIntensity)
            add("winter", p.winterIntensity)
            add("obsidian", p.obsidianIntensity)
            add("dreamy", p.dreamyIntensity)

            activeEffects = active
            effectParams = params
            Log.i("EaglesEye", "applyPreset: active=$active")
        } catch (e: Throwable) {
            Log.e("EaglesEye", "applyPreset", e)
            GpuDebugLog.logError("GL", "applyPreset threw: ${e.message}")
        }
    }

    // ── Effect tile previews: render ONE effect onto a shared test chart so the
    //    sheet's tiles show the real look instead of hand-drawn art. Runs on the
    //    GL thread (via queueEvent); the callback delivers the bitmap. ──
    private var tileChartTex = -1
    private var tileOutTex = -1
    private var tileFbo = -1
    private var tileBlurTex2 = -1
    private var tileBlurFbo2 = -1
    private var tileBlur: SeparableBlur? = null
    private var processBlur: SeparableBlur? = null

    private fun makeChart(): Bitmap {
        val w = 192; val h = 144
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val g = android.graphics.Paint()
        g.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF414148.toInt(), 0xFF6D6D76.toInt(), 0xFF2A2A2F.toInt()),
            floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), g)
        // bright warm light source top-right (feeds bloom / leak / flare / rays)
        val sun = android.graphics.Paint()
        sun.shader = android.graphics.RadialGradient(w * 0.78f, h * 0.26f, w * 0.42f,
            intArrayOf(0xFFFFF2C8.toInt(), 0xFFFFE9B0.toInt(), 0x00000000.toInt()),
            floatArrayOf(0f, 0.4f, 1f), android.graphics.Shader.TileMode.CLAMP)
        cv.drawCircle(w * 0.78f, h * 0.26f, w * 0.42f, sun)
        // subject: circle + rounded box (same as the old art-tile base)
        val sub = android.graphics.Paint().apply { color = 0xFFB8B8C0.toInt() }
        cv.drawCircle(w * 0.5f, h * 0.42f, h * 0.15f, sub)
        val box = android.graphics.Paint().apply { color = 0xFFB8B8C0.toInt() }
        cv.drawRoundRect(w * 0.30f, h * 0.60f, w * 0.70f, h * 0.80f, h * 0.06f, h * 0.06f, box)
        // a couple of colored chips so color-affecting effects have something to bite on
        val chip = android.graphics.Paint().apply { color = 0xFFE4A94B.toInt() }
        cv.drawRect(w * 0.10f, h * 0.62f, w * 0.22f, h * 0.78f, chip)
        val chip2 = android.graphics.Paint().apply { color = 0xFF4B8CE4.toInt() }
        cv.drawRect(w * 0.78f, h * 0.62f, w * 0.90f, h * 0.78f, chip2)
        return bmp
    }

    private fun fallbackTile(w: Int, h: Int): Bitmap = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val cv = android.graphics.Canvas(b)
            val p = android.graphics.Paint().apply { color = 0xFF3A3A40.toInt() }
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
    } catch (e: Throwable) { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    fun renderTilePreview(name: String, wIn: Int, hIn: Int, cb: (Bitmap?) -> Unit) {
        val w = wIn.coerceIn(16, 512); val h = hIn.coerceIn(16, 512)
        try {
            if (!initialized) { cb(null); return }
            val pass = phase2Passes[name] ?: effectPasses[name]
            if (pass == null || !pass.isValid) { cb(null); return }
            if (tileChartTex < 0) {
                val chart = makeChart()
                val t = IntArray(1); GLES30.glGenTextures(1, t, 0); tileChartTex = t[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tileChartTex)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                val buf = GlPixel.toRgbaBuffer(chart)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, chart.width, chart.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
                val ot = IntArray(1); GLES30.glGenTextures(1, ot, 0); tileOutTex = ot[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tileOutTex)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                val bt = IntArray(1); GLES30.glGenTextures(1, bt, 0); tileBlurTex2 = bt[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tileBlurTex2)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); tileFbo = f[0]
                val bf = IntArray(1); GLES30.glGenFramebuffers(1, bf, 0); tileBlurFbo2 = bf[0]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tileFbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tileOutTex, 0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tileBlurFbo2)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tileBlurTex2, 0)
                tileBlur = SeparableBlur(w, h)
            }
            val blurTex = if (name in Shaders.ALL_PHASE_2) {
                tileBlur?.blur(tileChartTex, tileBlurFbo2, w, h)
                tileBlurTex2
            } else -1
            val p = effectParams[name] ?: emptyMap()
            val f2 = currentPreset
            pass.draw(tileChartTex, inputTex2 = blurTex, outputFbo = tileFbo, width = w, height = h) {
                setFloat("uTime", (System.nanoTime() % 1000000000L) / 1e9f.toFloat())
                setFloat("uResolutionX", w.toFloat()); setFloat("uResolutionY", h.toFloat())
                setFloat2("uResolution", w.toFloat(), h.toFloat())
                if (name == "crt") { setFloat("uTime", 0.3f) }
                if (name == "vhs") {
                    setFloat("uTracking", p["tracking"] ?: 0.24f); setFloat("uChromaBleed", p["chromaBleed"] ?: 0.24f)
                    setFloat("uNoise", p["noise"] ?: 0.18f); setFloat("uScanline", p["scanline"] ?: 0.15f)
                    setFloat("uWobble", p["wobble"] ?: 0.12f); setFloat("uVignette", p["vignette"] ?: 0.18f)
                    setFloat("uColorShift", p["colorShift"] ?: 0.15f); setFloat("uHeadSwitch", p["headSwitch"] ?: 0.24f)
                    setFloat("uBlur", p["blur"] ?: 0.09f)
                }
                if (name == "grain") setFloat("uFrameSeed", 7f)
                if (name == "dust") setFloat("uSeed", 13f)
                if (name == "streak") {
                    setFloat("uThreshold", p["threshold"] ?: 0.5f)
                    setFloat("uIntensity", p["intensity"] ?: 1.2f)
                    setFloat("uStretch", 0.02f + (p["stretch"] ?: 0.75f) * 0.10f)
                    setFloat3("uTint", 1f, 0.95f, 0.85f)
                }
                if (name == "sharpen") {
                    setFloat("uIntensity", p["intensity"] ?: 0.4f)
                    setFloat2("uResolution", w.toFloat(), h.toFloat())
                }
                if (name == "light_leak") {
                    setFloat2("uEntryPoint", 0.78f, 0.26f)
                    val lc = f2?.lightLeakColor ?: floatArrayOf(1f, 0.55f, 0.1f)
                    setFloat3("uLeakColor", lc[0], lc[1], lc[2])
                }
                if (name == "lens_flare") {
                    setFloat2("uSourcePos", 0.78f, 0.26f)
                    val sc = f2?.lightLeakColor ?: floatArrayOf(1f, 0.8f, 0.5f)
                    setFloat3("uSourceColor", sc[0], sc[1], sc[2])
                    setFloat("uAnamorphic", p["anamorphic"] ?: 0.4f)
                }
                if (name == "light_rays") {
                    setFloat2("uSourcePos", 0.78f, 0.26f)
                    val sc = f2?.lightLeakColor ?: floatArrayOf(1f, 0.8f, 0.5f)
                    setFloat3("uSourceColor", sc[0], sc[1], sc[2])
                }
                if (name == "bw_grain") setFloat3("uWeights", 0.299f, 0.587f, 0.114f)
                if (name == "duotone" && f2 != null) { setFloat3("uShadowColor", f2.duotoneShadow[0], f2.duotoneShadow[1], f2.duotoneShadow[2]); setFloat3("uHighlightColor", f2.duotoneHighlight[0], f2.duotoneHighlight[1], f2.duotoneHighlight[2]) }
                if (name == "prism" && f2 != null) { setFloat3("uTint1", f2.prismTint1[0], f2.prismTint1[1], f2.prismTint1[2]); setFloat3("uTint2", f2.prismTint2[0], f2.prismTint2[1], f2.prismTint2[2]) }
                p.forEach { (k, v) -> setFloat(if (k.startsWith("u")) k else "u${k.replaceFirstChar { it.uppercaseChar() }}", v) }
            }
if (!fboReady(tileFbo)) { cb(null); return }
            var out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val buf = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, tileFbo)
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            out = GlPixel.fromRgbaBuffer(buf, w, h)
            // glReadPixels is bottom-up; flip vertically to match the chart's orientation
            val m = android.graphics.Matrix().apply { setScale(1f, -1f) }
            val flipped = Bitmap.createBitmap(out, 0, 0, w, h, m, true)
            out.recycle()
            cb(flipped)
        } catch (e: Exception) {
            Log.e("EaglesEye", "tile $name: ${e.message}")
            cb(null)
        }
    }
}