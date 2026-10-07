package com.eagleseye.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.ContentUris
import android.net.Uri
import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import com.eagleseye.camera.capture.HyperlapseRecorder
import com.eagleseye.camera.capture.GLVideoRecorder
import com.eagleseye.camera.capture.RawCapture
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import com.eagleseye.camera.engine.FusionPipelineEngine
import com.eagleseye.camera.capture.PanoStitcher
import com.eagleseye.camera.relight.RelightEngine
import com.eagleseye.camera.relight.RelightSurface
import com.eagleseye.camera.presets.FilmPreset
import com.eagleseye.camera.presets.EffectConfig
import com.eagleseye.camera.film.FilmCatalog
import com.eagleseye.camera.DateStampStyle
import com.eagleseye.camera.DateFormatType

// Background executor for in-memory captures (YUV→RGB conversion off the main thread)
private val captureBg = Executors.newFixedThreadPool(2) { r ->
    Thread(r, "eagle-capture").apply { priority = Thread.NORM_PRIORITY + 1 }
}

val EagleGold: Color get() = AppAccent.color
val RecordRed    = Color(0xFFE53935)
val GlassBg      = Color(0xBB111111)
val GlassBorder  = Color.White.copy(alpha = 0.15f)
val EagleGreen   = Color(0xFF00C853)
val FeatureYellow = Color(0xFFFFD600)
private val GOLD_JSX: Color get() = AppAccent.color
private val GOLD_HI_JSX: Color get() = AppAccent.hi
private val MUTED2_JSX = Color(0xFF3E3E48)
private val TEXT_JSX = Color(0xFFF0EDE8)

enum class FlashMode   { AUTO, ON, OFF, TORCH }
enum class GridOverlay { OFF, THIRDS, FIFTY, GOLDEN, SQUARE, CENTER, DIAGONAL }
enum class SelfTimer   { OFF, S3, S5, S10 }

private val EFFECT_PRESETS = listOf(
    "off" to "OFF", "bw" to "B&W", "fisheye" to "FISHEYE",
    "vignette" to "VIGNETTE", "bloom" to "BLOOM", "vhs" to "VHS",
    "glitch" to "GLITCH", "dust" to "DUST"
)

    val SS_NS = longArrayOf(
        250_000L, 500_000L, 1_000_000L, 2_000_000L, 2_500_000L, 3_125_000L,
        5_000_000L, 6_250_000L, 8_333_333L, 10_000_000L, 12_500_000L, 16_666_666L,
        20_000_000L, 25_000_000L, 33_333_333L, 40_000_000L, 50_000_000L, 66_666_666L,
        83_333_333L, 100_000_000L, 125_000_000L, 166_666_666L, 200_000_000L,
        250_000_000L, 333_333_333L, 500_000_000L, 666_666_666L, 800_000_000L,
        1_000_000_000L, 1_500_000_000L, 2_000_000_000L, 3_000_000_000L,
        4_000_000_000L, 6_000_000_000L, 8_000_000_000L, 10_000_000_000L,
        15_000_000_000L, 20_000_000_000L, 30_000_000_000L
    )

fun isFastBurstEligible(ssNs: Long): Boolean = ssNs >= 6_666_666L && ssNs <= 33_333_333L

fun capturePhoto(ic: ImageCapture, ctx: Context, onSaved: (Uri?) -> Unit) {
    val cv=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"EaglesEye_${System.currentTimeMillis()}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/EaglesEye")}
    ic.takePicture(ImageCapture.OutputFileOptions.Builder(ctx.contentResolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv).build(),ContextCompat.getMainExecutor(ctx),object:ImageCapture.OnImageSavedCallback{override fun onImageSaved(o:ImageCapture.OutputFileResults){onSaved(o.savedUri)};override fun onError(e:ImageCaptureException){onSaved(null)}})
}

// Decode a captured JPEG upright — EXIF rotation is applied so the CPU frame +
// date stamp land on a correctly oriented photo (mirrors the pano-tile path).
private fun decodeUpright(ctx: Context, uri: Uri): Bitmap? = try {
    ctx.contentResolver.openInputStream(uri)?.use { s ->
        var bm = BitmapFactory.decodeStream(s) ?: return@use null
        runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { exs ->
                val ei = androidx.exifinterface.media.ExifInterface(exs)
                when (ei.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> { val m = android.graphics.Matrix(); m.postRotate(90f); val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, false); bm.recycle(); bm = r }
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> { val m = android.graphics.Matrix(); m.postRotate(180f); val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, false); bm.recycle(); bm = r }
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> { val m = android.graphics.Matrix(); m.postRotate(270f); val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, false); bm.recycle(); bm = r }
                }
            }
        }
        bm
    }
} catch (e: Exception) { null }

// ── AGSL relight input converters (model-input RGB + CPU depth matte) ─────
// RGB888 (3 bytes/px, square) → ARGB bitmap for the RuntimeShader "frame" input.
private fun rgbBytesToBitmap(rgb: ByteArray, w: Int): Bitmap {
    val px = IntArray(w * w)
    var i = 0
    for (p in px.indices) {
        px[p] = (0xFF shl 24) or ((rgb[i].toInt() and 0xFF) shl 16) or ((rgb[i + 1].toInt() and 0xFF) shl 8) or (rgb[i + 2].toInt() and 0xFF)
        i += 3
    }
    // glReadPixels returns BOTTOM-UP rows; flip vertically so the relight frame
    // shows right-side up and lines up with the top-down depth matte.
    for (r in 0 until w / 2) {
        val a = r * w; val b = (w - 1 - r) * w
        for (c in 0 until w) {
            val t = px[a + c]; px[a + c] = px[b + c]; px[b + c] = t
        }
    }
    return Bitmap.createBitmap(px, w, w, android.graphics.Bitmap.Config.ARGB_8888)
}

// Normalized 0..1 depth floats → gray ARGB bitmap for the "depth" input.
private fun depthFloatsToBitmap(f: FloatArray, w: Int, h: Int): Bitmap {
    val px = IntArray(w * h)
    for (p in px.indices) {
        val v = (f[p] * 255f).toInt().coerceIn(0, 255)
        px[p] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
}

// Shake-to-undo: delete the most recent EaglesEye_ media item from the library.
private fun undoLastCapture(ctx: Context) {    val r = ctx.contentResolver
    val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
    val args = arrayOf("EaglesEye_%")
    var best: Pair<Long, Uri>? = null
    listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).forEach { base ->
        runCatching {
            r.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED),
                sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT 1")?.use { c ->
                if (c.moveToFirst()) {
                    val d = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
                    val u = ContentUris.withAppendedId(base, c.getLong(0))
                    if (best == null || d > best!!.first) best = d to u
                }
            }
        }
    }
    val target = best?.second ?: run {
        Toast.makeText(ctx, "Nothing to undo", Toast.LENGTH_SHORT).show(); return
    }
    r.delete(target, null, null)
    Toast.makeText(ctx, "UNDO — last capture removed", Toast.LENGTH_SHORT).show()
}

// In-memory capture (no MediaStore write) — used by hyperlapse intervalometer,
// night stacks and HDR. YUV→RGB conversion happens on the shared background
// executor, so the callback runs off the main thread.
fun capturePhotoMemory(ic: ImageCapture, ctx: Context, onBitmap: (Bitmap?) -> Unit) {
    ic.takePicture(captureBg, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val bm = runCatching { image.toBitmap() }.getOrNull()
            image.close()
            onBitmap(bm)
        }
        override fun onError(e: ImageCaptureException) { onBitmap(null) }
    })
}

