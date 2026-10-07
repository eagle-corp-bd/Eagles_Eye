package com.eagleseye.camera

import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        enableEdgeToEdge()
        // Immersive camera UI — bars appear transiently on swipe
        val wic = WindowInsetsControllerCompat(window, window.decorView)
        wic.hide(WindowInsetsCompat.Type.systemBars())
        wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        StampLcd.load(applicationContext)
        AppAccent.apply(AppSettings.accentIndex(this))
        setContent { CameraApp() }
    }

    // Writes any fatal exception to filesDir/ee_crash.log (device: run-as or
    // DDMS file explorer) so crash traces survive even when no adb session runs.
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val file = File(filesDir, "ee_crash.log")
                file.appendText(
                    "\n=== ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} " +
                        "thread=${thread.name} ===\n$sw"
                )
                Log.e("EaglesEye", "CRASH ${thread.name}", throwable)
            } catch (ignored: Throwable) {}
            prev?.uncaughtException(thread, throwable)
        }
    }
}