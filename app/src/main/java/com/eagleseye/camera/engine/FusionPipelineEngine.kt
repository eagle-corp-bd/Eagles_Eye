package com.eagleseye.camera.engine

import android.content.Context
import android.opengl.GLES30
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface DepthModel {
    val isAvailable: Boolean
    val inputSize: Int
    val outputWidth: Int
    val outputHeight: Int
    fun run(rgbInput: ByteBuffer): ByteBuffer
    val lastFrameMin: Float
    val lastFrameMax: Float
}

class FusionPipelineEngine(context: Context) {

    enum class Mode { OFF, DEPTH_VIEW, STUDIO_LIGHT, BOKEH }

    @Volatile var mode = Mode.OFF
    @Volatile var enabled = false

    data class DepthDiag(
        val fps: Float, val min: Float, val max: Float,
        val tapDepth: Float, val depthBusy: Boolean,
        val modelW: Int, val modelH: Int, val modelName: String,
        val modelsReady: Boolean, val glReady: Boolean, val modeName: String,
        val glErrCount: Int, val lastErrLabel: String,
        val deferCount: Int, val lastDeferReason: String, val modelFailReason: String,
        val programErr: String, val fallbackCount: Int,
        val segOk: Boolean, val segW: Int, val segH: Int, val segOverrides: Long,
        val lastInferError: String
    )

    // Result of a one-off depth inference (used for still-capture bokeh).
    data class DepthResult(val focusDepth: Float)

    companion object {
        const val TAG = "FusionPipeline"
        const val INPUT_SIZE = 256
        const val ZIP_INPUT_SIZE = 384
        const val MICRO_W = 320
        const val MICRO_H = 240
        const val PREVIEW_W = 640
        const val PREVIEW_H = 480
        const val EMA_ALPHA = 0.3f
        const val MAX_COC = 24f
        const val DEPTH_RANGE = 0.35f
        const val MAX_BLUR = 1.0f
        const val FOCUS_DEPTH_DEFAULT = 0.5f
    }

    private val ctx = context.applicationContext
    @Volatile private var zipDepthModel: ZipDepthEngine? = null
    @Volatile private var miDasModel: MiDasEngine? = null
    // Live-preview segmentation (SINet, sinet.tflite — fast, MIT). The file may
    // not be dropped into assets yet — every use below is null-safe and the
    // pipeline runs depth-only (no hair-edge mask) until it is.
    @Volatile private var hairSegModel: HairSegEngine? = null
    // Capture-grade segmentation (BiRefNet, birefnet.tflite — most accurate, MIT).
    // Used for the saved photo; degrades to SINet (or depth-only) when absent.
    @Volatile private var birefNetModel: BirefNetEngine? = null
    @Volatile var useMiDas = true
    @Volatile private var modelsReady = false
    @Volatile private var modelsLoading = false
    @Volatile private var modelLoadFailed = false
    private var modelLoadAttempts = 0
    private val activeDepthModel get() = if (useMiDas) miDasModel else zipDepthModel
    private val inferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var latestDepthBuf: ByteBuffer? = null
    // CPU-side copy of the last normalized depth map so tap-to-focus can read the
    // focus depth WITHOUT a GPU readback (glReadPixels on an R16F FBO with
    // GL_RED/GL_FLOAT is invalid on most Android GPUs and wedges the pipeline).
    @Volatile private var lastDepthCpu: FloatArray? = null
    @Volatile private var lastDepthCpuW = 0
    @Volatile private var lastDepthCpuH = 0
    // Bumped every time uploadDepth refreshes the CPU matte, so the relight UI
    // can skip bitmap rebuilds when the array instance is reused in place.
    @Volatile var lastDepthCpuGen = 0L
    @Volatile private var inferenceBusy = false
    // Set while a synchronous capture inference runs on the GL thread so the live
    // (coroutine-backed) inference can't touch the shared TFLite interpreter at the
    // same time and crash it.
    @Volatile private var captureInferring = false
    // Capture progress 0..1, written by the GL thread during the all-engines
    // capture pass and polled by the UI to drive the determinate progress bar.
    @Volatile var captureProgress = 0f
    private val depthLock = Any()

    private var indicatorProgram = 0
    private var lastDepthDoneNs = 0L
    // Generation counter + validity flag so the depth *texture* stays applied once
    // we have ever received a frame. The live preview reuses the last uploaded depth
    // between inferences, so bokeh must NOT be turned off just because an inference
    // is momentarily late or the optional segmentation model fails.
    private var depthGen = 0L
    private var uploadedDepthGen = -1L
    private var depthTexValid = false
    private val depthIntervalRing = FloatArray(30)
    private var depthIntervalIdx = 0
    @Volatile private var lastDepthTriggerNs = 0L
    // Consecutive live-inference failures / wedged-run watchdog. If the live
    // pipeline goes unhealthy the models are re-initialised (CPU-only) instead
    // of staying dead until an app restart.
    @Volatile private var liveFailStreak = 0
    @Volatile private var busySinceNs = 0L
    @Volatile private var reinitInFlight = false
    @Volatile private var reinitCount = 0
    @Volatile var glErrCount = 0
    @Volatile var lastErrLabel = ""
    @Volatile var deferCount = 0
    @Volatile var lastDeferReason = "ok"
    @Volatile var modelFailReason = ""
    // Last live/capture inference exception label — surfaced as INFERR: on the
    // diag line so a dead pipeline is diagnosable without logcat.
    @Volatile var lastInferError = ""

    @Volatile var tapX = 0.5f; @Volatile var tapY = 0.5f
    // Until the user taps to pick focus, auto-focus on the NEAREST object (the subject
    // in a portrait) so the bokeh "just works" instead of blurring everything.
    private var autoFocus = true
    fun onTap(x: Float, y: Float) { tapX = x.coerceIn(0f, 1f); tapY = y.coerceIn(0f, 1f); autoFocus = false }
    @Volatile private var lastTapDepth = 0f
    @Volatile private var lastDepthMax = 0.5f
    @Volatile private var lastDepthMin = 0.5f
    // Subject focus depth derived from the segmentation mask (median of the
    // foreground pixels). The mask fusion pushes the subject toward the LARGER
    // end of the depth range, so a global min/max is the WRONG focus plane and
    // blurs the subject. Anchoring focus to the masked subject itself makes the
    // subject sharp regardless of which end of the range it sits at.
    @Volatile private var subjectFocusDepth = 0.5f
    @Volatile private var hasSubjectFocus = false
    // Number of frames where the segmentation mask actually overrode depth
    // (hair-edge fix active). Debug counter for the on-screen diag line.
    @Volatile var segOverrides = 0L
    private var frameCounter = 0L

    // ── Pipeline debug view (Settings → "Pipeline Debug View") ──────────────
    @Volatile var debugEnabled = false
    @Volatile var debugInputBytes: ByteArray? = null
    @Volatile var debugInputSize = 0
    @Volatile var debugMaskBytes: ByteArray? = null
    @Volatile var debugMaskW = 0
    @Volatile var debugMaskH = 0

    // ── Relight input capture ───────────────────────────────────────────────
    // When the AGSL light-orb mode is on, the GL thread stashes the active
    // model's preprocessed RGB frame (the same bytes already read back for
    // inference — zero extra GL work) so the Compose relight surface can feed
    // its RuntimeShader input. Depth rides the existing lastDepthCpu matte.
    @Volatile var relightEnabled = false
    @Volatile var relightRGBBytes: ByteArray? = null
    @Volatile var relightRGBSize = 0
    @Volatile var relightRgbGen = 0L

    val dbgModeName: String get() = mode.name
    val dbgModelsReady: Boolean get() = modelsReady
    val dbgModelLoadFailed: Boolean get() = modelLoadFailed
    val dbgLastDeferReason: String get() = lastDeferReason
    val dbgSINet: Boolean get() = hairSegModel?.isAvailable ?: false
    val dbgBiRefNet: Boolean get() = birefNetModel?.isAvailable ?: false
    val dbgMiDaS: Boolean get() = miDasModel?.isAvailable ?: false
    val dbgZip: Boolean get() = zipDepthModel?.isAvailable ?: false
    val dbgDepthCpu: FloatArray? get() = lastDepthCpu
    val dbgDepthW: Int get() = lastDepthCpuW
    val dbgDepthH: Int get() = lastDepthCpuH
    // Live-inference health (for the debug panel): consecutive failure count and
    // whether the self-heal reload is in flight.
    val dbgLiveFailStreak: Int get() = liveFailStreak
    val dbgReinitInFlight: Boolean get() = reinitInFlight
    val dbgDepthTexValid: Boolean get() = depthTexValid
    val dbgDepthGen: Long get() = depthGen

    fun setDepthModel(miDas: Boolean) { useMiDas = miDas }

    // 0..1 → blur radius for BOKEH (scales MAX_COC).
    @Volatile var blurStrength = 0.6f
    // Depth band around the focus plane that stays sharp (0.05..0.8).
    @Volatile var depthRange = DEPTH_RANGE
    // Aperture shape for the bokeh kernel: 0 = circular, otherwise polygon side
    // count (3 = triangle … 6 = hexagon, 8 = octagon). User-selectable like a
    // real lens aperture picker.
    @Volatile var apertureShape = 0f
    // Whether objects NEARER than the focus plane also blur (foreground bokeh).
    @Volatile var foregroundBlur = false
    // Exponential lens-falloff sharpness (higher = blur ramps up faster).
    @Volatile var expFalloff = 3.0f
    // Gate for still-capture: when false the saved photo skips bokeh entirely
    // (live preview keeps it, mirroring "save effect" off in other apps).
    @Volatile var saveToPhoto = true

    fun diag(): DepthDiag {
        var sum = 0f; var c = 0
        for (v in depthIntervalRing) { if (v > 0f) { sum += v; c++ } }
        val intervalMs = if (c > 0) sum / c
            else if (lastDepthDoneNs == 0L) 0f else (System.nanoTime() - lastDepthDoneNs) / 1_000_000f
        val fps = if (intervalMs > 0f) 1000f / intervalMs else 0f
        val dm = activeDepthModel
        val hs = hairSegModel
        return DepthDiag(
            fps, dm?.lastFrameMin ?: 0f, dm?.lastFrameMax ?: 0f,
            lastTapDepth, inferenceBusy,
            dm?.outputWidth ?: 0, dm?.outputHeight ?: 0, if (useMiDas) "MIDAS" else "ZIP",
            modelsReady, glReady, mode.name, glErrCount, lastErrLabel,
            deferCount, lastDeferReason, modelFailReason, lastProgramErr, fallbackCount,
            hs?.isAvailable == true, hs?.outputWidth ?: 0, hs?.outputHeight ?: 0, segOverrides,
            lastInferError
        )
    }