@Composable fun CameraApp() {
    val ctx=LocalContext.current
    var hasPerm by remember{mutableStateOf(ContextCompat.checkSelfPermission(ctx,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    var showGallery by remember{mutableStateOf(false)}
    var showSettings by remember{mutableStateOf(false)}
    var showLicenses by remember{mutableStateOf(false)}
    var settingsFromGallery by remember{mutableStateOf(false)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){hasPerm=it[Manifest.permission.CAMERA]==true}
    // Photos/videos live in MediaStore: READ_MEDIA_* must be granted before the
    // gallery opens, otherwise only the app's own captures are visible (minSdk 33).
    val mediaLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){showGallery=true}
    val openGallery: () -> Unit = {
        val need=arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            .filter{ContextCompat.checkSelfPermission(ctx,it)!=PackageManager.PERMISSION_GRANTED}
        if(need.isEmpty())showGallery=true else mediaLauncher.launch(need.toTypedArray())
    }
    LaunchedEffect(Unit){if(!hasPerm)launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))}
    when{
        showGallery->GalleryScreen(onClose={showGallery=false},onOpenSettings={settingsFromGallery=true;showGallery=false;showSettings=true})
        showSettings->SettingsScreen(onClose={showSettings=false;if(settingsFromGallery){settingsFromGallery=false;showGallery=true}},onOpenLicenses={showSettings=false;showLicenses=true})
        showLicenses->LicensesScreen(onClose={showLicenses=false;showSettings=true})
        hasPerm->CameraScreen(onGallery=openGallery)
        else->Box(Modifier.fillMaxSize().background(Color.Black),Alignment.Center){Text("Camera permission required",color=Color.White)}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraScreen(onGallery: () -> Unit) {
    val context=LocalContext.current;val haptic=LocalHapticFeedback.current;val scope=rememberCoroutineScope()
    fun hapticIfOn(t: HapticFeedbackType) { if (AppSettings.haptics(context)) haptic.performHapticFeedback(t) }

    // ── Screen awake + haptics settings ───────────────────────────────────────
    val keepAwake = remember { AppSettings.keepAwake(context) }
    DisposableEffect(keepAwake) {
        val win = (context as? Activity)?.window
        if (keepAwake) win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // ── Core UI ───────────────────────────────────────────────────────────────
    var activeMode by remember{mutableStateOf(AppSettings.defaultMode(context))}
    val isVideo = activeMode == "video" || activeMode == "slomo" || activeMode == "hyperlapse"
    val isPano = activeMode == "pano"
    var isPro by remember{mutableStateOf(false)}
    var flash by remember{mutableStateOf(FlashMode.OFF)}
    var ratio by remember{mutableStateOf(AppSettings.defaultAspect(context))}
    var gridOverlay by remember{mutableStateOf(when(AppSettings.defaultGrid(context)){"3x3"->GridOverlay.THIRDS;"5x5"->GridOverlay.FIFTY;"4x4"->GridOverlay.SQUARE;"golden"->GridOverlay.GOLDEN;"center"->GridOverlay.CENTER;"diagonal"->GridOverlay.DIAGONAL;else->GridOverlay.OFF})}
    var timerMode by remember{mutableStateOf(SelfTimer.OFF)}
    var blink by remember{mutableStateOf(false)}
    var timerCount by remember{mutableStateOf(0)}
    var showGridDropdown by remember{mutableStateOf(false)}

    // ── GL view reference for capture processing ──────────────────────────────
    var glView by remember { mutableStateOf<CameraGLView?>(null) }
    val mainHandler=remember{Handler(Looper.getMainLooper())}

    // ── Star menu features ────────────────────────────────────────────────────
    var showStarMenu by remember{mutableStateOf(false)}
    var focusPeakOn by remember{mutableStateOf(false)}
    var histogramOn by remember{mutableStateOf(false)}
    var histBins by remember{mutableStateOf<IntArray?>(null)}
    var fastBurstOn by remember{mutableStateOf(false)}
    var hdrOn by remember{mutableStateOf(false)}
    var nightOn by remember{mutableStateOf(false)}
    var rawOn by remember{mutableStateOf(false)}
    var rawSupported by remember{mutableStateOf(false)}
    var eisOn by remember{mutableStateOf(false)}
    var frontFlash by remember{mutableStateOf(false)}
    var rawBusy by remember{mutableStateOf(false)}
    var hdrBusy by remember{mutableStateOf(false)}
    var nightBusy by remember{mutableStateOf(false)}

    // ── Teaching labels, develop ritual, HUD placement ───────────────────────
    var teachLabel by remember{mutableStateOf<String?>(null)}
    var teachJob by remember{mutableStateOf<Job?>(null)}
    var developing by remember{mutableStateOf(false)}
    var processing by remember{mutableStateOf(false)}
    var processProgress by remember{mutableStateOf(0f)}
    LaunchedEffect(processing){
        while(processing){
            processProgress = glView?.captureProgress ?: processProgress
            delay(50)
        }
    }

    // ── AGSL light orb (relight) ────────────────────────────────────────────
    val relightEngine = remember { RelightEngine(context) }
    var relightOn by remember { mutableStateOf(AppSettings.relightOn(context)) }
    val relightUv = remember { mutableStateOf(Offset(0.68f, 0.32f)) }
    val relightIntensity = remember { mutableStateOf(0.9f) }
    val relightColor = remember { mutableStateOf(Color(0xFFFFF2DF)) }
    val relightDebug = remember { mutableStateOf(0) }
    var relightFrame by remember { mutableStateOf<Bitmap?>(null) }
    var relightDepth by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(relightOn, glView) {
        val de = glView?.depthEngine
        if (de == null || !relightOn) { de?.relightEnabled = false; return@LaunchedEffect }
        de.relightEnabled = true
        var fGen = -1L; var dGen = -1L
        try {
            while (relightOn && isActive) {
                val fg = de.relightRgbGen; val fb = de.relightRGBBytes; val fs = de.relightRGBSize
                if (fb != null && fs > 0 && fg != fGen) { fGen = fg; relightFrame = withContext(Dispatchers.Default) { rgbBytesToBitmap(fb, fs) } }
                val dg = de.lastDepthCpuGen; val dp = de.dbgDepthCpu; val dw = de.dbgDepthW; val dh = de.dbgDepthH
                if (dp != null && dw > 0 && dh > 0 && dg != dGen) {
                    dGen = dg; relightDepth = withContext(Dispatchers.Default) { depthFloatsToBitmap(dp, dw, dh) }
                }
                delay(120)
            }
        } finally {
            de.relightEnabled = false
        }
    }
    var histOffset by remember{mutableStateOf(Offset.Zero)}
    var histBoxSize by remember{mutableStateOf(IntSize.Zero)}
    var lastZoomStop by remember{mutableIntStateOf(1)}
    val lastProStop = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit){
        rawSupported=RawCapture.isSupported(context)
        if(!rawSupported)rawOn=false
    }
LaunchedEffect(glView){
        val v=glView?:return@LaunchedEffect
        v.histogramCallback={bins->runCatching{mainHandler.post{histBins=bins}}}
    }

    // ── Hyperlapse state ──────────────────────────────────────────────────────
    var hyperRecorder by remember{mutableStateOf<HyperlapseRecorder?>(null)}
    var hyperJob by remember{mutableStateOf<Job?>(null)}

    // ── Burst state ───────────────────────────────────────────────────────────
    var isBurstActive by remember{mutableStateOf(false)}
    val burstUris = remember{mutableListOf<Uri>()}
    var burstJob by remember{mutableStateOf<Job?>(null)}

    // ── Pano state ────────────────────────────────────────────────────────────
    val panoUris = remember{mutableListOf<Uri>()}
    var panoTiles by remember{mutableStateOf(0)}
    var panoBusy by remember{mutableStateOf(false)}
    var panoDone by remember{mutableStateOf(false)}
    var panoLastUri by remember{mutableStateOf<Uri?>(null)}

    // ── False color state ─────────────────────────────────────────────────────
    val anyStarActive = fastBurstOn||hdrOn
    val anyGridActive = gridOverlay!=GridOverlay.OFF

    // ── Camera state ──────────────────────────────────────────────────────────
    var captureUseCase by remember{mutableStateOf<ImageCapture?>(null)}
    var videoCaptureUseCase by remember{mutableStateOf<VideoCapture<Recorder>?>(null)}
    var cameraRef by remember{mutableStateOf<Camera?>(null)}
    var previewSize by remember{mutableStateOf(IntSize.Zero)}
    var isFront by remember{mutableStateOf(false)}
    var activeLens by remember{mutableStateOf(0)}
    var zoomRatio by remember{mutableStateOf(1f)}
    var streamAspect by remember{mutableStateOf(0f)}
    var videoRes by remember{mutableStateOf("1080")}
    var cameraResetKey by remember{mutableStateOf(0)}
    var activeRecording by remember{mutableStateOf<Recording?>(null)}
    var glRecorder by remember{mutableStateOf<GLVideoRecorder?>(null)}
    var isRecording by remember{mutableStateOf(false)}
    var recordingSec by remember{mutableStateOf(0)}

    // ── Film state ────────────────────────────────────────────────────────────
    var lastPhotoUri by remember{mutableStateOf<Uri?>(null)}
    var selectedProfile by remember{mutableStateOf(FILM_PROFILES[0])}
    var showAnalogSheet by remember{mutableStateOf(false)}
    // System back dismisses whatever overlay is open instead of killing the camera.
    BackHandler(enabled = showAnalogSheet || showStarMenu || showGridDropdown || isPro) {
        when {
            showAnalogSheet -> showAnalogSheet = false
            showStarMenu -> showStarMenu = false
            showGridDropdown -> showGridDropdown = false
            isPro -> isPro = false
        }
    }
    var filmId by rememberSaveable{mutableStateOf<String?>(null)}
    var userGrain by remember{mutableStateOf(0f)}
    var userLeak by remember{mutableStateOf(0f)}
    var lookStrength by remember{mutableStateOf(1f)}
    var leakColorMode by remember{mutableStateOf(LightLeakColor.ORANGE)}
    var leakAdaptive by remember{mutableStateOf(true)}
    var frameType by remember{mutableStateOf(FrameType.NONE)}
    var frameMode by remember{mutableStateOf(FrameMode.OVERLAY)}
    var frameConfig by remember{mutableStateOf(FrameConfig())}
    var savedPresets by remember{mutableStateOf(listOf<FramePreset?>(null,null,null,null,null))}
    var showTimestamp by remember{mutableStateOf(AppSettings.timestampDefault(context))}
    var dateStyle by remember{mutableStateOf(AppSettings.dateStampStyle(context))}
    var dateFormat by remember{mutableStateOf(AppSettings.dateStampFormat(context))}
    var dateShowTime by remember{mutableStateOf(AppSettings.dateStampShowTime(context))}
    var datePos by remember{mutableStateOf(AppSettings.dateStampPos(context))}
    var dateCustom by remember{mutableStateOf("")}
    var hiFpsSetting by remember{mutableStateOf(AppSettings.hiFps(context))}
    var hiBitSetting by remember{mutableStateOf(AppSettings.hiBitrate(context))}
    var shakeUndoOn by remember{mutableStateOf(AppSettings.shakeUndo(context))}

    // ── Shake-to-undo ─────────────────────────────────────────────────────────
    DisposableEffect(shakeUndoOn) {
        if (!shakeUndoOn) return@DisposableEffect onDispose {}
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (acc == null) return@DisposableEffect onDispose {}
        var lastShake = 0L
        val undoCtx = context
        val listener = object : SensorEventListener {
            override fun onSensorChanged(ev: SensorEvent) {
                val ax = ev.values[0]; val ay = ev.values[1]; val az = ev.values[2]
                val mag = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
                val now = SystemClock.elapsedRealtime()
                if (mag > 16f && now - lastShake > 1600) { lastShake = now; undoLastCapture(undoCtx) }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(listener) }
    }

    // ── Lens effects ──────────────────────────────────────────────────────
    var ec by remember{mutableStateOf(EffectConfig().copy(depthBlurStrength = AppSettings.bokehStrength(context)))}
    var depthMiDas by remember{mutableStateOf(AppSettings.useMiDasDepth(context))}
    var effectPreset by remember{mutableStateOf("off")}
    fun teach(msg: String) {
        teachJob?.cancel()
        teachLabel = msg
        teachJob = scope.launch { delay(1700); if (teachLabel == msg) teachLabel = null }
    }
    fun applyEffectPreset(k: String) {
        effectPreset = k
        hapticIfOn(HapticFeedbackType.LongPress)
        val depthMode = ec.depthMode; val depthBlur = ec.depthBlurStrength
        ec = when (k) {
            "off" -> EffectConfig()
            "bw" -> EffectConfig().copy(bwOn = true)
            "fisheye" -> EffectConfig().copy(fisheyeOn = true, fisheyeStrength = 0.85f)
            "vignette" -> EffectConfig().copy(vignetteOn = true, vignetteIntensity = 0.65f)
            "bloom" -> EffectConfig().copy(bloomOn = true, bloomStrength = 0.7f, halationOn = true, halationStrength = 0.3f)
            "vhs" -> EffectConfig().copy(vhsOn = true, vhsTracking = 0.5f, grainOn = true, grainStrength = 0.25f, caOn = true, caStrength = 0.3f)
            "glitch" -> EffectConfig().copy(glitchOn = true, glitchStrength = 0.7f)
            "dust" -> EffectConfig().copy(dustOn = true, grainOn = true, grainStrength = 0.2f)
            else -> ec
        }
        ec = ec.copy(depthMode = depthMode, depthBlurStrength = depthBlur)
        teach(if (k == "off") "EFFECTS off" else "${k.uppercase()} EFFECT on")
    }
    // Depth engine config. Applied on every user change AND persisted (AppSettings)
// so CameraGLRenderer.onSurfaceCreated can restore it onto freshly created engine
// instances — a one-shot effect alone left a fresh engine at mode=OFF (raw camera)
// after surface recreation (backgrounding, app switch, screen off).
    LaunchedEffect(ec.depthMode, ec.depthBlurStrength, ec.bokehOn, glView) {
        val de = glView?.depthEngine
        // The plain BOKEH tile drives the real MiDaS depth bokeh (super-fast
        // separable CoC) unless a dedicated depth mode is already selected.
        val effMode = if (ec.depthMode != 0) ec.depthMode else if (ec.bokehOn) 3 else 0
        AppSettings.setDepthMode(context, ec.depthMode)
        AppSettings.setDepthBlur(context, ec.depthBlurStrength)
        AppSettings.setBokehOn(context, ec.bokehOn)
        de?.let { d ->
            d.enabled = effMode != 0
            d.mode = when (effMode) {
                1 -> FusionPipelineEngine.Mode.DEPTH_VIEW
                2 -> FusionPipelineEngine.Mode.STUDIO_LIGHT
                3 -> FusionPipelineEngine.Mode.BOKEH
                else -> FusionPipelineEngine.Mode.OFF
            }
            d.blurStrength = ec.depthBlurStrength
            d.setDepthModel(depthMiDas)
            d.depthRange = AppSettings.bokehRange(context)
            d.saveToPhoto = AppSettings.bakeBokeh(context)
        }
    }

    // ── Focus ─────────────────────────────────────────────────────────────────
    var focusPoint by remember{mutableStateOf<Offset?>(null)}
    val focusScale=remember{Animatable(1.5f)};val focusAlpha=remember{Animatable(0f)}

    // ── Long exposure ─────────────────────────────────────────────────────────
    var isExposing by remember{mutableStateOf(false)};val exposureProgress=remember{Animatable(0f)}

    // ── PRO ───────────────────────────────────────────────────────────────────
    val proController = remember { ProController() }
    var selectedProParam by remember{mutableStateOf<String?>(null)}
    var proEV by remember{mutableStateOf(0f)}
    var proISO by remember{mutableIntStateOf(400)}
    var proSSNs by remember{mutableLongStateOf(16_666_667L)}
    var proWB by remember{mutableIntStateOf(5600)}
    var proFocus by remember{mutableFloatStateOf(0.5f)}
    var proManualISO by remember{mutableStateOf(false)}
    var proManualSS by remember{mutableStateOf(false)}
    var proManualWB by remember{mutableStateOf(false)}
    var proManualFocus by remember{mutableStateOf(false)}
    var liveISO by remember{mutableStateOf(200)}
    var liveSSNs by remember{mutableStateOf(16_666_667L)}
    val exposureMode = remember { derivedStateOf {
        when {
            proManualISO && proManualSS -> ExposureMode.MANUAL
            proManualISO -> ExposureMode.ISO_PRIORITY
            proManualSS -> ExposureMode.SS_PRIORITY
            else -> ExposureMode.AUTO
        }
    } }
    val focusDisplay=when{!proManualFocus||proFocus<0.05f->"AUTO";1f/proFocus>=1f->"${(1f/proFocus).toInt()}m";else->"${(100f/proFocus).toInt()}cm"}
    val exposureRemainSec=if(isExposing&&proManualSS)((1f-exposureProgress.value)*proSSNs/1_000_000_000f).roundToInt().coerceAtLeast(0) else 0

    // ── Effects ───────────────────────────────────────────────────────────────
    LaunchedEffect(Unit){
        LensManager.discover(context)
        try{val saved=loadProPrefs(context);if(saved.iso!=400||saved.manualISO){proISO=saved.iso;proManualISO=saved.manualISO};if(saved.ssNs!=16_666_667L||saved.manualSS){proSSNs=saved.ssNs;proManualSS=saved.manualSS};proWB=saved.wb;proManualWB=saved.manualWB;proFocus=saved.focus;proManualFocus=saved.manualFocus;proEV=saved.ev}catch(e:Exception){}
    }
    LaunchedEffect(selectedProfile){userGrain=selectedProfile.defaultGrain;userLeak=selectedProfile.defaultLightLeak}

    // Film composite → GL renderer (live preview + effect-video parity)
    val glFilmStyle = remember(selectedProfile, userGrain, userLeak, lookStrength, leakColorMode, leakAdaptive, filmId) {
        val g = (userGrain * lookStrength).coerceIn(0f, 1f)
        val l = (userLeak * lookStrength).coerceIn(0f, 1f)
        val stock = filmId?.let { FilmCatalog.findById(it) }
        val (lr, lg, lb) = when (leakColorMode) {
            LightLeakColor.ORANGE -> Triple(1f, 0.40f, 0.05f)
            LightLeakColor.BLUE -> Triple(0.05f, 0.55f, 1f)
            LightLeakColor.RAINBOW -> Triple(1f, 0.5f, 0.15f)
            LightLeakColor.RANDOM -> Triple(1f, 0.6f, 0.25f)
        }
        val p = selectedProfile
        val ta = p.previewTint.toArgb()
        GlFilmStyle(
            grain = g,
            leakAmt = l,
            leakR = lr, leakG = lg, leakB = lb,
            adaptive = leakAdaptive,
            vignette = p.defaultVignette * lookStrength,
            tintR = ((ta shr 16) and 0xFF) / 255f,
            tintG = ((ta shr 8) and 0xFF) / 255f,
            tintB = (ta and 0xFF) / 255f,
            tintAlpha = p.previewTintAlpha,
            warmth = p.warmthPreview,
            fade = p.fade,
            mono = p.isMonochrome || stock?.isBlackAndWhite == true,
            filmSat = stock?.saturation ?: 1f,
            filmContrast = stock?.contrast ?: 1f,
            filmLift = stock?.shadowLift ?: 0f,
            filmRolloff = stock?.highlightRolloff ?: 0f
        )
    }
    LaunchedEffect(filmId) {
        val s = filmId?.let { FilmCatalog.findById(it) }
        if (s != null) { userGrain = s.grainAmount; userLeak = s.lightLeakProbability }
    }
    LaunchedEffect(glFilmStyle, glView) { glView?.glFilmStyle = glFilmStyle }
    LaunchedEffect(flash,captureUseCase){captureUseCase?.flashMode=when(flash){FlashMode.ON->ImageCapture.FLASH_MODE_ON;FlashMode.AUTO->ImageCapture.FLASH_MODE_AUTO;FlashMode.TORCH->ImageCapture.FLASH_MODE_OFF;else->ImageCapture.FLASH_MODE_OFF}}
    LaunchedEffect(flash, cameraRef) { cameraRef?.cameraControl?.enableTorch(flash == FlashMode.TORCH) }
    LaunchedEffect(isRecording){if(isRecording){recordingSec=0;while(isRecording){delay(1000);recordingSec++}}else recordingSec=0}
    LaunchedEffect(focusPoint){focusPoint?:return@LaunchedEffect;focusAlpha.snapTo(1f);focusScale.snapTo(1.5f);focusScale.animateTo(1f,spring(0.6f,500f));delay(1400);focusAlpha.animateTo(0f,tween(350))}
    LaunchedEffect(isPro){if(!isPro){saveProPrefs(context,ProPrefs(proISO,proSSNs,proWB,proFocus,proEV,proManualISO,proManualSS,proManualWB,proManualFocus));proController.resetToAuto()}}
    LaunchedEffect(exposureMode.value, proISO, proSSNs, proWB, proManualISO, proManualSS, proManualWB, proManualFocus, proFocus, proEV, isPro){
        if(!isPro)return@LaunchedEffect
        proController.exposureMode=exposureMode.value
        proController.isoValue = if (proManualISO) proISO.coerceIn(LensManager.minISO, LensManager.maxISO) else 0
        proController.ssNsValue = if (proManualSS) proSSNs.coerceIn(SS_NS.first(), SS_NS.last()) else 0L
        proController.evValue = proEV
        proController.wbMode = if (proManualWB) WbMode.Custom(proWB.coerceIn(2000, 8000), 0) else WbMode.Auto
        proController.focusDistance = if (proManualFocus && proFocus > 0.02f)
            (1f / proFocus).coerceIn(0.02f, ProController.maxFocusDiopters()) else -1f
        proController.apply()
    }

    // WB GL tint fallback: HAL ignores COLOR_CORRECTION_GAINS without MANUAL_POST_PROCESSING,
    // so tint the GL preview directly instead (skip when native gains are honored, to avoid double correction).
    LaunchedEffect(proManualWB, proWB, glView) {
        glView?.wbTempK = if (proManualWB && !LensManager.supportsManualWB) proWB else 0
    }

    val targetCameraId=when{isFront->null;activeLens==1&&LensManager.hasUltraWide()->LensManager.getUltraWideId();else->LensManager.getMainId()}
    val vfRatio=when(ratio){"1:1"->1f;"4:3"->4f/3f;"9:16"->9f/16f;"16:9"->16f/9f;"3:2"->3f/2f;else->3f/4f}

    // ── Pano capture helpers ──────────────────────────────────────────────────
    fun stitchPano() {
        val uris = panoUris.toList()
        if (uris.size < 2) { panoUris.clear(); panoTiles = 0; panoLastUri = null; return }
        panoBusy = true
        scope.launch {
            val out = withContext(Dispatchers.Default) { PanoStitcher.stitch(context, uris) }
            out?.let { lastPhotoUri = it }
            panoUris.clear(); panoTiles = 0; panoLastUri = null
            panoBusy = false; panoDone = true
        }
    }
    fun resetPano() {
        if (panoBusy) return
        panoUris.clear(); panoTiles = 0; panoLastUri = null; panoDone = false
    }
    fun cleanupPano(stitchIfAny: Boolean) {
        if (panoBusy) return
        if (stitchIfAny) stitchPano() else resetPano()
    }
    LaunchedEffect(panoDone) { if (panoDone) { delay(2800); panoDone = false } }

    // ── Momentary teaching label + gesture mode switch (Halide/One UI style) ─
    fun switchMode(id: String) {
        if (id == activeMode) return
        if (isPano) cleanupPano(stitchIfAny = true)
        activeMode = id
        hapticIfOn(HapticFeedbackType.LongPress)
        teach(
            when (id) {
                "photo" -> "PHOTO · tap shutter"
                "video" -> "VIDEO · hold shutter to record"
                "hyperlapse" -> "HYPERLAPSE · 720p time-lapse"
                "slomo" -> "SLO-MO · 60fps"
                "pano" -> "PANORAMA · pan · tap shutter per tile"
                else -> "${id.uppercase()} MODE"
            }
        )
    }

    // Haptic click-stop when crossing integer zoom stops (like a lens ring)
    LaunchedEffect(zoomRatio) {
        val s = zoomRatio.roundToInt().coerceAtLeast(1)
        if (s != lastZoomStop && zoomRatio > 1.01f) {
            lastZoomStop = s
            hapticIfOn(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(Modifier.fillMaxSize()) {
        glView?.depthEngine?.debugEnabled = AppSettings.debugPipeline(context)
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding(),
            horizontalAlignment=Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))

            Spacer(Modifier.height(if(showGridDropdown)4.dp else 8.dp))

            // ── Viewfinder ────────────────────────────────────────────────────
            // Full-bleed window, sharp edges (no corners): fills all space between
            // the top and bottom black bars. The stream is CenterCrop'd in the GL
            // renderer (never stretched); the selected aspect is matched by black
            // bars that overlay only where needed (landscape → above/below,
            // portrait/square → left/right).
            Box(Modifier.fillMaxWidth().weight(1f)
                .pointerInput(cameraRef){detectTransformGestures{_,_,zoom,_->if(activeLens==0){val cam=cameraRef?:return@detectTransformGestures;val nr=(zoomRatio*zoom).coerceIn(1f,10f);cam.cameraControl.setZoomRatio(nr);zoomRatio=nr}}}
                .pointerInput(isFront){detectTapGestures(
                    onTap={off->
                        val cam=cameraRef?:return@detectTapGestures
                        try{
                            val w=previewSize.width.toFloat().coerceAtLeast(1f)
                            val h=previewSize.height.toFloat().coerceAtLeast(1f)
                            val factory=object:MeteringPointFactory(android.util.Rational(previewSize.width,previewSize.height)){
                                override fun convertPoint(x:Float,y:Float)=android.graphics.PointF(x/w,y/h)
                            }
                            val pt=factory.createPoint(off.x,off.y)
                            cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt,FocusMeteringAction.FLAG_AF).build())
                            focusPoint=off;hapticIfOn(HapticFeedbackType.TextHandleMove)
                            glView?.depthEngine?.onTap(off.x / w, off.y / h)
                        }catch(e:Exception){}
                    },
                    onDoubleTap={isFront=!isFront;hapticIfOn(HapticFeedbackType.LongPress)}
                ).also{if(showGridDropdown||showStarMenu){showGridDropdown=false;showStarMenu=false}}
            }
            .pointerInput(activeMode, isFront, isRecording, panoBusy, showAnalogSheet, showStarMenu, showGridDropdown, isPro) {
                var totalX = 0f; var totalY = 0f
                detectDragGestures(
                    onDragStart = { totalX = 0f; totalY = 0f },
                    onDrag = { change, amount -> totalX += amount.x; totalY += amount.y; change.consume() },
                    onDragEnd = {
                        if (isRecording || panoBusy || showAnalogSheet || showStarMenu || showGridDropdown || isPro) return@detectDragGestures
                        val thresh = 90.dp.toPx()
                        if (kotlin.math.abs(totalX) > thresh && kotlin.math.abs(totalX) > kotlin.math.abs(totalY) * 1.3f) {
                            val modes = listOf("photo", "video", "hyperlapse", "slomo", "pano")
                            val cur = modes.indexOf(activeMode).coerceAtLeast(0)
                            val next = modes[(cur + if (totalX < 0) 1 else modes.size - 1) % modes.size]
                            switchMode(next)
                        } else if (kotlin.math.abs(totalY) > thresh && !isPano) {
                            isFront = !isFront
                            hapticIfOn(HapticFeedbackType.LongPress)
                            teach(if (isFront) "FRONT CAMERA" else "BACK CAMERA")
                        }
                    }
                )
            }) {
                // Full-bleed between the black bars: largest possible box at the
                // selected aspect, sharp edges, black overlays only where needed.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val fillRatio = maxWidth / maxHeight
                    val fitRatio = if (ratio == "FULL") fillRatio else vfRatio
                    val boxH = minOf(maxHeight, maxWidth / fitRatio)
                    val boxW = boxH * fitRatio
                    Box(Modifier.align(Alignment.Center).size(boxW, boxH)
                        .onSizeChanged{previewSize=it}.clipToBounds()) {
                    // Inner box holding the camera preview
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
CameraPreview(isFront=isFront,resetKey=cameraResetKey,cameraId=targetCameraId,proController=proController,
                            fps=if(activeMode=="slomo")60 else if(hiFpsSetting)60 else 30,
                            videoQuality=videoRes,
                            eisOn=eisOn,
                            hiBitrate=hiBitSetting,
                            frameType=frameType,frameConfig=frameConfig,
                            showTimestamp=showTimestamp,dateStyle=dateStyle,dateFormat=dateFormat,
                            dateShowTime=dateShowTime,datePos=datePos,dateCustom=dateCustom,
                            onAutoValues={iso,ss->if(iso>0)liveISO=iso;if(ss>0)liveSSNs=ss;proController.onCaptureResult(iso,ss)},
                            onCameraReady={cam->cameraRef=cam},
                            onImageCaptureReady={captureUseCase=it},
                            onVideoCaptureReady={videoCaptureUseCase=it},
                            onGlViewReady={glView=it},
                            onStreamAspect={streamAspect=it},
                            config=ec,
                            modifier=Modifier.fillMaxSize())
                    }
                    // ── AGSL light orb: live relight over the depth matte ──────
                    if (relightOn) {
                        RelightSurface(
                            relight = relightEngine,
                            frame = relightFrame,
                            depth = relightDepth,
                            lightUv = relightUv,
                            intensity = relightIntensity,
                            lightColor = relightColor,
                            debugMode = relightDebug,
                            onDragging = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // Toggle pill (top-right of the viewfinder)
                    Box(Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50))
                        .background(if (relightOn) AppAccent.color.copy(0.85f) else Color.Black.copy(0.55f))
                        .clickable { relightOn = !relightOn; AppSettings.setRelightOn(context, relightOn) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(if (relightOn) "● RELIGHT ON" else "● RELIGHT", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    // Film profile overlay (grain, light leak, vignette, tint) — ALWAYS
                    FilmOverlay(selectedProfile,Modifier.fillMaxSize(),userGrain*lookStrength,userLeak*lookStrength,leakColorMode)
                    if(gridOverlay!=GridOverlay.OFF)GridLines(gridOverlay,Modifier.fillMaxSize())

                    if(isPano)PanoHud(
                        tiles=panoTiles,lastTileUri=panoLastUri,busy=panoBusy,done=panoDone,
                        onDone={stitchPano()},modifier=Modifier.fillMaxSize()
                    )

                    // Lens effects
                    // FisheyeOverlay removed — GL shader handles distortion
                    // StarburstOverlay removed — GL shader handles in Phase 5

                    if(blink)Box(Modifier.fillMaxSize().background(Color.White.copy(0.85f)))
                    if(frontFlash)Box(Modifier.fillMaxSize().background(Color.White))
                    if(timerCount>0)Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)),Alignment.Center){Text(timerCount.toString(),color=Color.White,fontSize=96.sp,fontWeight=FontWeight.Bold)}
                    }
                    }
                // Frame overlay: drawn by the GL renderer (frameOverlayPass) — no Compose duplicate

                // Focus ring
                focusPoint?.let{fp->if(focusAlpha.value>0f)Canvas(Modifier.fillMaxSize()){val sz=56.dp.toPx()*focusScale.value;val a=focusAlpha.value;val gold=EagleGold.copy(alpha=a);drawRect(gold,topLeft=Offset(fp.x-sz/2,fp.y-sz/2),size=Size(sz,sz),style=Stroke(1.8.dp.toPx()));val c=8.dp.toPx();listOf(Offset(fp.x-sz/2,fp.y-sz/2),Offset(fp.x+sz/2,fp.y-sz/2),Offset(fp.x-sz/2,fp.y+sz/2),Offset(fp.x+sz/2,fp.y+sz/2)).forEach{corner->val dx=if(corner.x<fp.x)c else -c;val dy=if(corner.y<fp.y)c else -c;drawLine(gold,corner,Offset(corner.x-dx,corner.y),2.dp.toPx());drawLine(gold,corner,Offset(corner.x,corner.y-dy),2.dp.toPx())}}}

                // Recording timer
                if(isRecording)Row(Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color.Black.copy(0.6f),RoundedCornerShape(50)).padding(horizontal=12.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){Box(Modifier.size(7.dp).background(RecordRed,CircleShape));val m=recordingSec/60;val s=recordingSec%60;Text("${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Medium,letterSpacing=0.5.sp)}

                // Burst indicator
                if(isBurstActive)Box(Modifier.align(Alignment.TopCenter).padding(top=10.dp).background(FeatureYellow.copy(0.9f),RoundedCornerShape(6.dp)).padding(horizontal=10.dp,vertical=4.dp)){Text("BURST  ${burstUris.size}",color=Color.Black,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=0.5.sp)}

                // Active feature dots
                if(anyStarActive||anyGridActive)Row(Modifier.align(Alignment.TopStart).padding(10.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){if(anyStarActive)Box(Modifier.size(7.dp).background(FeatureYellow,CircleShape));if(anyGridActive)Box(Modifier.size(7.dp).background(EagleGreen,CircleShape))}
                if(hdrOn)Box(Modifier.align(Alignment.TopStart).padding(start=10.dp,top=24.dp).background(FeatureYellow.copy(0.9f),RoundedCornerShape(4.dp)).padding(horizontal=5.dp,vertical=2.dp)){Text("HDR",color=Color.Black,fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=0.5.sp)}
                if(ec.depthMode!=0 || ec.bokehOn){
                    var depthDiag by remember{mutableStateOf("DEPTH ...")}
                    LaunchedEffect(ec.depthMode, ec.bokehOn){
                        while(true){
                            val d=glView?.depthEngine?.diag()
                            depthDiag=if(d!=null){
                                val ready = if(d.modelsReady){
                                    if (d.deferCount > 0) "defer:${d.lastDeferReason}" else "ok"
                                } else if (d.modelFailReason.isNotEmpty()) d.modelFailReason else "loading"
                                val glErr = if (d.glErrCount > 0) " GLERR:${d.glErrCount} ${d.lastErrLabel}" else ""
                                val prg = if (d.programErr.isNotEmpty()) " PRG:${d.programErr}" else ""
                                val fb = if (d.fallbackCount > 0) " FALLBACK:${d.fallbackCount}" else ""
                                val sg = if (d.segOk) " SEG:${d.segW}x${d.segH}" else if (d.segOverrides > 0) " SEG done" else ""
                                val sgo = if (d.segOverrides > 0) "OVR:${d.segOverrides}" else ""
                                val infr = if (d.lastInferError.isNotEmpty()) " INFERR:${d.lastInferError}" else ""
                                try {
                                    "DEPTH ${d.modeName} %.1ffps rng %.2f-%.2f tap %.2f %dx%d %s %s%s%s%s%s%s%s".format(
                                        d.fps, d.min, d.max, d.tapDepth, d.modelW, d.modelH,
                                        if(d.depthBusy)"BUSY" else "idle", ready, glErr, prg, fb, sg, sgo, infr)
                                } catch (e: Exception) {
                                    "DEPTH ${d.modeName} ${d.fps}fps rng ${d.min}-${d.max} tap ${d.tapDepth} ${d.modelW}x${d.modelH} $ready$glErr$prg$fb$sg$sgo$infr"
                                }
                            } else "DEPTH no engine"
                            delay(400)
                        }
                    }
                    Row(Modifier.align(Alignment.TopStart).padding(start=10.dp,top=34.dp).background(Color.Black.copy(0.6f),RoundedCornerShape(6.dp)).padding(horizontal=8.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(depthDiag,color=Color.White.copy(0.92f),fontSize=9.sp,fontFamily=FontFamily.Monospace,letterSpacing=0.2.sp)
                        Text(if(depthMiDas)"MIDAS" else "ZIP",color=if(depthMiDas)FeatureYellow else EagleGreen,fontSize=9.sp,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{ depthMiDas=!depthMiDas; AppSettings.setUseMiDasDepth(context,depthMiDas); glView?.depthEngine?.setDepthModel(depthMiDas) })
                        Text("OFF",color=Color.White.copy(0.55f),fontSize=9.sp,fontFamily=FontFamily.Monospace,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{ ec=ec.copy(depthMode=0) })
                    }
                }
                if (AppSettings.debugPipeline(context)) {
                    PipelineDebugPanel(glView?.depthEngine, Modifier.align(Alignment.BottomCenter))
                }
            }

            // ── Bottom bar: Gallery | PRO | Shutter | Menu (4 buttons) ───────
            BottomBar(
                isVideo=isVideo,isPro=isPro,lastPhotoUri=lastPhotoUri,isRecording=isRecording,
                isExposing=isExposing,exposureProgress=exposureProgress.value,exposureRemainSec=exposureRemainSec,
                isBurstActive=isBurstActive,fastBurstOn=fastBurstOn,
                onGallery={if(!isRecording && !panoBusy)onGallery()},
                onPro={if(!isRecording && !panoBusy){isPro=!isPro;teach(if(isPro)"PRO MODE · manual ISO · shutter · WB" else "PRO MODE off")}},
                onShutter={
                    hapticIfOn(HapticFeedbackType.LongPress)
                    if(isPano){
                        if(panoBusy||isBurstActive)return@BottomBar
                        val ic=captureUseCase
                        if(ic==null){panoBusy=false;return@BottomBar}
                        panoBusy=true
                        scope.launch{
                            val latch=CompletableDeferred<Uri?>()
                            capturePhoto(ic,context){latch.complete(it)}
                            val uri=latch.await()
                            if(uri!=null){
                                withContext(Dispatchers.IO){
                                    try{
                                        var bm=context.contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}
                                        if(bm!=null){
                                            try{
                                                val ei=androidx.exifinterface.media.ExifInterface(context.contentResolver.openInputStream(uri)!!)
                                                when(ei.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1)){
                                                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> {val m=android.graphics.Matrix();m.postRotate(90f);val r=android.graphics.Bitmap.createBitmap(bm,0,0,bm.width,bm.height,m,false);bm.recycle();bm=r}
                                                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> {val m=android.graphics.Matrix();m.postRotate(180f);val r=android.graphics.Bitmap.createBitmap(bm,0,0,bm.width,bm.height,m,false);bm.recycle();bm=r}
                                                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> {val m=android.graphics.Matrix();m.postRotate(270f);val r=android.graphics.Bitmap.createBitmap(bm,0,0,bm.width,bm.height,m,false);bm.recycle();bm=r}
                                                }
                                            }catch(_:Exception){}
                                            val glBm=if(glView!=null){val l2=CompletableDeferred<Bitmap>();glView!!.processBitmap(bm){l2.complete(it)};l2.await()}else bm
                                            context.contentResolver.openOutputStream(uri,"w")?.use{os->glBm.compress(android.graphics.Bitmap.CompressFormat.JPEG,92,os)}
                                            if(glBm!=bm)glBm.recycle();bm.recycle()
                                        }
                                    }catch(e:Exception){e.printStackTrace()}
                                }
                                panoUris.add(uri);panoTiles=panoTiles+1;panoLastUri=uri
                                lastPhotoUri=uri
                                panoBusy=false
                                if(panoTiles>=PanoStitcher.MAX_TILES)stitchPano()
                            }
                            panoBusy=false
                        }
                        return@BottomBar
                    }
                    // ── Hyperlapse path: intervalometer + H.264 encode ───────
                    if(activeMode=="hyperlapse"){
                        if(isRecording){
                            hyperJob?.cancel();hyperJob=null
                            val rec=hyperRecorder
                            hyperRecorder=null
                            isRecording=false
                            scope.launch(Dispatchers.IO){
                                rec?.finish{uri->
                                    mainHandler.post{
                                        if(uri!=null)lastPhotoUri=uri
                                        else Toast.makeText(context,"Hyperlapse hiccup — try again",Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        } else {
                            val ic=captureUseCase
                            if(ic==null)return@BottomBar
                            val rec=HyperlapseRecorder(context,1280,720)
                            if(!rec.start()){Toast.makeText(context,"Encoder busy — retry",Toast.LENGTH_SHORT).show();return@BottomBar}
                            hyperRecorder=rec
                            isRecording=true
                            hyperJob=scope.launch{
                                while(isActive){
                                    val latch=CompletableDeferred<Bitmap?>()
                                    capturePhotoMemory(ic,context){latch.complete(it)}
                                    val bm=latch.await()
                                    if(bm!=null){
                                        withContext(Dispatchers.IO){
                                            rec.feedFrame(bm)
                                            bm.recycle()
                                        }
                                    }
                                    delay(500)
                                }
                            }
                        }
                        return@BottomBar
                    }
                    if(isVideo){
                        if(isRecording){
                            isRecording=false
                            val rec=glRecorder
                            glRecorder=null
                            glView?.stopVideoRecording(rec){ /* GL-side ack; URI via rec.onFinished */ }
                        }
                        else{
                            val gl=glView
                            if(gl==null)return@BottomBar
                            val res=when(videoRes){"4K"->3840 to 2160;"720"->1280 to 720;else->1920 to 1080}
                            val fri=if(activeMode=="slomo")60 else if(hiFpsSetting)60 else 30
                            val br=if(hiBitSetting)40_000_000 else if(videoRes=="4K")32_000_000 else 20_000_000
                            val rec=GLVideoRecorder(context,res.first,res.second,br,fri)
                            glRecorder=rec
                            rec.onFinished={uri->
                                glRecorder=null
                                isRecording=false
                                if(uri!=null)lastPhotoUri=uri
                                else Toast.makeText(context,"Video encode hiccup",Toast.LENGTH_SHORT).show()
                            }
                            isRecording=true
                            gl.startVideoRecording(rec){ok->
                                if(!ok){
                                    isRecording=false
                                    glRecorder=null
                                    Toast.makeText(context,"Recorder failed to start",Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        if(timerCount>0||isExposing)return@BottomBar

                        // ── RAW path: dedicated Camera2 DNG capture ───────────
                        if(rawOn){
                            if(rawBusy)return@BottomBar
                            rawBusy=true
                            scope.launch{
                                try{
                                    blink=true;delay(30);blink=false
                                    val camId=RawCapture.findRawCameraId(context)
                                    if(camId==null){
                                        Toast.makeText(context,"RAW not supported",Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    // Free CameraX so Camera2 can open the same device
                                    val provider=withContext(Dispatchers.IO){
                                        runCatching{ProcessCameraProvider.getInstance(context).get(3,TimeUnit.SECONDS)}.getOrNull()
                                    }
                                    provider?.unbindAll()
                                    Toast.makeText(context,"Capturing DNG…",Toast.LENGTH_SHORT).show()
                                    val uri=RawCapture.capture(context,camId){
                                        cameraResetKey++
                                    }
                                    if(uri!=null)lastPhotoUri=uri
                                    else Toast.makeText(context,"RAW slipped — try again",Toast.LENGTH_SHORT).show()
                                } finally { rawBusy=false }
                            }
                            return@BottomBar
                        }

                        // ── HDR path: 3-shot in-memory bracket + blend ────────
                        if(hdrOn){
                            if(hdrBusy)return@BottomBar
                            hdrBusy=true
                            scope.launch{
                                try{
                                    blink=true;delay(30);blink=false
                                    val bISO=liveISO.coerceIn(LensManager.minISO,800.coerceAtMost(LensManager.maxISO))
                                    val bSS=liveSSNs.coerceIn(250_000L,33_333_333L)
                                    val frames=mutableListOf<android.graphics.Bitmap>()
                                    val ic=captureUseCase
                                    if(ic!=null){
                                        for(evStop in listOf(-2f,0f,2f)){
                                            val adjSS=(bSS*Math.pow(2.0,evStop.toDouble())).toLong().coerceIn(250_000L,33_333_333L)
                                            cameraRef?.let{cam->try{Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,CameraMetadata.CONTROL_AE_MODE_OFF).setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY,bISO).setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME,adjSS).build());delay(120)}catch(e:Exception){}}
                                            val latch=CompletableDeferred<Bitmap?>()
                                            capturePhotoMemory(ic,context){latch.complete(it)}
                                            latch.await()?.let{frames.add(it)}
                                        }
                                    }
                                    if(frames.size>=2){
                                        val hdrBm=withContext(Dispatchers.Default){HdrEngine.blendFast(frames)}
                                        val cv=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"EaglesEye_HDR_${System.currentTimeMillis()}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/EaglesEye")}
                                        val hdrUri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv)
                                        hdrUri?.let{u->withContext(Dispatchers.IO){context.contentResolver.openOutputStream(u)?.use{os->hdrBm.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,os)}};developing=true;delay(420);developing=false;lastPhotoUri=u}
                                        frames.forEach{it.recycle()};hdrBm.recycle()
                                    } else frames.forEach{it.recycle()}
                                } finally {
                                    proController.restoreAfterCapture()
                                    hdrBusy=false
                                }
                            }
                        }

                        // ── Night mode path: 8-frame median stack ──────────────
                        if(nightOn){
                            if(nightBusy)return@BottomBar
                            nightBusy=true
                            scope.launch{
                                try{
                                    blink=true;delay(30);blink=false
                                    val bISO=(liveISO.coerceAtLeast(400)).coerceAtMost(LensManager.maxISO)
                                    val bSS=liveSSNs.coerceIn(250_000L,66_666_666L)
                                    cameraRef?.let{cam->try{Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,CameraMetadata.CONTROL_AE_MODE_OFF).setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY,bISO).setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME,bSS).build());delay(150)}catch(e:Exception){}}
                                    val frames=mutableListOf<android.graphics.Bitmap>()
                                    var outW=0;var outH=0
                                    val ic=captureUseCase
                                    if(ic!=null){
                                        for(i in 0 until 8){
                                            val latch=CompletableDeferred<Bitmap?>()
                                            capturePhotoMemory(ic,context){latch.complete(it)}
                                            val bm=latch.await()
                                            if(bm!=null){
                                                if(outW==0){outW=bm.width;outH=bm.height}
                                                // Downscale now: median runs on the small grid, output upscales later
                                                frames.add(withContext(Dispatchers.Default){
                                                    val s=Bitmap.createScaledBitmap(bm,(bm.width/4).coerceAtLeast(16),(bm.height/4).coerceAtLeast(16),true)
                                                    bm.recycle();s
                                                })
                                            }
                                            delay(80)
                                        }
                                    }
                                    if(frames.size>=3){
                                        val nightBm=withContext(Dispatchers.Default){NightEngine.stack(frames,outW,outH)}
                                        val cv=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,"EaglesEye_Night_${System.currentTimeMillis()}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/EaglesEye")}
                                        val u=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv)
                                        u?.let{uri->withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{os->nightBm.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,os)}};developing=true;delay(420);developing=false;lastPhotoUri=uri}
                                        frames.forEach{it.recycle()};nightBm.recycle()
                                    } else frames.forEach{it.recycle()}
                                } finally {
                                    proController.restoreAfterCapture()
                                    nightBusy=false
                                }
                            }
                        }

                        // ── Normal capture path ───────────────────────────────
                        scope.launch{
                            val selfSecs=when(timerMode){SelfTimer.S3->3;SelfTimer.S5->5;SelfTimer.S10->10;else->0}
                            for(i in selfSecs downTo 1){timerCount=i;delay(1000)};timerCount=0
                            blink=true;delay(30);blink=false
                            val screenFlash=isFront&&flash!=FlashMode.OFF
                            if(screenFlash){
                                frontFlash=true
                                scope.launch{delay(350);frontFlash=false}
                            }
                            val expNs=if(proManualSS) proSSNs else liveSSNs
                            if(proManualSS&&expNs>=1_000_000_000L){isExposing=true;exposureProgress.snapTo(0f);exposureProgress.animateTo(1f,tween(durationMillis=(expNs/1_000_000L).toInt(),easing=LinearEasing));isExposing=false}
                            proController.prepareForCapture()
                            val cg=userGrain*lookStrength;val cl=userLeak*lookStrength;val clm=leakColorMode;val ct=showTimestamp;val cp=selectedProfile
                            val cf=frameType;val fcfg=frameConfig;val cfm=frameMode
                            val cds=dateStyle;val cdf=dateFormat;val cdt=dateShowTime
                            captureUseCase?.let{ic->capturePhoto(ic,context){uri->if(uri!=null){scope.launch{processing=true;processProgress=0f;withContext(Dispatchers.IO){try{val bm=decodeUpright(context,uri);if(bm!=null){
                                val glBm = if (glView != null) {
                                    val latch = CompletableDeferred<Bitmap>()
                                    glView!!.processBitmap(bm) { glBmp -> latch.complete(glBmp) }
                                    latch.await()
                                } else bm
                                var proc=FilmEngine.applyToCapture(glBm,cp,cg,cl,cf,ct,clm,fcfg,cfm,cds,cdf,cdt,datePos,dateCustom)
                                context.contentResolver.openOutputStream(uri,"w")?.use{os->proc.compress(android.graphics.Bitmap.CompressFormat.JPEG,92,os)};if(glBm!=bm)glBm.recycle();if(proc!=glBm)proc.recycle();bm.recycle()}}catch(e:Exception){e.printStackTrace()}};processing=false;lastPhotoUri=uri;proController.restoreAfterCapture()}}}}
                        }
                    }
                },
                onBurstStart={
                    if(fastBurstOn&&!isBurstActive&&activeMode=="photo"){
                        val currentSS=if(proManualSS) proSSNs else liveSSNs
                        if(isFastBurstEligible(currentSS)||!proManualSS){
                            isBurstActive=true;burstUris.clear()
                            burstJob=scope.launch{
                                val ic=captureUseCase?:return@launch
                                while(isActive){
                                    val latch=CompletableDeferred<Uri?>()
                                    capturePhoto(ic,context){latch.complete(it)}
                                    latch.await()?.let{burstUris.add(it)}
                                    delay(130) // ~7fps burst
                                }
                            }
                        }
                    }
                },
                onBurstEnd={
                    if(isBurstActive){burstJob?.cancel();burstJob=null;isBurstActive=false;lastPhotoUri=burstUris.lastOrNull();hapticIfOn(HapticFeedbackType.LongPress)}
                },
                onMenu={if(!panoBusy)showAnalogSheet=true}
            )
            Spacer(Modifier.height(Spacing.xl))
        }


    // ── Analog sheet ──────────────────────────────────────────────────────
    AnalogEngineSheet(visible=showAnalogSheet,profiles=FILM_PROFILES,selectedProfile=selectedProfile,
            onSelect={selectedProfile=it},onDismiss={showAnalogSheet=false},
            grainValue=userGrain,onGrainChange={userGrain=it},leakValue=userLeak,onLeakChange={userLeak=it},
            lookStrength=lookStrength,onLookStrength={lookStrength=it},
            leakColorMode=leakColorMode,onLeakColorChange={leakColorMode=it},
            frameType=frameType,onFrameChange={frameType=it},
            frameMode=frameMode,onFrameModeChange={frameMode=it},
            frameConfig=frameConfig,onFrameConfigChange={frameConfig=it},
            savedPresets=savedPresets,
            onSavePreset={idx->savedPresets=savedPresets.toMutableList().also{it[idx]=FramePreset(frameType,frameConfig,frameMode)}.toList()},
            onLoadPreset={p->frameType=p.frameType;frameConfig=p.frameConfig;frameMode=p.frameMode},
            onClearPreset={idx->savedPresets=savedPresets.toMutableList().also{it[idx]=null}.toList()},
            showTimestamp=showTimestamp,onTimestampToggle={showTimestamp=!showTimestamp},
            dateStyle=dateStyle,onDateStyleChange={dateStyle=it;AppSettings.setDateStampStyle(context,it)},
            dateFormat=dateFormat,onDateFormatChange={dateFormat=it;AppSettings.setDateStampFormat(context,it)},
            dateShowTime=dateShowTime,onDateShowTimeChange={dateShowTime=it;AppSettings.setDateStampShowTime(context,it)},
            datePos=datePos,onDatePosChange={datePos=it;AppSettings.setDateStampPos(context,it)},
            dateCustom=dateCustom,onDateCustomChange={dateCustom=it},
            config=ec,onConfigChange={ec=it},
            leakAdaptive=leakAdaptive,onLeakAdaptiveChange={leakAdaptive=it},
                effectsThumb={ name, cb ->
                    val gl = glView
                    if (gl == null || name == null) cb(null)
                    else gl.renderTilePreview(name, 192, 144) { cb(it) }
                })

