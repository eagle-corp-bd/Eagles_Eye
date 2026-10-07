package com.eagleseye.camera.engine

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Mmap loader for .tflite model assets with a compressed-asset fallback.
 *
 * Preferred path: [android.content.res.AssetManager.openFd] + file-channel mmap —
 * zero-copy, works only when aapt2 stored the asset UNCOMPRESSED in the APK
 * (see `androidResources { noCompress += "tflite" }` in app/build.gradle.kts).
 *
 * If the asset is compressed (custom aapt2 builds, AGP misconfiguration, or a
 * stale device), openFd throws — we then copy the asset to the cache dir and
 * mmap the copied file so the model still loads. This is the difference between
 * "plain camera forever" and a working depth/bokeh pipeline.
 */
object ModelFileLoader {
    fun load(context: Context, assetName: String): MappedByteBuffer {
        return try {
            val afd = context.assets.openFd(assetName)
            FileInputStream(afd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
            )
        } catch (e: IOException) {
            val f = File(context.cacheDir, "ee_model_$assetName")
            if (!f.exists() || f.length() <= 0L) {
                context.assets.open(assetName).use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                }
            }
            FileInputStream(f).channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
        }
    }
}