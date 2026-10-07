package com.eagleseye.camera

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val PGOLD: Color get() = AppAccent.color
private val PGOLD_HI: Color get() = AppAccent.hi
private val P_BG = Color(0xE60A0A0C)
private val P_SURFACE = Color(0xFF101014)
private val P_BORDER = Color(0xFF22222B)
private val P_TEXT = Color(0xFFF0EDE8)
private val P_MUTED = Color(0xFF8A8A96)
private val P_FONT = FontFamily.Monospace

val PRO_TABS = listOf("ISO", "SS", "WB", "FOCUS", "EV")

fun ssNsToDisplay(ns: Long): String {
    val s = ns / 1_000_000_000.0
    return if (s >= 1.0) {
        if (ns % 1_000_000_000L == 0L) "${s.toLong()}s" else "%.1fs".format(s)
    } else "1/${(1.0 / s).roundToInt()}"
}

private fun scaleFine(key: String) = TickWheelScales.get(key).fine

// Fraction (0..1 across the scale) → actual value
private fun fracToValue(key: String, frac: Float): Double {
    val fine = scaleFine(key)
    if (fine.isEmpty()) return 0.0
    val idx = (frac.coerceIn(0f, 1f) * (fine.size - 1)).roundToInt().coerceIn(0, fine.size - 1)
    return scaleValueToDouble(fine[idx])
}

// Actual value → nearest fraction
private fun valueToFrac(key: String, value: Double): Float {
    val fine = scaleFine(key)
    if (fine.size < 2) return 0f
    return (nearestFineIndex(TickWheelScales.get(key), value) / (fine.size - 1).toFloat()).coerceIn(0f, 1f)
}

private fun formatVal(key: String, value: Double): String = when (key) {
    "ISO" -> if (value <= 0) "AUTO" else "${value.roundToInt()}"
    "SS" -> ssNsToDisplay(value.toLong())
    "WB" -> "${value.roundToInt()}K"
    "FOCUS" -> {
        val d = value.coerceIn(0.02, 10.0)
        if (1.0 / d >= 1.0) "${(1.0 / d).roundToInt()}m" else "${(100.0 / d).roundToInt()}cm"
    }
    else -> if (value > 0) "+%.1f".format(value) else "%.1f".format(value)
}

private fun tabIconChar(tab: String): String = when (tab) {
    "ISO" -> "I"; "SS" -> "S"; "WB" -> "K"; "FOCUS" -> "MF"; else -> "EV"
}

private fun scaleBounds(key: String): Pair<Double, Double> {
    val fine = scaleFine(key)
    return if (fine.size < 2) 0.0 to 1.0 else scaleValueToDouble(fine[0]) to scaleValueToDouble(fine.last())
}