// ── Zoom pill (hidden during pro panel to avoid overlap) ──
        if(!showAnalogSheet && !isPro) ZoomPill(
            zoom = zoomRatio,
            onZoom = { z ->
                if (z <= 0.6f && LensManager.hasUltraWide()) { activeLens = 1; zoomRatio = 0.5f }
                else { activeLens = 0; zoomRatio = z; cameraRef?.cameraControl?.setZoomRatio(z) }
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (isVideo) 205.dp else 155.dp)
        )

        // ── Video resolution pills (above bottom bar in video mode) ──
        if(isVideo && !isPro && !showAnalogSheet){
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 152.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                listOf("4K","1080","720").forEach { r ->
                    val sel = videoRes == r
                    Box(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (sel) EagleGold else Color.White.copy(0.08f))
                        .clickable(remember { MutableInteractionSource() }, null) { videoRes = r; hapticIfOn(HapticFeedbackType.LongPress) }
                        .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(r, color = if (sel) Color.Black else Color.White.copy(0.75f),
                            fontSize = 9.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.5.sp)
                    }
                }
                // 30 / 60 fps capture-rate toggle (hidden for SLO-MO, which is fixed at 60)
                if (activeMode != "slomo") {
                    listOf(false to "30", true to "60").forEach { (isHi, lbl) ->
                        val sel = hiFpsSetting == isHi
                        Box(Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (sel) EagleGold else Color.White.copy(0.08f))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                hiFpsSetting = isHi
                                AppSettings.setHiFps(context, isHi)
                                hapticIfOn(HapticFeedbackType.LongPress)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Text(lbl, color = if (sel) Color.Black else Color.White.copy(0.75f),
                                fontSize = 9.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.5.sp)
                        }
                    }
                }
            }
        }

        // ── Mode pill (top-left, photo/video toggle + 5-mode carousel) ─────────
        ModePill(
            mode = activeMode,
            onToggleVideo = { switchMode(if (isVideo) "photo" else "video") },
            onPickMode = { id ->
                if (id == activeMode) return@ModePill
                switchMode(id)
            },
            disabled = isRecording || panoBusy,
            modifier = Modifier.align(Alignment.TopStart).padding(top = Spacing.lg, start = Spacing.lg)
        )

        // ── Quick controls (One UI 8 pattern — bottom fly-out, keeps the
        //    viewfinder clean; hides under the PRO sheet) ─────────────────────
        val topAspect = when (ratio) {
            "3:4" -> "4:3"; "FULL" -> "FULL"; "1:1" -> "1:1"
            "16:9" -> "16:9"; "9:16" -> "9:16"; "3:2" -> "3:2"
            else -> "FULL"
        }
        val topGrid = when (gridOverlay) {
            GridOverlay.THIRDS -> "3x3"; GridOverlay.FIFTY -> "5x5"; GridOverlay.GOLDEN -> "golden"
            GridOverlay.SQUARE -> "4x4"; GridOverlay.CENTER -> "center"; GridOverlay.DIAGONAL -> "diagonal"
            else -> "off"
        }
        val topTimer = when (timerMode) {
            SelfTimer.S3 -> "3s"; SelfTimer.S5 -> "5s"; SelfTimer.S10 -> "10s"
            else -> "off"
        }
        val topFlash = when (flash) {
            FlashMode.ON -> "on"; FlashMode.AUTO -> "auto"; FlashMode.TORCH -> "fill"; else -> "off"
        }
        if (!isPro && !showAnalogSheet) {
            TopControls(
                aspect = topAspect, grid = topGrid, timer = topTimer, flash = topFlash,
                flipped = isFront,
                onAspect = { v -> ratio = when (v) {
                    "FULL" -> "FULL"; "1:1" -> "1:1"; "16:9" -> "16:9"
                    "9:16" -> "9:16"; "3:2" -> "3:2"; else -> "3:4"
                }; AppSettings.setDefaultAspect(context, ratio) },
                onGrid = { v -> gridOverlay = when (v) {
                    "3x3" -> GridOverlay.THIRDS; "5x5" -> GridOverlay.FIFTY; "golden" -> GridOverlay.GOLDEN
                    "4x4" -> GridOverlay.SQUARE; "center" -> GridOverlay.CENTER; "diagonal" -> GridOverlay.DIAGONAL
                    else -> GridOverlay.OFF
                }; AppSettings.setDefaultGrid(context, v) },
                onTimer = { v -> timerMode = when (v) {
                    "3s" -> SelfTimer.S3; "5s" -> SelfTimer.S5; "10s" -> SelfTimer.S10
                    else -> SelfTimer.OFF
                }},
                onFlash = { v -> flash = when (v) {
                    "on" -> FlashMode.ON; "auto" -> FlashMode.AUTO; "fill" -> FlashMode.TORCH; else -> FlashMode.OFF
                }},
                onFlip = { if (!isRecording && !isPano) { isFront = !isFront; hapticIfOn(HapticFeedbackType.LongPress) } },
                starActive = anyStarActive,
                onStar = { showStarMenu = !showStarMenu; hapticIfOn(HapticFeedbackType.LongPress) },
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 20.dp)
            )
        }

        // ── Momentary teaching label (top-center) ─────────────────────────────
        teachLabel?.let { t ->
            Box(Modifier.align(Alignment.TopCenter).padding(top = 20.dp)) {
                Row(
                    Modifier.uiGlass(UiRadiusPill).padding(horizontal = 15.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(AppAccent.color))
                    Text(t, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
                }
            }
        }

        // ── Star menu ─────────────────────────────────────────────────────────
        if(showStarMenu){
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(remember{MutableInteractionSource()},null){showStarMenu=false})
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f), animationSpec = tween(200)),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.95f, transformOrigin = TransformOrigin(1f, 0f), animationSpec = tween(150))
            ) {
            StarMenuPanel(modifier=Modifier.align(Alignment.TopEnd).padding(top=76.dp,end=12.dp),
                fastBurstOn=fastBurstOn,onFastBurst={
                    fastBurstOn=!fastBurstOn;hapticIfOn(HapticFeedbackType.LongPress)
                    teach(if (fastBurstOn) "FAST BURST · hold shutter · ~7fps" else "FAST BURST off")},
                hdrOn=hdrOn,onHDR={
                    hdrOn=!hdrOn;hapticIfOn(HapticFeedbackType.LongPress)
                    teach(if (hdrOn) "HDR · 3 exposures blended" else "HDR off")},
                nightOn=nightOn,onNight={
                    nightOn=!nightOn;hapticIfOn(HapticFeedbackType.LongPress)
                    teach(if (nightOn) "NIGHT · 8-frame median stack" else "NIGHT off")},
                rawOn=rawOn,rawSupported=rawSupported,onRaw={
                    if(rawSupported){rawOn=!rawOn;hapticIfOn(HapticFeedbackType.LongPress)
                        teach(if (rawOn) "RAW · uncompressed DNG" else "RAW off")}},
                focusPeakOn=focusPeakOn,onFocusPeak={
                    val newPeak=!focusPeakOn
                    focusPeakOn=newPeak
                    glView?.currentPreset=glView?.currentPreset?.copy(focusPeakIntensity=if(newPeak) 0.85f else 0f)
                    hapticIfOn(HapticFeedbackType.LongPress)
                    teach(if (newPeak) "FOCUS PEAK · sharp edges glow" else "FOCUS PEAK off")},
                histogramOn=histogramOn,onHistogram={
                    val newHist=!histogramOn
                    histogramOn=newHist
                    glView?.histogramEnabled=newHist
                    if(!newHist)histBins=null
                    hapticIfOn(HapticFeedbackType.LongPress)
                    teach(if (newHist) "HISTOGRAM · luminance graph · drag to move" else "HISTOGRAM off")},
                eisOn=eisOn,onEis={
                    eisOn=!eisOn;hapticIfOn(HapticFeedbackType.LongPress)
                    teach(if (eisOn) "STABILIZATION · gyro smoothing" else "STABILIZATION off")},
                onDismiss={showStarMenu=false})
            }
        }

        // ── Live histogram (star menu toggle) — draggable HUD overlay ───────
        if(histogramOn){
            histBins?.let{bins->
                val density = LocalDensity.current
                val cfg = LocalConfiguration.current
                val sw = with(density){ cfg.screenWidthDp.dp.toPx() }
                val sh = with(density){ cfg.screenHeightDp.dp.toPx() }
                Box(Modifier.align(Alignment.BottomStart).padding(start=20.dp,bottom=190.dp)
                    .offset { IntOffset(histOffset.x.roundToInt(), histOffset.y.roundToInt()) }
                    .onSizeChanged { histBoxSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            val nx = (histOffset.x + amount.x).coerceIn(-20.dp.toPx(), sw - 20.dp.toPx() - histBoxSize.width.toFloat())
                            val ny = (histOffset.y + amount.y).coerceIn(-(sh - 190.dp.toPx() - histBoxSize.height.toFloat()), 190.dp.toPx())
                            histOffset = Offset(nx, ny)
                        }
                    }
                ){
                    HistogramOverlay(bins=bins)
                }
            }
        }

        // ── Portrait AI processing — determinate progress for the all-engines
        // ── capture pass (BiRefNet+SINet mask union, dual-depth fusion) ────────
        if (processing) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.uiGlass(UiRadiusPill).padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("PROCESSING PORTRAIT", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(0.18f))) {
                        Box(
                            Modifier.fillMaxWidth(processProgress.coerceIn(0f, 1f))
                                .height(5.dp).clip(CircleShape)
                                .background(AppAccent.color)
                        )
                    }
                    Text("AI depth + mask — ${(processProgress * 100).toInt()}%", color = Color.White.copy(0.55f), fontSize = 10.sp)
                }
            }
        }

        // ── Develop ritual — film develops before revealing the shot ─────────
        if (developing) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.45f)), contentAlignment = Alignment.Center) {
                val devPulse = rememberInfiniteTransition(label = "dev")
                val devAlpha by devPulse.animateFloat(0.4f, 1f, infiniteRepeatable(tween(480), RepeatMode.Reverse), label = "da")
                Row(
                    Modifier.uiGlass(UiRadiusPill).padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(18.dp).clip(CircleShape)
                            .border(2.dp, AppAccent.hi, CircleShape)
                            .padding(3.dp).alpha(devAlpha).background(AppAccent.color, CircleShape)
                    )
                    Text("DEVELOPING", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("film in progress", color = Color.White.copy(0.5f), fontSize = 10.sp)
                }
            }
        }

        // ── Share-after-capture chip ────────────────────────────────────────
        if(!isVideo && lastPhotoUri!=null && !isRecording){
            Box(Modifier.align(Alignment.BottomStart).padding(start=20.dp,bottom=128.dp)){
                ShareChip(uri=lastPhotoUri!!)
            }
        }

        // ── PRO panel — floating sheet above the bottom bar (overlay) ───────
        AnimatedVisibility(
            visible = isPro,
            enter = fadeIn(tween(180)) + slideInVertically(tween(260)) { it },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ProPanel(
                open = true,
                isoManual = proManualISO, ssManual = proManualSS,
                wbManual = proManualWB, focusManual = proManualFocus,
                isoDefault = if (proManualISO) proISO else liveISO,
                ssNsDefault = if (proManualSS) proSSNs else liveSSNs,
                wbDefault = if (proManualWB) proWB else 5600,
                focusDefault = if (proManualFocus) proFocus else 0.5f,
                evDefault = proEV,
                onValueChange = { param, v ->
                    val stopKey = when (param) {
                        "ISO" -> (Math.log((v / 100f).coerceAtLeast(1f).toDouble()) / Math.log(2.0)).roundToInt().toString()
                        "SS" -> SS_NS.indices.minByOrNull { Math.abs(SS_NS[it] - v.toLong()) }?.toString() ?: ""
                        "WB" -> (v / 100).roundToInt().toString()
                        "EV" -> (v * 3).roundToInt().toString()
                        "FOCUS" -> (1f / v.coerceAtLeast(0.02f)).roundToInt().toString()
                        else -> ""
                    }
                    if (lastProStop[param] != stopKey) {
                        lastProStop[param] = stopKey
                        hapticIfOn(HapticFeedbackType.TextHandleMove)
                    }
                    when (param) {
                        "EV" -> proEV = v
                        "ISO" -> { proISO = v.toInt().coerceIn(LensManager.minISO, LensManager.maxISO); proManualISO = true }
                        "SS" -> { proSSNs = v.toLong().coerceIn(SS_NS.first(), SS_NS.last()); proManualSS = true }
                        "WB" -> { proWB = v.toInt().coerceIn(2000, 8000); proManualWB = true }
                        "FOCUS" -> { proFocus = v.coerceIn(0f, 2f); proManualFocus = v > 0.02f }
                    }
                },
                onAutoToggle = { param, wasAuto ->
                    when (param) {
                        "ISO" -> proManualISO = wasAuto
                        "SS" -> proManualSS = wasAuto
                        "WB" -> proManualWB = wasAuto
                        "FOCUS" -> proManualFocus = wasAuto
                    }
                },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 118.dp)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
fun CameraPreview(
    cameraId:String?=null,isFront:Boolean=false,resetKey:Int=0,
    fps:Int=30,
    videoQuality:String="1080",
    eisOn:Boolean=false,
    hiBitrate:Boolean=false,
    frameType:FrameType=FrameType.NONE,frameConfig:FrameConfig=FrameConfig(),
    showTimestamp:Boolean=false,
    dateStyle:DateStampStyle=DateStampStyle.ORANGE_FILM,
    dateFormat:DateFormatType=DateFormatType.DD_MM_YY,
    dateShowTime:Boolean=true,datePos:Int=0,dateCustom:String="",
    onAutoValues:(Int,Long)->Unit={_,_->},onCameraReady:(Camera)->Unit={},
    onImageCaptureReady:(ImageCapture)->Unit,onVideoCaptureReady:(VideoCapture<Recorder>)->Unit,
    proController: ProController? = null,
    config: EffectConfig = EffectConfig(),
    onGlViewReady:((CameraGLView)->Unit)?=null,
    onStreamAspect: (Float) -> Unit = {},
    modifier:Modifier=Modifier
) {
    val lco=LocalLifecycleOwner.current;val ctx=LocalContext.current

    // Battery: when this preview leaves composition (e.g. opening the gallery or
    // editor), release the camera stream. CameraX binds to the ACTIVITY lifecycle,
    // so without this the sensor keeps running behind every other screen.
    DisposableEffect(Unit) {
        onDispose {
            try {
                val provider = ProcessCameraProvider.getInstance(ctx).get()
                provider.unbindAll()
                android.util.Log.i("EaglesEye", "camera released")
            } catch (e: Exception) {
                android.util.Log.e("EaglesEye", "camera release failed", e)
            }
        }
    }

    val vc=remember(fps, videoQuality, eisOn, hiBitrate){
        val q=when(videoQuality){"4K"->Quality.UHD;"720"->Quality.HD;else->Quality.FHD}
        val rb=Recorder.Builder().setQualitySelector(QualitySelector.fromOrderedList(listOf(q),FallbackStrategy.lowerQualityOrHigherThan(q)))
        if(hiBitrate)rb.setTargetVideoEncodingBitRate(40_000_000)
        val rec=rb.build()
        val vb=VideoCapture.Builder(rec)
        if(fps>30)Camera2Interop.Extender(vb).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,Range(fps,fps))
        if(eisOn)Camera2Interop.Extender(vb).setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        vb.build()
    }
    var glView by remember { mutableStateOf<CameraGLView?>(null) }

    val preset = remember(config) { buildCustomPreset(config) }
    LaunchedEffect(preset, glView) { glView?.currentPreset = preset }
    LaunchedEffect(frameType, glView) { glView?.frameType = frameType }
    LaunchedEffect(frameConfig, glView) { glView?.frameConfig = frameConfig }
    LaunchedEffect(frameType, glView) { glView?.showFrame = frameType != FrameType.NONE }
    LaunchedEffect(showTimestamp, glView) { glView?.showTimestamp = showTimestamp }
    LaunchedEffect(dateStyle, glView) { glView?.dateStyle = dateStyle }
    LaunchedEffect(dateFormat, glView) { glView?.dateFmt = dateFormat }
    LaunchedEffect(dateShowTime, glView) { glView?.dateShowTime = dateShowTime }
    LaunchedEffect(datePos, glView) { glView?.datePos = datePos }
    LaunchedEffect(dateCustom, glView) { glView?.dateCustom = dateCustom.takeIf { it.isNotBlank() } }

    LaunchedEffect(isFront, cameraId, resetKey, fps, videoQuality, eisOn, glView) {
        val view=glView?:return@LaunchedEffect
        suspendCancellableCoroutine<Unit>{cont->
            ProcessCameraProvider.getInstance(ctx).addListener({
                try{
                    val provider=ProcessCameraProvider.getInstance(ctx).get()
                    val sel=when{cameraId!=null->CameraSelector.Builder().addCameraFilter{infos->infos.filter{Camera2CameraInfo.from(it).cameraId==cameraId}.ifEmpty{infos}}.build();isFront->CameraSelector.DEFAULT_FRONT_CAMERA;else->CameraSelector.DEFAULT_BACK_CAMERA}
                    var lastAU=0L
                    var effFps=fps
                    try{
                        val cinfo=provider.getCameraInfo(sel)
                        val ranges=Camera2CameraInfo.from(cinfo).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        if(ranges?.none{it.lower==fps&&it.upper==fps} != false)effFps=30
                    }catch(e:Exception){effFps=30}
                    val pb=Preview.Builder()
                    val ext=Camera2Interop.Extender(pb)
                    ext.setSessionCaptureCallback(object:CameraCaptureSession.CaptureCallback(){
                        override fun onCaptureCompleted(s:CameraCaptureSession,r:CaptureRequest,result:TotalCaptureResult){
                            val now=SystemClock.elapsedRealtime()
                            if(now-lastAU<500L)return;lastAU=now
                            val iso=result.get(CaptureResult.SENSOR_SENSITIVITY)?:return
                            val ss=result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?:return
                            Handler(Looper.getMainLooper()).post{onAutoValues(iso,ss)}
                        }
                    })
                    if(effFps>30)ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,Range(effFps,effFps))
                    val preview=pb.build()
                    preview.setSurfaceProvider(view)
                    val ic=ImageCapture.Builder().build()
                    provider.unbindAll()
                    val cam=provider.bindToLifecycle(lco,sel,preview,ic,vc)
                    onCameraReady(cam);proController?.attach(cam);onImageCaptureReady(ic);onVideoCaptureReady(vc)
                    try {
                        val info=Camera2CameraInfo.from(cam.cameraInfo)
                        val dRot=ctx.display.rotation
                        val dDeg=dRot*90
                        val sDeg=info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)?:0
                        var rot=(dDeg-sDeg+360)%360
                        if(isFront)rot=(360-rot)%360
                        view.rotDeg=rot
                        view.isFront=isFront
                    }catch(e:Exception){android.util.Log.e("EaglesEye","rot:${e.message}")}
                }catch(e:Exception){e.printStackTrace()}
                if(cont.isActive)cont.resume(Unit)
            },ContextCompat.getMainExecutor(ctx))
        }
    }

    AndroidView(factory={ctx->CameraGLView(ctx).also{glView=it; it.onStreamAspect={onStreamAspect(it)}; onGlViewReady?.invoke(it)}},modifier=modifier.fillMaxSize())
}

