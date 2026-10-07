package com.eagleseye.camera

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val CIRCLE = RoundedCornerShape(50)

private val GOLD: Color get() = AppAccent.color
private val GOLD_HI: Color get() = AppAccent.hi
private val BG = Color(0xFF030304)
private val SURFACE = Color(0xFF0A0A0C)
private val BORDER = Color(0xFF1C1C22)
private val TEXT_COLOR = Color(0xFFF0EDE8)
private val MUTED = Color(0xFF3E3E48)

private val ASPECTS = listOf("FULL" to "FULL", "16:9" to "16:9", "3:2" to "3:2", "4:3" to "4:3", "1:1" to "1:1", "9:16" to "9:16")
private val GRIDS = listOf("off" to "OFF", "3x3" to "3×3", "5x5" to "5×5", "4x4" to "4×4", "golden" to "GOLDEN", "center" to "CENTER", "diagonal" to "DIAG")
private val TIMERS = listOf("off" to "OFF", "3s" to "3S", "10s" to "10S")
private val FLASHES = listOf("off" to "OFF", "auto" to "AUTO", "on" to "ON", "fill" to "FILL")
private val AW = mapOf("FULL" to 16f, "16:9" to 17f, "3:2" to 16f, "4:3" to 15f, "1:1" to 13f, "9:16" to 9.5f)
private val AH = mapOf("FULL" to 16f, "16:9" to 9.5f, "3:2" to 10.7f, "4:3" to 11.25f, "1:1" to 13f, "9:16" to 17f)
private val GV = mapOf("3x3" to listOf(6.33f, 12.67f), "5x5" to listOf(4f, 7.5f, 11f, 14.5f), "4x4" to listOf(4.75f, 9.5f, 14.25f), "golden" to listOf(7.4f, 11.6f), "center" to listOf(9.5f))
private val GH = mapOf("3x3" to listOf(6.33f, 12.67f), "5x5" to listOf(4f, 7.5f, 11f, 14.5f), "4x4" to listOf(4.75f, 9.5f, 14.25f), "golden" to listOf(7.4f, 11.6f), "center" to listOf(9.5f))

@Composable private fun IconBox(c: @Composable () -> Unit) { Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) { c() } }
@Composable private fun AspectGlyph(r: String, c: Color) { val w = AW[r] ?: 15f; val h = AH[r] ?: 11.25f; IconBox { Canvas(Modifier.size(19.dp)) { val x = (19f - w) / 2f; val y = (19f - h) / 2f; drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(1.5f), style = Stroke(1.5f)) } } }
@Composable private fun GridGlyph(t: String, c: Color) { val v = GV[t] ?: emptyList(); val h = GH[t] ?: emptyList(); IconBox { Canvas(Modifier.size(19.dp)) { val o = if (t == "off") 0.55f else 1f; drawRoundRect(c.copy(alpha = o), Offset(2f, 2f), Size(15f, 15f), CornerRadius(2f), style = Stroke(1.3f)); if (t == "diagonal") { drawLine(c.copy(alpha = 0.85f), Offset(2f, 2f), Offset(17f, 17f), 1f); drawLine(c.copy(alpha = 0.85f), Offset(17f, 2f), Offset(2f, 17f), 1f) }; v.forEach { x -> drawLine(c.copy(alpha = 0.85f), Offset(x, 2f), Offset(x, 17f), 0.9f) }; h.forEach { y -> drawLine(c.copy(alpha = 0.85f), Offset(2f, y), Offset(17f, y), 0.9f) } } } }
@Composable private fun TimerIcon(c: Color) { IconBox { Canvas(Modifier.size(17.dp)) { drawCircle(c, 8f, style = Stroke(1.6f), center = Offset(12f, 13f)); val ex = 12f + 3f * cos(0.5f); val ey = 13f + 3f * sin(0.5f); drawLine(c, Offset(12f, 13f), Offset(ex, ey), 1.6f); drawLine(c, Offset(9.5f, 2f), Offset(14.5f, 2f), 1.6f) } } }
@Composable private fun FlashIcon(c: Color, filled: Boolean) { IconBox { Canvas(Modifier.size(16.dp, 17.dp)) { val p = Path().apply { moveTo(13f, 2f); lineTo(4f, 14f); lineTo(10f, 14f); lineTo(9f, 22f); lineTo(18f, 10f); lineTo(12f, 10f); close() }; if (filled) drawPath(p, c); drawPath(p, c, style = Stroke(1.5f)) } } }
@Composable private fun FlipIcon(c: Color) { IconBox { Canvas(Modifier.size(18.dp, 17.dp)) { drawLine(c, Offset(4f, 9f), Offset(9f, 6f), 1.6f); drawLine(c, Offset(9f, 6f), Offset(14f, 5.5f), 1.6f); drawLine(c, Offset(14f, 4f), Offset(14f, 8f), 1.6f); drawLine(c, Offset(10f, 4f), Offset(14f, 4f), 1.6f); drawLine(c, Offset(20f, 15f), Offset(15f, 18f), 1.6f); drawLine(c, Offset(15f, 18f), Offset(10f, 18.5f), 1.6f); drawLine(c, Offset(10f, 20f), Offset(10f, 16f), 1.6f); drawLine(c, Offset(14f, 20f), Offset(10f, 20f), 1.6f) } } }
@Composable private fun StarGlyph(c: Color) { IconBox { Icon(Icons.Default.Star, null, tint = c, modifier = Modifier.size(16.dp)) } }