// Live circle — arc tracks the current value (snaps while dragging, no overshoot);
// breathing halo only in AUTO; tap toggles AUTO/MANUAL (non-EV tabs)
@Composable
private fun LiveCircle(
    value: String,
    mode: String,
    frac: Float,
    isAuto: Boolean,
    dragging: Boolean,
    onToggleAuto: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sweepAnim by animateFloatAsState(
        frac,
        if (dragging) tween(0) else tween(160, easing = LinearEasing),
        label = "arc"
    )
    val idle = rememberInfiniteTransition(label = "idle")
    val breathe by idle.animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "br"
    )
    Box(
        modifier
            .size(54.dp)
            .clip(CircleShape)
            .clickable(remember { MutableInteractionSource() }, null) { onToggleAuto() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val arcFrac = sweepAnim.coerceIn(0f, 1f)
            drawCircle(color = Color(0xFF1E1E26), style = Stroke(stroke))
            if (isAuto && breathe > 0f) {
                drawCircle(
                    PGOLD.copy(alpha = 0.16f * breathe),
                    radius = size.minDimension * 0.42f * (0.72f + 0.22f * breathe),
                    style = Stroke(stroke)
                )
            }
            drawArc(
                brush = Brush.sweepGradient(listOf(PGOLD_HI, PGOLD, PGOLD.copy(alpha = 0.15f), PGOLD_HI)),
                startAngle = -90f,
                sweepAngle = 270f * arcFrac,
                useCenter = false,
                style = Stroke(stroke * 1.4f, cap = StrokeCap.Round),
                alpha = if (isAuto) 0.35f + 0.25f * breathe else 1f
            )
            val ang = Math.toRadians((-90 + 270 * arcFrac).toDouble())
            val dotR = size.minDimension * 0.36f
            drawCircle(
                PGOLD,
                radius = 2.dp.toPx(),
                center = Offset(
                    size.width / 2f + dotR * kotlin.math.cos(ang).toFloat(),
                    size.height / 2f + dotR * kotlin.math.sin(ang).toFloat()
                ),
                alpha = if (isAuto) 0.4f + 0.6f * breathe else 1f
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(value, fontFamily = P_FONT, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PGOLD_HI, maxLines = 1)
            Text(mode, fontFamily = P_FONT, fontSize = 6.sp, color = P_MUTED, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun ProPanel(
    open: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (String, Float) -> Unit = { _, _ -> },
    onAutoToggle: (String, Boolean) -> Unit = { _, _ -> },
    isoManual: Boolean = false,
    ssManual: Boolean = false,
    wbManual: Boolean = false,
    focusManual: Boolean = false,
    isoDefault: Int = 400,
    ssNsDefault: Long = 16_666_667L,
    wbDefault: Int = 5600,
    focusDefault: Float = 0.5f,
    evDefault: Float = 0f
) {
    var activeTab by remember { mutableStateOf("ISO") }
    var dragging by remember { mutableStateOf(false) }
    val isEv = activeTab == "EV"
    var autoSwitchDone by remember(activeTab, isEv) { mutableStateOf(false) }
    val autoMap = remember(isoManual, ssManual, wbManual, focusManual) {
        mapOf("ISO" to !isoManual, "SS" to !ssManual, "WB" to !wbManual, "FOCUS" to !focusManual)
    }
    val isAuto = !isEv && (autoMap[activeTab] == true)

    fun defaultFor(tab: String): Double = when (tab) {
        "ISO" -> if (isoManual) isoDefault.toDouble() else -1.0
        "SS" -> if (ssManual) ssNsDefault.toDouble() else 0.0
        "WB" -> if (wbManual) wbDefault.toDouble() else -1.0
        "FOCUS" -> if (focusManual) focusDefault.toDouble() else -1.0
        else -> evDefault.toDouble()
    }

    val defaultFrac = remember(activeTab, isoDefault, ssNsDefault, wbDefault, focusDefault, evDefault, isAuto, open) {
        val d = defaultFor(activeTab)
        if (!isEv && (d < 0 || d == 0.0)) {
            when (activeTab) {
                "ISO" -> valueToFrac("ISO", isoDefault.coerceAtLeast(1).toDouble())
                "SS" -> valueToFrac("SS", ssNsDefault.coerceAtLeast(1).toDouble())
                "WB" -> valueToFrac("WB", 5600.0)
                else -> valueToFrac("FOCUS", 0.5)
            }
        } else valueToFrac(activeTab, d)
    }

    var frac by remember(activeTab) { mutableFloatStateOf(0.5f) }
    LaunchedEffect(activeTab) { frac = defaultFrac }
    LaunchedEffect(defaultFrac) { if (!dragging) frac = defaultFrac }

    // Throttle commit to parent — the old code recomposed the whole app (and the
    // live feed) on every pointer event while dragging a slider.
    var lastCommit by remember { mutableLongStateOf(0L) }
    fun commitTab(v: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCommit >= 66L) { lastCommit = now; onValueChange(activeTab, v) }
    }

    val liveValue = fracToValue(activeTab, frac)
    val displayVal = if (isAuto) "AUTO" else formatVal(activeTab, liveValue)

    val panelAnim by animateFloatAsState(
        if (open) 1f else 0f,
        spring(dampingRatio = 0.78f, stiffness = 300f),
        label = "panelAnim"
    )

    Box(
        modifier.graphicsLayer {
            translationY = (1f - panelAnim) * 42f * density
            alpha = panelAnim
            scaleX = 0.96f + 0.04f * panelAnim
            scaleY = 0.96f + 0.04f * panelAnim
        }
    ) {
        Box(
            Modifier
                .shadow(24.dp, RoundedCornerShape(24.dp), true, Color(0xDD000000), Color.Transparent)
                .clip(RoundedCornerShape(24.dp))
                .background(P_BG)
                .border(1.dp, P_BORDER, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Drag handle
                Box(Modifier.width(42.dp).height(4.dp).clip(CircleShape).background(Color(0xFF2E2E38)))

                Spacer(Modifier.height(10.dp))

                // ── Tab row (compact tiles) + live circle ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        PRO_TABS.forEach { t ->
                            val isActive = activeTab == t
                            val tileAuto = t != "EV" && (autoMap[t] == true)
                            val preview = when (t) {
                                "ISO" -> if (isoManual) "${isoDefault}" else "AUTO"
                                "SS" -> if (ssManual) ssNsToDisplay(ssNsDefault) else "AUTO"
                                "WB" -> if (wbManual) "${wbDefault}K" else "AUTO"
                                "FOCUS" -> if (focusManual) formatVal("FOCUS", focusDefault.toDouble()) else "AUTO"
                                else -> formatVal("EV", evDefault.toDouble())
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isActive) P_SURFACE else Color.Transparent)
                                    .border(1.dp, if (isActive) PGOLD.copy(alpha = 0.65f) else Color(0xFF1C1C24), RoundedCornerShape(10.dp))
                                    .clickable(remember { MutableInteractionSource() }, null) { activeTab = t }
                                    .padding(vertical = 7.dp)
                            ) {
                                Text(tabIconChar(t), fontFamily = P_FONT, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    color = when { isActive && tileAuto -> P_MUTED; isActive -> PGOLD_HI; else -> P_MUTED.copy(alpha = 0.8f) })
                                Text(preview, fontFamily = P_FONT, fontSize = 6.sp, maxLines = 1,
                                    color = if (isActive) P_TEXT.copy(alpha = 0.55f) else P_MUTED.copy(alpha = 0.4f))
                                Box(Modifier.size(3.dp).clip(CircleShape).background(
                                    when {
                                        !tileAuto && t != "EV" && !isActive -> PGOLD.copy(alpha = 0.5f)
                                        isActive && !tileAuto -> PGOLD
                                        else -> Color.Transparent
                                    }
                                ))
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    LiveCircle(
                        value = displayVal,
                        mode = if (isEv) "EV" else if (isAuto) "AUTO" else activeTab,
                        frac = frac,
                        isAuto = isAuto && !isEv,
                        dragging = dragging,
                        onToggleAuto = { if (!isEv) onAutoToggle(activeTab, isAuto) }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── Horizontal pill slider ──
                var sliderW by remember { mutableFloatStateOf(1f) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .onSizeChanged { sliderW = it.width.toFloat().coerceAtLeast(1f) }
                        .pointerInput(activeTab, isAuto) {
                            detectDragGestures(
                                onDragStart = { o ->
                                    dragging = true
                                    if (isAuto && !autoSwitchDone) {
                                        autoSwitchDone = true
                                        onAutoToggle(activeTab, true)
                                    }
                                    val f = (o.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    frac = f
                                    commitTab(fracToValue(activeTab, f).toFloat())
                                },
                                onDragEnd = {
                                    dragging = false
                                    onValueChange(activeTab, fracToValue(activeTab, frac).toFloat())
                                },
                                onDragCancel = { dragging = false },
                                onDrag = { c, _ ->
                                    val f = (c.position.x / sliderW).coerceIn(0f, 1f)
                                    frac = f
                                    commitTab(fracToValue(activeTab, f).toFloat())
                                }
                            )
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    val fine = scaleFine(activeTab)
                    val tickN = 9
                    // Track
                    Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(Color(0xFF17171E))
                        .border(1.dp, Color(0xFF23232C), CircleShape))
                    // Fill up to thumb — gradient anchored to full track width so it never shifts on drag
                    Canvas(Modifier.fillMaxWidth().height(7.dp)) {
                        val fillW = (frac * size.width).coerceAtLeast(0f)
                        if (fillW > 0f) {
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to PGOLD.copy(alpha = 0.3f),
                                    size.width to PGOLD,
                                    startX = 0f, endX = size.width
                                ),
                                size = Size(fillW, size.height)
                            )
                        }
                    }
                    // Ticks
                    Canvas(Modifier.fillMaxSize()) {
                        val tw = size.width
                        val thumbIdx = (frac * (tickN - 1)).roundToInt()
                        for (i in 0 until tickN) {
                            val x = tw * i / (tickN - 1)
                            val big = i == 0 || i == tickN - 1 || i == (tickN - 1) / 2
                            drawLine(P_MUTED.copy(alpha = if (i <= thumbIdx) 0.5f else 0.16f),
                                Offset(x, size.height / 2f - if (big) 6.dp.toPx() else 3.dp.toPx()),
                                Offset(x, size.height / 2f + if (big) 6.dp.toPx() else 3.dp.toPx()),
                                strokeWidth = 1.4.dp.toPx())
                        }
                    }
                    // Thumb — clamped so it never overflows the panel edges
                    Box(
                        Modifier
                            .offset { IntOffset((frac * sliderW - 9.dp.toPx()).coerceIn((-2f).dp.toPx(), sliderW - 16.dp.toPx()).toInt(), 0) }
                            .size(18.dp)
                            .shadow(4.dp, CircleShape, false, PGOLD.copy(alpha = 0.5f), Color.Transparent)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(PGOLD_HI, PGOLD)))
                            .border(1.5.dp, Color(0xFF2A2213), CircleShape)
                    )
                    // End caps (0% / 100% value labels)
                    Text(formatVal(activeTab, scaleBounds(activeTab).first), fontFamily = P_FONT, fontSize = 5.5.sp,
                        color = P_MUTED.copy(alpha = 0.45f), modifier = Modifier.align(Alignment.BottomStart))
                    Text(formatVal(activeTab, scaleBounds(activeTab).second), fontFamily = P_FONT, fontSize = 5.5.sp,
                        color = P_MUTED.copy(alpha = 0.45f), modifier = Modifier.align(Alignment.BottomEnd))
                }
            }
        }
    }
}
