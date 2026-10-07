package com.eagleseye.camera

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val GOLD: Color get() = AppAccent.color
private val GOLD_HI: Color get() = AppAccent.hi
private val BG = Color(0xFF030304)
private val SURFACE = Color(0xFF0A0A0C)
private val BORDER = Color(0xFF1C1C22)
private val TEXT = Color(0xFFF0EDE8)
private val CIRCLE = RoundedCornerShape(50)
private val FONT = FontFamily.SansSerif

private data class ModeDef(val id: String, val label: String)

private val MODES = listOf(
    ModeDef("pano", "PANO"),
    ModeDef("slomo", "SLO-MO"),
    ModeDef("photo", "PHOTO"),
    ModeDef("video", "VIDEO"),
    ModeDef("hyperlapse", "HYPERLAPSE"),
)

// Distinct glyph per mode — camera-grade icons, hand-drawn on Canvas
@Composable
private fun ModeGlyph(id: String, c: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val sw = s * 0.075f
        when (id) {
            "pano" -> {
                val y = s * 0.28f
                drawRoundRect(c, Offset(s * 0.14f, y), Size(s * 0.72f, s * 0.44f), CornerRadius(s * 0.12f), style = Stroke(sw))
                drawLine(c, Offset(s * 0.68f, y), Offset(s * 0.62f, y - s * 0.16f), sw)
                drawLine(c, Offset(s * 0.68f, y), Offset(s * 0.74f, y - s * 0.16f), sw)
            }
            "slomo" -> {
                val p = Path().apply { moveTo(s * 0.3f, s * 0.22f); lineTo(s * 0.72f, s * 0.44f); lineTo(s * 0.3f, s * 0.66f); close() }
                drawPath(p, c)
                drawLine(c, Offset(s * 0.72f, s * 0.22f), Offset(s * 0.72f, s * 0.66f), sw)
                drawLine(c, Offset(s * 0.8f, s * 0.22f), Offset(s * 0.8f, s * 0.66f), sw)
            }
            "hyperlapse" -> {
                val p = Path().apply { moveTo(s * 0.24f, s * 0.3f); lineTo(s * 0.6f, s * 0.5f); lineTo(s * 0.24f, s * 0.7f); close() }
                drawPath(p, c)
                drawLine(c, Offset(s * 0.6f, s * 0.3f), Offset(s * 0.6f, s * 0.7f), sw)
                drawLine(c, Offset(s * 0.72f, s * 0.3f), Offset(s * 0.72f, s * 0.7f), sw)
                drawLine(c, Offset(s * 0.6f, s * 0.08f), Offset(s * 0.72f, s * 0.14f), sw)
                drawLine(c, Offset(s * 0.72f, s * 0.14f), Offset(s * 0.6f, s * 0.2f), sw)
            }
            "video" -> {
                val p = Path().apply { moveTo(s * 0.32f, s * 0.24f); lineTo(s * 0.7f, s * 0.5f); lineTo(s * 0.32f, s * 0.76f); close() }
                drawPath(p, c)
            }
            else -> {
                drawCircle(c, radius = s * 0.34f, style = Stroke(sw))
                drawLine(c, Offset(s * 0.5f, s * 0.24f), Offset(s * 0.5f, s * 0.44f), sw)
                drawLine(c, Offset(s * 0.5f, s * 0.5f), Offset(s * 0.64f, s * 0.6f), sw)
            }
        }
    }
}