private fun buildCustomPreset(config: EffectConfig) = FilmPreset(
    id = "custom", name = "Custom",
    fisheye = if (config.fisheyeOn) config.fisheyeStrength else 0f,
    vignetteIntensity = if (config.vignetteOn) config.vignetteIntensity else 0f,
    vignetteRadius = config.vignetteRadius, vignetteSoftness = config.vignetteSoftness,
    chromaticIntensity = if (config.caOn) config.caStrength else 0f,
    grainIntensity = if (config.grainOn) config.grainStrength else 0f,
    grainSize = 1.5f,
    duotoneIntensity = if (config.duotoneOn) 0.8f else 0f,
    bwIntensity = if (config.bwOn) 1f else 0f,
    bleachIntensity = if (config.bleachOn) 0.7f else 0f,
    crossProcessIntensity = if (config.crossOn) 0.7f else 0f,
    prismIntensity = if (config.prismOn) config.prismOpacity else 0f,
    prismAngle = config.prismAngle, prismOffset = config.prismOffset,
    warpIntensity = if (config.warpOn) 0.5f else 0f,
    dustIntensity = if (config.dustOn) 0.5f else 0f, randomDust = config.dustOn,
    vhsIntensity = if (config.vhsOn) 0.8f else 0f,
    vhsTracking = config.vhsTracking,
    crtIntensity = if (config.crtOn) config.crtStrength else 0f,
    softFocus = if (config.softFocusOn) config.softFocusStrength else 0f,
    colorShift = if (config.colorShiftOn) config.colorShiftStrength else 0f,
    instantIntensity = if (config.instantOn) config.instantStrength else 0f,
    retroIntensity = if (config.retroOn) config.retroStrength else 0f,
    bloomIntensity = if (config.bloomOn) config.bloomStrength else 0f,
    halationIntensity = if (config.halationOn) config.halationStrength else 0f,
    mistIntensity = if (config.mistOn) config.mistStrength else 0f,
    tiltShiftIntensity = if (config.tiltShiftOn) config.tiltShiftStrength else 0f,
    streakIntensity = if (config.streakOn) config.streakIntensity else 0f,
    streakSpread = if (config.streakOn) config.streakSpread else 0f,
    sunStreakIntensity = if (config.sunStreakOn) config.sunStreakIntensity else 0f,
    sunStreakWarp = if (config.sunStreakOn) config.sunStreakWarp else 0f,
    sharpenIntensity = if (config.sharpenOn) config.sharpenStrength else 0f,
    glitchIntensity = if (config.glitchOn) config.glitchStrength else 0f,
    lightLeakIntensity = if (config.lightLeakOn) config.lightLeakStrength else 0f,
    wideAngleIntensity = if (config.wideAngleOn) config.wideAngleStrength else 0f,
    doubleExposureIntensity = if (config.doubleExposureOn) config.doubleExposureStrength else 0f,
    focusPeakIntensity = if (config.focusPeakOn) config.focusPeakStrength else 0f,
    falseColorIntensity = if (config.falseColorOn) config.falseColorStrength else 0f,
    kaleidoIntensity = if (config.kaleidoOn) 1f else 0f,
    kaleidoSlices = config.kaleidoSlices,
    motionBlurIntensity = if (config.motionBlurOn) config.motionBlurStrength else 0f,
    isoGrainIntensity = if (config.isoGrainOn) config.isoGrainStrength else 0f,
    gyroLeakIntensity = if (config.gyroLeakOn) config.gyroLeakStrength else 0f,
    inColorIntensity = if (config.inColorOn) config.inColorStrength else 0f,
    inColorWarmth = config.inColorWarmth,
    oneBitIntensity = if (config.oneBitOn) 1f else 0f,
    oneBitScale = config.oneBitScale,
    fatPixelIntensity = if (config.fatPixelOn) 1f else 0f,
    fatPixelSize = config.fatPixelSize,
    fatPixelGlitch = config.fatPixelGlitch,
    halftoneIntensity = if (config.halftoneOn) 1f else 0f,
    halftoneSize = config.halftoneSize,
    halftoneAngle = config.halftoneAngle,
    cmykIntensity = if (config.cmykOn) 1f else 0f,
    cmykSize = config.cmykSize,
    teletextIntensity = if (config.teletextOn) 1f else 0f,
    terminalIntensity = if (config.terminalOn) 1f else 0f,
    terminalColor = config.terminalColor,
    bokehIntensity = if (config.bokehOn) 1f else 0f,
    bokehSize = config.bokehSize,
    bokehThreshold = config.bokehThreshold,
    velviaIntensity = if (config.velviaOn) config.velviaStrength else 0f,
    portraIntensity = if (config.portraOn) config.portraStrength else 0f,
    winterIntensity = if (config.winterOn) config.winterStrength else 0f,
    obsidianIntensity = if (config.obsidianOn) config.obsidianStrength else 0f,
    dreamyIntensity = if (config.dreamyOn) config.dreamyStrength else 0f,
    splitToneIntensity = if (config.splitToneOn) config.splitToneStrength else 0f,
    lightRaysIntensity = if (config.lightRaysOn) config.lightRaysStrength else 0f,
    fadedFilmIntensity = if (config.fadedFilmOn) config.fadedFilmStrength else 0f
)

