package com.eagleseye.camera.editor

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.eagleseye.camera.engine.GlPixel
import com.eagleseye.camera.engine.ShaderPass
import com.eagleseye.camera.engine.Shaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** One command in the editor chain. `name` is a pass id ("tune", "curves",
 *  "select", "vignette", "grain", "sharpen", "tilt", "streak", "blend",
 *  "frame") or any camera effect name ("vhs", "dust", "duotone", …).
 *  `params` keys follow the camera `effectParams` convention: camelCase names
 *  WITHOUT the leading "u" (e.g. "intensity", "tracking"). */
class EditorOp(val name: String, val params: MutableMap<String, Float> = mutableMapOf(), var overlay: Bitmap? = null) {
    fun p(k: String, v: Float): EditorOp { params[k] = v; return this }
    fun copy() = EditorOp(name, params.toMutableMap(), null)
}
