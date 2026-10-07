package com.eagleseye.camera.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

// Real hyperlapse: intervalometer frames (2 fps) encoded into a smooth 24 fps H.264 clip.
class HyperlapseRecorder(
    private val context: Context,
    private val outWidth: Int,
    private val outHeight: Int,
    private val fps: Int = 24,
    private val captureRateFps: Int = 2
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private var muxerStarted = false
    private var frameIdx = 0L
    private var yuvBuf: ByteArray? = null
    private var pxBuf: IntArray? = null
    private var tmpFile: File? = null
    private val lock = Any()

    val frameTimesPerCapture = fps / captureRateFps

    private val yuvSize: Int get() = outWidth * outHeight * 3 / 2

    fun start(): Boolean {
        return runCatching {
            val f = File(context.cacheDir, "hyperlapse_${System.currentTimeMillis()}.mp4")
            val mux = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
                if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            val cc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            cc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            cc.start()
            codec = cc
            muxer = mux
            tmpFile = f
            yuvBuf = ByteArray(yuvSize)
            pxBuf = IntArray(outWidth * outHeight)
            true
        }.getOrElse { false }
    }

    private fun queueFrame(yuv: ByteArray, ptsUs: Long) {
        val c = codec ?: return
        while (true) {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: break
                buf.clear()
                val img = c.getInputImage(idx)
                if (img != null) {
                    val planes = img.planes
                    var src = 0
                    val yP = planes[0]; val uP = planes[1]; val vP = planes[2]
                    val ySize = outWidth * outHeight
                    for (row in 0 until outHeight) {
                        yP.buffer.position(yP.rowStride * row)
                        yP.buffer.put(yuv, src, outWidth); src += outWidth
                    }
                    val cw = outWidth / 2; val ch = outHeight / 2
                    for (row in 0 until ch) {
                        uP.buffer.position(uP.rowStride * row)
                        uP.buffer.put(yuv, src, cw); src += cw
                    }
                    for (row in 0 until ch) {
                        vP.buffer.position(vP.rowStride * row)
                        vP.buffer.put(yuv, src, cw); src += cw
                    }
                } else {
                    buf.put(yuv)
                }
                c.queueInputBuffer(idx, 0, yuv.size, ptsUs, 0)
                break
            }
        }
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = c.dequeueOutputBuffer(info, 0)
            if (outIdx >= 0) {
                if (info.size > 0) {
                    val data = c.getOutputBuffer(outIdx) ?: continue
                    if (muxerStarted && muxerTrack >= 0) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer?.writeSampleData(muxerTrack, data, info)
                    }
                }
                c.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            } else {
                return
            }
        }
    }

    // Feed one captured frame (repeated internally to reach target fps)
    fun feedFrame(bm: Bitmap) {
        synchronized(lock) {
            val yuv = yuvBuf ?: return
            val px = pxBuf ?: return
            val scaled = if (bm.width == outWidth && bm.height == outHeight) bm
            else Bitmap.createScaledBitmap(bm, outWidth, outHeight, true)
            rgbaToI420(scaled, yuv, px)
            if (scaled !== bm) scaled.recycle()
            if (!muxerStarted) {
                val m = muxer ?: return
                val c = codec ?: return
                m.start()
                muxerTrack = m.addTrack(c.outputFormat)
                muxerStarted = true
            }
            val stepUs = 1_000_000L / fps
            for (i in 0 until frameTimesPerCapture) {
                queueFrame(yuv, frameIdx * stepUs)
                frameIdx++
                drain()
            }
        }
    }

    fun finish(onDone: (Uri?) -> Unit) {
        synchronized(lock) {
        try {
            val c = codec ?: return
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                c.queueInputBuffer(idx, 0, 0, frameIdx * (1_000_000L / fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            val info = MediaCodec.BufferInfo()
            var done = false
            while (!done) {
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val data = c.getOutputBuffer(outIdx) ?: continue
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        if (muxerStarted && muxerTrack >= 0) muxer?.writeSampleData(muxerTrack, data, info)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            c.stop()
            c.release()
            if (muxerStarted) {
                muxer?.stop()
                muxer?.release()
            } else {
                muxer?.release()
            }
            codec = null
            muxer = null
            tmpFile?.let { f ->
                if (muxerStarted && frameIdx > 0) {
                    val uri = importToMediaStore(f)
                    onDone(uri)
                } else {
                    onDone(null)
                }
                f.delete()
            } ?: onDone(null)
        } catch (e: Exception) {
            android.util.Log.e("EaglesEye", "hyperlapse finish: ${e.message}")
            runCatching { codec?.release(); muxer?.release(); tmpFile?.delete() }
            onDone(null)
        }
        }
    }

    private fun importToMediaStore(f: File): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "EaglesEye_HL_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/EaglesEye")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { os -> f.inputStream().use { it.copyTo(os) } }
            uri
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    companion object {
        // RGBA -> I420 (Y plane, then U, then V). `px` is a caller-owned scratch buffer.
        fun rgbaToI420(src: Bitmap, out: ByteArray, px: IntArray) {
            val w = src.width; val h = src.height
            src.getPixels(px, 0, w, 0, 0, w, h)
            var yIdx = 0
            var uIdx = w * h
            var vIdx = w * h + w * h / 4
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val rgb = px[row + x]
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    val yv = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    out[yIdx++] = yv.toByte()
                    if (x % 2 == 0 && y % 2 == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        out[uIdx++] = u.toByte()
                        out[vIdx++] = v.toByte()
                    }
                }
            }
        }
    }
}
