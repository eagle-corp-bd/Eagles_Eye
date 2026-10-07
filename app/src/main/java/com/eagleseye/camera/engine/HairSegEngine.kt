package com.eagleseye.camera.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Live-preview person/foreground segmentation — **SINet** (Extreme Lightweight
 * Portrait Segmentation, MIT, ~430 KB, ~1.8 ms on a mid-range phone).
 *
 * Asset: `sinet.tflite` (224x224 RGB in, 2-class [background, foreground] out).
 * The file may not have been dropped into assets yet — until it exists
 * [isAvailable] stays false and the engine silently runs depth-only. Every
 * reference to the asset below is null-safe for that case.
 *
 * Used for the *live* bokeh preview (fast, coarse person mask). The accurate
 * capture-grade mask comes from [BirefNetEngine]. Both feed the same binary
 * mask contract into the fusion pipeline.
 */
class HairSegEngine(context: android.content.Context) : BaseSegEngine(context, ASSET_NAME, PREVIEW_INPUT_SIZE) {

    companion object {
        private const val TAG = "HairSegEngine"

        /** Asset path — flagged: may be missing until the .tflite is dropped in. */
        const val ASSET_NAME = "sinet.tflite"

        /** Input the engine prepares when the model file isn't loaded yet. */
        const val PREVIEW_INPUT_SIZE = 224

        /** Foreground probability threshold (2-class softmax/logits). */
        const val MASK_THRESHOLD = 0.5f
    }

    // SINet input: RGB float in [0,1].
    override fun preprocess(src: ByteArray, n: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            buf.putFloat((src[i * 3].toInt() and 0xFF) / 255f)
            buf.putFloat((src[i * 3 + 1].toInt() and 0xFF) / 255f)
            buf.putFloat((src[i * 3 + 2].toInt() and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    // SINet output: 2-class. Take the foreground channel (index 1) regardless of
    // NCHW/NHWC layout and emit a SOFT foreground PROBABILITY in [0,255] — not a
    // binary mask. The fusion pipeline feathers edges from this probability, which
    // is what keeps hair / thin structures from becoming a hard cutout.
    override fun decode(out: FloatArray, count: Int): ByteArray {
        val w = outputWidth; val h = outputHeight; val c = outputChannels
        val mask = ByteArray(w * h)
        if (c >= 2) {
            for (y in 0 until h) for (x in 0 until w) {
                val idx = if (outputLayout == Layout.NCHW) 1 * h * w + y * w + x else y * w * c + x * c + 1
                val p = (out[idx].coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
                mask[y * w + x] = p.toByte()
            }
        } else {
            for (i in 0 until w * h) {
                val p = (out[i].coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
                mask[i] = p.toByte()
            }
        }
        return mask
    }
}