@Composable
fun GridLines(type:GridOverlay=GridOverlay.THIRDS,modifier:Modifier=Modifier){
    Canvas(modifier){val sw=0.6.dp.toPx();val c=Color.White.copy(0.38f)
        when(type){
            GridOverlay.THIRDS->{drawLine(c,Offset(size.width/3,0f),Offset(size.width/3,size.height),sw);drawLine(c,Offset(2*size.width/3,0f),Offset(2*size.width/3,size.height),sw);drawLine(c,Offset(0f,size.height/3),Offset(size.width,size.height/3),sw);drawLine(c,Offset(0f,2*size.height/3),Offset(size.width,2*size.height/3),sw)}
            GridOverlay.FIFTY->{(1..4).forEach{i->val f=size.width*i/5f;drawLine(c,Offset(f,0f),Offset(f,size.height),sw);val g=size.height*i/5f;drawLine(c,Offset(0f,g),Offset(size.width,g),sw)}}
            GridOverlay.GOLDEN->{val g=0.618f;drawLine(c,Offset(size.width*g,0f),Offset(size.width*g,size.height),sw);drawLine(c,Offset(size.width*(1f-g),0f),Offset(size.width*(1f-g),size.height),sw);drawLine(c,Offset(0f,size.height*g),Offset(size.width,size.height*g),sw);drawLine(c,Offset(0f,size.height*(1f-g)),Offset(size.width,size.height*(1f-g)),sw)}
            GridOverlay.SQUARE->{(1..3).forEach{i->val f=size.width*i/4f;drawLine(c,Offset(f,0f),Offset(f,size.height),sw);val g=size.height*i/4f;drawLine(c,Offset(0f,g),Offset(size.width,g),sw)}}
            GridOverlay.CENTER->{drawLine(c,Offset(size.width/2,0f),Offset(size.width/2,size.height),sw);drawLine(c,Offset(0f,size.height/2),Offset(size.width,size.height/2),sw);drawLine(c,Offset(size.width/2,size.height/2),Offset(size.width*0.5f+12f,size.height/2),sw*2f)}
            GridOverlay.DIAGONAL->{drawLine(c,Offset(0f,0f),Offset(size.width,size.height),sw);drawLine(c,Offset(size.width,0f),Offset(0f,size.height),sw)}
            else->{}
        }
    }
}

