package com.eagleseye.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

object LensManager {
    private var ultraWideId: String? = null
    private var mainId: String? = null
    private var discovered = false

    var evStep: Float = 0.333333f; private set
    var minISO: Int = 100; private set
    var maxISO: Int = 3200; private set
    var supportsManualSensor: Boolean = false; private set
    var supportsManualWB: Boolean = false; private set
    var minFocusDiopters: Float = 0f; private set

    fun discover(context: Context) { try {
        if (discovered) return
        discovered = true
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        data class CamEntry(val id: String, val focalLen: Float)
        val backs = mutableListOf<CamEntry>()
        for (id in mgr.cameraIdList) {
            val chars = mgr.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: continue
            backs.add(CamEntry(id, fl))
        }
        backs.sortBy { it.focalLen }
        when {
            backs.size >= 2 && backs[0].focalLen / backs[1].focalLen < 0.82f -> { ultraWideId = backs[0].id; mainId = backs[1].id }
            backs.isNotEmpty() -> mainId = backs[0].id
        }
        mainId?.let { mid ->
            val chars = mgr.getCameraCharacteristics(mid)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            supportsManualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            supportsManualWB = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { minISO = it.lower; maxISO = it.upper.coerceAtMost(6400) }
            chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()?.let { evStep = it }
            chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let { minFocusDiopters = it }
        }
        } catch(e:Exception){Log.e("LensManager","discover: ${e.message}")};Log.d("LensManager", "main=$mainId uw=$ultraWideId manualSensor=$supportsManualSensor manualWB=$supportsManualWB ISO=$minISO-$maxISO evStep=$evStep")
    }

    fun hasUltraWide() = ultraWideId != null
    fun getUltraWideId() = ultraWideId
    fun getMainId() = mainId
}
