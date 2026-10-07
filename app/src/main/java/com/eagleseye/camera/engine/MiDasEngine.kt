package com.eagleseye.camera.engine

import android.content.Context
import android.util.Half
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * MiDaS v2.1 small (midas_small_256_fp16.tflite).
 *
 * Input : 1×256×256×3 float32, ImageNet-normalized RGB (mean=[0.485,0.456,0.406], std=[0.229,0.224,0.225]).
 * Output: 1×256×256 UNBOUNDED inverse depth (near = high). It has no fixed [0,1] range —
 * typical scenes produce values from ~0.5 up to 10+ for close objects. The range must be
 * tracked adaptively and NEVER hard-capped, or everything close to the cap normalizes to
 * ~1.0 and the depth view saturates to a single color.
 */
    class MiDasEngine(context: Context, modelPath: String = "midas_small_256_fp16.tflite", useGpu: Boolean = false) : DepthModel {

    companion object {
        const val TAG = "MiDasEngine"
        const val INPUT_SIZE = 256
    }

    private val appCtx = context.applicationContext
    private val modelFile = modelPath

    // EMA of robust (1%..99%) frame range — no absolute clamp
    private var runningMin = 0.0f
    private var runningMax = 1.0f
    private var rangeInitialized = false
    private val alpha = 0.15f

    override val lastFrameMin: Float get() = runningMin
    override val lastFrameMax: Float get() = runningMax

    @Volatile private var interpreter: Interpreter? = null
    var isInputFloat = false
        private set
    override val isAvailable: Boolean get() = interpreter != null
    override val inputSize = INPUT_SIZE
    val outputSize: Int
    override val outputWidth: Int
    override val outputHeight: Int
    private var outFp16 = false

    // GPU delegate self-heal: if the fp16 model produces degenerate output on GPU
    // (constant/garbage), rebuild the interpreter on CPU and carry on.
    @Volatile private var rebuiltCpu = false
    private var degenerateFrames = 0

    init {
        var oW = INPUT_SIZE; var oH = INPUT_SIZE
        var size = INPUT_SIZE * INPUT_SIZE
        var fp16 = false
        var inFloat = false
        var interp: Interpreter? = null
        try {
            val model = loadModelFile(context, modelPath)
            interp = createInterpreter(model, useGpu = useGpu)
            interp?.let {
                inFloat = it.getInputTensor(0).dataType() == DataType.FLOAT32
                val outShape = it.getOutputTensor(0).shape()
                Log.i(TAG, "Loaded inFloat=$inFloat outputShape=${outShape.contentToString()}")
                if (outShape.size == 4 && outShape[1] > 0 && outShape[2] > 0) {
                    oH = outShape[1]; oW = outShape[2]
                }
                if (outShape.size >= 2) {
                    val h = outShape[outShape.size - 2]; val w = outShape[outShape.size - 1]
                    if (h > 0 && w > 0) size = h * w
                }
                fp16 = it.getOutputTensor(0).dataType().byteSize() == 2
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Model failed: $modelPath", e)
        }
        interpreter = interp
        isInputFloat = inFloat
        outFp16 = fp16
        outputSize = size
        outputWidth = oW
        outputHeight = oH
    }

    private fun createInterpreter(model: MappedByteBuffer, useGpu: Boolean): Interpreter? {
        // Try GPU delegate first; if the delegate can't build a working interpreter on
        // this device, FALL BACK to a CPU interpreter (do NOT drop the model entirely).
        // The previous version wrapped the whole thing in one try/catch, so a GPU
        // failure left MiDaS unavailable and the live bokeh showed plain camera.
        if (useGpu) {
            try {
                val opts = Interpreter.Options().apply {
                    addDelegate(GpuDelegate(GpuDelegate.Options().setPrecisionLossAllowed(true)))
                    numThreads = 2
                }
                Log.i(TAG, "GPU delegate added")
                return Interpreter(model, opts)
            } catch (e: Throwable) {
                Log.w(TAG, "GPU delegate failed, falling back to CPU: ${e.message}")
            }
        }
        return try {
            val opts = Interpreter.Options().apply {
                numThreads = 2
                setUseXNNPACK(true)
            }
            Log.i(TAG, "CPU-only interpreter (XNNPACK)")
            Interpreter(model, opts)
        } catch (e: Throwable) {
            Log.e(TAG, "CPU interpreter create failed", e)
            null
        }
    }

    private fun rebuildCpuOnly(model: MappedByteBuffer) {
        try {
            interpreter?.close()
            interpreter = createInterpreter(model, useGpu = false)
            rebuiltCpu = true
            Log.w(TAG, "Rebuilt interpreter without GPU delegate")
        } catch (e: Throwable) {
            Log.e(TAG, "CPU rebuild failed", e)
        }
    }

    override fun run(rgbInput: ByteBuffer): ByteBuffer {
        val interp = interpreter ?: return emptyDepth()
        rgbInput.rewind()
        val src = ByteArray(INPUT_SIZE * INPUT_SIZE * 3)
        rgbInput.get(src); rgbInput.rewind()

        val input: ByteBuffer = if (isInputFloat) {
            val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
            val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
            val std  = floatArrayOf(0.229f, 0.224f, 0.225f)
            for (i in 0 until INPUT_SIZE * INPUT_SIZE) {
                val r = src[i*3].toInt() and 0xFF; val g = src[i*3+1].toInt() and 0xFF; val b = src[i*3+2].toInt() and 0xFF
                buf.putFloat((r / 255f - mean[0]) / std[0])
                buf.putFloat((g / 255f - mean[1]) / std[1])
                buf.putFloat((b / 255f - mean[2]) / std[2])
            }
            buf.rewind(); buf
        } else {
            ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3).order(ByteOrder.nativeOrder()).apply {
                put(src); rewind()
            }
        }

        val needFloats = outputSize
        val result = ByteBuffer.allocateDirect(needFloats * 4).order(ByteOrder.nativeOrder())
        if (outFp16) {
            val raw = ByteBuffer.allocateDirect(needFloats * 2).order(ByteOrder.nativeOrder())
            interp.run(input, raw)
            raw.rewind()
            var i = 0
            while (raw.hasRemaining()) result.putFloat(Half.toFloat(raw.short))
        } else {
            val output = ByteBuffer.allocateDirect(needFloats * 4).order(ByteOrder.nativeOrder())
            interp.run(input, output)
            output.rewind()
            result.put(output)
        }
        result.rewind()

        // ── Robust 1%..99% percentile range (outlier-proof) ──
        var fMin = Float.MAX_VALUE; var fMax = -Float.MAX_VALUE
        var badCount = 0
        result.rewind()
        for (i in 0 until needFloats) {
            val v = result.getFloat()
            if (v.isNaN() || v.isInfinite()) { badCount++; continue }
            if (v < fMin) fMin = v
            if (v > fMax) fMax = v
        }
        val rawSpread = if (fMax >= fMin) fMax - fMin else 0f

        // GPU self-heal: degenerate constant output for several frames → CPU rebuild
        if (!rebuiltCpu && (rawSpread.isNaN() || rawSpread < 0.02f)) {
            if (++degenerateFrames > 5) {
                degenerateFrames = 0
                try {
                    val m = loadModelFile(appCtx, modelFile)
                    rebuildCpuOnly(m)
                } catch (e: Throwable) {
                    Log.w(TAG, "rebuild load failed", e)
                }
                return emptyDepth()
            }
        } else {
            degenerateFrames = 0
        }

        var rbMin = fMin; var rbMax = fMax
        if (badCount == 0 && rawSpread.isFinite() && rawSpread > 0.001f && needFloats > 0) {
            val bins = IntArray(512)
            result.rewind()
            for (i in 0 until needFloats) {
                val v = result.getFloat()
                if (v.isNaN() || v.isInfinite()) continue
                val bi = ((v - fMin) / rawSpread * (bins.size - 1)).toInt().coerceIn(0, bins.size - 1)
                bins[bi]++
            }
            val valid = (needFloats - badCount).toFloat()
            var acc = 0
            for (b in 0 until bins.size) {
                acc += bins[b]
                if (acc >= valid * 0.01f) { rbMin = fMin + rawSpread * b / (bins.size - 1); break }
            }
            acc = 0
            for (b in 0 until bins.size) {
                acc += bins[b]
                if (acc >= valid * 0.99f) { rbMax = fMin + rawSpread * b / (bins.size - 1); break }
            }
            if (rbMax <= rbMin + 0.05f) { rbMin = fMin; rbMax = fMax }
        }

        if (badCount == 0 && rbMax > rbMin + 0.05f && rbMax.isFinite()) {
            if (!rangeInitialized) {
                runningMin = rbMin; runningMax = rbMax; rangeInitialized = true
            } else {
                runningMin = runningMin * (1f - alpha) + rbMin * alpha
                runningMax = runningMax * (1f - alpha) + rbMax * alpha
            }
        }
        if (runningMax <= runningMin + 0.05f) runningMax = runningMin + 0.05f
        if (runningMin.isNaN() || runningMax.isNaN() || runningMin.isInfinite() || runningMax.isInfinite()) {
            runningMin = 0.0f; runningMax = 1.0f
        }

        val invRange = 1f / (runningMax - runningMin).coerceAtLeast(0.05f)
        val normalized = ByteBuffer.allocateDirect(needFloats * 4).order(ByteOrder.nativeOrder())
        result.rewind()
        for (i in 0 until needFloats) {
            val v = result.getFloat()
            val n = if (v.isNaN() || v.isInfinite()) 0.5f else ((v - runningMin) * invRange).coerceIn(0f, 1f)
            normalized.putFloat(n)
        }
        normalized.rewind()
        return normalized
    }

    private fun emptyDepth(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())
        while (buf.hasRemaining()) buf.put(0.toByte())
        buf.rewind(); return buf
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        return ModelFileLoader.load(context, modelPath)
    }

    fun close() { interpreter?.close() }
}
