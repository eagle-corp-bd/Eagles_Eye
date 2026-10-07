package com.eagleseye.camera.relight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.eagleseye.camera.AppAccent

private val LIGHT_COLORS = listOf(
    Color(0xFFFFF2DF), // warm tungsten
    Color(0xFFFFC561), // gold
    Color(0xFF9BD0FF), // cool daylight
    Color(0xFFFF9FE3)  // magenta stage light
)

/** RGB in 0..1 (uniform format) for a Compose color. */
fun Color.rgb01(): Triple<Float, Float, Float> {
    val r = red.coerceIn(0f, 1f)
    val g = green.coerceIn(0f, 1f)
    val b = blue.coerceIn(0f, 1f)
    return Triple(r, g, b)
}

/**
 * Live relight surface: draws the camera frame through the AGSL RuntimeShader
 * (ShaderBrush pattern), overlays the draggable light orb, and hosts the
 * intensity / color / debug controls. Falls back to the plain frame when AGSL
 * is unavailable (Android < 13) or the inputs haven't arrived yet.
 */
@Composable
fun RelightSurface(
    relight: RelightEngine?,
    frame: Bitmap?,
    depth: Bitmap?,
    lightUv: MutableState<Offset>,
    intensity: MutableState<Float>,
    lightColor: MutableState<Color>,
    debugMode: MutableState<Int>,
    onDragging: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val shader = relight?.shader
    Box(modifier.clipToBounds().onSizeChanged { relight?.setSurfaceSize(it.width, it.height) }) {
        if (shader == null || frame == null || depth == null) {
            if (frame != null) {
                Image(bitmap = frame.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            val hint = when {
                shader == null && relight != null && relight.failReason != null ->
                    relight.failReason ?: "RELIGHT needs Android 13+ (AGSL)"
                shader == null -> "RELIGHT needs Android 13+ (AGSL)"
                depth == null -> "AWAITING DEPTH MATTE — enable BOKEH/DEPTH VIEW"
                else -> null
            }
            if (hint != null) {
                Text(
                    hint,
                    color = Color.White.copy(0.85f),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.Center)
                        .background(Color.Black.copy(0.55f), RoundedCornerShape(10.dp))
                        .border(1.dp, AppAccent.color.copy(0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        } else {
            // AGSL is drawn through a hardware-accelerated View (Paint.shader →
            // canvas.drawRect), NOT a Compose ShaderBrush: Compose's brush path can
            // drop the RuntimeShader's `uniform shader` (frame/depth) bindings on
            // some GPUs, which makes the shader sample empty inputs → BLACK screen.
            // A real View preserves setInputShader bindings, so every device that
            // can run AGSL at all renders the relight (matching the Google AGSL
            // sample's canvas.drawRect path).
            val (r, g, b) = lightColor.value.rgb01()
            AndroidView(
                factory = { ctx -> AgslRelightView(ctx).apply { this.shader = shader } },
                update = { sv ->
                    sv.shader = shader
                    val f = frame
                    val d = depth
                    if (f != null) relight?.setFrame(f)
                    if (d != null) relight?.setDepth(d)
                    relight?.setIntensity(intensity.value)
                    relight?.setLightColor(r, g, b)
                    relight?.setLight(lightUv.value.x, lightUv.value.y)
                    relight?.setDebug(debugMode.value)
                    sv.invalidate()
                },
                modifier = Modifier.fillMaxSize()
            )
            if (debugMode.value == 0) {
                LightOrb(lightUv, lightColor.value, onDragging, Modifier.fillMaxSize())
            }
            RelightHud(intensity, lightColor, debugMode, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * Hardware view that paints the AGSL RuntimeShader over a full canvas. Must stay
 * hardware-accelerated (AGSL never runs on the software rasterizer); Compose hosts
 * it inside a hardwace layer by default and we force LAYER_TYPE_HARDWARE anyway.
 */
private class AgslRelightView(context: Context) : View(context) {
    var shader: RuntimeShader? = null
    private val paint = Paint()

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    override fun onDraw(canvas: Canvas) {
        val s = shader ?: return
        paint.shader = s
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}

/**
 * The virtual light source. Dragging anywhere on the surface moves the orb
 * (UV 0..1, uniform-updated immediately — no bitmap churn per drag frame).
 */
@Composable
private fun LightOrb(
    lightUv: MutableState<Offset>,
    color: Color,
    onDragging: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onDragging(true) },
                    onDragEnd = { onDragging(false) },
                    onDragCancel = { onDragging(false) },
                    onDrag = { change, _ ->
                        val s = size
                        if (s.width > 0 && s.height > 0) {
                            lightUv.value = Offset(
                                (change.position.x / s.width).coerceIn(0f, 1f),
                                (change.position.y / s.height).coerceIn(0f, 1f)
                            )
                        }
                        change.consume()
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val uv = lightUv.value
            val center = Offset(uv.x * size.width, uv.y * size.height)
            val coreR = 9.dp.toPx()
            val glowR = 56.dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.45f), Color.Transparent),
                    center = center,
                    radius = glowR
                ),
                radius = glowR,
                center = center
            )
            drawCircle(color = color.copy(alpha = 0.9f), radius = coreR, center = center)
            drawCircle(color = Color.White, radius = coreR * 0.45f, center = center)
        }
    }
}

/** Slider row + light color presets + debug view cycle. */
@Composable
private fun RelightHud(
    intensity: MutableState<Float>,
    lightColor: MutableState<Color>,
    debugMode: MutableState<Int>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Color.Black.copy(0.55f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("LIGHT", color = Color.White.copy(0.65f), fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.sp)
            Slider(
                value = intensity.value,
                onValueChange = { intensity.value = it },
                valueRange = 0f..2f,
                modifier = Modifier.width(120.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LIGHT_COLORS.forEach { c ->
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(c)
                        .border(
                            if (lightColor.value == c) 2.dp else 0.dp,
                            Color.White,
                            CircleShape
                        )
                        .clickable { lightColor.value = c }
                )
            }
            val labels = listOf("LIT", "NORM", "DEPTH")
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.12f))
                    .clickable { debugMode.value = (debugMode.value + 1) % 3 }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(labels[debugMode.value], color = AppAccent.hi, fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}