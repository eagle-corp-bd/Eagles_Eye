package com.eagleseye.camera.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Capture-grade hair/matting segmentation — **BiRefNet** (MIT, SOTA accuracy,
 * ~214 MB). Used for the *saved photo* where accuracy matters more than speed.
 *
 * Asset: `birefnet.tflite` (default 1024x1024 RGB in, single alpha/sigmoid out
 * in [0,1]). Input is ImageNet-normalized exactly as BiRefNet was trained.
 *
 * The file may not have been dropped into assets yet — until it exists
 * [isAvailable] stays false and capture degrades to the SINet live mask (or
 * depth-only). All references are null-safe for that case.
 *
 * NOTE: BiRefNet is published as ONNX/PyTorch; this engine expects a TFLite
 * export (e.g. via onnx2tf / community `birefnet-*-tflite` exports) so it runs
 * on the existing TFLite stack without pulling in the ONNX Runtime dependency.
 */
class BirefNetEngine(context: android.content.Context) : BaseSegEngine(context, ASSET_NAME, CAPTURE_INPUT_SIZE) {

    companion object {
        private const val TAG = "BirefNetEngine"

        /** Asset path — flagged: may be missing until the .tflite is dropped in. */
        const val ASSET_NAME = "birefnet.tflite"

        /** Input the engine prepares when the model file isn't loaded yet. */
        const val CAPTURE_INPUT_SIZE = 1024

        /** Alpha/foreground threshold (sigmoid output in [0,1]). */
        const val MASK_THRESHOLD = 0.5f

        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    // BiRefNet input: ImageNet-normalized RGB float.
    override fun preprocess(src: ByteArray, n: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(n * 3 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            buf.putFloat(((src[i * 3].toInt() and 0xFF) / 255f - MEAN[0]) / STD[0])
            buf.putFloat(((src[i * 3 + 1].toInt() and 0xFF) / 255f - MEAN[1]) / STD[1])
            buf.putFloat(((src[i * 3 + 2].toInt() and 0xFF) / 255f - MEAN[2]) / STD[2])
        }
        buf.rewind()
        return buf
    }

    // BiRefNet output: single alpha channel (or [bg, fg] if a 2-class export).
    // Emit a SOFT alpha PROBABILITY in [0,255] rather than a hard binary mask so
    // the saved-photo bokeh feathers hair / thin structures instead of cutting.
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
