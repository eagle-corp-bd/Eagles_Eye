package com.eagleseye.camera

import android.Manifest
import android.content.ContentValues
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import com.daasuu.camerarecorder.CameraRecorder
import com.daasuu.camerarecorder.CameraRecorderBuilder
import com.daasuu.camerarecorder.LensFacing
import com.daasuu.camerarecorder.egl.filter.GlFilter
import java.io.File

/**
 * MIT integration: MasayukiSuda/CameraRecorder-android (com.github.MasayukiSuda:CameraRecorder-android:0.1.5).
 * Clean OpenGL live camera preview + GL-filter video recording, layered alongside
 * our depth/bokeh photo pipeline (which is untouched). Saves EaglesEye_live_*.mp4.
 */
class LiveGLPreviewActivity : ComponentActivity() {
    private var glView: GLSurfaceView? = null
    private var cameraRecorder: CameraRecorder? = null
    private var lensFacing = LensFacing.BACK
    private var recording = false
    private var outFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_gl)

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            1
        )

        val wrap = findViewById<FrameLayout>(R.id.wrap_view)
        glView = GLSurfaceView(this).also { wrap.addView(it) }
        buildRecorder()

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnRecord).setOnClickListener {
            val btn = it as Button
            if (!recording) {
                outFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "EaglesEye_live_${System.currentTimeMillis()}.mp4")
                cameraRecorder?.start(outFile!!.absolutePath)
                recording = true
                btn.text = "Stop"
            } else {
                cameraRecorder?.stop()
                recording = false
                btn.text = "Record"
                outFile?.let { f -> importToGallery(f) }
                Toast.makeText(this, "Saved to gallery", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSwitch).setOnClickListener {
            lensFacing = if (lensFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
            rebuild()
        }

        findViewById<Button>(R.id.btnFlash).setOnClickListener { cameraRecorder?.switchFlashMode() }
        findViewById<Button>(R.id.btnFilter).setOnClickListener { cameraRecorder?.setFilter(GlFilter()) }
    }

    private fun buildRecorder() {
        cameraRecorder = CameraRecorderBuilder(this, glView).lensFacing(lensFacing).build()
    }

    private fun importToGallery(f: File) {
        runCatching {
            if (!f.exists()) return
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "EaglesEye_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/EaglesEye")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            f.delete()
        }
    }

    private fun rebuild() {
        cameraRecorder?.stop()
        cameraRecorder?.release()
        cameraRecorder = null
        glView?.onPause()
        buildRecorder()
        glView?.onResume()
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        cameraRecorder?.stop()
        cameraRecorder?.release()
        cameraRecorder = null
        glView?.onPause()
        glView?.let { (findViewById<FrameLayout>(R.id.wrap_view)).removeView(it) }
        glView = null
    }
}
