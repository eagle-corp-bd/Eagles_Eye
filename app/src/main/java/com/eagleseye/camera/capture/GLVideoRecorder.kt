package com.eagleseye.camera.capture

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Surface-input H.264 encoder + MP4 muxer. The live GL viewfinder renders each
 * frame into [inputSurface] (via a dedicated EGL context on the GL thread), so
 * the recorded video is byte-for-byte the processed preview (WYSIWYG).
 */
class GLVideoRecorder(
    private val context: Context,
    val width: Int,
    val height: Int,
    private val bitrate: Int = 24_000_000,
    private val fps: Int = 30
) {
    @Volatile var onFinished: ((Uri?) -> Unit)? = null

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var inputTrack = -1
    private var basePtsUs: Long = -1
    private var file: File? = null
    @Volatile var inputSurface: Surface? = null
    private var drainThread: Thread? = null
    @Volatile private var eosSent = false
    @Volatile private var released = false

    fun start(): Boolean {
        return try {
            val f = File(context.cacheDir, "ee_gl_${System.currentTimeMillis()}.mp4")
            file = f
            muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            mf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            mf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            mf.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            mf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            codec = c
            c.start()
            drainThread = Thread({ drainLoop() }, "ee-glvr-drain").also { it.start() }
            Log.i("EaglesEye", "GLVideoRecorder started ${width}x${height}@$fps")
            true
        } catch (e: Exception) {
            Log.e("EaglesEye", "GLVR start failed: ${e.message}")
            false
        }
    }

    /** Called on the GL/rendering thread once recording must end. */
    fun stop() {
        if (finished || released) return
        try { codec?.signalEndOfInputStream() } catch (e: Exception) {}
    }

    private var finished = false

    private fun drainLoop() {
        val c = codec ?: run { finish(null); return }
        val m = muxer
        if (m == null) { finish(null); return }
        val info = MediaCodec.BufferInfo()
        var sawEos = false
        try {
            while (!sawEos && !Thread.currentThread().isInterrupted) {
                var idx = c.dequeueOutputBuffer(info, 10_000)
                while (idx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEos = true
                    if (info.size > 0) {
                        if (!muxerStarted) {
                            inputTrack = m.addTrack(c.outputFormat)
                            m.start()
                            muxerStarted = true
                        }
                        if (basePtsUs < 0) basePtsUs = info.presentationTimeUs
                        val pts = info.presentationTimeUs - basePtsUs
                        m.writeSampleData(inputTrack, c.getOutputBuffer(idx)!!, info.apply { presentationTimeUs = pts })
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (sawEos) break
                    idx = c.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
            Log.e("EaglesEye", "GLVR drain: ${e.message}")
        }
        finish(if (muxerStarted) file else null)
    }

    private fun finish(muxFile: File?) {
        if (finished) return
        finished = true
        try {
            if (muxerStarted) muxer?.stop()
        } catch (e: Exception) { }
        try { muxer?.release() } catch (e: Exception) {}
        try { codec?.release() } catch (e: Exception) {}
        released = true
        val uri = importToGallery(muxFile)
        ContextCompat.getMainExecutor(context).execute { onFinished?.invoke(uri); onFinished = null }
    }

    private fun importToGallery(f: File?): Uri? {
        if (f == null || !f.exists()) return null
        return try {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "EaglesEye_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/EaglesEye")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
            f.delete()
            uri
        } catch (e: Exception) {
            Log.e("EaglesEye", "GLVR import: ${e.message}")
            null
        }
    }
}