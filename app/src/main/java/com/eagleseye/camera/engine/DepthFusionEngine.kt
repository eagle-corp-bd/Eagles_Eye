package com.eagleseye.camera.engine

import java.nio.ByteBuffer

/**
 * Production-grade multi-cue depth fusion (CPU, runs at model resolution ~256px).
 *
 * Implements the fusion pipeline from the engineering brief:
 *   - never trusts depth or segmentation blindly (soft probabilistic fusion)
 *   - confidence-weighted combination of neural depth + RGB structure
 *   - RGB edge guidance so depth does not bleed across real boundaries
 *   - edge-aware (joint-bilateral) depth refinement
 *   - depth completion / hole filling via RGB-guided median
 *   - segmentation soft prior (person priority) without hard cutouts
 *   - graceful degradation when depth or segmentation fail
 *
 * Orientation convention matches MiDaS: near objects => larger values.
 */
object DepthFusionEngine {

    data class FusionResult(val depth: FloatArray, val confidence: FloatArray)

    /**
     * @param rawDepth  normalized [0,1] neural depth (near = high), size w*h
     * @param rgb       ARGB pixels of the same w*h frame (used for edges/color), or null
     * @param seg       foreground probability [0,1] at segW*segH, or null
     * @param hair      hair alpha [0,1] at hairW*hairH, or null
     */
    fun refine(
        rawDepth: FloatArray,
        w: Int, h: Int,
        rgb: IntArray?,
        seg: FloatArray?, segW: Int, segH: Int,
        hair: FloatArray? = null, hairW: Int = 0, hairH: Int = 0
    ): FusionResult {
        val n = w * h
        if (rawDepth.size != n || w <= 0 || h <= 0) {
            return FusionResult(rawDepth.copyOf(), FloatArray(n) { 0.5f })
        }

        // 1. Validate + clamp depth (NaN/Inf -> 0.5 neutral).
        val depth = FloatArray(n)
        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (i in 0 until n) {
            val v = rawDepth[i]
            val c = if (v.isNaN() || v.isInfinite()) 0.5f else v.coerceIn(0f, 1f)
            depth[i] = c
            if (c < minV) minV = c
            if (c > maxV) maxV = c
        }
        val spread = maxV - minV

        // 2. RGB-derived cues (luminance + Sobel edge + local colour variance).
        val lum = FloatArray(n)
        val hasRgb = rgb != null && rgb.size == n
        if (hasRgb) {
            for (i in 0 until n) {
                val c = rgb!![i]
                val r = (c shr 16 and 0xFF); val g = (c shr 8 and 0xFF); val b = (c and 0xFF)
                lum[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            }
        }
        val edge = if (hasRgb) sobel(lum, w, h) else FloatArray(n)

        // 3. Depth reliability (low local variance => confident; real depth edges
        //    supported by RGB edges stay confident). Also detect outliers.
        val depthConf = FloatArray(n)
        var varSum = 0f; var varCnt = 0
        val localVar = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var m = 0f; var c2 = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { val v = depth[yy * w + xx]; m += v; c2++ }
            }
            m /= c2
            var v = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { val d = depth[yy * w + xx] - m; v += d * d }
            }
            v /= c2
            localVar[i] = v
            varSum += v; varCnt++
        }
        val meanVar = if (varCnt > 0) varSum / varCnt else 0.02f
        for (i in 0 until n) {
            val e = edge[i]
            // Confident where the region is smooth AND there is no unsupported depth
            // discontinuity; where an RGB edge supports the depth edge, stay confident.
            val smooth = 1f - (localVar[i] / (meanVar * 6f + 0.004f)).coerceIn(0f, 1f)
            depthConf[i] = if (e > 0.35f) 0.92f else smooth.coerceIn(0.25f, 1f)
        }

        // 4. Edge-aware (joint bilateral) depth refinement guided by RGB colour.
        //    Smooths depth inside iso-colour regions, preserves boundaries.
        val refined = FloatArray(n)
        if (hasRgb) {
            val sigS = 2.0f
            val sigC = 0.18f
            val inv2s2 = 1f / (2f * sigS * sigS)
            val inv2c2 = 1f / (2f * sigC * sigC)
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val cr = ((rgb!![i] shr 16 and 0xFF)).toFloat()
                val cg = ((rgb!![i] shr 8 and 0xFF)).toFloat()
                val cb = ((rgb!![i] and 0xFF)).toFloat()
                var sw = 0f; var sd = 0f
                for (dy in -2..2) for (dx in -2..2) {
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val j = yy * w + xx
                    val dr = ((rgb!![j] shr 16 and 0xFF)).toFloat() - cr
                    val dg = ((rgb!![j] shr 8 and 0xFF)).toFloat() - cg
                    val db = ((rgb!![j] and 0xFF)).toFloat() - cb
                    val cd = (dr * dr + dg * dg + db * db) / (3f * 255f * 255f)
                    val wgt = kotlin.math.exp(-((dx * dx + dy * dy) * inv2s2 + cd * inv2c2))
                    sw += wgt; sd += depth[j] * wgt
                }
                refined[i] = if (sw > 0f) sd / sw else depth[i]
            }
        } else {
            depth.copyInto(refined)
        }

        // 5. Depth completion: replace extreme outliers (vs 3x3 median) with the
        //    median, and fill very-low-confidence pixels from the refined field.
        val median = median3x3(refined, w, h)
        for (i in 0 until n) {
            if (kotlin.math.abs(depth[i] - median[i]) > 0.18f && depthConf[i] < 0.5f) {
                refined[i] = median[i]
            }
        }

        // 6. Segmentation soft prior (person priority) — NO binary mask.
        var segFull: FloatArray? = null
        if (seg != null && segW > 0 && segH > 0) {
            segFull = resample(seg, segW, segH, w, h)
            if (hair != null && hairW > 0 && hairH > 0) {
                val hFull = resample(hair, hairW, hairH, w, h)
                for (i in 0 until n) segFull[i] = kotlin.math.max(segFull[i], hFull[i] * 0.85f)
            }
            // Subject should be the near (high-depth) region. If the depth model
            // put the subject farther than the background, gently pull subject
            // depth upward so it reads as foreground — preserving internal variation.
            var sMed = 0f; var sCnt = 0
            var bMed = 0f; var bCnt = 0
            for (i in 0 until n) {
                if (segFull[i] > 0.5f) { sMed += refined[i]; sCnt++ }
                else if (segFull[i] < 0.4f) { bMed += refined[i]; bCnt++ }
            }
            if (sCnt > 0) sMed /= sCnt
            if (bCnt > 0) bMed /= bCnt
            if (sCnt > 0 && bCnt > 0 && (bMed - sMed) > 0.06f) {
                val target = (bMed + 0.12f).coerceIn(0f, 1f)
                for (i in 0 until n) {
                    val s = segFull[i]
                    if (s > 0.5f) {
                        val pulled = kotlin.math.max(refined[i], target)
                        refined[i] = refined[i] + (pulled - refined[i]) * (s * 0.6f)
                    }
                }
            }
            // Orientation normalization: the subject MUST be the nearest (highest
            // depth) region so the editor's MAX-based auto-focus and tap-to-focus
            // both land on the subject. The soft pull above is sometimes too weak
            // (when the model returns an inverted map the subject stays below the
            // background), which makes the blur hit the subject instead of the
            // background. If, after the pull, the subject median is still below the
            // background median, flip the whole field so subject = high (near).
            var sMed2 = 0f; var sC2 = 0
            var bMed2 = 0f; var bC2 = 0
            for (i in 0 until n) {
                if (segFull[i] > 0.5f) { sMed2 += refined[i]; sC2++ }
                else if (segFull[i] < 0.4f) { bMed2 += refined[i]; bC2++ }
            }
            if (sC2 > 0) sMed2 /= sC2
            if (bC2 > 0) bMed2 /= bC2
            if (sC2 > 0 && bC2 > 0 && sMed2 < bMed2) {
                for (i in 0 until n) refined[i] = 1f - refined[i]
            }
        }

        // 7. Graceful fallback when neural depth is degenerate.
        if (spread < 0.02f) {
            if (segFull != null) {
                for (i in 0 until n) refined[i] = (0.2f + 0.65f * segFull[i]).coerceIn(0f, 1f)
            } else {
                for (i in 0 until n) refined[i] = 0.5f
            }
        }

        // 8. Final confidence: depth reliability modulated by edge support.
        val confidence = FloatArray(n)
        for (i in 0 until n) {
            confidence[i] = depthConf[i] * (0.6f + 0.4f * edge[i]).coerceIn(0f, 1f)
        }

        return FusionResult(refined, confidence)
    }

    /** Build a foreground-probability FloatArray [0,1] from a SINet mask ByteBuffer. */
    fun segFromMask(maskBytes: ByteBuffer, count: Int): FloatArray {
        val out = FloatArray(count)
        if (maskBytes.remaining() < count) return out
        val arr = ByteArray(count)
        maskBytes.get(arr)
        for (i in 0 until count) out[i] = (arr[i].toInt() and 0xFF) / 255f
        return out
    }

    private fun sobel(lum: FloatArray, w: Int, h: Int): FloatArray {
        val e = FloatArray(w * h)
        var maxE = 1e-5f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val tl = lum[i - w - 1]; val t = lum[i - w]; val tr = lum[i - w + 1]
            val l = lum[i - 1]; val r = lum[i + 1]
            val bl = lum[i + w - 1]; val b = lum[i + w]; val br = lum[i + w + 1]
            val gx = -tl - 2 * l - bl + tr + 2 * r + br
            val gy = -tl - 2 * t - tr + bl + 2 * b + br
            val m = kotlin.math.sqrt(gx * gx + gy * gy)
            e[i] = m
            if (m > maxE) maxE = m
        }
        for (i in e.indices) e[i] = (e[i] / maxE).coerceIn(0f, 1f)
        return e
    }

    private fun median3x3(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        val buf = FloatArray(9)
        for (y in 0 until h) for (x in 0 until w) {
            var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                buf[k++] = src[yy * w + xx]
            }
            buf.sort()
            out[y * w + x] = buf[4]
        }
        return out
    }

    private fun resample(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        if (sw <= 0 || sh <= 0) return out
        for (y in 0 until dh) for (x in 0 until dw) {
            val sx = ((x * sw) / dw).coerceIn(0, sw - 1)
            val sy = ((y * sh) / dh).coerceIn(0, sh - 1)
            out[y * dw + x] = src[sy * sw + sx]
        }
        return out
    }
}
