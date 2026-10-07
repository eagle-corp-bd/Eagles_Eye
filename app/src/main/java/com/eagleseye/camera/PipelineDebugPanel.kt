package com.eagleseye.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagleseye.camera.engine.FusionPipelineEngine
import com.eagleseye.camera.engine.GpuDebugLog
import kotlinx.coroutines.delay

@Composable
fun PipelineDebugPanel(engine: FusionPipelineEngine?, modifier: Modifier = Modifier) {
    var data by remember { mutableStateOf<PipelineDebugData?>(null) }
    var gpu by remember { mutableStateOf("") }
    var gpuDetail by remember { mutableStateOf("") }
    LaunchedEffect(engine) {
        while (true) {
            delay(250)
            data = engine?.let { buildDebugData(it) }
            gpu = GpuDebugLog.panelLine()
            gpuDetail = GpuDebugLog.panelDetail()
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(UiBg.copy(0.82f))
            .padding(6.dp)
    ) {
        if (gpu.isNotEmpty()) {
            Text(
                gpu,
                color = if (GpuDebugLog.invalidPasses.isNotEmpty() || GpuDebugLog.fboIssues.isNotEmpty() || GpuDebugLog.agslFail != null)
                    Color(0xFFFF6B6B) else UiGold,
                fontSize = 9.sp, fontFamily = UiFontMono, lineHeight = 12.sp
            )
            if (gpuDetail.isNotEmpty()) {
                Text(gpuDetail, color = UiTextDim, fontSize = 8.sp, fontFamily = UiFontMono, lineHeight = 11.sp)
            }
        }
        data?.let { d ->
            if (gpu.isNotEmpty()) Spacer(Modifier.height(4.dp))
            Text(d.status, color = UiText, fontSize = 9.sp, fontFamily = UiFontMono, lineHeight = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DebugBox("RAW", d.raw)
                DebugBox("DEPTH", d.depth)
                DebugBox("MASK", d.mask)
            }
        }
    }
}

@Composable
private fun DebugBox(label: String, bmp: Bitmap?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(78.dp)
                .background(Color.Black)
                .border(0.5.dp, UiGold.copy(0.5f))
        ) {
            bmp?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = label, modifier = Modifier.fillMaxSize())
            }
        }
        Text(label, color = UiTextDim, fontSize = 8.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

private data class PipelineDebugData(
    val status: String,
    val raw: Bitmap?,
    val depth: Bitmap?,
    val mask: Bitmap?,
)

private fun buildDebugData(e: FusionPipelineEngine): PipelineDebugData {
    val sb = StringBuilder()
    sb.append("mode=${e.dbgModeName} ready=${e.dbgModelsReady} defer=${e.dbgLastDeferReason}\n")
    sb.append("sinet=${e.dbgSINet} birefnet=${e.dbgBiRefNet} midas=${e.dbgMiDaS} zip=${e.dbgZip}\n")
    sb.append("failStreak=${e.dbgLiveFailStreak} reinit=${e.dbgReinitInFlight} texValid=${e.dbgDepthTexValid}\n")
    sb.append("segOverrides=${e.segOverrides} err=${e.lastInferError}\n")
    val dc = e.dbgDepthCpu
    if (dc != null && dc.isNotEmpty()) {
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in dc) { if (v.isFinite()) { if (v < mn) mn = v; if (v > mx) mx = v } }
        sb.append("depth min=${"%.2f".format(mn)} max=${"%.2f".format(mx)} ${e.dbgDepthW}x${e.dbgDepthH}")
    } else sb.append("depth none")

    val raw = e.debugInputBytes?.let { toRgbBitmap(it, e.debugInputSize, e.debugInputSize) }
    val depth = e.dbgDepthCpu?.let { toGrayBitmap(it, e.dbgDepthW, e.dbgDepthH) }
    val mask = e.debugMaskBytes?.let { toMaskBitmap(it, e.debugMaskW, e.debugMaskH) }
    return PipelineDebugData(sb.toString(), raw, depth, mask)
}

private fun toRgbBitmap(rgb: ByteArray, w: Int, h: Int): Bitmap {
    if (w <= 0 || h <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val px = IntArray(w * h)
    val n = minOf(w * h, rgb.size / 3)
    for (i in 0 until n) {
        val r = rgb[i * 3].toInt() and 0xFF
        val g = rgb[i * 3 + 1].toInt() and 0xFF
        val b = rgb[i * 3 + 2].toInt() and 0xFF
        px[i] = android.graphics.Color.argb(255, r, g, b)
    }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp
}

private fun toGrayBitmap(f: FloatArray, w: Int, h: Int): Bitmap {
    if (w <= 0 || h <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val px = IntArray(w * h)
    var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
    for (v in f) { if (v.isFinite()) { if (v < mn) mn = v; if (v > mx) mx = v } }
    val span = (mx - mn).coerceAtLeast(1e-5f)
    val n = minOf(w * h, f.size)
    for (i in 0 until n) {
        val v = f[i]
        val g = if (v.isFinite()) (((v - mn) / span) * 255f).toInt().coerceIn(0, 255) else 0
        px[i] = android.graphics.Color.argb(255, g, g, g)
    }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp
}

private fun toMaskBitmap(m: ByteArray, w: Int, h: Int): Bitmap {
    if (w <= 0 || h <= 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val px = IntArray(w * h)
    val n = minOf(w * h, m.size)
    for (i in 0 until n) {
        val on = (m[i].toInt() and 0xFF) > 127
        val v = if (on) 255 else 0
        px[i] = android.graphics.Color.argb(255, v, v, v)
    }
    bmp.setPixels(px, 0, w, 0, 0, w, h)
    return bmp
}
