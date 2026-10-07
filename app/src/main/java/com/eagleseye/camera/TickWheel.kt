package com.eagleseye.camera

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

private val GOLD: Color get() = AppAccent.color
private val GOLD_HI: Color get() = AppAccent.hi
private val BG = Color(0xFF030304)
private val SURFACE = Color(0xFF0A0A0C)
private val BORDER = Color(0xFF1C1C22)
private val TEXT = Color(0xFFF0EDE8)
private val MUTED = Color(0xFF3E3E48)
private val GROOVE_LO = Color.Black
private val GROOVE_HI = Color(0xFF17171D)
private val FONT = FontFamily.Monospace
private val CIRCLE = RoundedCornerShape(50)

private const val TW = 240
private const val TW_H = 52
private const val COARSE_PX = 42f
private const val FINE_PX = 24f
private const val FINE_SUBS = 4
private const val FLASH_MS = 220f
private const val SPRING_K = 1.56f
private const val SPRING_D = 0.34f

data class ScaleDef(
    val label: String,
    val coarse: List<Any>,
    val fine: List<Any>,
    val unit: String = "",
    val signed: Boolean = false
)

private val ISO_FINE_TEMPLATE = listOf(100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800, 16000, 20480, 25600, 32000, 40960, 51200, 64000)
private val ISO_COARSE_TEMPLATE = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200)

object TickWheelScales {
    fun isoScale(): ScaleDef {
        val maxIso = LensManager.maxISO.coerceAtLeast(100)
        val fine = ISO_FINE_TEMPLATE.takeWhile { it <= maxIso }.ifEmpty { listOf(100) }
        val coarse = ISO_COARSE_TEMPLATE.takeWhile { it <= maxIso }.ifEmpty { listOf(100) }
        return ScaleDef(label = "ISO", coarse = coarse, fine = fine, unit = "")
    }
    val SS = ScaleDef(
        label = "SS",
        coarse = listOf("1/4000","1/2000","1/1000","1/500","1/250","1/125","1/60","1/30","1/15","1/8","1/4","1/2","1s","2s"),
        fine = listOf("1/4000","1/3200","1/2500","1/2000","1/1600","1/1250","1/1000","1/800","1/640","1/500","1/400","1/320","1/250","1/200","1/160","1/125","1/100","1/80","1/60","1/50","1/40","1/30","1/25","1/20","1/15","1/13","1/10","1/8","1/6","1/5","1/4","1/3","1/2.5","1/2","1/1.6","1/1.3","1s","1.3s","1.6s","2s"),
        unit = ""
    )
    val WB = ScaleDef(
        label = "WB",
        coarse = listOf(2500, 3200, 4000, 5000, 5500, 6500, 7500, 10000),
        fine = List(31) { 2500 + it * 250 },
        unit = "K"
    )
    val FOCUS = ScaleDef(
        label = "MF",
        coarse = listOf(0.02, 0.1, 0.2, 0.33, 0.5, 1.0, 2.0),
        fine = List(31) { i -> (kotlin.math.round((0.02 + i * (1.98 / 30.0)) * 100.0) / 100.0) },
        unit = "m"
    )
    val EV = ScaleDef(
        label = "EV",
        coarse = listOf(-3, -2, -1, 0, 1, 2, 3),
        fine = List(25) { -3.0 + it * 0.25 },
        unit = "",
        signed = true
    )

    fun get(key: String): ScaleDef = when (key) {
        "ISO" -> isoScale(); "SS" -> SS; "WB" -> WB; "FOCUS" -> FOCUS; "EV" -> EV
        else -> isoScale()
    }
}

fun scaleValueToDouble(v: Any): Double = when (v) {
    is Number -> v.toDouble()
    is String -> {
        if (v.endsWith("s")) {
            val num = v.removeSuffix("s").toDoubleOrNull() ?: 0.0
            num * 1_000_000_000.0
        } else if (v.startsWith("1/")) {
            val denom = v.removePrefix("1/").toDoubleOrNull() ?: 0.0
            if (denom > 0.0) 1_000_000_000.0 / denom else 0.0
        } else 0.0
    }
    else -> 0.0
}

fun nearestFineIndex(scale: ScaleDef, value: Double): Int {
    var best = 0
    var bestDist = Double.MAX_VALUE
    for (i in scale.fine.indices) {
        val d = abs(scaleValueToDouble(scale.fine[i]) - value)
        if (d < bestDist) { bestDist = d; best = i }
    }
    return best
}

