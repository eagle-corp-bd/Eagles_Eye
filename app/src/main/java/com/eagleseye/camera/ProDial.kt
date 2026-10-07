package com.eagleseye.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val DIAL_GOLD: Color get() = AppAccent.color
private val DIAL_GOLD_HI: Color get() = AppAccent.hi
private val DIAL_BG = Color(0xFF0A0A0C)
private val DIAL_TRACK = Color(0xFF1E1E26)
private val DIAL_TICK = Color(0xFF3A3A46)
private val DIAL_TICK_MAJOR = Color(0xFF5A5A68)
private val DIAL_TEXT = Color(0xFFF0EDE8)
private val DIAL_MUTED = Color(0xFF8A8A96)
private val DIAL_FONT = FontFamily.Monospace

private const val DIAL_SWEEP = 280f
private const val DIAL_START = -140f
private const val DIAL_TICKS = 28

private fun angleOf(pos: Offset, size: IntSize): Float {
    val cx = size.width / 2f; val cy = size.height / 2f
    return Math.toDegrees(atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())).toFloat()
}

private fun shortestDelta(a: Float, b: Float): Float {
    var d = (a - b + 540f) % 360f - 180f
    if (d < -180f) d += 360f
    return d
}

private fun snapFrac(f: Float): Float = (f.coerceIn(0f, 1f) * (DIAL_TICKS - 1)).roundToInt() / (DIAL_TICKS - 1).toFloat()

@Composable
fun ProDial(
    fraction: Float,
    label: String,
    value: String,
    active: Boolean = true,
    onChange: (Float) -> Unit = {},
    onCommit: (Float) -> Unit = {},
    onDragStateChanged: (Boolean) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val anim = remember { Animatable(fraction.coerceIn(0f, 1f)) }
    val dragging = remember { mutableStateOf(false) }
    val dragFrac = remember { mutableFloatStateOf(fraction.coerceIn(0f, 1f)) }
    val scope = rememberCoroutineScope()
    val lastTick = remember { mutableIntStateOf(-1) }
    val lastAngle = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(fraction, dragging.value) {
        if (!dragging.value && abs(anim.value - fraction) > 0.003f) {
            anim.animateTo(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.72f, stiffness = 340f))
        }
    }

    fun tickFor(f: Float) = (f.coerceIn(0f, 1f) * (DIAL_TICKS - 1)).roundToInt()

    fun snapAndCommit(from: Float) {
        scope.launch {
            anim.snapTo(from)
            val target = snapFrac(from)
            anim.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 480f))
            onCommit(anim.value)
        }
    }

    Box(Modifier.size(182.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (active) 1f else 0.4f }
                .pointerInput(active) {
                    if (!active) return@pointerInput
                    detectDragGestures(
                        onDragStart = { pos ->
                            dragging.value = true
                            onDragStateChanged(true)
                            dragFrac.floatValue = anim.value
                            lastAngle.floatValue = angleOf(pos, size)
                            lastTick.intValue = tickFor(anim.value)
                        },
                        onDrag = { change, _ ->
                            val a = angleOf(change.position, size)
                            val d = shortestDelta(a, lastAngle.floatValue)
                            lastAngle.floatValue = a
                            val f = (dragFrac.floatValue + d / DIAL_SWEEP).coerceIn(0f, 1f)
                            dragFrac.floatValue = f
                            val t = tickFor(f)
                            if (t != lastTick.intValue) {
                                lastTick.intValue = t
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            onChange(f)
                        },
                        onDragEnd = {
                            dragging.value = false
                            onDragStateChanged(false)
                            snapAndCommit(dragFrac.floatValue)
                        },
                        onDragCancel = {
                            dragging.value = false
                            onDragStateChanged(false)
                            snapAndCommit(dragFrac.floatValue)
                        }
                    )
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = minOf(size.width, size.height) * 0.42f
            val ring = Offset(cx - r, cy - r)
            val ringSize = Size(r * 2f, r * 2f)
            val frac = if (dragging.value) dragFrac.floatValue else anim.value.coerceIn(0f, 1f)

            // Backing disc for legibility
            drawCircle(DIAL_BG.copy(alpha = 0.9f), radius = r * 0.78f, center = Offset(cx, cy))

            // Track
            drawArc(
                color = DIAL_TRACK,
                startAngle = DIAL_START,
                sweepAngle = DIAL_SWEEP,
                useCenter = false,
                topLeft = ring,
                size = ringSize,
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
            )

            // Progress arc
            if (frac > 0.002f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(DIAL_GOLD, DIAL_GOLD_HI, DIAL_GOLD),
                        center = Offset(cx, cy)
                    ),
                    startAngle = DIAL_START,
                    sweepAngle = DIAL_SWEEP * frac,
                    useCenter = false,
                    topLeft = ring,
                    size = ringSize,
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Ticks
            val tickIn = r - 6.dp.toPx()
            val tickOut = r - 16.dp.toPx()
            val tickOutMajor = r - 11.dp.toPx()
            for (i in 0..DIAL_TICKS) {
                val a = Math.toRadians((DIAL_START + DIAL_SWEEP * i / DIAL_TICKS).toDouble())
                val major = i % 4 == 0
                val rad = if (major) tickOutMajor else tickOut
                val inner = Offset(cx + tickIn * cos(a).toFloat(), cy + tickIn * sin(a).toFloat())
                val outer = Offset(cx + rad * cos(a).toFloat(), cy + rad * sin(a).toFloat())
                drawLine(
                    color = if (major) DIAL_TICK_MAJOR else DIAL_TICK,
                    start = outer,
                    end = inner,
                    strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Thumb
            val thumbA = Math.toRadians((DIAL_START + DIAL_SWEEP * frac).toDouble())
            val thumbR = r - 22.dp.toPx()
            val thumbPos = Offset(cx + thumbR * cos(thumbA).toFloat(), cy + thumbR * sin(thumbA).toFloat())
            drawCircle(DIAL_GOLD.copy(alpha = 0.22f), radius = 14.dp.toPx(), center = thumbPos)
            drawCircle(DIAL_GOLD_HI, radius = 5.5.dp.toPx(), center = thumbPos)
        }

        // Center readout: label + animated value
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                fontFamily = DIAL_FONT,
                fontSize = 10.sp,
                letterSpacing = 0.35.sp,
                color = DIAL_MUTED.copy(alpha = if (active) 1f else 0.5f)
            )
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (fadeIn(tween(140)) + slideInVertically(tween(140)) { it / 3 }) togetherWith
                        (fadeOut(tween(90)) + slideOutVertically(tween(90)) { -it / 3 })
                },
                label = "dialValue"
            ) { v ->
                Text(
                    v,
                    fontFamily = DIAL_FONT,
                    fontSize = if (v.length > 6) 22.sp else 28.sp,
                    fontWeight = FontWeight.Light,
                    color = DIAL_TEXT.copy(alpha = if (active) 1f else 0.5f),
                    letterSpacing = (-0.4).sp
                )
            }
        }
    }
}
