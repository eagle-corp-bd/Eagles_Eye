package com.eagleseye.camera.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * CPU panorama stitcher — sequential tiles with overlap correlation + gradient seam blend.
 * Tiles are downscaled for speed (~1100px wide) so output stays interactive.
 */
object PanoStitcher {
    const val TAG = "PanoStitcher"
    const val MAX_TILE_W = 1100
    const val MAX_TILES = 7

    data class Tile(val bm: Bitmap, val px: IntArray, val x: Int, val y: Int)

    fun stitch(context: Context, uris: List<Uri>): Uri? {
        if (uris.isEmpty()) return null
        if (uris.size == 1) return uris[0]
        val tiles = uris.mapNotNull { decode(context, it) }
        if (tiles.isEmpty()) return null
        return try {
            val placed = alignTiles(tiles)
            val pano = composite(placed)
            save(context, pano).also { pano.recycle() }
        } catch (e: Exception) {
            Log.e(TAG, "stitch failed", e); null
        } finally {
            tiles.forEach { it.recycle() }
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(ins, null, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= MAX_TILE_W) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { ins2 ->
                    BitmapFactory.decodeStream(ins2, null, opts)
                }
            }
        } catch (e: Exception) { Log.e(TAG, "decode $uri failed", e); null }
    }

    private fun toPx(bm: Bitmap): IntArray {
        val px = IntArray(bm.width * bm.height)
        bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        return px
    }

    private fun luma(p: Int): Int =
        ((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)

    /**
     * Find the horizontal overlap and vertical offset between consecutive tiles.
     * A (left) right edge must match B (right) left edge.
     */
    private fun findOffset(aPx: IntArray, aW: Int, aH: Int, bPx: IntArray, bW: Int, bH: Int): Pair<Int, Int> {
        val step = 4
        val gAw = aW / step; val gAh = aH / step
        val gBw = bW / step; val gBh = bH / step
        val gA = IntArray(gAw * gAh)
        for (y in 0 until gAh) for (x in 0 until gAw) gA[y * gAw + x] = luma(aPx[(y * step) * aW + x * step])
        val gB = IntArray(gBw * gBh)
        for (y in 0 until gBh) for (x in 0 until gBw) gB[y * gBw + x] = luma(bPx[(y * step) * bW + x * step])

        val minOv = (bW * 0.08f).toInt()
        val maxOv = (bW * 0.24f).toInt()
        val y0 = (gBh * 0.22).toInt(); val y1 = (gBh * 0.78).toInt()

        var bestCost = Double.MAX_VALUE; var bestOv = minOv; var bestDy = 0
        var ov = maxOv
        while (ov >= minOv) {
            val ovS = ov / step
            for (dyS in -10..10) {
                var cost = 0L; var n = 0
                for (y in y0 until y1) {
                    val by = y + dyS
                    if (by < 0 || by >= gBh) continue
                    val ar = y * gAw; val br = by * gBw
                    for (c in 0 until ovS) {
                        val ax = gAw - ovS + c
                        if (ax < 0 || ax >= gAw) continue
                        val d = gA[ar + ax] - gB[br + c]
                        cost += d * d; n++
                    }
                }
                if (n > 0) {
                    val avg = cost.toDouble() / n
                    if (avg < bestCost) { bestCost = avg; bestOv = ov; bestDy = dyS * step }
                }
            }
            ov -= 2 * step
        }

        // Full-resolution refine around the best coarse match
        val refine = 6
        var b2 = bestCost; var ov2 = bestOv; var dy2 = bestDy
        val y0f = (bH * 0.22).toInt(); val y1f = (bH * 0.78).toInt()
        for (ovr in (bestOv - refine)..(bestOv + refine)) {
            if (ovr < 1 || ovr >= bW) continue
            for (dyr in (bestDy - refine)..(bestDy + refine)) {
                var cost = 0L; var n = 0
                for (y in y0f until y1f step 2) {
                    val by = y + dyr
                    if (by < 0 || by >= bH) continue
                    val ar = y * aW; val br = by * bW
                    for (c in 0 until ovr step 2) {
                        val ax = aW - ovr + c
                        if (ax < 0 || ax >= aW) continue
                        val d = luma(aPx[ar + ax]) - luma(bPx[br + c])
                        cost += d * d; n++
                    }
                }
                if (n > 0) {
                    val avg = cost.toDouble() / n
                    if (avg < b2) { b2 = avg; ov2 = ovr; dy2 = dyr }
                }
            }
        }
        return ov2 to dy2
    }

    private fun alignTiles(bitmaps: List<Bitmap>): List<Tile> {
        val px = bitmaps.map { toPx(it) }
        val result = mutableListOf(Tile(bitmaps[0], px[0], 0, 0))
        var accX = 0; var accY = 0
        for (i in 1 until bitmaps.size) {
            val a = bitmaps[i - 1]; val b = bitmaps[i]
            val (ov, dy) = findOffset(px[i - 1], a.width, a.height, px[i], b.width, b.height)
            accX += b.width - ov
            accY += dy
            result.add(Tile(b, px[i], accX, accY))
        }
        return result
    }

    private fun blendColor(ac: Int, bc: Int, w: Float): Int {
        if (ac == 0) return bc
        if (bc == 0) return ac
        val iw = 1f - w
        val a = ((ac shr 24 and 0xFF) * iw + (bc shr 24 and 0xFF) * w).toInt()
        val r = ((ac shr 16 and 0xFF) * iw + (bc shr 16 and 0xFF) * w).toInt()
        val g = ((ac shr 8 and 0xFF) * iw + (bc shr 8 and 0xFF) * w).toInt()
        val b = ((ac and 0xFF) * iw + (bc and 0xFF) * w).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun composite(placed: List<Tile>): Bitmap {
        var outW = 0; var outH = 0; var minY = 0
        for (t in placed) {
            outW = maxOf(outW, t.x + t.bm.width)
            outH = maxOf(outH, t.y + t.bm.height)
            minY = minOf(minY, t.y)
        }
        outH -= minY
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        for (i in placed.indices) {
            val t = placed[i]
            if (i == 0) {
                canvas.drawBitmap(t.bm, t.x.toFloat(), (t.y - minY).toFloat(), null)
                continue
            }
            val prev = placed[i - 1]
            val ov = (prev.x + prev.bm.width) - t.x
            if (ov > 0) {
                // gradient-blended seam strip
                val strip = blendStrip(prev, t, ov)
                canvas.drawBitmap(strip, prev.x.toFloat(), (prev.y - minY).toFloat(), null)
                strip.recycle()
                // rest of current tile (cropped past the overlap)
                val src = Rect(ov, 0, t.bm.width, t.bm.height)
                val dst = RectF((t.x + ov).toFloat(), (t.y - minY).toFloat(), (t.x + t.bm.width).toFloat(), (t.y - minY + t.bm.height).toFloat())
                canvas.drawBitmap(t.bm, src, dst, null)
            } else {
                canvas.drawBitmap(t.bm, t.x.toFloat(), (t.y - minY).toFloat(), null)
            }
        }
        return out
    }

    private fun blendStrip(a: Tile, b: Tile, ov: Int): Bitmap {
        val h = maxOf(a.bm.height, b.bm.height)
        val strip = Bitmap.createBitmap(ov, h, Bitmap.Config.ARGB_8888)
        val out = IntArray(ov * h)
        val aOff = a.bm.width - ov
        val aw = a.bm.width; val bw = b.bm.width
        val dy = b.y - a.y
        for (y in 0 until h) {
            val ay = y; val by = y - dy
            val aBase = if (ay in 0 until a.bm.height) ay * aw else -1
            val bBase = if (by in 0 until b.bm.height) by * bw else -1
            for (x in 0 until ov) {
                val w = smoothstep(x / ov.toFloat())
                val ac = if (aBase >= 0 && (aOff + x) in 0 until aw) a.px[aBase + aOff + x] else 0
                val bc = if (bBase >= 0 && x in 0 until bw) b.px[bBase + x] else 0
                out[y * ov + x] = blendColor(ac, bc, w)
            }
        }
        strip.setPixels(out, 0, ov, 0, 0, ov, h)
        return strip
    }

    private fun save(context: Context, bm: Bitmap): Uri? {
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "EaglesEye_Pano_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/EaglesEye")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { os -> bm.compress(Bitmap.CompressFormat.JPEG, 92, os) }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e); null
        }
    }
}