@Composable fun GlassOpt(label:String,active:Boolean,onClick:()->Unit){Box(Modifier.clip(RoundedCornerShape(50)).background(if(active)Color.White else Color.Transparent).clickable(remember{MutableInteractionSource()},null){onClick()}.padding(horizontal=16.dp,vertical=9.dp),Alignment.Center){Text(label,color=if(active)Color.Black else Color.White.copy(0.75f),fontSize=13.sp,fontWeight=if(active)FontWeight.Bold else FontWeight.Normal)}}

// Live luma histogram — 64 bins, drawn as gold bars
@Composable
fun HistogramOverlay(bins: IntArray) {
    var max by remember { mutableStateOf(1) }
    max = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
    Canvas(
        Modifier
            .size(width = 160.dp, height = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(0.55f))
            .padding(6.dp)
    ) {
        val barW = size.width / bins.size
        bins.forEachIndexed { i, v ->
            val h = (v.toFloat() / max) * size.height
            drawRect(
                if (i < 6 || i > 57) EagleGreen else if (i < 20 || i > 43) FeatureYellow else Color(0xFFB8B8B0),
                topLeft = Offset(i * barW, size.height - h),
                size = Size(barW - 1.5f, h)
            )
        }
    }
}

// Quick share chip shown right after a photo capture
@Composable
fun ShareChip(uri: Uri) {
    val context = LocalContext.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(0.14f))
            .border(1.dp, Color.White.copy(0.12f), RoundedCornerShape(50))
            .clickable(remember { MutableInteractionSource() }, null) {
                runCatching {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share photo"))
                }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Default.Share, null, tint = EagleGold, modifier = Modifier.size(14.dp))
        Text("SHARE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

// ProPanel and InlineProBar replaced by ProPanel.kt

// ── Bottom Bar — glass pill: Gallery | PRO | Shutter | FILM ───────────────────
@Composable
fun BottomBar(
    isVideo:Boolean,isPro:Boolean,lastPhotoUri:Uri?,isRecording:Boolean,
    isExposing:Boolean=false,exposureProgress:Float=0f,exposureRemainSec:Int=0,
    isBurstActive:Boolean=false,fastBurstOn:Boolean=false,
    onGallery:()->Unit,onPro:()->Unit,
    onShutter:()->Unit,onBurstStart:()->Unit,onBurstEnd:()->Unit,
    onMenu:()->Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .uiGlass(UiRadiusPill)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        // Gallery — live thumbnail
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .uiPressScale(remember { MutableInteractionSource() })
                .alpha(if(isRecording)0.3f else 1f)
                .clickable(remember{MutableInteractionSource()},null){onGallery()}){
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(0.35f)).border(1.dp,Color.White.copy(0.14f),RoundedCornerShape(10.dp))){
                if(lastPhotoUri!=null)AsyncImage(model=lastPhotoUri,null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
                else Box(Modifier.fillMaxSize(),Alignment.Center){Icon(Icons.Default.PhotoLibrary,null,tint=TEXT_JSX.copy(alpha=0.5f),modifier=Modifier.size(17.dp))}
            }
            Text("LIBRARY",fontFamily=UiFont,fontSize=8.sp,letterSpacing=1.1.sp,fontWeight=FontWeight.Medium,color=MUTED2_JSX)
        }

        // PRO
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .uiPressScale(remember { MutableInteractionSource() })
                .alpha(if(isRecording)0.3f else 1f)
                .clickable(remember{MutableInteractionSource()},null){onPro()}){
            Box(Modifier.size(40.dp).clip(CircleShape).background(if(isPro)GOLD_JSX.copy(0.16f) else Color.Transparent).border(1.dp,if(isPro)GOLD_JSX.copy(0.5f) else Color.Transparent,CircleShape),Alignment.Center){
                Icon(Icons.Default.Tune,null,tint=if(isPro)GOLD_HI_JSX else TEXT_JSX.copy(alpha=0.8f),modifier=Modifier.size(18.dp))
            }
            Text("PRO",fontFamily=UiFont,fontSize=8.sp,letterSpacing=1.1.sp,fontWeight=FontWeight.Medium,color=if(isPro)GOLD_JSX else MUTED2_JSX)
        }

        // Shutter
        ShutterButton(
            isVideo=isVideo, isRecording=isRecording,
            onArmVideo={onShutter()}, onStopVideo={onShutter()}, onCapture={onShutter()},
            modifier=Modifier.size(82.dp)
        )

        // FILM
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .uiPressScale(remember { MutableInteractionSource() })
                .alpha(if(isRecording)0.3f else 1f)
                .clickable(remember{MutableInteractionSource()},null){onMenu()}){
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Transparent).border(1.dp,Color.Transparent,CircleShape),Alignment.Center){
                Icon(Icons.Default.Menu,null,tint=TEXT_JSX.copy(alpha=0.8f),modifier=Modifier.size(18.dp))
            }
            Text("FILM",fontFamily=UiFont,fontSize=8.sp,letterSpacing=1.1.sp,fontWeight=FontWeight.Medium,color=MUTED2_JSX)
        }
    }
}

