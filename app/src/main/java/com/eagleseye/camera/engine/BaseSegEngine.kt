package com.eagleseye.camera.engine

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Shared boilerplate for on-device segmentation models loaded from assets as
 * TensorFlow-Lite. Subclasses supply input preprocessing and output decoding.
 *
 * Both shipped engines use the same contract as the fusion pipeline:
 *   - [run] returns a binary mask ByteBuffer, one byte per output pixel:
 *     255 = subject (foreground), 0 = background. The pipeline turns any
 *     subject pixel into "nearest possible" depth so it stays sharp under bokeh.
 *
 * Every engine degrades gracefully: if the asset is missing the model simply
 * reports [isAvailable] = false and the caller falls back to depth-only. All
 * references to the asset are null-safe for that state.
 *
 * Note: both engines assume a float32 input tensor (SINet / BiRefNet exports
 * are float32). The input size, channel count and output layout are resolved
 * dynamically from the graph so the same code handles NCHW and NHWC output.
 */
abstract class BaseSegEngine(
    context: Context,
    protected val assetName: String,
    defaultInput: Int
) {
    companion object {
        private const val TAG = "BaseSegEngine"
    }

    enum class Layout { NHWC, NCHW }

    private var interpreter: Interpreter? = null
    private val initError = StringBuilder()

    @Volatile var isAvailable: Boolean = false
        protected set

    /** Resolved square input size (from the graph). */
    @Volatile var inputSize: Int = defaultInput
        protected set

    /** Input tensor channel count (3 = RGB). */
    @Volatile var inputChannels: Int = 3
        protected set

    /** Resolved mask output width / height. */
    @Volatile var outputWidth: Int = 0
        protected set

    @Volatile var outputHeight: Int = 0
        protected set

    /** Output tensor channel count (1 = single mask, 2 = [bg, fg]). */
    @Volatile var outputChannels: Int = 1
        protected set

    @Volatile var outputLayout: Layout = Layout.NHWC
        protected set

    val modelName: String get() = assetName
    val initErrorLabel: String get() = initError.toString()

    init {
        var interp: Interpreter? = null
        try {
            val model = loadModelFile(context, assetName)
            val options = Interpreter.Options().apply {
                numThreads = 4
                setUseXNNPACK(true)
            }
            interp = Interpreter(model, options)
            val inShape = interp.getInputTensor(0).shape()
            if (inShape.size == 4 && inShape[1] > 0 && inShape[2] > 0 && inShape[1] == inShape[2]) {
                inputSize = inShape[1]
            }
            if (inShape.size == 4) inputChannels = inShape[3]
            val outShape = interp.getOutputTensor(0).shape()
            when {
                // NCHW: [1, C, H, W]
                outShape.size == 4 && outShape[1] > 0 && outShape[2] == outShape[3] && outShape[2] > 0 -> {
                    outputLayout = Layout.NCHW
                    outputHeight = outShape[2]; outputWidth = outShape[3]
                    outputChannels = outShape[1]
                }
                // NHWC: [1, H, W, C]
                outShape.size == 4 && outShape[3] > 0 -> {
                    outputLayout = Layout.NHWC
                    outputHeight = outShape[1]; outputWidth = outShape[2]
                    outputChannels = outShape[3]
                }
                // Rank-3 single channel: [1, H, W]
                outShape.size == 3 && outShape[1] > 0 && outShape[2] > 0 -> {
                    outputHeight = outShape[1]; outputWidth = outShape[2]
                    outputChannels = 1
                }
            }
            Log.i(TAG, "$assetName in=$inputSize ch=$inputChannels out=${outputWidth}x${outputHeight} c=$outputChannels $outputLayout")
        } catch (e: Exception) {
            // Flagged: missing asset is an expected state until the .tflite is dropped in.
            initError.append("seg unavailable ($assetName): ").append(e.message)
            Log.w(TAG, initError.toString())
        }
        interpreter = interp
        isAvailable = interp != null
    }

    /** Build the input tensor for one [inputSize]x[inputSize] RGB frame (0..255). */
    protected abstract fun preprocess(src: ByteArray, n: Int): ByteBuffer

    /** Decode the flat float output into a binary mask: 255 = subject, 0 = background. */
    protected abstract fun decode(out: FloatArray, count: Int): ByteArray

    /**
     * One-shot mask inference. [rgb] is interleaved RGB 0..255, [inputSize]x[inputSize].
     * Returns a binary mask sized [outputWidth]x[outputHeight] (one byte per pixel).
     */
    fun run(rgb: ByteBuffer): ByteBuffer {
        val it = interpreter ?: throw IllegalStateException("segmenter unavailable: $assetName")
        val n = inputSize * inputSize
        rgb.rewind()
        val src = ByteArray(n * 3)
        rgb.get(src); rgb.rewind()
        val input = preprocess(src, n)
        val outSize = outputWidth * outputHeight * outputChannels
        val outBuf = ByteBuffer.allocateDirect(outSize * 4).order(ByteOrder.nativeOrder())
        it.run(input, outBuf)
        outBuf.rewind()
        val fb = outBuf.asFloatBuffer()
        val floats = FloatArray(outSize)
        fb.get(floats)
        return ByteBuffer.wrap(decode(floats, n))
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        return ModelFileLoader.load(context, modelPath)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isAvailable = false
    }
}
