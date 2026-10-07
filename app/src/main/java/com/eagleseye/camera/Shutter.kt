package com.eagleseye.camera

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min

private val GOLD: Color get() = AppAccent.color
private val GOLD_HI: Color get() = AppAccent.hi
private val RED = Color(0xFFE23838)
private val BG = Color(0xFF030304)
private val TEXT = Color(0xFFF0EDE8)
private val CIRCLE = RoundedCornerShape(50)
private const val HOLD_ARM_MS = 750L

@Composable
fun ShutterButton(
    isVideo: Boolean,
    isRecording: Boolean,
    onArmVideo: () -> Unit,
    onStopVideo: () -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var ripple by remember { mutableStateOf(0L) }
    val fired = remember { mutableStateOf(false) }
    val active = remember { mutableStateOf(false) }
    val holdStart = remember { mutableLongStateOf(0L) }
    val recordingRotation = remember { Animatable(0f) }

    // Spinning dashed circle during recording
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isActive) {
                recordingRotation.animateTo(
                    targetValue = recordingRotation.value + 360f,
                    animationSpec = tween(4000, easing = LinearEasing)
                )
            }
        } else {
            recordingRotation.snapTo(0f)
        }
    }

    val scale by animateFloatAsState(
        if (pressed) 0.93f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 300f),
        label = "sh"
    )

    fun spawnRipple() {
        ripple = System.currentTimeMillis()
    }

    fun down() {
        active.value = true; pressed = true
        if (isRecording) return
        if (isVideo) {
            onArmVideo()
            spawnRipple()
            return
        }
        holdStart.longValue = System.nanoTime()
        fired.value = false
    }

    fun up() {
        active.value = false; pressed = false
        holdProgress = 0f
        if (isRecording) {
            onStopVideo()
            spawnRipple()
            return
        }
        if (fired.value) {
            fired.value = false
            return
        }
        if (!isVideo) {
            onCapture()
            spawnRipple()
        }
    }

    // Hold progress animation
    LaunchedEffect(pressed, isVideo, isRecording) {
        if (!pressed || isVideo || isRecording) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        val startNs = System.nanoTime()
        while (active.value) {
            val p = min(((System.nanoTime() - startNs) / 1_000_000f) / HOLD_ARM_MS.toFloat(), 1f)
            holdProgress = p
            if (p >= 1f && !fired.value) {
                fired.value = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onArmVideo()
            }
            delay(16)
        }
    }

    val ringCirc = 2f * 34f * 3.14159f

    Box(modifier = modifier.size(82.dp).semantics { contentDescription = "Shutter" }, contentAlignment = Alignment.Center) {
        // Ripple
        if (ripple > 0) {
            Canvas(Modifier.size(70.dp)) {
                drawCircle(
                    color = if (isRecording || isVideo) RED.copy(alpha = 0.6f) else GOLD_HI.copy(alpha = 0.6f),
                    radius = size.minDimension / 2,
                    style = Stroke(1.5f.dp.toPx())
                )
            }
        }

        // Progress ring (hold to arm video in photo mode)
        Canvas(Modifier.size(78.dp).rotate(-90f)) {
            // Background ring
            drawCircle(
                Color(226, 56, 56).copy(alpha = 0.15f),
                radius = 34f * (this.size.width / 78f),
                style = Stroke(2f * (this.size.width / 78f))
            )
            // Progress arc
            if (pressed && !isVideo && !isRecording && holdProgress > 0f) {
                drawArc(
                    color = RED,
                    startAngle = 0f,
                    sweepAngle = 360f * holdProgress,
                    useCenter = false,
                    style = Stroke(2f * (this.size.width / 78f), cap = StrokeCap.Round),
                    topLeft = Offset(
                        (this.size.width - 68f * (this.size.width / 78f)) / 2f,
                        (this.size.height - 68f * (this.size.width / 78f)) / 2f
                    ),
                    size = Size(68f * (this.size.width / 78f), 68f * (this.size.width / 78f))
                )
            }
        }

        // Dashed spinner when recording
        if (isRecording) {
            Canvas(
                Modifier
                    .size(88.dp)
                    .rotate(recordingRotation.value)
            ) {
                drawCircle(
                    color = RED,
                    radius = 38f * (this.size.width / 88f),
                    style = Stroke(
                        width = 1.6f * (this.size.width / 88f),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(5f * (this.size.width / 88f), 7f * (this.size.width / 88f))
                        )
                    ),
                    alpha = 0.7f
                )
            }
        }

        // Outer ring (70dp)
        Box(
            Modifier
                .size(70.dp)
                .scale(scale)
                .shadow(6.dp, CircleShape, false, Color(0x881C1C2E), Color.Transparent)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        0f to Color(0xFF1C1C1C),
                        1f to Color(0xFF050505)
                    )
                )
                .then(
                    Modifier.border(1.dp, GOLD.copy(alpha = 0.3f), CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            // Inner button (52dp)
            Box(
                Modifier
                    .size(52.dp)
                    .clip(if (isRecording) RoundedCornerShape(16.dp) else CircleShape)
                    .background(
                        if (isRecording) Brush.linearGradient(listOf(Color(0xFFFF6B6B), RED))
                        else Brush.linearGradient(listOf(GOLD_HI, GOLD))
                    )
                    .shadow(
                        if (pressed) 0.dp else 2.dp,
                        if (isRecording) RoundedCornerShape(16.dp) else CircleShape,
                        false,
                        if (isRecording) Color(0x80E23838) else GOLD.copy(alpha = 0.5f),
                        Color.Transparent
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            down()
                            val up = waitForUpOrCancellation()
                            up()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
            }
        }
    }
}