    private var preprocessTex = 0; private var preprocessFbo = 0
    private var zipPreprocessTex = 0; private var zipPreprocessFbo = 0
    // Segmentation preprocess target — sized once the seg model's input size is
    // known (defaults to 128x128, see HairSegEngine.PREVIEW_INPUT_SIZE).
    private var segPreprocessTex = 0; private var segPreprocessFbo = 0
    private var segPreprocessSize = 0
    private var depthRawTex = 0
    private var depthRawW = 0; private var depthRawH = 0
    private var depthUpsampledTex = 0
    private var upsampleFbo = 0
    private var segFullTex = 0; private var segFullFbo = 0
    private var cocBlurHTex = 0; private var cocBlurHFbo = 0
    private var cocBlurVTex = 0; private var cocBlurVFbo = 0
    private var cocBlurHProgram = 0
    private var cocBlurVProgram = 0
    private var cocCompositeProgram = 0
    private var bokehGatherProgram = 0

    // Fusion + EMA resources
    private var fusedAlphaTex = 0; private var fusedAlphaFbo = 0
    private var prevAlphaTex = 0; private var prevAlphaFbo = 0
    private var emaBlendProgram = 0

    private var prepPass: ShaderPass? = null
    private var studioProgram = 0
    private var blitProgram = 0
    private var quadVAO = 0
    private var surfaceW = 0; private var surfaceH = 0
    private var glReady = false
    private var outTex = 0; private var outFbo = 0

    private val vert = """#version 300 es
        in vec2 aPosition; out vec2 vUV;
        void main(){ vUV = aPosition*0.5+0.5; gl_Position = vec4(aPosition,0.0,1.0); }
    """

    private val blitFrag = """#version 300 es
        precision mediump float;
        uniform sampler2D uTex;
        uniform vec2 uScale; uniform vec2 uOff;
        in vec2 vUV; out vec4 o;
        void main(){ o = texture(uTex, vUV * uScale + uOff); }
    """

    // Crop mapping helpers: the depth models run on a SQUARE input, but the live
    // frame is 16:9 (or any other aspect). Without correction the square depth map
    // is stretched across the frame, so tap-to-focus and the blur mask hit the
    // wrong scene points (horizontally compressed). We center-crop the frame to a
    // square before inference and reverse-map the square depth back onto the frame,
    // which makes depth pixels line up with scene geometry exactly.
    private fun cropParams(aspect: Float): FloatArray =
        if (aspect >= 1f) floatArrayOf(1f / aspect, 0.5f - 0.5f / aspect, 1f, 0f) // rx, ox, ry, oy → frame→square
        else floatArrayOf(1f, 0f, aspect, 0.5f - 0.5f * aspect)

    private fun uncropParams(aspect: Float): FloatArray =
        if (aspect >= 1f) floatArrayOf(aspect, 0.5f - 0.5f * aspect, 1f, 0f) // input sx, ox, sy, oy → square→frame
        else floatArrayOf(1f, 0f, 1f / aspect, 0.5f - 0.5f / aspect)

    private val COC_BLUR_H_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uColor;
        uniform sampler2D uBlurMap;
        uniform float uMaxRadius; uniform float uTexelW;
        in vec2 vUV; out vec4 o;
        void main(){
            // Variable radius per pixel: the blur map tells how far this pixel is
            // from the focus plane, so in-focus pixels stay sharp and background
            // pixels spread. Radius 0 = identity (no blur) — a true CoC falloff,
            // not a flat Gaussian over the whole frame.
            float rad = clamp(texture(uBlurMap, vUV).r, 0.0, 1.0) * uMaxRadius;
            float sigma = max(rad * 0.5, 0.5);
            vec4 sum = vec4(0.0); float wsum = 0.0;
            int R = int(ceil(rad));
            // Constant loop bounds (GLES3 requirement); runtime radius via per-tap continue.
            for(int x = -24; x <= 24; x++){
                if(abs(float(x)) > float(R)) continue;
                float w = exp(-float(x*x) / (2.0 * sigma * sigma));
                sum += texture(uColor, vUV + vec2(float(x) * uTexelW, 0.0)) * w;
                wsum += w;
            }
            o = sum / wsum;
        }
    """

    private val COC_BLUR_V_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uColor;
        uniform sampler2D uBlurMap;
        uniform float uMaxRadius; uniform float uTexelH;
        in vec2 vUV; out vec4 o;
        void main(){
            float rad = clamp(texture(uBlurMap, vUV).r, 0.0, 1.0) * uMaxRadius;
            float sigma = max(rad * 0.5, 0.5);
            vec4 sum = vec4(0.0); float wsum = 0.0;
            int R = int(ceil(rad));
            for(int y = -24; y <= 24; y++){
                if(abs(float(y)) > float(R)) continue;
                float w = exp(-float(y*y) / (2.0 * sigma * sigma));
                sum += texture(uColor, vUV + vec2(0.0, float(y) * uTexelH)) * w;
                wsum += w;
            }
            o = sum / wsum;
        }
    """

    private val COC_COMPOSITE_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uSharp; uniform sampler2D uBlur; uniform sampler2D uBlurMap;
        in vec2 vUV; out vec4 o;
        void main(){
            float m = clamp(texture(uBlurMap, vUV).r, 0.0, 1.0);
            vec3 s = texture(uSharp, vUV).rgb;
            vec3 b = texture(uBlur, vUV).rgb;
            o = vec4(mix(s, b, m), 1.0);
        }
    """

    // Blur map: 0 at the focus plane (sharp) → uMaxBlur away from it. This is the
    // portrait mask; the colour is blurred uniformly and mixed by this map, which is
    // artifact-free and gives a smooth native-camera-style falloff.
    //
    // Falloff is EXPONENTIAL (not linear) to mimic a real lens: pixels near the
    // focus plane stay sharp longer, then blur ramps up aggressively with distance.
    // uForeground controls whether objects NEARER than the focus plane also blur.
    private val FUSE_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uDepth;
        uniform float uFocusDepth; uniform float uFocusRange; uniform float uMaxBlur;
        uniform float uHasDepth; uniform float uForeground; uniform float uExpK;
        in vec2 vUV; out vec4 o;
        void main(){
            float blur;
            if (uHasDepth < 0.5) {
                // Depth not ready (model still loading / failed): apply a gentle
                // default blur so the preview is never black or garbage.
                blur = 0.22;
            } else {
                float d = texture(uDepth, vUV).r;
                // Blur everything that is NOT on the focus plane: the focus point
                // (auto = nearest/subject, or a tap) stays sharp and the rest of the
                // scene blurs. Using the absolute distance makes this convention
                // agnostic — whichever end of the depth range the subject sits at, the
                // subject stays sharp and the off-focus side blurs. The previous
                // `max(d - focus, 0)` only blurred one side, inverting the bokeh so
                // the subject blurred and the background stayed sharp.
                float diff = abs(uFocusDepth - d);
                float raw = diff;
                float n = clamp(raw / uFocusRange, 0.0, 1.0);
                blur = (1.0 - exp(-uExpK * n)) * uMaxBlur;
            }
            o = vec4(blur, 0.0, 0.0, 1.0);
        }
    """

    // Minimal-portable fuse: same idea, no exp() falloff / no extra uniforms.
    // Auto-selected when the complex variant won't link on a strict driver.
    private val FUSE_FRAG_SIMPLE = """#version 300 es
        precision highp float;
        uniform sampler2D uDepth;
        uniform float uFocusDepth; uniform float uFocusRange; uniform float uMaxBlur;
        uniform float uHasDepth;
        in vec2 vUV; out vec4 o;
        void main(){
            float blur = 0.22;
            if (uHasDepth >= 0.5) {
                float d = texture(uDepth, vUV).r;
                blur = clamp(abs(uFocusDepth - d) / max(uFocusRange, 0.02), 0.0, 1.0) * uMaxBlur;
            }
            o = vec4(blur, 0.0, 0.0, 1.0);
        }
    """

    // ─── Disc / aperture BOKEH (gather) ──────────────────────────────────────────
    // Native-camera portrait bokeh comes from a DISC-SHAPED blur kernel, not a
    // Gaussian. We use a scatter-as-gather: for every output pixel we walk a golden-
    // angle spiral of samples; a neighbour contributes to this pixel only if this
    // pixel lies inside the neighbour's circle-of-confusion disc. Weighting by
    // 1/area (1/coc^2) is energy-preserving, so bright background lights naturally
    // render as distinct, shaped bokeh balls instead of being smeared away.
    //
    // uAperture = polygon side count: 0 (or <2.5) = circular, >=3 = N-gon aperture
    // (triangle, square, pentagon, hexagon, octagon …). uSamples caps the gather
    // quality vs. cost.
    private val BOKEH_GATHER_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uColor;
        uniform sampler2D uBlurMap;
        uniform float uMaxRadius;   // max CoC radius, in full-res pixels
        uniform vec2  uTexel;       // 1.0 / fullRes
        uniform float uAperture;    // 0 = circle, >=3 = N-gon
        uniform int   uSamples;
        in vec2 vUV; out vec4 o;

        float polyRadius(float ang, float sides){
            if (sides < 2.5) return 1.0;
            float seg = 6.28318530718 / sides;
            float a = mod(ang, seg) - seg * 0.5;
            return cos(3.14159265359 / sides) / max(cos(a), 1e-3);
        }