private fun anyToString(v: Any, scale: ScaleDef): String {
    if (scale.signed && v is Number) {
        val d = v.toDouble()
        return if (d > 0) "+${"%.1f".format(d)}" else "%.1f".format(d)
    }
    if (v is Number && scale.unit.isNotEmpty()) return "${v}${scale.unit}"
    return v.toString()
}

private fun formatDisplay(v: Any, scale: ScaleDef): String = anyToString(v, scale)

private fun isCoarseMajor(v: Any, scale: ScaleDef): Boolean = scale.coarse.any { c ->
    if (c is Number && v is Number) abs(c.toDouble() - v.toDouble()) < 0.01
    else c.toString() == v.toString()
}

@Composable
fun FlapNumber(value: String, isFine: Boolean, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(value) }
    val animProgress = remember { Animatable(1f) }

    LaunchedEffect(value) {
        if (value == shown) return@LaunchedEffect
        animProgress.snapTo(1f)
        animProgress.animateTo(0f, tween(80, easing = LinearEasing))
        shown = value
        animProgress.animateTo(1f, tween(120, easing = FastOutSlowInEasing))
    }

    val fs = when {
        shown.length > 5 -> 15
        shown.length > 4 -> 18
        shown.length > 3 -> 21
        else -> 26
    }

    Text(
        shown,
        modifier = modifier.graphicsLayer {
            scaleY = animProgress.value.coerceIn(0.01f, 1f)
            alpha = animProgress.value
            cameraDistance = 12f * density
        },
        fontFamily = FONT,
        fontSize = fs.sp,
        fontWeight = FontWeight.Light,
        color = if (isFine) GOLD else TEXT,
        letterSpacing = (-0.04).sp,
        lineHeight = (fs + 4).sp
    )
}

@Composable
fun RangeRail(frac: Float, isFine: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(GROOVE_LO)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(frac.coerceAtLeast(0.015f))
                .background(
                    Brush.horizontalGradient(
                        listOf(GOLD.copy(alpha = 0.13f), if (isFine) GOLD_HI else GOLD)
                    )
                )
        )
        Box(
            Modifier
                .fillMaxHeight()
                .offset { IntOffset((frac * 100f * density.density).toInt(), 0) }
                .size(5.dp)
                .clip(CIRCLE)
                .background(if (isFine) GOLD_HI else GOLD)
                .shadow(3.dp, CIRCLE, false, GOLD.copy(alpha = 0.4f), Color.Transparent)
        )
    }
}

