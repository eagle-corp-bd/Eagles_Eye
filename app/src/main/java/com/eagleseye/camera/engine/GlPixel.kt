package com.eagleseye.camera.engine

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unambiguous ARGB_8888 <-> tightly-packed RGBA byte transfer for OpenGL.
 *
 * Android [Bitmap.Config.ARGB_8888] is NOT guaranteed to be laid out as RGBA in
 * memory — on little-endian it is stored as BGRA, and the exact convention used by
 * [Bitmap.copyPixelsToBuffer] / [Bitmap.copyPixelsFromBuffer] is platform/version
 * dependent. Uploading such a buffer directly to `glTexImage2D(..., GL_RGBA, ...)`
 * therefore produces a red/blue channel swap on some devices, and reading a GL
 * framebuffer back the same way swaps it again. The net effect on affected devices
 * is the "magenta / RGB hue shift + multi-coloured static" corruption reported in
 * the editor and gallery.
 *
 * Going through [Bitmap.getPixels] / [Bitmap.setPixels] (which use the defined
 * 0xAARRGGBB integer order) and packing R,G,B,A explicitly removes all ambiguity.
 */
object GlPixel {

    fun toRgbaBuffer(bmp: Bitmap): ByteBuffer {
        val src = if (bmp.config == Bitmap.Config.HARDWARE) {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } else bmp
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (i in px.indices) {
            val c = px[i]
            buf.put(((c shr 16) and 0xFF).toByte()) // R
            buf.put(((c shr 8) and 0xFF).toByte())  // G
            buf.put((c and 0xFF).toByte())          // B
            buf.put(((c shr 24) and 0xFF).toByte()) // A
        }
        buf.rewind()
        return buf
    }

    fun fromRgbaBuffer(buf: ByteBuffer, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        buf.rewind()
        for (i in px.indices) {
            val r = buf.get().toInt() and 0xFF
            val g = buf.get().toInt() and 0xFF
            val b = buf.get().toInt() and 0xFF
            val a = buf.get().toInt() and 0xFF
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}