        void main(){
            float ownCoc = clamp(texture(uBlurMap, vUV).r, 0.0, 1.0);
            vec3 sharp = texture(uColor, vUV).rgb;
            if (ownCoc < 0.01) { o = vec4(sharp, 1.0); return; }

            vec3 sum = vec3(0.0); float wsum = 0.0;
            float uN = float(uSamples);
            const int MAX_S = 96;
            for (int i = 0; i < MAX_S; i++){
                if (i >= uSamples) break;
                float t = (float(i) + 0.5) / uN;
                float r = sqrt(t);                  // radial position 0..1
                float a = float(i) * 2.39996323;    // golden angle
                float shapeR = polyRadius(a, uAperture);
                vec2 off = vec2(cos(a), sin(a)) * (r * shapeR * uMaxRadius) * uTexel;
                vec2 uvN = vUV + off;
                float cocN = texture(uBlurMap, uvN).r;
                float distN = r * shapeR;           // distance in CoC units (0..1)
                if (cocN > 0.02 && distN <= cocN) {
                    float w = 1.0 / max(cocN * cocN, 1e-4);
                    sum += texture(uColor, uvN).rgb * w;
                    wsum += w;
                }
            }
            vec3 blurred = (wsum > 0.0) ? sum / wsum : sharp;
            o = vec4(blurred, 1.0);
        }
    """

    // Minimal-portable bokeh gather: circular disc only (no N-gon), fewer taps,
    // no helper function. Auto-selected when the complex gather won't link.
    private val BOKEH_GATHER_FRAG_SIMPLE = """#version 300 es
        precision highp float;
        uniform sampler2D uColor;
        uniform sampler2D uBlurMap;
        uniform float uMaxRadius;
        uniform vec2  uTexel;
        uniform int   uSamples;
        in vec2 vUV; out vec4 o;
        void main(){
            float ownCoc = clamp(texture(uBlurMap, vUV).r, 0.0, 1.0);
            vec3 sharp = texture(uColor, vUV).rgb;
            if (ownCoc < 0.01) { o = vec4(sharp, 1.0); return; }
            vec3 sum = vec3(0.0); float wsum = 0.0;
            float uN = float(uSamples);
            for (int i = 0; i < 32; i++){
                if (i >= uSamples) break;
                float t = (float(i) + 0.5) / uN;
                float r = sqrt(t);
                float a = float(i) * 2.39996323;
                vec2 off = vec2(cos(a), sin(a)) * (r * uMaxRadius) * uTexel;
                float cocN = texture(uBlurMap, vUV + off).r;
                if (cocN > 0.02 && r <= cocN) {
                    float w = 1.0 / max(cocN * cocN, 1e-4);
                    sum += texture(uColor, vUV + off).rgb * w;
                    wsum += w;
                }
            }
            o = vec4((wsum > 0.0) ? sum / wsum : sharp, 1.0);
        }
    """

    private val EMA_BLEND_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uCurr; uniform sampler2D uPrev;
        uniform float uBlend;
        in vec2 vUV; out vec4 o;
        void main(){
            float c = texture(uCurr, vUV).r;
            float p = texture(uPrev, vUV).r;
            o = vec4(mix(p, c, uBlend));
        }
    """

