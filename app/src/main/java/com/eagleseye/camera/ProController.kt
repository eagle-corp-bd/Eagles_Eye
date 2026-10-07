package com.eagleseye.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import kotlin.math.roundToInt

private const val TAG = "ProController"

enum class ExposureMode {
    AUTO,
    SS_PRIORITY,
    ISO_PRIORITY,
    MANUAL
}

enum class WbPreset(val mode: Int) {
    AUTO(CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE(CameraMetadata.CONTROL_AWB_MODE_SHADE),
    TUNGSTEN(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT(CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
    TWILIGHT(CameraMetadata.CONTROL_AWB_MODE_TWILIGHT)
}

sealed class WbMode {
    data object Auto : WbMode()
    data class Preset(val preset: WbPreset) : WbMode()
    data class Custom(val temperature: Int, val tint: Int) : WbMode()
}

class ProController {
    private var camera: Camera? = null
    private var c2Control: Camera2CameraControl? = null

    // -- Inputs (set by UI) --
    @Volatile var exposureMode = ExposureMode.AUTO
    @Volatile var isoValue = 0  // 0 = auto, >0 = actual ISO
    @Volatile var ssNsValue = 0L  // 0 = auto, >0 = actual nanoseconds
    @Volatile var evValue = 0f  // -3..3
    @Volatile var wbMode: WbMode = WbMode.Auto
    @Volatile var focusDistance = -1f  // <0 = auto, >=0 = diopters

    // -- Live readings (from camera metadata) --
    @Volatile var liveISO = 200
    @Volatile var liveSSNs = 33_333_334L

    // -- Metered reference (used when entering priority modes) --
    @Volatile var meteredISO = 200
    @Volatile var meteredSSNs = 33_333_334L
    private var refLocked = false

    fun attach(cam: Camera) {
        camera = cam
        c2Control = Camera2CameraControl.from(cam.cameraControl)
        apply()
    }

    fun detach() {
        resetToAuto()
        camera = null
        c2Control = null
    }

    fun resetToAuto() {
        exposureMode = ExposureMode.AUTO
        isoValue = 0; ssNsValue = 0L; evValue = 0f
        wbMode = WbMode.Auto; focusDistance = -1f
        refLocked = false
        try {
            c2Control?.setCaptureRequestOptions(CaptureRequestOptions.Builder().build())
        } catch (e: Exception) {
            Log.e(TAG, "reset failed: ${e.message}")
        }
    }

    fun apply() {
        val c2 = c2Control ?: return
        try {
            val b = CaptureRequestOptions.Builder()

            when (exposureMode) {
                ExposureMode.AUTO -> {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    if (LensManager.evStep > 0.001f) {
                        val evIdx = (evValue / LensManager.evStep).roundToInt().coerceIn(-9, 9)
                        b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIdx)
                    }
                }

                ExposureMode.SS_PRIORITY -> {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    val ss = if (ssNsValue > 0L) ssNsValue.coerceIn(250_000L, 30_000_000_000L) else liveSSNs
                    val iso = if (isoValue > 0) isoValue.coerceIn(LensManager.minISO, LensManager.maxISO) else liveISO
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ss)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                }

                ExposureMode.ISO_PRIORITY -> {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    val iso = if (isoValue > 0) isoValue.coerceIn(LensManager.minISO, LensManager.maxISO) else liveISO
                    val ss = if (ssNsValue > 0L) ssNsValue.coerceIn(250_000L, 30_000_000_000L) else liveSSNs
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ss)
                }

                ExposureMode.MANUAL -> {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    val iso = isoValue.coerceIn(LensManager.minISO, LensManager.maxISO)
                    val ss = ssNsValue.coerceIn(250_000L, 30_000_000_000L)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ss)
                }
            }

            applyWb(b)
            applyFocus(b)
            c2.setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            Log.e(TAG, "apply error: ${e.message}")
        }
    }

    fun prepareForCapture() {
        if (exposureMode == ExposureMode.AUTO) {
            val c2 = c2Control ?: return
            try {
                val b = CaptureRequestOptions.Builder()
                b.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                applyWb(b); applyFocus(b)
                c2.setCaptureRequestOptions(b.build())
            } catch (e: Exception) {
                Log.e(TAG, "prepareForCapture: ${e.message}")
            }
        }
    }

    fun restoreAfterCapture() {
        refLocked = false
        if (exposureMode == ExposureMode.AUTO) {
            try {
                c2Control?.setCaptureRequestOptions(CaptureRequestOptions.Builder().build())
            } catch (e: Exception) {
                Log.e(TAG, "restoreAfterCapture: ${e.message}")
            }
        }
        apply()
    }

    fun onCaptureResult(iso: Int, ssNs: Long) {
        if (iso > 0) {
            liveISO = iso.coerceIn(LensManager.minISO, LensManager.maxISO)
            if (!refLocked) meteredISO = liveISO
        }
        if (ssNs > 0L) {
            liveSSNs = ssNs.coerceAtLeast(250_000L)
            if (!refLocked) meteredSSNs = liveSSNs
        }
    }

    fun applyWithMode(mode: ExposureMode) {
        if (mode == ExposureMode.SS_PRIORITY || mode == ExposureMode.ISO_PRIORITY) {
            meteredISO = liveISO
            meteredSSNs = liveSSNs
        }
        exposureMode = mode
        apply()
    }

    fun resetCompensationToMetered() {
        meteredISO = liveISO
        meteredSSNs = liveSSNs
    }

    private fun applyWb(b: CaptureRequestOptions.Builder) {
        try {
            when (wbMode) {
                is WbMode.Auto ->
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
                is WbMode.Preset ->
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, (wbMode as WbMode.Preset).preset.mode)
                is WbMode.Custom -> {
                    val c = wbMode as WbMode.Custom
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                    b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                    val temp = c.temperature.coerceIn(2000, 8000)
                    val t = c.tint.coerceIn(-100, 100) / 100f
                    val (rGain, bGain) = kelvinToRg(temp)
                    b.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS,
                        RggbChannelVector(
                            (rGain * (1f + t * 0.15f)).coerceIn(0.5f, 2.5f), 1f, 1f,
                            (bGain * (1f - t * 0.15f)).coerceIn(0.5f, 2.5f)))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyWb: ${e.message}")
        }
    }

    private fun kelvinToRg(k: Int): Pair<Float, Float> = kelvinGains(k)

    private fun applyFocus(b: CaptureRequestOptions.Builder) {
        try {
            if (focusDistance < 0f) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            } else {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE,
                    focusDistance.coerceIn(0.02f, maxFocusDiopters()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyFocus: ${e.message}")
        }
    }

    companion object {
        fun maxFocusDiopters(): Float =
            LensManager.minFocusDiopters.takeIf { it >= 0.05f } ?: 10f

        fun kelvinGains(k: Int): Pair<Float, Float> {
            val f = (k.coerceIn(2000, 8000) - 2000f) / 6000f
            return Pair((0.8f + f * 1.2f).coerceIn(0.5f, 2.5f), (2.3f - f * 1.8f).coerceIn(0.5f, 2.5f))
        }
    }
}
