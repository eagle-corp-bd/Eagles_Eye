package com.eagleseye.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagleseye.camera.presets.EffectConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREVIEW_MAX = 1280
private const val EXPORT_MAX = 4096

/**
 * Film-look editor for an arbitrary (custom) photo picked from the device.
 *
 * The picked image is decoded once, then re-baked on a background thread through
 * [FilmEngine.applyToCapture] every time a control changes (debounced), so the
 * preview is a true render of the final output — film grade, grain, light leak,
 * vignette, frame and timestamp all update live. APPLY bakes the full-resolution
 * result and saves it to the gallery as an `EaglesEye_edit_*` photo.
 *
 * It reuses the existing [AnalogEngineSheet] as the control surface so the FILM /
 * FRAME / EFFECTS tabs behave exactly like the live camera.
 */
@Composable
fun PhotoAnalogSheet(uri: Uri, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf(FILM_PROFILES[0]) }
    var grain by remember { mutableFloatStateOf(FILM_PROFILES[0].defaultGrain) }
    var leak by remember { mutableFloatStateOf(FILM_PROFILES[0].defaultLightLeak) }
    var leakColorMode by remember { mutableStateOf(LightLeakColor.ORANGE) }
    var leakAdaptive by remember { mutableStateOf(true) }
    var frameType by remember { mutableStateOf(FrameType.NONE) }
    var frameMode by remember { mutableStateOf(FrameMode.OVERLAY) }
    var frameConfig by remember { mutableStateOf(FrameConfig()) }
    var showTimestamp by remember { mutableStateOf(false) }
    var dateStyle by remember { mutableStateOf(DateStampStyle.ORANGE_FILM) }
    var dateFormat by remember { mutableStateOf(DateFormatType.DD_MM_YY) }
    var dateShowTime by remember { mutableStateOf(true) }
    var datePos by remember { mutableIntStateOf(0) }
    var dateCustom by remember { mutableStateOf("") }
    var config by remember { mutableStateOf(EffectConfig()) }
    var lookStrength by remember { mutableFloatStateOf(1f) }

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var decodeFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        val bm = withContext(Dispatchers.IO) { decodeForUri(context, uri, PREVIEW_MAX) }
        if (bm == null) decodeFailed = true else source = bm
    }

    LaunchedEffect(source, profile, grain, leak, leakColorMode, frameType, frameMode, frameConfig,
        showTimestamp, dateStyle, dateFormat, dateShowTime, datePos, dateCustom, lookStrength) {
        val src = source ?: return@LaunchedEffect
        delay(70)
        preview = withContext(Dispatchers.Default) {
            FilmEngine.applyToCapture(
                src, profile,
                grainAmt = grain * lookStrength,
                leakAmt = leak * lookStrength,
                frameType = frameType,
                showTimestamp = showTimestamp,
                leakColorMode = leakColorMode,
                frameConfig = frameConfig,
                frameMode = frameMode,
                dateStyle = dateStyle,
                dateFormat = dateFormat,
                dateShowTime = dateShowTime,
                datePos = datePos,
                dateText = dateCustom.ifBlank { null }
            )
        }
    }

    fun save() {
        if (saving) return
        saving = true
        val p = profile; val g = grain; val l = leak; val lc = leakColorMode
        val ft = frameType; val fm = frameMode; val fc = frameConfig
        val st = showTimestamp; val ds = dateStyle; val df = dateFormat
        val dst = dateShowTime; val dp = datePos; val dc = dateCustom; val ls = lookStrength
        scope.launch {
            val out = withContext(Dispatchers.Default) {
                val full = withContext(Dispatchers.IO) { decodeForUri(context, uri, EXPORT_MAX) } ?: return@withContext null
                FilmEngine.applyToCapture(
                    full, p,
                    grainAmt = g * ls,
                    leakAmt = l * ls,
                    frameType = ft,
                    showTimestamp = st,
                    leakColorMode = lc,
                    frameConfig = fc,
                    frameMode = fm,
                    dateStyle = ds,
                    dateFormat = df,
                    dateShowTime = dst,
                    datePos = dp,
                    dateText = dc.ifBlank { null }
                )
            }
            if (out != null) withContext(Dispatchers.IO) { saveToGallery(context, out) }
            saving = false
            onSaved()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF000000))) {
        val shown = preview ?: source
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = "Analogue preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp)
            )
        } else if (decodeFailed) {
            Text("Couldn't open photo", color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        } else {
            CircularProgressIndicator(color = AppAccent.color, modifier = Modifier.align(Alignment.Center))
        }

        AnalogEngineSheet(
            visible = true,
            profiles = FILM_PROFILES,
            selectedProfile = profile,
            onSelect = { p -> profile = p; grain = p.defaultGrain; leak = p.defaultLightLeak },
            onDismiss = onClose,
            onApply = { save() },
            grainValue = grain, onGrainChange = { grain = it },
            leakValue = leak, onLeakChange = { leak = it },
            leakColorMode = leakColorMode, onLeakColorChange = { leakColorMode = it },
            leakAdaptive = leakAdaptive, onLeakAdaptiveChange = { leakAdaptive = it },
            frameType = frameType, onFrameChange = { frameType = it },
            frameMode = frameMode, onFrameModeChange = { frameMode = it },
            frameConfig = frameConfig, onFrameConfigChange = { frameConfig = it },
            showTimestamp = showTimestamp, onTimestampToggle = { showTimestamp = !showTimestamp },
            dateStyle = dateStyle, onDateStyleChange = { dateStyle = it },
            dateFormat = dateFormat, onDateFormatChange = { dateFormat = it },
            dateShowTime = dateShowTime, onDateShowTimeChange = { dateShowTime = it },
            datePos = datePos, onDatePosChange = { datePos = it },
            dateCustom = dateCustom, onDateCustomChange = { dateCustom = it },
            config = config, onConfigChange = { config = it },
            lookStrength = lookStrength, onLookStrength = { lookStrength = it }
        )
    }
}

private fun saveToGallery(context: Context, bmp: Bitmap): Uri? = runCatching {
    val vals = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "EaglesEye_edit_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/EaglesEye")
    }
    val u = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals) ?: return null
    context.contentResolver.openOutputStream(u)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) } ?: return null
    u
}.getOrNull()
