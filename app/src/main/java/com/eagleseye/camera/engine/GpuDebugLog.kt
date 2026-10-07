package com.eagleseye.camera.engine

import android.content.Context
import android.opengl.GLES30
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent GPU diagnostics for devices where Logcat is unavailable.
 * Writes to app-internal storage (filesDir/gpu_debug.log) and keeps a small
 * in-memory snapshot for the in-app debug panel.
 *
 * Safe to call from GL thread, render setup, and AGSL constructors.
 * Never throws; falls back to Logcat-only when [init] has not run.
 */
object GpuDebugLog {
    private const val TAG = "GpuDebug"
    private const val FILE_NAME = "gpu_debug.log"
    private const val MAX_BYTES = 256 * 1024L

    @Volatile private var file: File? = null
    private val lock = Any()
    private val onceKeys = ConcurrentHashMap.newKeySet<String>()
    private val tsFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // Snapshot for PipelineDebugPanel / UI
    @JvmStatic @Volatile var renderer: String = ""
    @JvmStatic @Volatile var glVersion: String = ""
    @JvmStatic @Volatile var glExtensions: String = ""
    @JvmStatic @Volatile var invalidPasses: List<String> = emptyList()
    @JvmStatic @Volatile var fboIssues: List<String> = emptyList()
    @JvmStatic @Volatile var agslFail: String? = null
    @JvmStatic @Volatile var lastError: String = ""
    /** First detailed shader compile/link failure — never overwritten by summaries. */
    @JvmStatic @Volatile var firstShaderError: String = ""
    @JvmStatic @Volatile var passSummary: String = ""

    @JvmStatic
    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            try {
                val f = File(context.filesDir, FILE_NAME)
                file = f
                if (f.length() > MAX_BYTES) f.writeText("")
                if (f.length() == 0L) {
                    f.appendText("${ts()} HEADER pid android app=com.eagleseye.camera\n")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "init failed", t)
            }
        }
    }

    @JvmStatic
    fun log(tag: String, msg: String) {
        Log.d(TAG, "[$tag] $msg")
        val line = "${ts()} [$tag] $msg\n"
        synchronized(lock) {
            val f = file ?: return
            try {
                if (f.length() > MAX_BYTES) f.writeText("${ts()} HEADER rotated\n")
                f.appendText(line)
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun logOnce(key: String, tag: String, msg: String) {
        if (onceKeys.add(key)) log(tag, msg)
    }

    @JvmStatic
    fun logError(tag: String, msg: String) {
        lastError = msg
        if (tag == "SHADER" && firstShaderError.isEmpty() &&
            !msg.startsWith("ShaderPass invalid") && !msg.startsWith("draw skipped")
        ) {
            firstShaderError = msg
        }
        log("ERR/$tag", msg)
    }

    /** Write GL_RENDERER / GL_VERSION / extension digest once (GL thread). */
    @JvmStatic
    fun captureGlStrings() {
        try {
            val r = GLES30.glGetString(GLES30.GL_RENDERER) ?: "?"
            val v = GLES30.glGetString(GLES30.GL_VERSION) ?: "?"
            val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
            val changed = renderer != r || glVersion != v
            renderer = r
            glVersion = v
            glExtensions = if (ext.length > 400) ext.substring(0, 400) + "…" else ext
            if (changed) {
                log("GL", "renderer=$r version=$v")
                log("GL", "extensions(len=${ext.length}) contains_OES_image_external_essl3=${ext.contains("GL_OES_EGL_image_external_essl3")}")
            }
        } catch (t: Throwable) {
            logError("GL", "glGetString failed: ${t.message}")
        }
    }

    /**
     * Check framebuffer completeness of whatever FBO is currently bound.
     * Returns true when complete; records and logs issues otherwise.
     */
    @JvmStatic
    fun checkFbo(label: String): Boolean {
        return try {
            val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            val ok = st == GLES30.GL_FRAMEBUFFER_COMPLETE
            if (!ok) {
                val msg = "$label status=0x${Integer.toHexString(st)}"
                val list = fboIssues
                if (!list.contains(msg)) fboIssues = (list + msg).takeLast(12)
                logError("FBO", "incomplete $msg")
            }
            ok
        } catch (t: Throwable) {
            logError("FBO", "$label check threw ${t.message}")
            false
        }
    }

    @JvmStatic
    fun agslFail(reason: String) {
        agslFail = reason
        logError("AGSL", reason)
    }

    @JvmStatic
    fun setPassSummary(summary: String, invalid: List<String>) {
        passSummary = summary
        invalidPasses = invalid
    }

    /** Single-line status for the debug panel. */
    @JvmStatic
    fun panelLine(): String {
        val sb = StringBuilder()
        sb.append(renderer.ifEmpty { "?" }).append(' ').append(glVersion.ifEmpty { "?" })
        if (invalidPasses.isNotEmpty()) sb.append(" | BAD:").append(invalidPasses.joinToString(","))
        if (fboIssues.isNotEmpty()) sb.append(" | FBO:").append(fboIssues.last())
        agslFail?.let { sb.append(" | AGSL:").append(it) }
        if (lastError.isNotEmpty() && invalidPasses.isEmpty() && fboIssues.isEmpty() && agslFail == null) {
            sb.append(" | ").append(lastError.take(80))
        }
        return sb.toString()
    }

    @JvmStatic
    fun panelDetail(): String {
        val parts = mutableListOf<String>()
        if (passSummary.isNotEmpty()) parts += passSummary
        if (invalidPasses.isNotEmpty()) parts += "invalid=" + invalidPasses.joinToString(",")
        if (fboIssues.isNotEmpty()) parts += "fbo=" + fboIssues.joinToString(" ; ")
        agslFail?.let { parts += "agsl=$it" }
        if (firstShaderError.isNotEmpty()) parts += "shader1=${firstShaderError.replace('\n', ' ').take(300)}"
        else if (lastError.isNotEmpty()) parts += "err=$lastError"
        return parts.joinToString(" | ")
    }

    @JvmStatic
    fun filePath(): String = file?.absolutePath ?: "(not initialized)"

    private fun ts(): String = tsFmt.format(Date())
}
