package com.eagleseye.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val GOLD: Color get() = AppAccent.color
private val GOLD_HI: Color get() = AppAccent.hi
private val BG = Color(0xFF030304)
private val BORDER = Color(0xFF1C1C22)
private val TEXT = Color(0xFFF0EDE8)
private val CIRCLE = RoundedCornerShape(50)
private val FONT = FontFamily.Monospace

private val Z_STOPS = floatArrayOf(0.5f, 1f, 2f, 3f)
private const val Z_MIN = 0.5f
private const val Z_MAX = 10f
private val Z_MAJORS = floatArrayOf(0.5f, 1f, 2f, 3f, 5f, 7f, 10f)
private const val Z_SNAP_EPS = 0.045f
private const val Z_FLICK_VEL = 3.5f
private const val Z_BASE_MM = 18
private const val Z_IDLE_MS = 8000L
private const val Z_DRAG_THRESHOLD_PX = 20f
private const val Z_COMPACT_W = 108
private const val Z_COMPACT_H = 30
private const val Z_EXPANDED_W = 260
private const val Z_EXPANDED_H = 36
private const val Z_MM_W = 32
private const val Z_THUMB = 24
private const val Z_THUMB_EXP = 12
private const val Z_PAD = 4

private fun zClamp(v: Float, a: Float, b: Float) = v.coerceIn(a, b)
private fun zRound1(v: Float) = (v * 10).roundToInt() / 10f
private fun zMm(v: Float) = (Z_BASE_MM * v).roundToInt()

private fun zFormat(v: Float): String {
    if (abs(v - 0.5f) < 0.02f) return ".5\u00D7"
    if (abs(v - v.roundToInt()) < 0.02f) return "${v.roundToInt()}\u00D7"
    return "${"%.1f".format(v)}\u00D7"
}

private fun zIndexPos(v: Float): Float {
    for (i in 0 until Z_STOPS.size - 1) {
        if (v <= Z_STOPS[i + 1] || i == Z_STOPS.size - 2) {
            val t = (v - Z_STOPS[i]) / (Z_STOPS[i + 1] - Z_STOPS[i])
            return i + zClamp(t, 0f, 1f)
        }
    }
    return 0f
}

