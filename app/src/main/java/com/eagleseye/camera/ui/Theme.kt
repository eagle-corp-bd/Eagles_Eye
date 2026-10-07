package com.eagleseye.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eagleseye.camera.AppAccent

/**
 * EaglesEye design system — dark studio aesthetic with a warm gold signature
 * accent (Apple/Samsung-grade chrome: glass panels, precise type, restrained color).
 */
object AppColors {
    val BG = Color(0xFF030304)          // app background (near-black)
    val SURFACE = Color(0xFF0A0A0C)     // raised panels
    val SURFACE_2 = Color(0xFF131318)   // elevated panels
    val BORDER = Color(0xFF1C1C22)      // hairline borders
    val BORDER_HI = Color(0xFF2A2A32)

    // Accent family is dynamic — follows Settings → Appearance, so goldBrush(),
    // labels and icons tinted with these all switch with the user accent.
    val GOLD get() = AppAccent.color        // signature accent
    val GOLD_HI get() = AppAccent.hi        // bright accent
    val GOLD_DEEP get() = AppAccent.deep

    val RED = Color(0xFFE23838)         // recording / danger
    val GREEN = Color(0xFF34C759)       // active / live (iOS green)
    val BLUE = Color(0xFF0A84FF)        // interactive / links (iOS blue)

    val TEXT = Color(0xFFF5F2ED)        // primary text
    val TEXT_DIM = Color(0xFFB8B4AC)    // secondary text
    val MUTED = Color(0xFF3E3E48)       // tertiary / icons
    val MUTED2 = Color(0xFF565660)

    // Glass fill helpers — translucent dark + hairline highlight for the "frosted" look
    val GLASS = Color(0xCC0A0A0C)
    val GLASS_LIGHT = Color(0xE6131318)
    val GLASS_HI = Color(0x1AFFFFFF)    // top-edge highlight
    val GLASS_HAIRLINE = Color(0x33FFFFFF)
}

object AppType {
    // SF-style: system sans-serif, tight tracking, tabular numerics for data
    val Display = FontFamily.SansSerif
    val Mono = FontFamily.Monospace

    val caption = TextStyle(fontFamily = Display, fontSize = 9.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontFamily = Display, fontSize = 11.sp, letterSpacing = 0.2.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontFamily = Display, fontSize = 15.sp, letterSpacing = 0.1.sp, fontWeight = FontWeight.Bold)
    val monoData = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.2.sp, fontWeight = FontWeight.Medium)
    val monoTiny = TextStyle(fontFamily = Mono, fontSize = 7.sp, letterSpacing = 0.08.sp, fontWeight = FontWeight.Bold)
}

object AppShape {
    val pill = RoundedCornerShape(50)
    val tile = RoundedCornerShape(14.dp)
    val tileSm = RoundedCornerShape(10.dp)
}

/**
 * Frosted glass panel: translucent fill + top-edge highlight + hairline border.
 * Drop-in replacement for flat dark panels.
 */
fun Modifier.glass(
    fill: Color = AppColors.GLASS,
    corner: Shape = AppShape.pill,
    borderColor: Color = AppColors.BORDER,
    highlight: Boolean = true
): Modifier = this
    .background(fill, corner)
    .border(1.dp, borderColor, corner)
    .then(if (highlight) Modifier.border(1.dp, AppColors.GLASS_HI.copy(alpha = 0.28f), corner) else Modifier)

/** Vertical gold gradient — signature accent fill for primary controls. */
fun goldBrush(): Brush = Brush.linearGradient(listOf(AppColors.GOLD_HI, AppColors.GOLD, AppColors.GOLD_DEEP))

/** Dark panel gradient. */
fun darkBrush(): Brush = Brush.linearGradient(listOf(AppColors.SURFACE_2, AppColors.BG))
