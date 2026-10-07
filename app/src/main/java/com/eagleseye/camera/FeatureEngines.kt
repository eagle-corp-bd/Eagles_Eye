package com.eagleseye.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

// ── HDR Engine ────────────────────────────────────────────────────────────────
object HdrEngine {
    private val weightLUT = FloatArray(256) { v ->
        val n = v / 255f
        Math.exp(-12.0 * Math.pow((n - 0.5), 2.0)).toFloat()
    }

    // Full blend (slow — use blendFast for captures)
    fun blend(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val w = frames[0].width; val h = frames[0].height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixels = frames.map { it.getPixel(x, y) }
                result.setPixel(x, y, Color.rgb(
                    blendChannel(pixels.map { Color.red(it) }),
                    blendChannel(pixels.map { Color.green(it) }),
                    blendChannel(pixels.map { Color.blue(it) })
                ))
            }
        }
        return result
    }

    // Downsampled blend — practical for full-res captures (scale=6 → ~16× faster)
    fun blendFast(frames: List<Bitmap>, scale: Int = 6): Bitmap {
        val w = frames[0].width; val h = frames[0].height
        val small = frames.map { Bitmap.createScaledBitmap(it, w / scale, h / scale, true) }
        val merged = blend(small)
        return Bitmap.createScaledBitmap(merged, w, h, true)
    }

    private fun blendChannel(values: List<Int>): Int {
        var wSum = 0f; var vSum = 0f
        values.forEach { v -> val w = weightLUT[v]; wSum += w; vSum += v * w }
        return if (wSum > 0f) (vSum / wSum).toInt().coerceIn(0, 255) else values[values.size / 2]
    }
}

// ── Night Engine: median-stack denoise ───────────────────────────────────────
object NightEngine {
    // Median per channel on the (pre-scaled) input frames, then upscale to outW×outH.
    // Callers downscale each captured frame before stacking so peak memory stays low.
    fun stack(frames: List<Bitmap>, outW: Int, outH: Int): Bitmap {
        val w = outW.coerceAtLeast(1); val h = outH.coerceAtLeast(1)
        if (frames.isEmpty()) return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val sw = frames[0].width; val sh = frames[0].height
        val n = frames.size
        val merged = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val rs = IntArray(n); val gs = IntArray(n); val bs = IntArray(n)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                for (i in 0 until n) {
                    val c = frames[i].getPixel(x, y)
                    rs[i] = Color.red(c); gs[i] = Color.green(c); bs[i] = Color.blue(c)
                }
                rs.sort(); gs.sort(); bs.sort()
                merged.setPixel(x, y, Color.rgb(rs[n / 2], gs[n / 2], bs[n / 2]))
            }
        }
        val out = Bitmap.createScaledBitmap(merged, w, h, true)
        if (out !== merged) merged.recycle()
        return out
    }
}

