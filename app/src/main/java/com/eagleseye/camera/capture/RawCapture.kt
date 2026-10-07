package com.eagleseye.camera.capture

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// RAW/DNG capture via a dedicated Camera2 session (CameraX is unbound for the
// capture, then rebound by the caller).
object RawCapture {
    private const val TAG = "EaglesEyeRaw"

    fun findRawCameraId(ctx: Context): String? {
        return runCatching {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in mgr.cameraIdList) {
                val ch = mgr.getCameraCharacteristics(id)
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return id
            }
            null
        }.getOrNull()
    }

    fun isSupported(ctx: Context): Boolean = findRawCameraId(ctx) != null

    suspend fun capture(ctx: Context, camId: String, onRebind: () -> Unit): Uri? {
        return suspendCancellableCoroutine { cont ->
            val thread = HandlerThread("eagle-raw").apply { start() }
            val handler = Handler(thread.looper)
            val mainHandler = Handler(Looper.getMainLooper())
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var reader: ImageReader? = null
            var done = false
            var savedUri: Uri? = null
            var lastResult: TotalCaptureResult? = null
            val latch = CountDownLatch(1)

            fun finish(uri: Uri?) {
                if (done) return
                done = true
                runCatching { session?.close() }
                runCatching { reader?.close() }
                runCatching { device?.close() }
                thread.quitSafely()
                mainHandler.post {
                    onRebind()
                    if (cont.isActive) cont.resume(uri)
                }
            }

            handler.post {
                try {
                    val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val ch = mgr.getCameraCharacteristics(camId)
                    val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?: throw RuntimeException("no stream map")
                    val size = map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
                        .maxByOrNull { it.width * it.height } ?: throw RuntimeException("no raw size")
                    val rd = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.RAW_SENSOR, 2)
                    reader = rd
                    rd.setOnImageAvailableListener({ r ->
                        val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        runCatching {
                            val result = lastResult
                            if (result == null) {
                                image.close()
                                return@setOnImageAvailableListener
                            }
                            val cv = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, "EaglesEye_RAW_${System.currentTimeMillis()}.dng")
                                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/EaglesEye")
                            }
                            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                            if (uri != null) {
                                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                                    val dng = DngCreator(ch, result)
                                    dng.setOrientation(ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                                    dng.writeImage(os, image)
                                    dng.close()
                                } ?: throw RuntimeException("output open failed")
                                savedUri = uri
                            }
                            image.close()
                            latch.countDown()
                        }.onFailure {
                            runCatching { image.close() }
                            latch.countDown()
                        }
                    }, handler)

                    // CameraX unbind is asynchronous: retry while the device is still held
                    var opened = false
                    val deadline = SystemClock.elapsedRealtime() + 4_000
                    while (!opened && SystemClock.elapsedRealtime() < deadline) {
                        try {
                            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                                override fun onOpened(camera: CameraDevice) {
                                    device = camera
                                    val sc = SessionConfiguration(
                                        SessionConfiguration.SESSION_REGULAR,
                                        listOf(OutputConfiguration(rd.surface)),
                                        { r -> handler.post(r) },
                                        object : CameraCaptureSession.StateCallback() {
                                            override fun onConfigured(s: CameraCaptureSession) {
                                                session = s
                                                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                    addTarget(rd.surface)
                                                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                                                }.build()
                                                s.capture(req, object : CameraCaptureSession.CaptureCallback() {
                                                    override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                                        lastResult = result
                                                    }
                                                }, handler)
                                            }
                                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                                latch.countDown()
                                            }
                                        }
                                    )
                                    camera.createCaptureSession(sc)
                                }
                                override fun onDisconnected(camera: CameraDevice) { latch.countDown() }
                                override fun onError(camera: CameraDevice, error: Int) { latch.countDown() }
                            }, handler)
                            opened = true
                        } catch (e: CameraAccessException) {
                            if (e.reason != CameraAccessException.CAMERA_IN_USE && e.reason != CameraAccessException.MAX_CAMERAS_IN_USE) throw e
                            Thread.sleep(80)
                        }
                    }
                    if (!opened) throw RuntimeException("camera busy")

                    // Await completion off the handler thread: the image listener is posted
                    // to this same handler and would never run while it is blocked.
                    Thread {
                        runCatching { latch.await(10, TimeUnit.SECONDS) }
                        finish(savedUri)
                    }.start()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "capture: ${e.message}")
                    finish(null)
                }
            }

            cont.invokeOnCancellation { finish(null) }
        }
    }
}