@Composable fun BarControl(icon: @Composable () -> Unit, label: String?, active: Boolean, description: String, onToggle: () -> Unit) {
    Column(
        Modifier
            .clickable(remember { MutableInteractionSource() }, null) { onToggle() }
            .semantics { contentDescription = description }
            .defaultMinSize(minWidth = 44.dp)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        icon()
        Text(label ?: "\u00A0", fontFamily = UiFont, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium, color = if (active) GOLD else MUTED, maxLines = 1)
    }
}

@Composable fun CtrlDivider() { Box(Modifier.width(1.dp).height(16.dp).background(Brush.verticalGradient(listOf(Color.Transparent, BORDER, Color.Transparent)))) }

/** Simple drop-down pill: active option statically filled gold. No moving pill, no slide. */
@Composable fun OptionsPanel(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Box(
        Modifier
            .shadow(16.dp, CIRCLE, true, Color(0xCC000000), Color(0x00000000))
            .clip(CIRCLE)
            .background(Color(0xF20A0A0C))
            .border(1.dp, BORDER, CIRCLE)
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { (ov, ol) ->
                val ia = ov == value
                Box(
                    Modifier
                        .clip(CIRCLE)
                        .then(if (ia) Modifier.background(GOLD) else Modifier)
                        .clickable(remember { MutableInteractionSource() }, null) { onPick(ov) }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(ol, fontFamily = UiFont, fontSize = 9.sp, fontWeight = if (ia) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.5.sp, color = if (ia) BG else TEXT_COLOR.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable fun TopControls(
    aspect: String, grid: String, timer: String, flash: String, flipped: Boolean,
    onAspect: (String) -> Unit, onGrid: (String) -> Unit, onTimer: (String) -> Unit, onFlash: (String) -> Unit, onFlip: () -> Unit,
    modifier: Modifier = Modifier,
    starActive: Boolean = false, onStar: () -> Unit = {}
) {
    var open by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }

    // Back closes the options dropdown first instead of leaving the camera.
    BackHandler(enabled = open && openId != null) {
        openId = null
        open = false
    }

    val mw by animateDpAsState(if (open) 400.dp else 0.dp, spring(dampingRatio = 0.65f, stiffness = 220f), label = "mw")
    val bo by animateFloatAsState(if (open) 1f else 0f, tween(220), label = "bo")
    val barPadH by animateDpAsState(if (open) 4.dp else 0.dp, tween(220), label = "bph")
    val barPadV by animateDpAsState(if (open) 2.dp else 0.dp, tween(220), label = "bpv")

    Box(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            // Collapsible controls bar (grows leftward from the three-dot handle)
            Box(
                Modifier
                    .widthIn(max = mw)
                    .height(38.dp)
                    .alpha(bo)
                    .clip(CIRCLE)
                    .background(Color(0xF20A0A0C))
                    .border(1.dp, BORDER, CIRCLE)
                    .padding(horizontal = barPadH, vertical = barPadV),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BarControl(icon = { StarGlyph(if (starActive) GOLD_HI else TEXT_COLOR.copy(alpha = 0.75f)) }, label = if (starActive) "FX" else null, active = starActive, description = "Feature menu", onToggle = onStar)
                    CtrlDivider()
                    BarControl(icon = { AspectGlyph(aspect, if (aspect != "FULL") GOLD_HI else TEXT_COLOR.copy(alpha = 0.8f)) }, label = if (aspect != "FULL") aspect else null, active = aspect != "FULL", description = "Aspect ratio") { openId = if (openId == "aspect") null else "aspect" }
                    CtrlDivider()
                    val gl = if (grid != "off") GRIDS.find { it.first == grid }?.second else null
                    BarControl(icon = { GridGlyph(grid, if (grid != "off") GOLD_HI else TEXT_COLOR.copy(alpha = 0.8f)) }, label = gl, active = grid != "off", description = "Grid overlay") { openId = if (openId == "grid") null else "grid" }
                    CtrlDivider()
                    BarControl(icon = { TimerIcon(if (timer != "off") GOLD_HI else TEXT_COLOR.copy(alpha = 0.8f)) }, label = if (timer != "off") timer.uppercase() else null, active = timer != "off", description = "Timer") { openId = if (openId == "timer") null else "timer" }
                    CtrlDivider()
                    val fc = when (flash) { "on", "fill" -> GOLD_HI; "auto" -> GOLD; else -> TEXT_COLOR.copy(alpha = 0.8f) }
                    BarControl(icon = { FlashIcon(fc, flash == "on" || flash == "fill") }, label = if (flash != "off") flash.uppercase() else null, active = flash != "off", description = "Flash") { openId = if (openId == "flash") null else "flash" }
                    CtrlDivider()
                    Box(Modifier.clickable(remember { MutableInteractionSource() }, null) { onFlip() }.semantics { contentDescription = "Flip camera" }.padding(horizontal = 12.dp, vertical = 8.dp).graphicsLayer(rotationY = if (flipped) 180f else 0f, scaleX = if (flipped) 1.06f else 1f, scaleY = if (flipped) 1.06f else 1f)) { FlipIcon(if (flipped) GOLD_HI else TEXT_COLOR.copy(alpha = 0.8f)) }
                }
            }
            Spacer(Modifier.width(8.dp))
            // Three-dot handle — toggles the bar open/closed
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CIRCLE)
                    .background(if (open) GOLD else Color(0xF20A0A0C))
                    .border(1.dp, if (open) GOLD else BORDER, CIRCLE)
                    .clickable(remember { MutableInteractionSource() }, null) { open = !open; openId = null }
                    .semantics { contentDescription = "Toggle controls" },
                contentAlignment = Alignment.Center
            ) {
                val c = if (open) BG else TEXT_COLOR.copy(alpha = 0.8f)
                if (open) Canvas(Modifier.size(18.dp)) { drawLine(c, Offset(4f, 4f), Offset(14f, 14f), 1.8f); drawLine(c, Offset(14f, 4f), Offset(4f, 14f), 1.8f) }
                else Canvas(Modifier.size(18.dp)) { for (i in 0..2) drawCircle(c, radius = 1f, center = Offset(9f, 5f + i * 7f)) }
            }
        }

        // Drop-down panel below the bar (right-aligned under the handle)
        if (open && openId != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 46.dp)) {
                when (openId) {
                    "aspect" -> OptionsPanel(ASPECTS, aspect, onAspect)
                    "grid" -> OptionsPanel(GRIDS, grid, onGrid)
                    "timer" -> OptionsPanel(TIMERS, timer, onTimer)
                    "flash" -> OptionsPanel(FLASHES, flash, onFlash)
                }
            }
        }
    }
}