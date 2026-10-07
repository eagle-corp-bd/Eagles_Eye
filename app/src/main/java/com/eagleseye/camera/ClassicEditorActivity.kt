package com.eagleseye.camera

import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.*
import androidx.activity.ComponentActivity
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoFilter
import java.io.File
import java.io.FileInputStream

/**
 * MIT integration: burhanrashid52/PhotoEditor (com.burhanrashid52:photoeditor:1.1.4).
 * 2D editing layer (text, brush, filters, undo) layered on top of our depth/bokeh
 * renders. Saves the result as an EaglesEye_edit_* image into the gallery.
 */
class ClassicEditorActivity : ComponentActivity() {
    private lateinit var photoEditor: PhotoEditor
    private var brushOn = false
    private var filterIdx = 0
    private val filters = arrayOf(
        PhotoFilter.NONE,
        PhotoFilter.BRIGHTNESS,
        PhotoFilter.CONTRAST,
        PhotoFilter.GRAY_SCALE,
        PhotoFilter.SEPIA,
        PhotoFilter.SATURATE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_classic_editor)

        val uri = intent.getParcelableExtra<Uri>("EXTRA_URI")
        if (uri == null) { finish(); return }

        val view = findViewById<ja.burhanrashid52.photoeditor.PhotoEditorView>(R.id.photoEditorView)
        val bmp = decodeForUri(this, uri, 2000)
        if (bmp == null) { Toast.makeText(this, "Cannot open image", Toast.LENGTH_SHORT).show(); finish(); return }
        view.source.setImageBitmap(bmp)

        photoEditor = PhotoEditor.Builder(this, view)
            .setPinchTextScalable(true)
            .build()

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnAddText).setOnClickListener {
            val t = findViewById<EditText>(R.id.etText).text.toString().ifBlank { "Text" }
            photoEditor.addText(t, Color.WHITE)
        }
        findViewById<Button>(R.id.btnBrush).setOnClickListener {
            brushOn = !brushOn
            photoEditor.setBrushDrawingMode(brushOn)
            photoEditor.brushSize = 25f
            photoEditor.brushColor = Color.RED
            Toast.makeText(this, if (brushOn) "Brush ON" else "Brush OFF", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnFilter).setOnClickListener {
            filterIdx = (filterIdx + 1) % filters.size
            photoEditor.setFilterEffect(filters[filterIdx])
            Toast.makeText(this, filters[filterIdx].name, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { photoEditor.undo() }
    }

    private fun save() {
        val tmp = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "classic_tmp.jpg")
        photoEditor.saveAsFile(tmp.absolutePath, object : PhotoEditor.OnSaveListener {
            override fun onSuccess(imagePath: String) {
                var saved = false
                runCatching {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "EaglesEye_edit_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/EaglesEye")
                    }
                    val out: Uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                        ?: error("insert failed")
                    contentResolver.openOutputStream(out)?.use { os ->
                        FileInputStream(imagePath).use { it.copyTo(os) }
                    } ?: error("no stream")
                    saved = true
                }
                runCatching { tmp.delete() }
                runOnUiThread {
                    if (saved) {
                        Toast.makeText(this@ClassicEditorActivity, "Saved to gallery", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        Toast.makeText(this@ClassicEditorActivity, "Save failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun onFailure(exception: Exception) {
                runOnUiThread { Toast.makeText(this@ClassicEditorActivity, "Save failed: ${exception.message}", Toast.LENGTH_SHORT).show() }
            }
        })
    }
}
