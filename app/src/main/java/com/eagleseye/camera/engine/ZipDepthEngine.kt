package com.eagleseye.camera.engine

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

    class ZipDepthEngine(context: Context, modelPath: String = "zipdepth_384x384.tflite", useGpu: Boolean = false) : DepthModel {

    companion object {
        const val TAG = "ZipDepthEngine"
        const val INPUT_SIZE = 384
        const val ABSOLUTE_MAX_DEPTH = 10.0f
    }

    private var runningMin = 0.0f
    private var runningMax = 1.0f
    private val alpha = 0.15f

    override val lastFrameMin: Float get() = runningMin
    override val lastFrameMax: Float get() = runningMax

    private val interpreter: Interpreter?
    val isInputFloat: Boolean
    override val isAvailable: Boolean
    override val inputSize = INPUT_SIZE
    val outputSize: Int
    override val outputWidth: Int
    override val outputHeight: Int

    init {
        var interp: Interpreter? = null
        var inFloat = false
        var oW = INPUT_SIZE; var oH = INPUT_SIZE
        try {
            val model = loadModelFile(context, modelPath)
            val options = Interpreter.Options().apply {
                if (useGpu) {
                    // Try GPU delegate first (much faster on Adreno)
                    try {
                        val gpuOpts = GpuDelegate.Options().apply {
                            setPrecisionLossAllowed(true) // FP16
                        }
                        val gpu = GpuDelegate(gpuOpts)
                        addDelegate(gpu)
                        Log.i(TAG, "GPU delegate added")
                    } catch (e: Throwable) {
                        Log.w(TAG, "GPU delegate failed, falling back to CPU: ${e.message}")
                        numThreads = 2
                        setUseXNNPACK(true)
                    }
                } else {
                    numThreads = 2
                    setUseXNNPACK(true)
                    Log.i(TAG, "CPU-only interpreter (XNNPACK) — keeps the GPU free for the live preview")
                }
            }
            interp = Interpreter(model, options)
            inFloat = interp.getInputTensor(0).dataType() == org.tensorflow.lite.DataType.FLOAT32
            val outShape = interp.getOutputTensor(0).shape()
            Log.i(TAG, "Loaded inFloat=$inFloat outputShape=${outShape.contentToString()}")
            if (outShape.size == 4 && outShape[1] > 0 && outShape[2] > 0) {
                oH = outShape[1]; oW = outShape[2]
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Model failed: $modelPath", e)
        }
        interpreter = interp
        isAvailable = interp != null
        isInputFloat = inFloat
        outputSize = oW * oH
        outputWidth = oW
        outputHeight = oH
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
        val output = ByteBuffer.allocateDirect(needFloats * 4).order(ByteOrder.nativeOrder())
        interp.run(input, output)
        output.rewind()

        val result = ByteBuffer.allocateDirect(needFloats * 4).order(ByteOrder.nativeOrder())
        result.put(output); result.rewind(); output.rewind()

        result.rewind()
        var frameMin = Float.MAX_VALUE; var frameMax = -Float.MAX_VALUE
        for (i in 0 until needFloats) { val v = result.getFloat(); if (v < frameMin) frameMin = v; if (v > frameMax) frameMax = v }

        if (frameMax > frameMin + 0.1f) {
            runningMin = runningMin * (1f - alpha) + frameMin * alpha
            runningMax = runningMax * (1f - alpha) + frameMax * alpha
        }
        runningMin = runningMin.coerceIn(0.01f, 5.0f)
        runningMax = runningMax.coerceIn(0.1f, ABSOLUTE_MAX_DEPTH)

        val normalized = ByteBuffer.allocateDirect(needFloats * 4).order(ByteOrder.nativeOrder())
        result.rewind()
        val invRange = 1f / (runningMax - runningMin).coerceAtLeast(0.05f)
        for (i in 0 until needFloats) {
            val v = result.getFloat()
            normalized.putFloat(((v - runningMin) * invRange).coerceIn(0f, 1f))
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