@Composable
fun ZoomPill(zoom: Float, onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var value by remember { mutableFloatStateOf(zoom) }
    var springTarget by remember { mutableFloatStateOf(zoom) }
    var isExpanded by remember { mutableStateOf(false) }
    var isMini by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val valueRef = remember { mutableFloatStateOf(zoom) }
    val velRef = remember { mutableFloatStateOf(0f) }
    val draggingRef = remember { mutableStateOf(false) }
    val isExpandedRef = remember { mutableStateOf(false) }
    val trackWidthPx = remember { mutableIntStateOf(0) }

    val collapseTimer = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val miniTimer = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun buzz() {
        try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
    }

    fun armMiniTimer() {
        miniTimer.value?.cancel()
        miniTimer.value = scope.launch {
            delay(Z_IDLE_MS)
            if (!draggingRef.value && !isExpandedRef.value) isMini = true
        }
    }

    fun commitValue(target: Float) {
        val clamped = zClamp(zRound1(target), Z_MIN, Z_MAX)
        springTarget = clamped
        onZoom(clamped)
        buzz()
    }

    fun scheduleCollapse() {
        collapseTimer.value?.cancel()
        collapseTimer.value = scope.launch {
            delay(900)
            isExpandedRef.value = false; isExpanded = false
            armMiniTimer()
        }
    }

    LaunchedEffect(Unit) { armMiniTimer() }

    DisposableEffect(Unit) {
        onDispose { collapseTimer.value?.cancel(); miniTimer.value?.cancel() }
    }

    // Spring physics
    LaunchedEffect(springTarget) {
        if (draggingRef.value) return@LaunchedEffect
        var last = System.nanoTime()
        val stiffness = 520f; val damping = 26f
        while (true) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.032f)
            last = now
            val force = -stiffness * (valueRef.floatValue - springTarget)
            val damp = -damping * velRef.floatValue
            velRef.floatValue += (force + damp) * dt
            valueRef.floatValue += velRef.floatValue * dt
            value = valueRef.floatValue
            if (abs(valueRef.floatValue - springTarget) > 0.0015f || abs(velRef.floatValue) > 0.0015f) {
                delay(16)
            } else {
                valueRef.floatValue = springTarget; velRef.floatValue = 0f; value = springTarget
                break
            }
        }
    }

    LaunchedEffect(zoom) {
        if (!draggingRef.value && abs(zoom - springTarget) > 0.01f) {
            springTarget = zoom; valueRef.floatValue = zoom; value = zoom
        }
    }

    val idxPos = zIndexPos(value)

    val compactWPx = with(density) { Z_COMPACT_W.dp.toPx() }
    val compactHPx = with(density) { Z_COMPACT_H.dp.toPx() }
    val expandedWPx = with(density) { Z_EXPANDED_W.dp.toPx() }
    val padPx = with(density) { Z_PAD.dp.toPx() }
    val thumbPx = with(density) { Z_THUMB.dp.toPx() }
    val thumbExpPx = with(density) { Z_THUMB_EXP.dp.toPx() }

    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isMini) {
                // Mini: 36dp circle
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CIRCLE)
                        .background(Brush.linearGradient(0f to Color(0xFF1C1C1F).copy(0.9f), 1f to BG.copy(0.96f)))
                        .border(1.dp, BORDER, CIRCLE)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                isMini = false
                                isExpandedRef.value = false; isExpanded = false
                                miniTimer.value?.cancel()
                                buzz()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(zFormat(value), fontFamily = FONT, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TEXT)
                }
            } else {
                // Compact/expanded pill
                Box(
                    Modifier
                        .graphicsLayer(scaleX = if (pressed) 0.97f else 1f, scaleY = if (pressed) 0.97f else 1f)
                        .clip(CIRCLE)
                        .background(Brush.linearGradient(0f to Color(0xFF1C1C1F).copy(0.9f), 1f to BG.copy(0.96f)))
                        .border(1.dp, BORDER, CIRCLE)
                        .shadow(6.dp, CIRCLE, false, Color(0x55000000), Color.Transparent)
                ) {
                    Row(
                        Modifier.height(if (isExpanded) Z_EXPANDED_H.dp else Z_COMPACT_H.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // TRACK — gesture handler ONLY here
                        Box(
                            Modifier
                                .width(if (isExpanded) Z_EXPANDED_W.dp else Z_COMPACT_W.dp)
                                .height(if (isExpanded) Z_EXPANDED_H.dp else Z_COMPACT_H.dp)
                                .onGloballyPositioned { trackWidthPx.intValue = it.size.width }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        draggingRef.value = true
                                        pressed = true
                                        collapseTimer.value?.cancel()
                                        miniTimer.value?.cancel()

                                        var startX = down.position.x
                                        var lastX = down.position.x
                                        var moved = false
                                        var expandedNow = isExpandedRef.value

                                        horizontalDrag(down.id) { change ->
                                            val dx = change.position.x - startX
                                            val totalDx = change.position.x - lastX
                                            lastX = change.position.x

                                            if (!expandedNow && abs(dx) > Z_DRAG_THRESHOLD_PX) {
                                                expandedNow = true
                                                isExpandedRef.value = true
                                                isExpanded = true
                                                startX = change.position.x
                                                lastX = change.position.x
                                                buzz()
                                            }

                                            if (expandedNow) {
                                                moved = true
                                                val w = trackWidthPx.intValue.toFloat()
                                                val usable = w - 2 * padPx
                                                if (usable > 0f) {
                                                    val deltaValue = (totalDx / usable) * (Z_MAX - Z_MIN)
                                                    var next = valueRef.floatValue + deltaValue
                                                    if (next < Z_MIN) next = Z_MIN + (next - Z_MIN) * 0.3f
                                                    if (next > Z_MAX) next = Z_MAX + (next - Z_MAX) * 0.3f
                                                    velRef.floatValue = deltaValue * 70f
                                                    valueRef.floatValue = next; value = next
                                                    onZoom(zClamp(zRound1(next), Z_MIN, Z_MAX))
                                                }
                                            }
                                            change.consume()
                                        }

                                        draggingRef.value = false; pressed = false

                                        if (!expandedNow) {
                                            val totalW = trackWidthPx.intValue.toFloat()
                                            if (totalW > 0f) {
                                                val frac = zClamp(lastX / totalW, 0f, 1f)
                                                val idx = (frac * (Z_STOPS.size - 1)).roundToInt().coerceIn(0, Z_STOPS.size - 1)
                                                commitValue(Z_STOPS[idx])
                                            }
                                            armMiniTimer()
                                        } else if (!moved) {
                                            val totalW = trackWidthPx.intValue.toFloat()
                                            if (totalW > 0f) {
                                                val frac = zClamp((lastX - padPx) / (totalW - 2 * padPx), 0f, 1f)
                                                commitValue(Z_MIN + frac * (Z_MAX - Z_MIN))
                                            }
                                            scheduleCollapse()
                                        } else {
                                            var target = valueRef.floatValue
                                            val nearStop = Z_STOPS.find { abs(target - it) < Z_SNAP_EPS }
                                            if (nearStop != null) target = nearStop
                                            else if (abs(velRef.floatValue) > Z_FLICK_VEL) {
                                                val dir = if (velRef.floatValue > 0) 1 else -1
                                                val candidates = Z_STOPS.filter { if (dir > 0) it > target else it < target }
                                                if (candidates.isNotEmpty()) target = if (dir > 0) candidates.min() else candidates.max()
                                            }
                                            commitValue(target)
                                            scheduleCollapse()
                                        }
                                    }
                                }
                        ) {
                            // Tick marks (expanded)
                            if (isExpanded) {
                                Canvas(Modifier.matchParentSize()) {
                                    val cw = size.width
                                    var t = Z_MIN
                                    while (t <= Z_MAX + 0.001f) {
                                        val tv = zRound1(t)
                                        val isMajor = Z_MAJORS.any { abs(it - tv) < 0.01f }
                                        val x = padPx + ((tv - Z_MIN) / (Z_MAX - Z_MIN)) * (cw - 2 * padPx)
                                        val h = if (isMajor) 10f else 5f
                                        val alpha = if (isMajor) 0.8f else 0.28f
                                        drawRect(TEXT.copy(alpha = alpha), Offset(x - 0.5f, size.height / 2 - h / 2), Size(1f, h))
                                        t = if (t < 3f) zRound1(t + 0.1f) else zRound1(t + 0.5f)
                                    }
                                }
                                Z_MAJORS.forEach { m ->
                                    val xFrac = padPx + ((m - Z_MIN) / (Z_MAX - Z_MIN)) * (expandedWPx - 2 * padPx)
                                    val label = if (m == 0.5f) ".5" else m.toInt().toString()
                                    Box(
                                        Modifier.offset { IntOffset(xFrac.roundToInt(), (Z_EXPANDED_H * density.density * 0.76f).roundToInt()) }
                                    ) {
                                        Text(label, fontFamily = FONT, fontSize = 6.5.sp, color = TEXT.copy(0.4f))
                                    }
                                }
                            }

                            // Gold thumb
                            val thumbXPx = if (isExpanded) {
                                padPx + ((value - Z_MIN) / (Z_MAX - Z_MIN)) * (expandedWPx - 2 * padPx - thumbExpPx)
                            } else {
                                padPx + (idxPos / (Z_STOPS.size - 1)) * (compactWPx - padPx * 2 - thumbPx)
                            }
                            val thumbW = if (isExpanded) Z_THUMB_EXP else Z_THUMB
                            val thumbH = if (isExpanded) Z_EXPANDED_H - 2 * Z_PAD else Z_THUMB
                            val thumbYPx = if (isExpanded) padPx else (compactHPx - thumbPx) / 2

                            Box(
                                Modifier
                                    .offset { IntOffset(thumbXPx.roundToInt(), thumbYPx.roundToInt()) }
                                    .size(thumbW.dp, thumbH.dp)
                                    .clip(CIRCLE)
                                    .background(Brush.linearGradient(0f to GOLD_HI, 0.55f to GOLD, 1f to AppAccent.deep))
                                    .shadow(8.dp, CIRCLE, false, Color(0x88C9A96E), Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!isExpanded) {
                                    Text(zFormat(value), fontFamily = FONT, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = BG)
                                }
                            }
                        }

                        // MM readout — NO gesture handler
                        Box(
                            Modifier
                                .width(Z_MM_W.dp)
                                .fillMaxHeight()
                        ) {
                            Box(
                                Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .align(Alignment.CenterStart)
                                    .background(BORDER)
                            )
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(zMm(value).toString(), fontFamily = FONT, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GOLD_HI)
                                Text("MM", fontFamily = FONT, fontSize = 6.5.sp, fontWeight = FontWeight.SemiBold, color = GOLD, letterSpacing = 0.06.sp)
                            }
                        }
                    }
                }
            }
        }

    }
}
