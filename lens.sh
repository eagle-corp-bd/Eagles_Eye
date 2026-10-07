#!/bin/bash
SRC=/storage/internal_new/project/EaglesEye/app/src/main/java/com/eagleseye/camera

# LensManager — discovers physical cameras by focal length
cat > $SRC/LensManager.kt << 'KOTLIN'
package com.eagleseye.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

data class DiscoveredLens(val cameraId: String, val focalLength: Float)

object LensManager {
    private var ultraWideId: String? = null
    private var mainId: String? = null
    private var discovered = false

    fun discover(context: Context) {
        if (discovered) return
        discovered = true
        try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val back = mutableListOf<DiscoveredLens>()

            for (id in mgr.cameraIdList) {
                try {
                    val chars = mgr.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                    val fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: continue
                    val min = fl.min()
                    back.add(DiscoveredLens(id, min))
                } catch (e: Exception) { continue }
            }

            back.sortBy { it.focalLength }
            Log.d("LensManager", "Back cameras found: $back")

            when {
                back.size >= 2 -> {
                    // If shortest focal length is less than 75% of next, it's a real ultra-wide
                    val shortest = back[0]; val next = back[1]
                    if (shortest.focalLength / next.focalLength < 0.82f) {
                        ultraWideId = shortest.cameraId
                        mainId = next.cameraId
                    } else {
                        mainId = shortest.cameraId
                    }
                    Log.d("LensManager", "UW=${ultraWideId} Main=${mainId}")
                }
                back.size == 1 -> mainId = back[0].cameraId
            }
        } catch (e: Exception) { Log.e("LensManager", "Discover failed: ${e.message}") }
    }

    fun hasUltraWide() = ultraWideId != null
    fun getUltraWideId() = ultraWideId
    fun getMainId() = mainId
}
KOTLIN

# Patch CameraScreen — add imports + lens logic via targeted seds
F=$SRC/CameraScreen.kt

# 1. Add Camera2 interop imports after existing camera imports
sed -i 's|import androidx.camera.video.VideoRecordEvent|import androidx.camera.video.VideoRecordEvent\nimport androidx.camera.camera2.interop.Camera2CameraInfo\nimport androidx.camera.camera2.interop.ExperimentalCamera2Interop|' $F

# 2. Add activeLens state after showAnalogSheet declaration
sed -i 's|var showAnalogSheet by remember { mutableStateOf(false) }|var showAnalogSheet by remember { mutableStateOf(false) }\n    var activeLens by remember { mutableStateOf(0) } // 0=main 1=ultrawide|' $F

# 3. Add LensManager.discover after the existing LaunchedEffect(selectedProfile)
sed -i 's|LaunchedEffect(selectedProfile) { userGrain|LaunchedEffect(Unit) { LensManager.discover(context) }\n    LaunchedEffect(selectedProfile) { userGrain|' $F

# 4. Add targetCameraId computed val before vfRatio
sed -i 's|val vfRatio = when (ratio)|val targetCameraId = when {\n        isFront -> null\n        activeLens == 1 \&\& LensManager.hasUltraWide() -> LensManager.getUltraWideId()\n        else -> LensManager.getMainId()\n    }\n    val vfRatio = when (ratio)|' $F

# 5. Add cameraId param to CameraPreview call
sed -i 's|CameraPreview(isFront=isFront,onCameraReady={cam,pv->cameraRef=cam;previewViewRef=pv}|CameraPreview(isFront=isFront,cameraId=targetCameraId,onCameraReady={cam,pv->cameraRef=cam;previewViewRef=pv}|' $F

# 6. Update zoom handler to switch lens on 0.5x
sed -i 's|ZoomPill(zoomRatio,{z->zoomRatio=z;cameraRef?.cameraControl?.setZoomRatio(z)}|ZoomPill(zoomRatio,{z->if(z<=0.6f\&\&LensManager.hasUltraWide()){activeLens=1;zoomRatio=0.5f}else{activeLens=0;zoomRatio=z;cameraRef?.cameraControl?.setZoomRatio(z)}}|' $F

# 7. Also update the pinch zoom to skip setZoomRatio when on ultra-wide
sed -i 's|detectTransformGestures{_,_,zoom,_->val cam=cameraRef?:return@detectTransformGestures;val nr=(zoomRatio\*zoom).coerceIn(1f,10f);cam.cameraControl.setZoomRatio(nr);zoomRatio=nr}|detectTransformGestures{_,_,zoom,_->if(activeLens==0){val cam=cameraRef?:return@detectTransformGestures;val nr=(zoomRatio*zoom).coerceIn(1f,10f);cam.cameraControl.setZoomRatio(nr);zoomRatio=nr}}|' $F

# 8. Add @OptIn and cameraId param to CameraPreview function definition
sed -i 's|@Composable\nfun CameraPreview(|@Composable\n@OptIn(ExperimentalCamera2Interop::class)\nfun CameraPreview(|' $F

# Actually the above multiline sed won't work — do it differently:
sed -i 's|fun CameraPreview(|@OptIn(ExperimentalCamera2Interop::class)\nfun CameraPreview(|' $F

# 9. Add cameraId param to CameraPreview signature
sed -i 's|fun CameraPreview(\n.*isFront:Boolean=false,|fun CameraPreview(cameraId:String?=null,isFront:Boolean=false,|' $F
# Single line version since kotlin is minified:
sed -i 's|fun CameraPreview(isFront:Boolean=false,|fun CameraPreview(cameraId:String?=null,isFront:Boolean=false,|' $F

# 10. Update LaunchedEffect in CameraPreview to include cameraId as key
sed -i 's|LaunchedEffect(isFront,previewView){|LaunchedEffect(isFront,cameraId,previewView){|' $F

# 11. Update camera selector inside LaunchedEffect to use cameraId when set
sed -i 's|val sel=if(isFront)CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA|val sel=when{cameraId!=null->CameraSelector.Builder().addCameraFilter{infos->infos.filter{Camera2CameraInfo.from(it).cameraId==cameraId}.ifEmpty{infos}}.build();isFront->CameraSelector.DEFAULT_FRONT_CAMERA;else->CameraSelector.DEFAULT_BACK_CAMERA}|' $F

echo "Done — verifying key changes:"
grep -n "ultraWide\|activeLens\|cameraId\|LensManager\|Camera2CameraInfo\|targetCamera" $F | head -30