    private val STUDIO_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uCameraTex; uniform sampler2D uDepthTex;
        uniform vec2 uTexelSize;
        uniform float uFocusDepth; uniform float uFocusRange;
        uniform vec3 uKeyColor; uniform float uKeyInt;
        uniform vec3 uFillDir; uniform vec3 uFillColor; uniform float uFillInt;
        uniform vec3 uRimDir; uniform vec3 uRimColor; uniform float uRimInt;
        in vec2 vUV; out vec4 o;
        vec3 norm(vec2 uv){
            float cx = texture(uDepthTex, uv + vec2(uTexelSize.x, 0.0)).r;
            float cy = texture(uDepthTex, uv + vec2(0.0, uTexelSize.y)).r;
            float cc = texture(uDepthTex, uv).r;
            return normalize(vec3(cx - cc, cy - cc, 0.01));
        }
        void main(){
            vec3 c = texture(uCameraTex, vUV).rgb;
            // Subject mask from depth (no segmentation model): the in-focus band
            // around the focus plane is the subject, everything else stays flat.
            float d = texture(uDepthTex, vUV).r;
            float s = 1.0 - clamp(abs(d - uFocusDepth) / max(uFocusRange, 0.02), 0.0, 1.0);
            vec3 n = norm(vUV);
            vec3 key = max(0.0, dot(n, normalize(uKeyColor))) * uKeyColor * uKeyInt;
            vec3 fill = max(0.0, dot(n, normalize(uFillDir))) * uFillColor * uFillInt;
            vec3 rim = max(0.0, dot(n, normalize(uRimDir))) * uRimColor * uRimInt;
            vec3 light = vec3(0.3) + key + fill + rim;
            o = vec4(mix(c, c * light, s * 0.8), 1.0);
        }
    """

    private val PREPROCESS_FRAG = """#version 300 es
        precision mediump float;
        uniform sampler2D uTexture;
        uniform vec2 uCropScale; uniform vec2 uCropOff;
        in vec2 vUV; out vec4 o;
        void main(){ o = texture(uTexture, vUV * uCropScale + uCropOff); }
    """

    private var loadAttemptStartedNs = 0L
    @Volatile private var switchLoadInFlight = false

    private fun ensureModelsAsync() {
        if (modelsReady || modelLoadFailed) return
        // Watchdog: if a load attempt is somehow wedged (or died without the
        // finally running), reset the loading flag after 60s so a retry can fire.
        if (modelsLoading) {
            val hung = loadAttemptStartedNs != 0L && (System.nanoTime() - loadAttemptStartedNs) > 60_000_000_000L
            if (hung) {
                Log.w(TAG, "model load watchdog: resetting stuck loading flag")
                modelsLoading = false
            } else return
        }
        // Bounded retries: a transient failure gets one retry, but a permanent one
        // must NOT re-attempt ~250MB of model loading on every frame forever.
        if (modelLoadAttempts >= 2) {
            modelLoadFailed = true
            if (modelFailReason.isEmpty()) modelFailReason = "load attempts exhausted"
            return
        }
        modelLoadAttempts++
        modelsLoading = true
        loadAttemptStartedNs = System.nanoTime()
        val useMid = useMiDas
        inferenceScope.launch {
            try {
                // STAGE 1 — pipeline-ready: the ACTIVE depth model + live SINet
                // only, so the camera opens and bokeh starts almost immediately.
                // GPU delegate for full inference speed; each engine already falls
                // back to CPU (XNNPACK) internally when the delegate can't be
                // created on the device.
                val active: DepthModel
                val s = HairSegEngine(ctx)
                synchronized(this@FusionPipelineEngine) {
                    if (useMid) {
                        miDasModel = MiDasEngine(ctx, "midas_small_256_fp16.tflite", useGpu = true); active = miDasModel!!
                    } else {
                        zipDepthModel = ZipDepthEngine(ctx, "zipdepth_384x384.tflite", useGpu = true); active = zipDepthModel!!
                    }
                    hairSegModel = s
                    modelsReady = true
                }
                Log.i(TAG, "stage1 ready ${if (useMid) "midas" else "zip"}=${active.isAvailable} sinet=${s.isAvailable}")
                // STAGE 2 — background, never gates the live pipeline: the inactive
                // depth model for instant MIDAS/ZIP switching + capture BiRefNet.
                try {
                    val inactive: DepthModel
                    synchronized(this@FusionPipelineEngine) {
                        if (useMid) {
                            zipDepthModel = ZipDepthEngine(ctx, "zipdepth_384x384.tflite", useGpu = true); inactive = zipDepthModel!!
                        } else {
                            miDasModel = MiDasEngine(ctx, "midas_small_256_fp16.tflite", useGpu = true); inactive = miDasModel!!
                        }
                    }
                    val b = BirefNetEngine(ctx)
                    synchronized(this@FusionPipelineEngine) {
                        birefNetModel = b
                    }
                    Log.i(TAG, "stage2 loaded ${if (useMid) "zip" else "midas"}=${inactive.isAvailable} birefnet=${b.isAvailable}")
                } catch (e2: Exception) {
                    // Stage-1 already served the pipeline: a stage-2 failure is
                    // non-fatal (affects only switching/capture fidelity).
                    modelFailReason = "stage2:${e2.message ?: e2.javaClass.simpleName}"
                    Log.w(TAG, "model stage2 load failed (non-fatal)", e2)
                }
            } catch (e: Exception) {
                modelFailReason = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "model load failed (attempt $modelLoadAttempts)", e)
            } finally {
                modelsLoading = false
            }
        }
    }

    // Lazy fill-in for the depth model the user just switched to (MIDAS/ZIP pill)
    // when it wasn't part of stage 1 — loaded on demand, GPU delegate included.
    private fun ensureActiveAvailable() {
        val dm = activeDepthModel
        if (dm != null && dm.isAvailable) return
        if (!modelsReady || modelsLoading || modelLoadFailed || switchLoadInFlight) return
        switchLoadInFlight = true
        val useMid = useMiDas
        inferenceScope.launch {
            try {
                if (useMid) {
                    synchronized(this@FusionPipelineEngine) {
                        // Replace unconditionally — a non-null but dead engine
                        // (load failed inside the constructor) must be swapped out.
                        miDasModel = MiDasEngine(ctx, "midas_small_256_fp16.tflite", useGpu = true)
                    }
                    Log.i(TAG, "switch-loaded midas ok=${miDasModel?.isAvailable}")
                } else {
                    synchronized(this@FusionPipelineEngine) {
                        zipDepthModel = ZipDepthEngine(ctx, "zipdepth_384x384.tflite", useGpu = true)
                    }
                    Log.i(TAG, "switch-loaded zip ok=${zipDepthModel?.isAvailable}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "switch model load failed", e)
            } finally {
                switchLoadInFlight = false
            }
        }
    }

    // Closes and reloads every model interpreter. The reload itself is STAGED (active
    // depth + SINet first, then the rest) via the standard async loader, so the live
    // pipeline resumes as soon as the essential models are back. Used as the
    // self-heal for a wedged interpreter after repeated live failures; render()
    // shows the plain camera during the brief reload.
    private fun reinitModels() {
        if (reinitInFlight) return
        if (reinitCount >= 3) {
            // Permanent failure — stop the churn and defer to plain camera.
            modelLoadFailed = true
            modelFailReason = "reinit attempts exhausted"
            Log.e(TAG, "model reinit exhausted — giving up")
            return
        }
        reinitCount++
        reinitInFlight = true
        inferenceScope.launch {
            runCatching { zipDepthModel?.close() }
            runCatching { miDasModel?.close() }
            runCatching { hairSegModel?.close() }
            runCatching { birefNetModel?.close() }
            zipDepthModel = null; miDasModel = null; hairSegModel = null; birefNetModel = null
            modelsReady = false; modelLoadFailed = false; modelsLoading = false
            modelLoadAttempts = 0
            liveFailStreak = 0; inferenceBusy = false; busySinceNs = 0L
            depthTexValid = false; latestDepthBuf = null; depthGen = 0; uploadedDepthGen = -1
            ensureModelsAsync()
            reinitInFlight = false
            Log.w(TAG, "model reinit finished ready=$modelsReady failed=$modelLoadFailed")
        }
    }

    fun render(cameraTex: Int, w: Int, h: Int): Int {
        if (!enabled || mode == Mode.OFF) return -1
        // A failed shader link leaves a sticky GL error behind; draining it here
        // keeps checkGlError("mode-X") tagging only this frame's real errors.
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* drain */ }
        val t0 = System.nanoTime()
        ensureModelsAsync()
        // Self-heal: a wedged interpreter (native state) or a run of consecutive
        // failures kills the live depth. Reload the models fresh and carry on
        // instead of staying dead until a restart.
        val busyWedged = inferenceBusy && busySinceNs != 0L && (System.nanoTime() - busySinceNs) > 4_000_000_000L
        if (liveFailStreak >= 5 || busyWedged) {
            Log.w(TAG, "live inference unhealthy (streak=$liveFailStreak busyWedged=$busyWedged) — reinitialising models")
            liveFailStreak = 0
            reinitModels()
        }
        // If the user switched MIDAS/ZIP and that model isn't loaded yet, kick its
        // background load instead of deferring forever.
        ensureActiveAvailable()
        val dm = activeDepthModel
        if (!modelsReady) {
            deferCount++; lastDeferReason = if (modelLoadFailed) "model-load-failed" else "models-loading"
            return -1
        }
        if (dm == null || !dm.isAvailable) {
            deferCount++; lastDeferReason = "model-unavailable"
            if (modelFailReason.isEmpty()) {
                modelFailReason = "active depth model load failed (tflite asset compressed?)"
                Log.e(TAG, modelFailReason)
            }
            return -1
        }
        ensureGL(w, h)
        if (!glReady) { deferCount++; lastDeferReason = "gl-not-ready"; return -1 }

        // Depth inference, throttled to a low live rate. Running the model every frame
        // (or even at 30Hz) starves the GL compositor — the preview reuses the last
        // uploaded depth map between inferences, so ~8Hz is plenty for smooth bokeh.
        val nowNs = System.nanoTime()
        if (nowNs - lastDepthTriggerNs >= 120_000_000L) {
            lastDepthTriggerNs = nowNs
            triggerDepth(cameraTex)
        }
        val t1 = System.nanoTime()

        // Upload depth
        uploadDepth()
        blitDepthUpsample()
        // Fuse depth into alpha and apply EMA
        fuseAlpha(tapX, tapY)
        applyEMASmooth()
        val t2 = System.nanoTime()

        // Mode-dependent output — all write to outFbo
        when (mode) {
            Mode.DEPTH_VIEW -> compositeDepthView(cameraTex, w, h)
            Mode.STUDIO_LIGHT -> compositeStudio(cameraTex, w, h)
            Mode.BOKEH -> if (bokehChainOk()) { computeCoCBlur(cameraTex, w, h, tapX, tapY); compositeBokeh(cameraTex, w, h) }
                           else drawRawCamera(cameraTex, w, h)
            Mode.OFF -> { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
                GLES30.glViewport(0, 0, w, h); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0) }
        }
        checkGlError("mode-${mode.name}")
        val t3 = System.nanoTime()

        val totalMs = (t3 - t0) / 1e6
        if (totalMs > 20) Log.w(TAG, "SLOW frame: inf=${(t1-t0)/1e6} fuse=${(t2-t1)/1e6} bokeh=${(t3-t2)/1e6} total=${totalMs}ms")
        if (frameCounter % 30 == 0L) Log.i(TAG, "frame: inf=${(t1-t0)/1e6} fuse=${(t2-t1)/1e6} bokeh=${(t3-t2)/1e6} total=${totalMs}ms")
        frameCounter++

        // FPS indicator bar (depth update interval)
        var cnt = 0
        val depthMs = if (lastDepthDoneNs == 0L) 0f else {
            var sum = 0f
            for (v in depthIntervalRing) { if (v > 0f) { sum += v; cnt++ } }
            if (cnt == 0) (System.nanoTime() - lastDepthDoneNs) / 1_000_000f
            else sum / cnt
        }
        if (frameCounter % 60 == 0L) {
            val ringStr = depthIntervalRing.joinToString(limit = 5) { "%.1f".format(it) }
            Log.i(TAG, "depthMs=${"%.1f".format(depthMs)}ms cnt=$cnt indicator=${if(depthMs<42) "GREEN" else if(depthMs<67) "YELLOW" else if(depthMs<100) "ORANGE" else "RED"} ring=[$ringStr] lastDone=$lastDepthDoneNs busy=$inferenceBusy")
        }
        val depthAlive = lastDepthDoneNs != 0L && (System.nanoTime() - lastDepthDoneNs) < 500_000_000L
        val indicatorColor = if (depthAlive) floatArrayOf(0f, 1f, 0f, 1f)
        else floatArrayOf(1f, 0f, 0f, 1f)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        val barW = 10; val barH = 60
        GLES30.glViewport(w - barW, h - barH, barW, barH)
        GLES30.glUseProgram(indicatorProgram)
        GLES30.glUniform4f(GLES30.glGetUniformLocation(indicatorProgram, "uColor"),
            indicatorColor[0], indicatorColor[1], indicatorColor[2], indicatorColor[3])
        GLES30.glBindVertexArray(quadVAO)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        // Depth range diagnostic bar (width = depth spread). Frozen range = broken model input.
        if (dm != null) {
            val dRange = (dm.lastFrameMax - dm.lastFrameMin)
            val rangeW = (dRange * 200f).toInt().coerceIn(2, 60)
            val rangeColor = if (dRange > 0.05f) floatArrayOf(0f, 1f, 0f, 1f) else floatArrayOf(1f, 0f, 0f, 1f)
            GLES30.glViewport(w - rangeW - 14, h - barH, rangeW, 8)
            GLES30.glUseProgram(indicatorProgram)
            GLES30.glUniform4f(GLES30.glGetUniformLocation(indicatorProgram, "uColor"),
                rangeColor[0], rangeColor[1], rangeColor[2], rangeColor[3])
            GLES30.glBindVertexArray(quadVAO)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glBindVertexArray(0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return outTex
    }

    fun destroy() {
        inferenceScope.cancel()
        zipDepthModel?.close(); miDasModel?.close(); hairSegModel?.close(); birefNetModel?.close()
        deleteGL()
    }

    // One-off synchronous depth inference for a still capture (runs on the calling
    // GL thread, NOT a coroutine, so it can't race the live inference). Returns the
    // auto focus plane (nearest object = largest inverse-depth) and leaves the depth
    // map in `depthRawTex` (R16F, linear) ready for the capture bokeh pass to sample.
    // Returns null if GL/depth isn't ready — callers must then skip bokeh and still save.
    fun inferDepth(srcTex: Int): DepthResult? {
        if (!glReady) return null
        val model = activeDepthModel ?: return null
        if (!model.isAvailable) return null
        captureInferring = true
        try {
            captureProgress = 0f
            val otherDepth0 = if (useMiDas) zipDepthModel?.takeIf { it.isAvailable } else miDasModel?.takeIf { it.isAvailable }
            val segs0 = listOfNotNull(
                birefNetModel?.takeIf { it.isAvailable },
                hairSegModel?.takeIf { it.isAvailable }
            )
            var totalSteps = 1 + (if (otherDepth0 != null) 1 else 0) + segs0.size
            var stepsDone = 0
            fun stepDone() { stepsDone++; captureProgress = (stepsDone.toFloat() / totalSteps) * 0.92f }
            val size = model.inputSize
            val fbo = if (size == ZIP_INPUT_SIZE) zipPreprocessFbo else preprocessFbo
            preprocessDepth(srcTex, size, fbo)
            val rgb = readbackRgb(fbo, size)
            // Production capture: fire EVERY available engine on this exact frame.
            // Depth — run both MiDaS and ZipDepth, keep the map with the richer
            // dynamic range (larger robust 1-99% spread = more usable detail).
            var depthBuf = model.run(rgb)
            stepDone()
            var depthW = model.outputWidth; var depthH = model.outputHeight
            var depthSpread = robustSpread(depthBuf)
            val otherDepth = otherDepth0
            if (otherDepth != null) {
                val oSize = otherDepth.inputSize
                val ofbo = if (oSize == ZIP_INPUT_SIZE) zipPreprocessFbo else preprocessFbo
                preprocessDepth(srcTex, oSize, ofbo)
                val otherRgb = readbackRgb(ofbo, oSize)
                val otherRes = otherDepth.run(otherRgb); otherRes.rewind()
                stepDone()
                if (robustSpread(otherRes) > depthSpread * 1.15f) {
                    depthBuf = otherRes; depthSpread = robustSpread(otherRes)
                    depthW = otherDepth.outputWidth; depthH = otherDepth.outputHeight
                }
            }
            // Mask — run BOTH BiRefNet and SINet on this same frame and union the
            // results (any pixel flagged subject by either engine stays sharp;
            // union eliminates hair-edge false negatives from the coarse SINet
            // grid at the cost of a slightly conservative bokeh).
            val segs = segs0
            var unionMask: ByteArray? = null
            var maskW = 0; var maskH = 0
            if (segs.isNotEmpty()) {
                val primary = segs[0]
                val pSize = primary.inputSize
                preprocessDepth(srcTex, pSize, segFboFor(pSize))
                val pRgb = readbackRgb(segFboFor(pSize), pSize)
                val pRes = primary.run(pRgb); pRes.rewind()
                stepDone()
                val pm = ByteArray(pRes.remaining()); pRes.get(pm)
                unionMask = pm
                maskW = primary.outputWidth; maskH = primary.outputHeight
                for (i in 1 until segs.size) {
                    val extra = segs[i]
                    val eSize = extra.inputSize
                    preprocessDepth(srcTex, eSize, segFboFor(eSize))
                    val eRgb = readbackRgb(segFboFor(eSize), eSize)
                    val eRes = extra.run(eRgb); eRes.rewind()
                    stepDone()
                    val em = ByteArray(eRes.remaining()); eRes.get(em)
                    unionMask = maskUnion(unionMask!!, maskW, maskH, em, extra.outputWidth, extra.outputHeight)
                }
            }
            val (normalized, mx) = combineAndNormalize(depthBuf, unionMask, maskW, maskH, forceNormalize = true)
            val ow = depthW; val oh = depthH
            if (depthRawTex == 0 || depthRawW != ow || depthRawH != oh) {
                if (depthRawTex == 0) { val t = IntArray(1); GLES30.glGenTextures(1, t, 0); depthRawTex = t[0] }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthRawTex)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, ow, oh, 0, GLES30.GL_RED, GLES30.GL_FLOAT, null)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                depthRawW = ow; depthRawH = oh
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthRawTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, ow, oh, GLES30.GL_RED, GLES30.GL_FLOAT, normalized)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            // Mark depth as fresh so the capture blur-map FUSE sees uHasDepth = true.
            lastDepthDoneNs = System.nanoTime()
            captureProgress = 1f
            lastDepthMax = mx
            val focus = if (autoFocus) mx.coerceIn(0.01f, 1f) else lastTapDepth
            return DepthResult(focus)
        } catch (e: Exception) {
            Log.e(TAG, "inferDepth failed", e)
            lastInferError = "capture:${e.message ?: e.javaClass.simpleName}"
            return null
        } finally {
            captureInferring = false
        }
    }

    // The depth map texture left in place by inferDepth (R16F, linear).
    val depthTexture: Int get() = depthRawTex

    // Robust 1-99 percentile spread of a depth engine's float32 output — how
    // much usable dynamic range the map carries. NaN/Inf values are skipped.
    private fun robustSpread(buf: ByteBuffer): Float {
        val pos = buf.position()
        buf.rewind()
        val n = buf.remaining() / 4
        if (n < 4) { buf.position(pos); return 0f }
        val f = FloatArray(n)
        buf.asFloatBuffer().get(f)
        buf.position(pos)
        val finite = f.filter { it.isFinite() }
        if (finite.isEmpty()) return 0f
        val sorted = finite.sorted()
        val lo = sorted[(sorted.size * 0.01).toInt().coerceIn(0, sorted.size - 1)]
        val hi = sorted[(sorted.size * 0.99).toInt().coerceIn(0, sorted.size - 1)]
        return hi - lo
    }

    // Merges a second segmentation mask into the primary one. Different engines
    // ship different grids, so the extra mask is nearest-neighbor scaled onto
    // the primary grid, then OR'd — a pixel is subject if EITHER engine flagged
    // it (union = max subject coverage, no hair-edge holes).
    private fun maskUnion(a: ByteArray, aw: Int, ah: Int, b: ByteArray, bw: Int, bh: Int): ByteArray {
        if (aw <= 0 || ah <= 0 || b.size < bw * bh || bw <= 0 || bh <= 0) return a
        if (a.size != aw * ah) return a
        val out = a.copyOf()
        var idx = 0
        for (y in 0 until ah) {
            val sy = (y * bh) / ah
            for (x in 0 until aw) {
                val sx = (x * bw) / aw
                val bv = b[sy * bw + sx]
                val av = out[idx]
                out[idx] = if (bv.toInt() and 0xFF > av.toInt() and 0xFF) bv else av
                idx++
            }
        }
        return out
    }

    private fun ensureGL(w: Int, h: Int) {
        if (glReady) return
        glReady = true; surfaceW = w; surfaceH = h
        quadVAO = createQuad()
        prepPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, PREPROCESS_FRAG)
studioProgram = createProgram(vert, STUDIO_FRAG, "studio")
        blitProgram = createProgram(vert, blitFrag, "blit")
        cocBlurHProgram = createProgram(vert, COC_BLUR_H_FRAG, "cocBlurH")
        cocBlurVProgram = createProgram(vert, COC_BLUR_V_FRAG, "cocBlurV")
        cocCompositeProgram = createProgram(vert, COC_COMPOSITE_FRAG, "cocComposite")
        bokehGatherProgram = createProgram(vert, BOKEH_GATHER_FRAG, "bokehGather")
        if (bokehGatherProgram == 0) {
            Log.w(TAG, "bokehGather (complex) rejected — trying minimal variant")
            bokehGatherProgram = createProgram(vert, BOKEH_GATHER_FRAG_SIMPLE, "bokehGatherSimple")
        }
        emaBlendProgram = createProgram(vert, EMA_BLEND_FRAG, "emaBlend")
        indicatorProgram = createProgram(vert, """
            #version 300 es
            precision mediump float; in vec2 vUV;
            uniform vec4 uColor; out vec4 o;
            void main(){ o = uColor; }
        """, "indicator")
        createTextures(w, h)
    }

    private fun createQuad(): Int {
        val verts = floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)
        val buf = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts); buf.position(0)
        val vao = IntArray(1); val vbo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0); GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(vao[0]); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 64, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glBindVertexArray(0); return vao[0]
    }

    private fun normalizeGlsl(src: String): String {
        var s = src.trimStart()
        val idx = s.indexOf("#version")
        if (idx > 0) s = s.substring(idx).trimStart()
        while (s.isNotEmpty() && s[0].isWhitespace()) s = s.substring(1)
        return s
    }

    private fun createProgram(vert: String, frag: String, name: String): Int {
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { }
        val vsrc = normalizeGlsl(vert)
        val fsrc = normalizeGlsl(frag)
        val vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER); GLES30.glShaderSource(vs, vsrc); GLES30.glCompileShader(vs)
        val vsOk = IntArray(1); GLES30.glGetShaderiv(vs, GLES30.GL_COMPILE_STATUS, vsOk, 0)
        if (vsOk[0] == 0) logProgramFail(name, "vert", GLES30.glGetShaderInfoLog(vs))
        val fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER); GLES30.glShaderSource(fs, fsrc); GLES30.glCompileShader(fs)
        val fsOk = IntArray(1); GLES30.glGetShaderiv(fs, GLES30.GL_COMPILE_STATUS, fsOk, 0)
        if (fsOk[0] == 0) logProgramFail(name, "frag", GLES30.glGetShaderInfoLog(fs))
        val p = GLES30.glCreateProgram(); GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs)
        GLES30.glBindAttribLocation(p, 0, "aPosition")
        GLES30.glLinkProgram(p)
        val linkOk = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, linkOk, 0)
        if (linkOk[0] == 0) {
            logProgramFail(name, "link", GLES30.glGetProgramInfoLog(p))
            GLES30.glDeleteProgram(p); GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
            return 0
        }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return p
    }

    @Volatile var lastProgramErr = ""
    private fun logProgramFail(name: String, stage: String, log: String) {
        lastProgramErr = "$name-$stage: ${log?.trim()?.take(700) ?: "?"}"
        Log.e(TAG, "program '$name' $stage failed: $log")
        GpuDebugLog.logError("DEPTH-GL", "program '$name' $stage failed: ${log?.trim()?.take(700)}")
    }

    private fun createTexture(w: Int, h: Int, fmt: Int): Int {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val pf = when (fmt) { GLES30.GL_R32F, GLES30.GL_R16F -> GLES30.GL_RED; GLES30.GL_RGBA8 -> GLES30.GL_RGBA; else -> GLES30.GL_RGBA }
        val pt = when (fmt) { GLES30.GL_R32F, GLES30.GL_R16F -> GLES30.GL_FLOAT; else -> GLES30.GL_UNSIGNED_BYTE }
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, fmt, w, h, 0, pf, pt, null)
        return t[0]
    }

    private fun createFbo(tex: Int): Int {
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE)
            Log.e(TAG, "FBO incomplete")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); return f[0]
    }

    private fun createTextures(w: Int, h: Int) {
        preprocessTex = createTexture(INPUT_SIZE, INPUT_SIZE, GLES30.GL_RGBA8)
        preprocessFbo = createFbo(preprocessTex)
        zipPreprocessTex = createTexture(ZIP_INPUT_SIZE, ZIP_INPUT_SIZE, GLES30.GL_RGBA8)
        zipPreprocessFbo = createFbo(zipPreprocessTex)
        depthRawTex = createTexture(ZIP_INPUT_SIZE, ZIP_INPUT_SIZE, GLES30.GL_R16F)

        if (outTex != 0) { GLES30.glDeleteTextures(1, intArrayOf(outTex), 0); outTex = 0 }
        if (outFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(outFbo), 0); outFbo = 0 }
        outTex = createTexture(w, h, GLES30.GL_RGBA8)
        outFbo = createFbo(outTex)

        depthUpsampledTex = createTexture(w, h, GLES30.GL_R16F)
        upsampleFbo = createFbo(depthUpsampledTex)
        segFullTex = createTexture(w, h, GLES30.GL_R16F)
        segFullFbo = createFbo(segFullTex)

        // CoC bokeh textures — blur passes run at half resolution for 4x fill-rate saving
        val hw = (w / 2).coerceAtLeast(2); val hh = (h / 2).coerceAtLeast(2)
        cocBlurHTex = createTexture(hw, hh, GLES30.GL_RGBA8)
        cocBlurHFbo = createFbo(cocBlurHTex)
        cocBlurVTex = createTexture(hw, hh, GLES30.GL_RGBA8)
        cocBlurVFbo = createFbo(cocBlurVTex)

        // Fusion + EMA textures (MICRO_W × MICRO_H)
        fusedAlphaTex = createTexture(MICRO_W, MICRO_H, GLES30.GL_R16F)
        fusedAlphaFbo = createFbo(fusedAlphaTex)
        prevAlphaTex = createTexture(MICRO_W, MICRO_H, GLES30.GL_R16F)
        prevAlphaFbo = createFbo(prevAlphaTex)
    }

    private fun deleteGL() {
        prepPass?.destroy(); prepPass = null
        listOf(preprocessTex, zipPreprocessTex, segPreprocessTex, depthRawTex,
            outTex, depthUpsampledTex, segFullTex,
            cocBlurHTex, cocBlurVTex, fusedAlphaTex, prevAlphaTex).forEach {
            if (it != 0) GLES30.glDeleteTextures(1, intArrayOf(it), 0)
        }
        listOf(preprocessFbo, zipPreprocessFbo, segPreprocessFbo, outFbo, upsampleFbo,
            segFullFbo,
            cocBlurHFbo, cocBlurVFbo, fusedAlphaFbo, prevAlphaFbo).forEach {
            if (it != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(it), 0)
        }
        if (indicatorProgram != 0) GLES30.glDeleteProgram(indicatorProgram)
    }

    private fun checkGlError(label: String) {
        var e: Int
        while (GLES30.glGetError().also { e = it } != GLES30.GL_NO_ERROR) {
            glErrCount++
            lastErrLabel = "$label:$e"
            Log.e(TAG, "GL error after $label: $e")
        }
    }

    private fun preprocessDepth(cameraTex: Int, size: Int, fbo: Int) {
        if (prepPass?.isValid != true) return
        val aspect = if (surfaceW > 0 && surfaceH > 0) surfaceW.toFloat() / surfaceH.toFloat() else 1f
        val cp = cropParams(aspect)
        prepPass?.draw(cameraTex, outputFbo = fbo, width = size, height = size) {
            setFloat2("uCropScale", cp[0], cp[2])
            setFloat2("uCropOff", cp[1], cp[3])
        }
        checkGlError("preprocessDepth")
    }

    // Reads the preprocess FBO back as interleaved RGB (0..255), ready for a
    // TFLite model. Runs on the GL thread; one bulk readback + one bulk get.
    private fun readbackRgb(fbo: Int, size: Int): ByteBuffer {
        val rgba = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0)
        GLES30.glReadPixels(0, 0, size, size, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        rgba.rewind()
        val rgbaBytes = ByteArray(size * size * 4)
        rgba.get(rgbaBytes)
        val rgbBytes = ByteArray(size * size * 3)
        var j = 0; var b = 0
        for (i in 0 until size * size) {
            rgbBytes[j++] = rgbaBytes[b++]
            rgbBytes[j++] = rgbaBytes[b++]
            rgbBytes[j++] = rgbaBytes[b++]
            b++
        }
        return ByteBuffer.wrap(rgbBytes) // zero-copy view, no per-element JNI
    }

    // Lazily (re)sizes the segmentation preprocess target to whichever seg model's
    // square input is currently active (SINet 224 live / BiRefNet 1024 capture).
    private fun segFboFor(size: Int): Int {
        if (segPreprocessTex == 0 || segPreprocessSize != size) {
            if (segPreprocessTex != 0) GLES30.glDeleteTextures(1, intArrayOf(segPreprocessTex), 0)
            if (segPreprocessFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(segPreprocessFbo), 0)
            segPreprocessTex = createTexture(size, size, GLES30.GL_RGBA8)
            segPreprocessFbo = createFbo(segPreprocessTex)
            segPreprocessSize = size
        }
        return segPreprocessFbo
    }

    /**
     * Combines depth + segmentation and normalizes, single place for live and
     * capture paths. NEVER throws — a bad model frame must not blank the pipeline.
     *
     * 1. Where the soft subject mask fires, depth is pulled NEARER in proportion
     *    to its segmentation PROBABILITY (never forced to a constant). This keeps
     *    the subject's internal depth variation, feathers hair / thin-structure
     *    edges, and — crucially — preserves the existing "nearest = largest value"
     *    convention so the auto-focus plane still locks onto the subject. This is
     *    the fix for the old hard binary cutout (and its haloing / edge artifacts).
     * 2. Per-frame normalization: 1st..99th percentile clip then min-max stretch
     *    to 0..1. Applied only when the mask actually shifted pixels (or on the
     *    capture path, [forceNormalize]). Unmasked live frames ride the models'
     *    own running normalization untouched — re-stretching already-normalized
     *    output per frame flattens the depth map and kills the bokeh look.
     *
     * Returns the normalized float buffer (rewound) + its maximum (= the focus
     * plane when auto-focusing).
     */
    private fun combineAndNormalize(result: ByteBuffer, maskBytes: ByteArray?, maskW: Int, maskH: Int, forceNormalize: Boolean): Pair<ByteBuffer, Float> {
        // remaining() is BYTES; each depth pixel is one float32 — the count of
        // floats is remaining()/4. (Reading a FloatArray sized to the byte count
        // throws BufferUnderflowException and silently killed every live and
        // capture inference.)
        val n = result.remaining() / 4
        if (n < 1) return result to FOCUS_DEPTH_DEFAULT
        val f = FloatArray(n)
        result.asFloatBuffer().get(f)
        result.rewind()
        // Soft segmentation fusion. The mask now carries a foreground PROBABILITY
        // (0..255), not a binary threshold. We never override depth to a constant
        // (that flattens the subject and creates a hard halo); instead we pull each
        // subject pixel NEARER in proportion to its segmentation confidence, by a
        // bounded fraction of the scene's depth range. This keeps the subject's
        // internal depth variation, feathers hair/edge transitions, and — crucially
        // — preserves the "nearest = largest value" convention so the auto-focus
        // plane (largest depth) still locks onto the subject.
        var masked = false
        val subjDepths = mutableListOf<Float>()
        if (maskBytes != null && maskBytes.size >= maskW * maskH && maskW > 0 && maskH > 0) {
            val mw = maskW; val mh = maskH
            val m = maskBytes
            try {
                val gw = Math.sqrt(n.toDouble()).toInt().coerceAtLeast(1)
                // Scene depth range (finite pixels) bounds the subject pull so it
                // never eclipses the true nearest object nor collapses to a flat plane.
                var loF = Float.POSITIVE_INFINITY; var hiF = Float.NEGATIVE_INFINITY
                for (v in f) if (v.isFinite()) { if (v < loF) loF = v; if (v > hiF) hiF = v }
                val push = (hiF - loF).coerceAtLeast(1e-5f) * 0.6f
                for (gy in 0 until gw) {
                    val mrow = ((gy * mh) / gw) * mw
                    val grow = gy * gw
                    for (gx in 0 until gw) {
                        val p = ((m[mrow + (gx * mw) / gw].toInt() and 0xFF) / 255f)
                        if (p > 0.02f) {
                            val nv = (f[grow + gx] + p * push).coerceAtMost(hiF + push)
                            f[grow + gx] = nv
                            subjDepths.add(nv)
                            masked = true
                        }
                    }
                }
                if (masked) segOverrides++
            } catch (e: Exception) {
                Log.w(TAG, "mask merge failed", e)
            }
        }
        // Anchor the auto-focus plane to the subject itself: the median depth of
        // the masked foreground pixels. This is convention-independent (works
        // whether the model emits FAR=large or NEAR=large) and guarantees the
        // subject — not the global min/max — ends up sharp.
        if (subjDepths.isNotEmpty()) {
            subjDepths.sort()
            subjectFocusDepth = subjDepths[subjDepths.size / 2].coerceIn(0.01f, 1f)
            hasSubjectFocus = true
        } else {
            hasSubjectFocus = false
        }
        if (!masked && !forceNormalize) {
            // No subject mask this frame: pass the model's output through exactly
            // as-is (models already normalize per frame internally).
            var mx = 0f
            for (i in 0 until n) if (f[i] > mx) mx = f[i]
            return result to mx
        }
        try {
            // Percentile clip over all finite depth pixels. The subject has already
            // been pulled NEARER (not forced to a sentinel) so every pixel is finite
            // and participates normally — no NaN tail, no blank-map-on-subject bug.
            val sorted = FloatArray(n)
            var finiteCount = 0
            for (i in 0 until n) { val v = f[i]; if (v.isFinite()) { sorted[finiteCount++] = v } }
            if (finiteCount < 2) {
                // Frame is (almost) entirely subject: keep a sane neutral range.
                val outAll = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
                val ofAll = outAll.asFloatBuffer()
                for (i in 0 until n) ofAll.put(0.5f)
                outAll.rewind()
                return outAll to 0.5f
            }
            sorted.sort()
            val lo = sorted[(finiteCount * 0.01).toInt().coerceIn(0, finiteCount - 1)]
            var hi = sorted[(finiteCount * 0.99).toInt().coerceIn(0, finiteCount - 1)]
            if (hi.isNaN() || hi <= lo) hi = lo + 1e-3f
            val span = (hi - lo).coerceAtLeast(1e-5f)
            var mx = 0f
            val out = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            val of = out.asFloatBuffer()
            for (i in 0 until n) {
                val v = ((f[i] - lo) / span).coerceIn(0f, 1f)
                of.put(v)
                if (v > mx) mx = v
            }
            out.rewind()
            return out to mx
        } catch (e: Exception) {
            Log.w(TAG, "normalize failed", e)
            var mx = 0f
            for (i in 0 until n) if (f[i].isFinite() && f[i] > mx) mx = f[i]
            return result to mx
        }
    }

    private fun triggerDepth(cameraTex: Int) {
        if (inferenceBusy) { Log.v(TAG, "depth SKIP - busy"); return }
        if (captureInferring) { Log.v(TAG, "depth SKIP - capture"); return }
        inferenceBusy = true
        busySinceNs = System.nanoTime()
        val model = activeDepthModel
        if (model == null || !model.isAvailable) { inferenceBusy = false; return }
        val size = model.inputSize
        val fbo = if (size == ZIP_INPUT_SIZE) zipPreprocessFbo else preprocessFbo
        preprocessDepth(cameraTex, size, fbo)
        val rgb = readbackRgb(fbo, size)
        if (relightEnabled) {
            val ib = ByteArray(rgb.remaining()); rgb.get(ib); rgb.rewind()
            relightRGBBytes = ib; relightRGBSize = size; relightRgbGen++
        }

        // Segmentation shares the throttle cadence with depth, on the same thread
        // pool — preprocess (GL thread, cheap at 128x128) then model.run() in a
        // coroutine joined to the depth model's coroutine.
        val seg = hairSegModel
        var maskRgb: ByteBuffer? = null
        if (seg != null && seg.isAvailable) {
            val segSize = seg.inputSize
            preprocessDepth(cameraTex, segSize, segFboFor(segSize))
            maskRgb = readbackRgb(segFboFor(segSize), segSize)
            if (debugEnabled && maskRgb != null) {
                val ib = ByteArray(maskRgb!!.remaining()); maskRgb!!.get(ib); maskRgb!!.rewind()
                debugInputBytes = ib; debugInputSize = segSize
            }
        }
        inferenceScope.launch {
            try {
                if (captureInferring) { inferenceBusy = false; return@launch }
                val segRun = seg != null && seg.isAvailable && maskRgb != null
                val tModel0 = System.nanoTime()
                // IMPORTANT: run depth and seg sequentially. Two TFLite
                // Interpreter instances sharing the process-wide XNNPACK
                // threadpool must never execute concurrently, or the second
                // run collides and throws (causes the "1 frame then blank"
                // flicker). They are therefore NOT launched as parallel
                // async coroutines.
                val r0 = System.nanoTime()
                val result = model.run(rgb); result.rewind()
                Log.v(TAG, "depth model run ${(System.nanoTime()-r0)/1e6}ms")
                // Segmentation is OPTIONAL for the live bokeh: a failure here must
                // never discard the depth result, otherwise a single SINet hiccup
                // starves the whole pipeline and the bokeh drops out. Isolate it.
                val liveMaskBytes = if (segRun) {
                    try {
                        val s0 = System.nanoTime()
                        val m = seg!!.run(maskRgb!!); m.rewind()
                        val a = ByteArray(m.remaining()); m.get(a)
                        Log.v(TAG, "seg model run ${(System.nanoTime()-s0)/1e6}ms")
                        a
                    } catch (se: Exception) {
                        Log.w(TAG, "seg inference failed — continuing with depth only", se)
                        null
                    }
                } else null
                if (debugEnabled && liveMaskBytes != null) {
                    debugMaskBytes = liveMaskBytes
                    debugMaskW = seg?.outputWidth ?: 0
                    debugMaskH = seg?.outputHeight ?: 0
                }
                val (normalized, mx) = combineAndNormalize(
                    result, liveMaskBytes, seg?.outputWidth ?: 0, seg?.outputHeight ?: 0, forceNormalize = false
                )
                synchronized(depthLock) {
                    latestDepthBuf = normalized
                    depthGen++
                    lastDepthMax = mx
                }
                val now = System.nanoTime()
                if (lastDepthDoneNs != 0L) {
                    val interval = (now - lastDepthDoneNs) / 1_000_000
                    depthIntervalRing[depthIntervalIdx] = interval.toFloat()
                    depthIntervalIdx = (depthIntervalIdx + 1) % depthIntervalRing.size
                }
                lastDepthDoneNs = now
                liveFailStreak = 0
            } catch (e: Exception) {
                Log.e(TAG, "Depth inference failed", e)
                lastInferError = "live:${e.message ?: e.javaClass.simpleName}"
                liveFailStreak++
            } finally { inferenceBusy = false }
        }
    }

    private fun uploadDepth(): Boolean {
        val (buf, gen) = synchronized(depthLock) {
            (latestDepthBuf to depthGen)
        }
        // No depth frame has ever arrived — nothing to upload yet.
        if (buf == null) return depthTexValid
        // The texture already holds this generation; keep reusing it so the
        // bokeh stays applied continuously between inferences (no flicker).
        if (gen == uploadedDepthGen && depthTexValid) return true
        val model = activeDepthModel ?: return depthTexValid
        buf.rewind()
        // The buffer is already depth+seg combined and per-frame normalized by
        // combineAndNormalize (0..1, max = nearest = subject when auto-focusing).
        // Keep the scan as a cheap sanity check for the focus plane.
        val fb = buf.asFloatBuffer(); var mx = 0f; var mn = Float.POSITIVE_INFINITY; val n = fb.remaining()
        val cpu = if (lastDepthCpu == null || lastDepthCpu!!.size != n) FloatArray(n) else lastDepthCpu!!
        // NaN/inf can never reach the R16F texture: a single NaN pixel propagates
        // through FUSE_FRAG and blanks the alpha map (the "depth disappears
        // when an object appears" symptom). Patch in place + copy to CPU.
        for (i in 0 until n) {
            val v = fb.get(i)
            if (v.isNaN() || v.isInfinite()) { fb.put(i, 0f); cpu[i] = 0f } else cpu[i] = v
            if (v > mx) mx = v
            if (v < mn) mn = v
        }
        lastDepthMax = mx
        lastDepthMin = if (mn.isFinite()) mn else 0f
        buf.rewind()
        val ow = model.outputWidth; val oh = model.outputHeight
        lastDepthCpu = cpu; lastDepthCpuW = ow; lastDepthCpuH = oh
        lastDepthCpuGen++
        if (depthRawTex == 0 || depthRawW != ow || depthRawH != oh) {
            if (depthRawTex == 0) { val t = IntArray(1); GLES30.glGenTextures(1, t, 0); depthRawTex = t[0] }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthRawTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, ow, oh, 0, GLES30.GL_RED, GLES30.GL_FLOAT, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            depthRawW = ow; depthRawH = oh
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthRawTex)
        // Both depth engines (MiDaS + ZipDepth) already normalise their output to 0..1
        // before it reaches here, so the focus plane / blur falloff below operate in a
        // stable 0..1 space. Do NOT re-normalise per frame — a per-frame min/max stretch
        // over-empts the histogram (subject at one extreme, everything else at the other)
        // and produces a hard face-mask where both subject and background blur.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, ow, oh, GLES30.GL_RED, GLES30.GL_FLOAT, buf)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        uploadedDepthGen = gen
        depthTexValid = true
        return true
    }

    private fun blitDepthUpsample() {
        if (blitProgram == 0 || upsampleFbo == 0) return
        val aspect = if (surfaceW > 0 && surfaceH > 0) surfaceW.toFloat() / surfaceH.toFloat() else 1f
        val up = uncropParams(aspect)
        GLES30.glUseProgram(blitProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uTex"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blitProgram, "uScale"), up[0], up[2])
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blitProgram, "uOff"), up[1], up[3])
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, upsampleFbo)
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthRawTex)
        drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    // ─── FUSION: Combine depth into single alpha map ───
    private fun fuseAlpha(focusX: Float, focusY: Float) {
        if (fuseProgram == 0) initFuseProgram()
        if (fuseProgram == 0) return   // link failed on this driver — skip, don't spam 1281
        val tapD = currentFocusDepth(focusX, focusY)
        lastTapDepth = tapD
        // Once we have a valid depth texture, keep applying it. The live preview
        // deliberately reuses the last uploaded depth between inferences, so a
        // momentary inference gap must NOT blank the bokeh (that was the
        // "depth shows then suddenly stops" bug).
        val depthReady = depthTexValid
        GLES30.glUseProgram(fuseProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(fuseProgram, "uDepth"), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(fuseProgram, "uFocusDepth"), tapD)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(fuseProgram, "uFocusRange"), depthRange)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(fuseProgram, "uMaxBlur"), MAX_BLUR)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(fuseProgram, "uHasDepth"), if (depthReady) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(fuseProgram, "uForeground"), if (foregroundBlur) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(fuseProgram, "uExpK"), expFalloff)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fusedAlphaFbo)
        GLES30.glViewport(0, 0, MICRO_W, MICRO_H)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,
            if (depthUpsampledTex != 0) depthUpsampledTex else depthRawTex)
        drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private var fuseProgram = 0
    private fun initFuseProgram() {
        fuseProgram = createProgram(vert, FUSE_FRAG, "fuse")
        if (fuseProgram == 0) {
            Log.w(TAG, "fuse (complex) rejected — trying minimal variant")
            fuseProgram = createProgram(vert, FUSE_FRAG_SIMPLE, "fuseSimple")
        }
    }

    // Focus plane: tapped point when the user has picked focus, otherwise the
    // nearest object so portraits are sharp by default. The depth model emits
    // FAR = large, so the nearest (subject) is the MINIMUM depth value, not the
    // max — focusing on the max locks onto the background and blurs the subject
    // (the "subject blurred, background sharp" bug).
    private fun currentFocusDepth(fx: Float, fy: Float): Float {
        if (!autoFocus) return readDepthAtTap(fx, fy)
        // Prefer the subject-anchored focus so the segmented subject is always
        // sharp; fall back to the global min only when no mask is available yet.
        return if (hasSubjectFocus) subjectFocusDepth else lastDepthMin.coerceIn(0.01f, 1f)
    }

    private fun readDepthAtTap(fx: Float, fy: Float): Float {
        val cpu = lastDepthCpu ?: return FOCUS_DEPTH_DEFAULT
        val w = lastDepthCpuW; val h = lastDepthCpuH
        if (w <= 0 || h <= 0 || cpu.size != w * h) return FOCUS_DEPTH_DEFAULT
        // Bilinear sample. Depth buffer rows are top-origin; flip Y to match the
        // GL (bottom-origin) readback the old path used, so focus lands where tapped.
        val x = (fx * (w - 1)).coerceIn(0f, (w - 1).toFloat())
        val y = ((1f - fy) * (h - 1)).coerceIn(0f, (h - 1).toFloat())
        val x0 = x.toInt(); val y0 = y.toInt()
        val x1 = minOf(x0 + 1, w - 1); val y1 = minOf(y0 + 1, h - 1)
        val tx = x - x0; val ty = y - y0
        val d00 = cpu[y0 * w + x0]; val d10 = cpu[y0 * w + x1]
        val d01 = cpu[y1 * w + x0]; val d11 = cpu[y1 * w + x1]
        val d = d00 * (1f - tx) * (1f - ty) + d10 * tx * (1f - ty) + d01 * (1f - tx) * ty + d11 * tx * ty
        return if (d.isNaN() || d <= 0f) FOCUS_DEPTH_DEFAULT else d.coerceIn(0.01f, 5f)
    }

    // ─── EMA: Temporal smooth the fused alpha ───
    private fun applyEMASmooth() {
        if (emaBlendProgram == 0) return
        // fusedAlphaTex = raw fused alpha (just written by fuseAlpha)
        // prevAlphaTex  = previous frame's EMA output
        // Write EMA to prevAlphaFbo: prev = 0.3 * curr_raw + 0.7 * prev_ema
        GLES30.glUseProgram(emaBlendProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(emaBlendProgram, "uCurr"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(emaBlendProgram, "uPrev"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(emaBlendProgram, "uBlend"), EMA_ALPHA)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevAlphaFbo)
        GLES30.glViewport(0, 0, MICRO_W, MICRO_H)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fusedAlphaTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevAlphaTex)
        drawQuad()
        // Copy prevAlphaTex back to fusedAlphaTex for downstream output
        GLES30.glUseProgram(blitProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uTex"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blitProgram, "uScale"), 1f, 1f)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blitProgram, "uOff"), 0f, 0f)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fusedAlphaFbo)
        GLES30.glViewport(0, 0, MICRO_W, MICRO_H)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevAlphaTex)
        drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    // ─── DISC/Aperture BOKEH ────────────────────────────────────────────────────
    // depth → blur map (fuseAlpha), then a single SQUARE (non-separable) gather that
    // walks a golden-angle spiral and accumulates only neighbours whose own CoC disc
    // contains this pixel. Weighting by 1/area makes bright lights render as real,
    // shaped bokeh balls. Runs at half-res for the live preview (composited back at
    // full res by compositeBokeh); capture re-runs it at full res for max quality.
    private fun computeCoCBlur(cameraTex: Int, w: Int, h: Int, fx: Float, fy: Float) {
        if (bokehGatherProgram == 0 || cocCompositeProgram == 0) return

        // Clear once first so the very first frame isn't full of uninitialized map.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, segFullFbo)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // Upscale the (micro-res) blur map to full-res for the gather mask.
        GLES30.glUseProgram(blitProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uTex"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blitProgram, "uScale"), 1f, 1f)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blitProgram, "uOff"), 0f, 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fusedAlphaTex)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, segFullFbo)
        GLES30.glViewport(0, 0, w, h); drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // Maximum per-pixel blur radius (map.r=1 → uMaxRadius). Map.r=0 → identity.
        val radius = MAX_COC * (0.4f + blurStrength * 0.6f)
        // Live preview uses a lighter gather; capture (renderBokehFull) uses more.
        val samples = 48

        GLES30.glUseProgram(bokehGatherProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(bokehGatherProgram, "uColor"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(bokehGatherProgram, "uBlurMap"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(bokehGatherProgram, "uMaxRadius"), radius)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(bokehGatherProgram, "uTexel"), 1f / w, 1f / h)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(bokehGatherProgram, "uAperture"), apertureShape)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(bokehGatherProgram, "uSamples"), samples)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, segFullTex)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cocBlurVFbo)
        GLES30.glViewport(0, 0, w / 2, h / 2); drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    // True only when every shader the bokeh chain links AND the inputs it needs
// exist. Any missing link → raw camera (never garbage/black on a weak driver).
private fun bokehChainOk(): Boolean =
    blitProgram != 0 && fuseProgram != 0 && emaBlendProgram != 0 &&
    cocBlurHProgram != 0 && cocBlurVProgram != 0 &&
    bokehGatherProgram != 0 && cocCompositeProgram != 0 &&
    depthUpsampledTex != 0 && fusedAlphaTex != 0 &&
    cocBlurHTex != 0 && cocBlurVTex != 0 && segFullTex != 0 && prevAlphaTex != 0

private fun compositeBokeh(cameraTex: Int, w: Int, h: Int) {
        if (!bokehChainOk() || cocCompositeProgram == 0) { drawRawCamera(cameraTex, w, h); return }
        GLES30.glUseProgram(cocCompositeProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cocCompositeProgram, "uSharp"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cocCompositeProgram, "uBlur"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cocCompositeProgram, "uBlurMap"), 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cocBlurVTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, segFullTex)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, w, h); drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun drawQuad() {
        GLES30.glBindVertexArray(quadVAO)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    private fun setInt(prog: Int, name: String, v: Int) {
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, name), v)
    }
    private fun setFloat(prog: Int, name: String, v: Float) {
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, name), v)
    }
    private fun setFloat2(prog: Int, name: String, a: Float, b: Float) {
        GLES30.glUniform2f(GLES30.glGetUniformLocation(prog, name), a, b)
    }
    private fun setFloat3(prog: Int, name: String, a: Float, b: Float, c: Float) {
        GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, name), a, b, c)
    }

    // ─── Composite: Studio light ───
    private fun compositeStudio(cameraTex: Int, w: Int, h: Int) {
        if (studioProgram == 0) { drawRawCamera(cameraTex, w, h); return }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(studioProgram)
        setInt(studioProgram, "uCameraTex", 0)
        setInt(studioProgram, "uDepthTex", 1)
        setFloat2(studioProgram, "uTexelSize", 1f / w, 1f / h)
        setFloat(studioProgram, "uFocusDepth", currentFocusDepth(tapX, tapY))
        setFloat(studioProgram, "uFocusRange", depthRange)
        setFloat3(studioProgram, "uKeyColor", 1.0f, 0.95f, 0.9f)
        setFloat(studioProgram, "uKeyInt", 1.2f)
        setFloat3(studioProgram, "uFillDir", -0.4f, 0.5f, 0.3f)
        setFloat3(studioProgram, "uFillColor", 0.7f, 0.8f, 1.0f)
        setFloat(studioProgram, "uFillInt", 0.4f)
        setFloat3(studioProgram, "uRimDir", 0.0f, 0.2f, -1.0f)
        setFloat3(studioProgram, "uRimColor", 1.0f, 1.0f, 1.0f)
        setFloat(studioProgram, "uRimInt", 0.8f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthUpsampledTex)
        drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    // ─── DEPTH VIEW: clean turbo colormap visualizer ───
    private val DEPTH_QUAD_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D uCameraTex;
        uniform sampler2D uDepthTex;
        uniform float uFocusDepth;
        uniform float uFocusRange;
        uniform sampler2D uFusedTex;
        uniform vec2 uTapUV;
        uniform vec2 uViewRes;
        in vec2 vUV;
        out vec4 o;

        vec3 turbo(float x) {
            x = clamp(x, 0.0, 1.0);
            const vec4 kr = vec4(0.13572138, 4.61539260, -42.66032258, 132.13108234);
            const vec4 kg = vec4(0.09140261, 2.19418839, 4.84296658, -14.18503333);
            const vec4 kb = vec4(0.10667330, 12.64194608, -60.58204836, 110.36276771);
            const vec2 kr2 = vec2(-152.94239396, 59.28637943);
            const vec2 kg2 = vec2(4.27729857, 2.82956604);
            const vec2 kb2 = vec2(-89.90310912, 27.34824973);
            float x4 = x * x * x * x;
            float x2 = x * x;
            float sx = sqrt(x);
            vec3 c = vec3(
                kr.x*x4 + kr.y*x2*x + kr.z*x2 + kr.w*x + kr2.x*sx + kr2.y,
                kg.x*x4 + kg.y*x2*x + kg.z*x2 + kg.w*x + kg2.x*sx + kg2.y,
                kb.x*x4 + kb.y*x2*x + kb.z*x2 + kb.w*x + kb2.x*sx + kb2.y);
            return clamp(c, 0.0, 1.0);
        }

        void main() {
            vec2 p = vUV * 2.0;
            vec2 cell = floor(p);
            vec2 uv = fract(p);
            vec3 col;
            if (cell.y < 1.0 && cell.x < 1.0) {
                col = texture(uCameraTex, uv).rgb;
            } else if (cell.y < 1.0 && cell.x >= 1.0) {
                float d = texture(uDepthTex, uv).r;
                if (!(d >= 0.0) || d > 1.0) d = 0.5;
                col = turbo(d);
            } else if (cell.y >= 1.0 && cell.x < 1.0) {
                float d0 = texture(uDepthTex, uv).r;
                float sm = 1.0 - clamp(abs(d0 - uFocusDepth) / max(uFocusRange, 0.02), 0.0, 1.0);
                col = mix(vec3(0.04, 0.04, 0.06), vec3(0.92, 0.36, 0.16), clamp(sm, 0.0, 1.0));
            } else {
                float f = texture(uFusedTex, uv).r;
                col = mix(vec3(0.02, 0.05, 0.08), vec3(0.95, 0.95, 0.98), clamp(f, 0.0, 1.0));
            }
            o = vec4(col, 1.0);

            vec2 fp = fract(p);
            if (fp.x < 0.005 || fp.y < 0.005) o = vec4(0.0, 0.0, 0.0, 1.0);

            if (cell.y < 1.0 && cell.x >= 1.0) {
                vec2 px = gl_FragCoord.xy;
                vec2 tp = uTapUV * uViewRes;
                vec2 d2 = abs(px - tp);
                if ((d2.x < 1.0 && d2.y < 14.0) || (d2.y < 1.0 && d2.x < 14.0)) o = vec4(vec3(0.95), 1.0);
            }
        }
    """