// EagleShutter replaced by ShutterButton.kt

// ── Panorama HUD: ghost guide on the right edge + tile counter + finish ──
@Composable
private fun PanoHud(
    tiles: Int,
    lastTileUri: Uri?,
    busy: Boolean,
    done: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ghostAlpha = remember { Animatable(0.3f) }
    LaunchedEffect(lastTileUri) {
        ghostAlpha.snapTo(0.3f)
        if (lastTileUri != null) ghostAlpha.animateTo(0.55f, tween(700))
    }
    Box(modifier) {
        // Counter / instruction pill
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(0.55f))
                .border(1.dp, EagleGold.copy(0.35f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.PanoramaHorizontal, null, tint = EagleGold, modifier = Modifier.size(15.dp))
            Text(
                when {
                    busy -> "STITCHING…"
                    tiles == 0 -> "PAN → pan right · tap shutter when aligned"
                    else -> "TILE $tiles/${PanoStitcher.MAX_TILES} → pan right · tap shutter · STITCH when done"
                },
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
            )
        }
        // Ghost of last tile on the right edge
        if (lastTileUri != null && !done) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .fillMaxHeight(0.94f)
                    .fillMaxWidth(0.16f)
                    .clip(RoundedCornerShape(14.dp))
                    .border(2.dp, EagleGold.copy(0.5f), RoundedCornerShape(14.dp))
            ) {
                AsyncImage(
                    model = lastTileUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = ghostAlpha.value }
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(0.55f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("ALIGN ⟶", color = EagleGold, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                }
            }
        }
        // Finish button
        if (tiles >= 2 && !busy) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(EagleGold)
                    .clickable(remember { MutableInteractionSource() }, null) { onDone() }
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("FINISH", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
        }
        // Busy spinner
        if (busy) {
            Box(Modifier.align(Alignment.Center)) {
                CircularProgressIndicator(color = EagleGold, strokeWidth = 2.5.dp, modifier = Modifier.size(34.dp))
            }
        }
        // Saved confirmation
        if (done) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(0.7f))
                    .border(1.dp, EagleGold.copy(0.4f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("PANO SAVED", color = EagleGold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            }
        }
    }
}