@Composable private fun MiniGooSwitch(mode: String, modifier: Modifier = Modifier) {
    val target = if (mode == "video") 1f else 0f
    val pos = remember { Animatable(if (mode == "video") 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(target) {
        pos.animateTo(target, spring(dampingRatio = 0.55f, stiffness = 420f))
    }
    val p = pos.value
    val W = 88f; val H = 26f; val PAD = 3f; val HALF = (W - PAD * 2) / 2f; val BLOB_W = HALF - 3f; val BLOB_H = H - PAD * 2f

    Box(modifier.size(W.dp, H.dp).clip(CIRCLE)) {
        Box(Modifier.matchParentSize().clip(CIRCLE).background(Brush.linearGradient(listOf(Color(0xFF1C1C1F), Color(0xFF030304)))).border(1.dp, BORDER, CIRCLE))
        Box(
            Modifier
                .offset { IntOffset((PAD + 1 + p * HALF).dp.roundToPx(), PAD.dp.roundToPx()) }
                .size(BLOB_W.dp, BLOB_H.dp)
                .clip(CIRCLE)
                .background(Brush.linearGradient(listOf(GOLD_HI, GOLD, AppAccent.deep)))
                .shadow(4.dp, CIRCLE, false, GOLD.copy(alpha = 0.4f), Color(0x00000000))
        )
        Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeGlyph("photo", GOLD_HI, 10.dp)
                    Text("PHOTO", fontFamily = FONT, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = if (p < 0.5f) BG else TEXT.copy(alpha = 0.6f))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ModeGlyph("video", GOLD_HI, 10.dp)
                    Text("VIDEO", fontFamily = FONT, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, color = if (p > 0.5f) BG else TEXT.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun ModePill(
    mode: String,
    onToggleVideo: () -> Unit,
    onPickMode: (String) -> Unit,
    disabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    // Back collapses the mode carousel instead of leaving the camera.
    BackHandler(enabled = expanded) { expanded = false }
    val haptic = LocalHapticFeedback.current
    val activeMeta = MODES.find { it.id == mode } ?: MODES[2]
    val isSwitchFace = !expanded && (mode == "photo" || mode == "video")

    val pick: (String) -> Unit = { id ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onPickMode(id)
        expanded = false
    }

    Box(modifier) {
        if (isSwitchFace) {
            // Tap toggles photo⇄video, long-press opens the full picker
            Box(
                Modifier
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !disabled,
                        onClick = { onToggleVideo() },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); expanded = true }
                    )
                    .clip(CIRCLE)
                    .padding(0.dp)
            ) {
                MiniGooSwitch(mode = mode)
            }
        } else if (!expanded) {
            // Current non-switch mode → tap or long-press opens the picker
            Box(
                Modifier
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !disabled,
                        onClick = { expanded = true },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); expanded = true }
                    )
                    .clip(CIRCLE)
                    .shadow(10.dp, CIRCLE, false, Color(0xAA000000), Color(0x00000000))
                    .background(SURFACE.copy(alpha = 0.78f))
                    .border(1.dp, BORDER, CIRCLE)
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ModeGlyph(mode, GOLD_HI, 13.dp)
                    Text(activeMeta.label, fontFamily = FONT, fontSize = 9.sp, letterSpacing = 0.6.sp, color = GOLD_HI, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            // Expanded 5-mode picker
            Row(
                Modifier
                    .clip(CIRCLE)
                    .shadow(12.dp, CIRCLE, false, Color(0xAA000000), Color(0x00000000))
                    .background(SURFACE.copy(alpha = 0.82f))
                    .border(1.dp, BORDER, CIRCLE)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                MODES.forEach { m ->
                    val isActive = m.id == mode
                    Box(
                        Modifier
                            .clip(CIRCLE)
                            .then(if (isActive) Modifier.background(Brush.linearGradient(listOf(GOLD_HI, GOLD))) else Modifier)
                            .clickable(remember { MutableInteractionSource() }, null) { pick(m.id) }
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            ModeGlyph(m.id, if (isActive) BG else TEXT.copy(alpha = 0.67f), 13.dp)
                            Text(m.label, fontFamily = FONT, fontSize = 6.5.sp, letterSpacing = 0.4.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, color = if (isActive) BG else TEXT.copy(alpha = 0.47f))
                        }
                    }
                }
            }
        }
    }
}