    private var turboProgram = 0

    // Minimal-portable DEPTH_VIEW: gray depth instead of turbo colormap, no
    // tap crosshair, no fused quad. Auto-selected if the complex variant fails.
    private val DEPTH_QUAD_FRAG_SIMPLE = """#version 300 es
        precision highp float;
        uniform sampler2D uCameraTex;
        uniform sampler2D uDepthTex;
        uniform float uFocusDepth;
        uniform float uFocusRange;
        in vec2 vUV;
        out vec4 o;
        void main(){
            vec2 p = vUV * 2.0;
            vec2 cell = floor(p);
            vec2 uv = fract(p);
            vec3 col = vec3(0.05);
            if (cell.y < 1.0 && cell.x < 1.0) {
                col = texture(uCameraTex, uv).rgb;
            } else if (cell.y < 1.0 && cell.x >= 1.0) {
                float d = texture(uDepthTex, uv).r;
                col = vec3(clamp(d, 0.0, 1.0));
            } else if (cell.y >= 1.0 && cell.x < 1.0) {
                float d0 = texture(uDepthTex, uv).r;
                float sm = 1.0 - clamp(abs(d0 - uFocusDepth) / max(uFocusRange, 0.02), 0.0, 1.0);
                col = mix(vec3(0.04, 0.04, 0.06), vec3(0.92, 0.36, 0.16), clamp(sm, 0.0, 1.0));
            } else {
                col = vec3(0.30, 0.42, 0.60);
            }
            o = vec4(col, 1.0);
        }
    """
    @Volatile var fallbackCount = 0
    // If a mode's shader failed to link, show the RAW camera feed instead of black.
    private fun drawRawCamera(cameraTex: Int, w: Int, h: Int) {
        fallbackCount++
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (blitProgram != 0) {
            GLES30.glUseProgram(blitProgram)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uTex"), 0)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(blitProgram, "uScale"), 1f, 1f)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(blitProgram, "uOff"), 0f, 0f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraTex)
            drawQuad()
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun compositeDepthView(cameraTex: Int, w: Int, h: Int) {
        if (turboProgram == 0) {
            turboProgram = createProgram(vert, DEPTH_QUAD_FRAG, "depthView")
            if (turboProgram == 0) {
                Log.w(TAG, "depthView (complex) rejected — trying minimal variant")
                turboProgram = createProgram(vert, DEPTH_QUAD_FRAG_SIMPLE, "depthViewSimple")
            }
        }
        // The fused-alpha quadrant needs fuseProgram; if it's missing the whole
        // 2x2 diagnostic is incomplete — show the raw camera instead of a garbage cell.
        if (turboProgram == 0 || fuseProgram == 0) { drawRawCamera(cameraTex, w, h); return }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(turboProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(turboProgram, "uCameraTex"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(turboProgram, "uDepthTex"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(turboProgram, "uFusedTex"), 3)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(turboProgram, "uFocusDepth"), currentFocusDepth(tapX, tapY))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(turboProgram, "uFocusRange"), depthRange)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(turboProgram, "uTapUV"), tapX, tapY)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(turboProgram, "uViewRes"), w.toFloat(), h.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthUpsampledTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fusedAlphaTex)
        drawQuad()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
}
