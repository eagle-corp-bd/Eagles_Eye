package com.eagleseye.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.collectIsPressedAsState

// ── Shared design tokens ─────────────────────────────────────────────────────
// One palette + type scale across camera, gallery, editor, settings and sheets.
// The gold family is dynamic: every consumer follows the user accent
// (Settings → Appearance), so no UI element ever stays stuck on the stock gold.
val UiGold: Color get() = AppAccent.color
val UiGoldHi: Color get() = AppAccent.hi
val UiGoldDeep: Color get() = AppAccent.deep
val UiBg       = Color(0xFF030304)   // deep background
val UiSurface  = Color(0xFF0A0A0C)   // panels
val UiSurfaceHi= Color(0xFF14141A)   // raised cards
val UiBorder   = Color(0xFF1C1C22)   // hairline borders
val UiText     = Color(0xFFF0EDE8)   // primary text
val UiTextDim  = Color(0xFF9A9AA2)   // secondary text
val UiRed      = Color(0xFFE23838)   // recording / destructive
val UiGreen    = Color(0xFF4CAF50)   // success / live states

val UiRadiusPill = RoundedCornerShape(50)
val UiRadiusCard = RoundedCornerShape(22.dp)
val UiRadiusSm   = RoundedCornerShape(12.dp)
val UiFont = FontFamily.SansSerif
val UiFontMono = FontFamily.Monospace

// ── Theme accent — user-customizable (Settings → Appearance) ─────────────────
data class AccentPalette(val name: String, val color: Color, val hi: Color, val deep: Color)

val ACCENT_PALETTES = listOf(
    AccentPalette("GOLD",   Color(0xFFC9A96E), Color(0xFFE8CD98), Color(0xFF9C7C4A)),
    AccentPalette("ROSE",   Color(0xFFE56A8A), Color(0xFFFF9BB3), Color(0xFFB84A6E)),
    AccentPalette("TEAL",   Color(0xFF4FB8A8), Color(0xFF7ADBCB), Color(0xFF2E877A)),
    AccentPalette("VIOLET", Color(0xFF9B8AF0), Color(0xFFBCB0FA), Color(0xFF6B5BC0)),
    AccentPalette("GREEN",  Color(0xFF6FBF73), Color(0xFF9BDF9E), Color(0xFF478C4B))
)

object AppAccent {
    var color by mutableStateOf(ACCENT_PALETTES[0].color)
    var hi by mutableStateOf(ACCENT_PALETTES[0].hi)
    var deep by mutableStateOf(ACCENT_PALETTES[0].deep)
    var index by mutableStateOf(0)
    fun apply(i: Int) {
        index = i.coerceIn(0, ACCENT_PALETTES.lastIndex)
        val p = ACCENT_PALETTES[index]
        color = p.color; hi = p.hi; deep = p.deep
    }
}

/** Dark glass panel: translucent surface + hairline border + soft shadow. */
fun Modifier.uiGlass(radius: Shape = UiRadiusCard): Modifier =
    this.shadow(18.dp, radius, false, Color(0x66000000), Color(0x00000000))
        .clip(radius)
        .background(Color(0xF00F0F12))
        .border(0.5.dp, Color.White.copy(0.10f), radius)

/** Dynamic accent gradient brush used by pills, thumbs and the shutter core. */
fun UiGoldGradient(): Brush = Brush.linearGradient(listOf(AppAccent.hi, AppAccent.color, AppAccent.deep))

/** Scale-on-press touch feedback (iOS-like). Pair with clickable(). */
@Composable
fun Modifier.uiPressScale(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    pressedScale: Float = 0.94f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    return this.graphicsLayer {
        val s = if (pressed) pressedScale else 1f
        scaleX = s; scaleY = s
    }
}

/** Pill button — gold (filled) or ghost variant. */
@Composable
fun UiPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    small: Boolean = false
) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier
            .uiPressScale(src)
            .clip(UiRadiusPill)
            .then(
                if (filled) Modifier.background(UiGoldGradient())
                else Modifier.background(Color.White.copy(0.09f))
            )
            .clickable(src, null) { onClick() }
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (small) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (filled) UiBg else UiText.copy(0.85f),
            fontFamily = UiFont, fontWeight = FontWeight.Bold,
            fontSize = if (small) 11.sp else 12.sp, letterSpacing = 1.sp
        )
    }
}

// ── iOS-style settings group ─────────────────────────────────────────────────
@Composable
fun UiSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(UiRadiusCard)
            .background(UiSurfaceHi)
            .border(0.5.dp, Color.White.copy(0.08f), UiRadiusCard),
        content = content
    )
}

@Composable
fun UiCardRow(
    onClick: (() -> Unit)? = null,
    chevron: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.uiPressScale(src, 0.98f).clickable(src, null) { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
        if (chevron && onClick != null) {
            Icon(
                Icons.Default.ChevronRight, null, tint = Color.White.copy(0.25f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun UiCardDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp)
            .height(0.5.dp)
            .background(Color.White.copy(0.08f))
    )
}

/** Gold iOS-style switch (51×31). */
@Composable
fun UiSwitch(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val w = 51.dp; val h = 31.dp
    Box(
        Modifier
            .size(w, h)
            .uiPressScale(src, 0.95f)
            .clip(UiRadiusPill)
            .then(
                if (checked) Modifier.background(Brush.linearGradient(listOf(UiGoldHi, UiGoldDeep)))
                else Modifier.background(Color.White.copy(0.16f))
            )
            .clickable(src, null) { onToggle(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(h - 4.dp)
                .clip(UiRadiusPill)
                .background(Color.White)
                .shadow(2.dp, UiRadiusPill, false, Color(0x55000000), Color.Transparent)
        )
    }
}

@Composable
fun UiSectionHeader(title: String) {
    Text(
        title,
        color = UiGold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(start = Spacing.xl, top = Spacing.xxl, bottom = Spacing.sm)
    )
}

/** Slider row in the iOS-style settings cards (accent-coloured thumb/track). */
@Composable
fun UiSliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
    fmt: (Float) -> String = { "${(it * 100).toInt()}%" }
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White.copy(0.75f), fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(fmt(value), color = UiGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
    Slider(
        value = value.coerceIn(min, max),
        onValueChange = onChange,
        valueRange = min..max,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = SliderDefaults.colors(
            thumbColor = UiGold,
            activeTrackColor = UiGold,
            inactiveTrackColor = Color.White.copy(0.1f)
        )
    )
}

// ── 8pt spacing scale ─────────────────────────────────────────────────────
// Every margin/padding/gap in the app must come from here (or the 4pt sub-steps).
object Spacing {
    val xxs = 2.dp      // hairlines, borders offset
    val xs = 4.dp      // icon+label gap
    val sm = 8.dp      // small gaps between related elements
    val md = 12.dp     // compact component padding
    val lg = 16.dp     // default component padding / primary gaps
    val xl = 24.dp     // section gaps
    val xxl = 32.dp    // spacing between components
    val xxxl = 48.dp   // section spacing (small)
    val huge = 64.dp   // section spacing (large)
}

// ── Motion durations ────────────────────────────────────────────────────────
object UiMotion {
    const val FastMs = 160
    const val MedMs = 240
    const val SlowMs = 380
}