@Composable
fun TickWheel(
    scaleKey: String,
    defaultIndex: Int = 0,
    active: Boolean = true,
    modifier: Modifier = Modifier,
    onChange: (index: Int) -> Unit = {}
) {
    val scale = TickWheelScales.get(scaleKey)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val gesturesEnabled = active

    val canvasW = with(density) { TW.dp.toPx() }
    val canvasH = with(density) { TW_H.dp.toPx() }
    val dpr = density.density

    var displayValue by remember { mutableStateOf(anyToString(scale.fine[defaultIndex.coerceIn(0,scale.fine.size-1)], scale)) }
    var displayMode by remember { mutableStateOf("coarse") }
    var pressed by remember { mutableStateOf(false) }

    val floatIdx = remember { mutableFloatStateOf(defaultIndex.toFloat()) }
    val mode = remember { mutableStateOf("coarse") }
    val dragging = remember { mutableStateOf(false) }
    val lastX = remember { mutableFloatStateOf(0f) }
    val lastT = remember { mutableLongStateOf(0L) }
    val velBuf = remember { mutableStateListOf<Float>() }
    val velHistory = remember { mutableStateListOf<Float>() }
    val lastCenter = remember { mutableIntStateOf(defaultIndex) }
    val flashIdx = remember { mutableIntStateOf(-1) }
    val flashStart = remember { mutableLongStateOf(0L) }
    val lastSwitch = remember { mutableLongStateOf(0L) }
    val animJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    val coarseSet = remember { scale.coarse.map { it.toString() }.toSet() }

    fun getVals(m: String) = if (m == "coarse") scale.coarse else scale.fine

    fun maybeFlash(sIdx: Int) {
        if (sIdx != lastCenter.intValue) {
            lastCenter.intValue = sIdx
            flashIdx.intValue = sIdx
            flashStart.longValue = System.nanoTime()
        }
    }

    fun switchMode(newMode: String) {
        if (mode.value == newMode) return
        val now = System.currentTimeMillis()
        if (now - lastSwitch.longValue < 600) return
        lastSwitch.longValue = now

        val oldVals = getVals(mode.value)
        val newVals = getVals(newMode)
        val curVal = oldVals[maxOf(0, minOf(floatIdx.floatValue.roundToInt(), oldVals.size - 1))]
        var best = 0
        var bestD = Double.MAX_VALUE
        newVals.forEachIndexed { i, v ->
            val d = when {
                curVal is Number && v is Number -> abs(v.toDouble() - curVal.toDouble())
                v.toString() == curVal.toString() -> 0.0
                else -> Double.MAX_VALUE
            }
            if (d < bestD) { bestD = d; best = i }
        }
        mode.value = newMode
        floatIdx.floatValue = best.toFloat()
        lastCenter.intValue = best
        displayValue = anyToString(newVals[best], scale)
        displayMode = newMode
    }

    fun snapTo(to: Int) {
        val from = floatIdx.floatValue
        val vals = getVals(mode.value)
        val target = to.coerceIn(0, vals.size - 1)
        if (abs(target.toFloat() - from) < 0.0005f) {
            floatIdx.floatValue = target.toFloat()
            displayValue = anyToString(vals[target], scale)
            onChange(target)
            return
        }
        val dur = 340f
        val t0 = System.nanoTime()
        animJob.value?.cancel()
        animJob.value = scope.launch {
            while (true) {
                val elapsed = (System.nanoTime() - t0) / 1_000_000f
                val t = minOf(elapsed / dur, 1f)
                val c1 = 1.4f; val c3 = c1 + 1
                val eased = 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
                floatIdx.floatValue = from + (target.toFloat() - from) * eased
                maybeFlash(target)
                displayValue = anyToString(vals[round(floatIdx.floatValue).toInt().coerceIn(0, vals.size - 1)], scale)
                if (t < 1f) delay(16) else {
                    floatIdx.floatValue = target.toFloat()
                    lastCenter.intValue = target
                    displayValue = anyToString(vals[target], scale)
                    onChange(target)
                    break
                }
            }
        }
    }

    fun onStart(clientX: Float) {
        dragging.value = true
        lastX.floatValue = clientX
        lastT.longValue = System.nanoTime()
        velBuf.clear()
        velHistory.clear()
        pressed = true
        animJob.value?.cancel()
    }

    fun onMove(clientX: Float) {
        if (!dragging.value) return
        val dx = clientX - lastX.floatValue
        val now = System.nanoTime()
        val dt = maxOf((now - lastT.longValue) / 1_000_000f, 1f)
        val spd = abs(dx) / dt
        velBuf.add(spd)
        if (velBuf.size > 8) velBuf.removeAt(0)
        val avg = velBuf.average().toFloat()
        velHistory.add(dx / dt)
        if (velHistory.size > 6) velHistory.removeAt(0)

        if (avg > 2.4f && mode.value == "fine") scope.launch { delay(160); switchMode("coarse") }
        if (avg < 0.15f && mode.value == "coarse") scope.launch { delay(400); switchMode("fine") }

        val vals = getVals(mode.value)
        val stepPx = if (mode.value == "coarse") COARSE_PX else FINE_PX
        val unclamped = floatIdx.floatValue - dx / stepPx
        floatIdx.floatValue = unclamped.coerceIn(0f, (vals.size - 1).toFloat())
        lastX.floatValue = clientX
        lastT.longValue = now
        maybeFlash(round(floatIdx.floatValue).toInt())

        displayValue = anyToString(vals[round(floatIdx.floatValue).toInt().coerceIn(0, vals.size - 1)], scale)
        animJob.value?.cancel()
    }

    fun onEnd() {
        if (!dragging.value) return
        dragging.value = false
        pressed = false
        animJob.value?.cancel()

        val vals = getVals(mode.value)
        val stepPx = if (mode.value == "coarse") COARSE_PX else FINE_PX

        val hist = velHistory.takeLast(5)
        val relVelPx = if (hist.isNotEmpty()) {
            hist.mapIndexed { i, v -> v * (i + 1) }.sum() / hist.mapIndexed { i, _ -> i + 1 }.sum().toFloat()
        } else 0f

        var flingVel = -relVelPx / stepPx
        val FRICTION = 0.86f
        val MIN_VEL = 0.0025f

        if (abs(flingVel) < MIN_VEL) {
            snapTo(round(floatIdx.floatValue).toInt())
            return
        }

        animJob.value = scope.launch {
            var lastNs = System.nanoTime()
            while (true) {
                val now = System.nanoTime()
                val dt = minOf((now - lastNs) / 1_000_000f, 32f)
                lastNs = now
                floatIdx.floatValue += flingVel * dt
                floatIdx.floatValue = floatIdx.floatValue.coerceIn(0f, (vals.size - 1).toFloat())
                flingVel *= FRICTION.pow(dt / 16.67f)
                maybeFlash(round(floatIdx.floatValue).toInt())
                displayValue = anyToString(vals[round(floatIdx.floatValue).toInt().coerceIn(0, vals.size - 1)], scale)
                val hitWall = floatIdx.floatValue <= 0f || floatIdx.floatValue >= vals.size - 1
                if (abs(flingVel) < MIN_VEL || hitWall) {
                    snapTo(round(floatIdx.floatValue).toInt())
                    break
                }
                delay(16)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { animJob.value?.cancel() }
    }

    val frac = if (getVals(displayMode).size > 1) {
        round(floatIdx.floatValue).toInt().coerceIn(0, getVals(displayMode).size - 1).toFloat() / (getVals(displayMode).size - 1).toFloat()
    } else 0f

    LaunchedEffect(defaultIndex) {
        if (defaultIndex < 0 || defaultIndex >= scale.fine.size) return@LaunchedEffect
        val fineVal = scale.fine[defaultIndex]
        val vals = getVals(mode.value)
        val modeIdx = vals.indexOfFirst {
            if (it is Number && fineVal is Number) abs(it.toDouble() - fineVal.toDouble()) < 0.001
            else it.toString() == fineVal.toString()
        }.let { if (it < 0) 0 else it }
        floatIdx.floatValue = modeIdx.toFloat()
        displayValue = anyToString(vals[modeIdx], scale)
        lastCenter.intValue = modeIdx
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .graphicsLayer(scaleX = if (pressed) 0.988f else 1f, scaleY = if (pressed) 0.988f else 1f)
                    .shadow(8.dp, RoundedCornerShape(8.dp), true, Color(0xCC000000), Color.Transparent)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BG)
                    .size(TW.dp, TW_H.dp)
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(BG, Color.Transparent, Color.Transparent, BG),
                                startX = 0f, endX = with(density) { TW.dp.toPx() }
                            )
                        )
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent, GOLD.copy(alpha = 0.73f),
                                    GOLD, GOLD,
                                    GOLD.copy(alpha = 0.73f), Color.Transparent
                                )
                            )
                        )
                        .graphicsLayer(alpha = if (pressed) 0.9f else 0.65f + 0.35f * java.lang.Math.sin(System.currentTimeMillis() / 2800.0).toFloat())
                )

                Canvas(
                    Modifier
                        .matchParentSize()
                        .pointerInput(gesturesEnabled) {
                            if (!gesturesEnabled) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                onStart(down.position.x)
                                horizontalDrag(down.id) { change ->
                                    onMove(change.position.x)
                                    change.consume()
                                }
                                onEnd()
                            }
                        }
                ) {
                    val cw = size.width
                    val ch = size.height
                    val cx = cw / 2f
                    val step = (if (mode.value == "coarse") COARSE_PX else FINE_PX) * dpr
                    val fi = floatIdx.floatValue
                    val vals = getVals(mode.value)
                    val isFine = mode.value == "fine"

                    if (isFine) {
                        val subStep = step / (FINE_SUBS + 1)
                        val subSpan = ceil((cw / subStep) / 2f).toInt() + 5
                        val baseF = fi.toInt()
                        for (si in (baseF - subSpan)..(baseF + subSpan)) {
                            for (k in 1..FINE_SUBS) {
                                if (si < 0 || si + 1 > vals.size - 1) continue
                                val sx = cx + (si + k.toFloat() / (FINE_SUBS + 1) - fi) * step
                                if (sx < 0f || sx > cw) continue
                                val dist = abs(sx - cx) / cx
                                val alpha = max(0f, java.lang.Math.cos((dist * 3.14159 * 0.5)).toFloat()) * 0.38f
                                if (alpha < 0.02f) continue
                                drawRect(
                                    Color(65, 63, 75).copy(alpha = alpha),
                                    Offset(sx - 0.5f * dpr, ch * 0.42f),
                                    Size(0.75f * dpr, 7f * dpr)
                                )
                            }
                        }
                    }

                    val span = ceil((cw / step) / 2f).toInt() + 3
                    val flashAge = (System.nanoTime() - flashStart.longValue) / 1_000_000f
                    val flashActive = flashAge < FLASH_MS
                    val flashK = if (flashActive) (1f - flashAge / FLASH_MS).pow(2) else 0f

                    for (vi in (max(0, round(fi).toInt() - span))..min(vals.size - 1, round(fi).toInt() + span)) {
                        val sx = cx + (vi - fi) * step
                        if (sx < -4f || sx > cw + 4f) continue
                        val dist = abs(sx - cx) / cx
                        val alpha = max(0f, java.lang.Math.cos((dist * 3.14159 * 0.5)).toFloat())
                        if (alpha < 0.01f) continue

                        val isCenter = vi == round(fi).toInt()
                        val isMajor = isFine && isCoarseMajor(vals[vi], scale) || !isFine
                        val isFlash = flashActive && vi == flashIdx.intValue
                        val shimmer = 1f + 0.16f * java.lang.Math.sin(System.currentTimeMillis() / 1100.0 + vi * 0.55).toFloat()

                        var th: Float
                        var topY: Float
                        var color: Color
                        var tw: Float

                        if (isCenter) {
                            th = 32f * dpr; topY = ch * 0.06f; tw = 2f * dpr; color = GOLD
                        } else if (isMajor) {
                            th = 20f * dpr * shimmer.coerceIn(0.85f, 1.1f)
                            topY = ch * 0.2f; tw = 1.2f * dpr
                            color = Color(180, 175, 165).copy(alpha = alpha * 0.7f * shimmer)
                        } else {
                            th = 11f * dpr * shimmer.coerceIn(0.8f, 1.15f)
                            topY = ch * 0.36f; tw = 0.9f * dpr
                            color = Color(72, 70, 82).copy(alpha = alpha * 0.8f * shimmer)
                        }

                        if (!isCenter) {
                            val baseH = if (isMajor) 20f * dpr else 11f * dpr
                            topY = ch * (if (isMajor) 0.2f else 0.36f) + (baseH - th) / 2f
                        }

                        if (isFlash) {
                            th *= 1f + 0.4f * flashK; tw *= 1f + 0.8f * flashK
                            color = if (isCenter) GOLD_HI else Color(201, 169, 110).copy(alpha = 0.35f * flashK + alpha * 0.3f)
                            val baseH = if (isCenter) 32f * dpr else if (isMajor) 20f * dpr else 11f * dpr
                            topY -= (th - baseH) * 0.5f
                        }

                        drawRect(color, Offset(sx - tw / 2f, topY), Size(tw, th))

                        if (isCenter) {
                            drawRect(GOLD.copy(alpha = 0.5f), Offset(sx - tw / 2f, topY), Size(tw, th))
                        }
                    }
                }
            }

            // Divider
            Box(
                Modifier
                    .width(1.dp)
                    .height(32.dp)
                    .padding(horizontal = 14.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, GOLD.copy(alpha = 0.33f), Color.Transparent)))
            )

            // Display column
            Column(
                Modifier.width(64.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "${scale.label}${if (displayMode == "fine") " \u00B7fine" else ""}",
                    fontFamily = FONT,
                    fontSize = 7.5.sp,
                    letterSpacing = 0.2.sp,
                    color = if (displayMode == "fine") GOLD.copy(alpha = 0.87f) else MUTED
                )
                FlapNumber(displayValue, displayMode == "fine")
                Row(
                    Modifier
                        .height(6.dp)
                        .alpha(if (displayMode == "fine") 1f else 0f)
                        .graphicsLayer(translationX = if (displayMode == "fine") 0f else 4f),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(0f, 1f, 2f).forEach { i ->
                        Box(
                            Modifier
                                .size(3.dp)
                                .clip(CIRCLE)
                                .background(if (i == 1f) GOLD else GOLD.copy(alpha = 0.27f))
                                .then(if (i == 1f) Modifier.shadow(2.dp, CIRCLE, false, GOLD, Color.Transparent) else Modifier)
                        )
                    }
                }
            }
        }

        RangeRail(frac, displayMode == "fine")
    }
}
