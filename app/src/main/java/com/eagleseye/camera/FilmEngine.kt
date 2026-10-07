package com.eagleseye.camera

import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.eagleseye.camera.engine.FrameDraw
import java.text.SimpleDateFormat
import java.util.*

enum class LightLeakColor { ORANGE, BLUE, RAINBOW, RANDOM }

// Retro film composite, forwarded to the GL renderer so the
// viewfinder (and recorded video) mirrors the CPU FilmOverlay exactly.
data class GlFilmStyle(
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val leakAmt: Float = 0f,
    val leakR: Float = 1f, val leakG: Float = 0.4f, val leakB: Float = 0f,
    val adaptive: Boolean = true,
    val tintR: Float = 1f, val tintG: Float = 1f, val tintB: Float = 1f,
    val tintAlpha: Float = 0f,
    val warmth: Float = 0f,
    val fade: Float = 0f,
    val mono: Boolean = false,
    // Film-stock grade (ported from photoncam applyGradeAndRolloff)
    val filmSat: Float = 1f,
    val filmContrast: Float = 1f,
    val filmLift: Float = 0f,
    val filmRolloff: Float = 0f
) {
    val active: Boolean
        get() = grain > 0.001f || vignette > 0.001f || leakAmt > 0.001f ||
                tintAlpha > 0.001f || kotlin.math.abs(warmth) > 0.001f || fade > 0.001f || mono ||
                filmSat != 1f || filmContrast != 1f || filmLift > 0.001f || filmRolloff > 0.001f
}
enum class DateStampStyle { ORANGE_FILM, WHITE_BORDER, YELLOW_PAINT, TYPEWRITER, MINIMAL_GRAY, VINTAGE_RED, LED_DIGITAL, FILM_BLUE, PINK_NEON, GOLD_SERIF, INK_CURSIVE, TAPE_BOLD, AMBER_GLOW, TIMER_GLOW }
enum class DateFormatType { DD_MM_YY, MM_DD_YY, YYYY_MM_DD, MM_YY }

// Film-date palette — shared by the analog sheet, live preview texture
// and the capture pipeline so the stamp looks identical everywhere.
fun dateStampColor(style: DateStampStyle): Int = when (style) {
    DateStampStyle.ORANGE_FILM   -> android.graphics.Color.rgb(255, 146, 0)
    DateStampStyle.WHITE_BORDER  -> android.graphics.Color.rgb(255, 255, 255)
    DateStampStyle.YELLOW_PAINT  -> android.graphics.Color.rgb(210, 224, 82)
    DateStampStyle.TYPEWRITER    -> android.graphics.Color.rgb(42, 42, 42)
    DateStampStyle.MINIMAL_GRAY  -> android.graphics.Color.rgb(185, 185, 185)
    DateStampStyle.VINTAGE_RED   -> android.graphics.Color.rgb(205, 72, 55)
    DateStampStyle.LED_DIGITAL   -> android.graphics.Color.rgb(120, 255, 0)
    DateStampStyle.FILM_BLUE     -> android.graphics.Color.rgb(123, 168, 255)
    DateStampStyle.PINK_NEON     -> android.graphics.Color.rgb(255, 60, 190)
    DateStampStyle.GOLD_SERIF    -> android.graphics.Color.rgb(231, 200, 91)
    DateStampStyle.INK_CURSIVE   -> android.graphics.Color.rgb(46, 44, 44)
    DateStampStyle.TAPE_BOLD     -> android.graphics.Color.rgb(255, 255, 255)
    DateStampStyle.AMBER_GLOW     -> android.graphics.Color.rgb(255, 150, 20)
    DateStampStyle.TIMER_GLOW    -> android.graphics.Color.rgb(255, 110, 10)
}

fun dateStampFontColor(style: DateStampStyle): Int = android.graphics.Color.argb(
    when (style) {
        DateStampStyle.ORANGE_FILM, DateStampStyle.WHITE_BORDER -> 235
        DateStampStyle.YELLOW_PAINT, DateStampStyle.VINTAGE_RED -> 215
        DateStampStyle.MINIMAL_GRAY -> 150
        DateStampStyle.TYPEWRITER -> 235
        DateStampStyle.LED_DIGITAL, DateStampStyle.GOLD_SERIF, DateStampStyle.INK_CURSIVE, DateStampStyle.PINK_NEON -> 235
        DateStampStyle.FILM_BLUE, DateStampStyle.TAPE_BOLD -> 245
        DateStampStyle.AMBER_GLOW, DateStampStyle.TIMER_GLOW -> 250
    },
    android.graphics.Color.red(dateStampColor(style)),
    android.graphics.Color.green(dateStampColor(style)),
    android.graphics.Color.blue(dateStampColor(style))
)

// One shared visual spec for every stamp type, used by BOTH the live preview
// texture (FrameTextureGenerator.renderStamp) and the capture pipeline
// (FilmEngine.applyTimestamp) so preview == saved photo.

// Glow emulates a printed-date bloom: a soft warm halo drawn behind the
// text plus a brighter hot core, then the crisp fill on top. glowRadius is a
// fraction of the current text size so it scales with preview and capture.
data class StampGlow(
    val color: Int = 0,
    val radius: Float = 0f,
    val core: Int = 0
)

data class StampVisual(
    val color: Int,
    val typeface: android.graphics.Typeface,
    val shadowRadius: Float = 0f,
    val shadowDx: Float = 0f,
    val shadowDy: Float = 0f,
    val shadowColor: Int = 0x00000000,
    val letterSpacing: Float = 0f,
    val rotation: Float = 0f,
    val chipColor: Int = 0x00000000,
    val stroke: Boolean = false,
    val glow: StampGlow = StampGlow(),
    // LCD readout: digits rendered as 7-segment bars instead of a font.
    // Drawn natively with Canvas so no font file ships and nothing third-party
    // (fonts are the only copied-looking asset; this avoids it entirely).
    val lcd: Boolean = false
)

fun dateStampVisual(style: DateStampStyle): StampVisual = when (style) {
    DateStampStyle.ORANGE_FILM   -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD), 4f, 1f, 1f, android.graphics.Color.argb(150, 0, 0, 0), glow = StampGlow(android.graphics.Color.argb(235, 255, 120, 10), 0.45f, android.graphics.Color.argb(255, 255, 240, 200)), lcd = true)
    DateStampStyle.WHITE_BORDER  -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD), 5f, 1f, 1f, android.graphics.Color.argb(190, 0, 0, 0), glow = StampGlow(android.graphics.Color.argb(170, 255, 210, 160), 0.4f, android.graphics.Color.argb(220, 255, 250, 235)), lcd = true)
    DateStampStyle.YELLOW_PAINT  -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD), 3f, 1f, 1f, android.graphics.Color.argb(110, 0, 0, 0), rotation = -0.04f)
    DateStampStyle.TYPEWRITER    -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD), 3f, 1f, 1f, android.graphics.Color.argb(80, 0, 0, 0), chipColor = android.graphics.Color.argb(200, 255, 255, 240))
    DateStampStyle.MINIMAL_GRAY  -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.MONOSPACE)
    DateStampStyle.VINTAGE_RED   -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD), 2f, 0.5f, 0.5f, android.graphics.Color.argb(70, 0, 0, 0), rotation = 0.03f)
    DateStampStyle.LED_DIGITAL   -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD), 5f, 0f, 0f, android.graphics.Color.argb(150, 60, 255, 0), letterSpacing = 0.28f, lcd = true)
    DateStampStyle.FILM_BLUE     -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL), 2f, 1f, 1f, android.graphics.Color.argb(120, 0, 0, 0), letterSpacing = 0.12f, lcd = true)
    DateStampStyle.PINK_NEON     -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create("cursive", android.graphics.Typeface.BOLD), 6f, 0f, 0f, android.graphics.Color.argb(150, 255, 50, 180))
    DateStampStyle.GOLD_SERIF    -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD_ITALIC), 3f, 1f, 1f, android.graphics.Color.argb(130, 0, 0, 0))
    DateStampStyle.INK_CURSIVE   -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL), 1f, 0.5f, 0.5f, android.graphics.Color.argb(60, 0, 0, 0))
    DateStampStyle.TAPE_BOLD     -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD), 2f, 1f, 1f, android.graphics.Color.argb(200, 0, 0, 0), stroke = true)
    DateStampStyle.AMBER_GLOW    -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD), 6f, 1f, 1f, android.graphics.Color.argb(120, 0, 0, 0), letterSpacing = 0.06f, glow = StampGlow(android.graphics.Color.argb(220, 255, 130, 0), 0.5f, android.graphics.Color.argb(255, 255, 235, 190)), lcd = true)
    DateStampStyle.TIMER_GLOW   -> StampVisual(dateStampFontColor(style), android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD), 5f, 1f, 1f, android.graphics.Color.argb(120, 0, 0, 0), letterSpacing = 0.12f, glow = StampGlow(android.graphics.Color.argb(230, 255, 90, 0), 0.5f, android.graphics.Color.argb(255, 255, 220, 170)), lcd = true)
}

fun dateStampStyleName(s: DateStampStyle): String = when (s) {
    DateStampStyle.ORANGE_FILM -> "ORANGE"; DateStampStyle.WHITE_BORDER -> "WHITE"
    DateStampStyle.YELLOW_PAINT -> "YELLOW"; DateStampStyle.TYPEWRITER -> "TYPE"
    DateStampStyle.MINIMAL_GRAY -> "GRAY"; DateStampStyle.VINTAGE_RED -> "RED"
    DateStampStyle.LED_DIGITAL -> "LED"; DateStampStyle.FILM_BLUE -> "BLUE"
    DateStampStyle.PINK_NEON -> "NEON"; DateStampStyle.GOLD_SERIF -> "GOLD"
    DateStampStyle.INK_CURSIVE -> "INK"; DateStampStyle.TAPE_BOLD -> "TAPE"
    DateStampStyle.AMBER_GLOW -> "GLOW"; DateStampStyle.TIMER_GLOW -> "TIMER"
}

// Draws the stamp text (optional white-outline pass for TAPE_BOLD) then the fill.
// lcd=true renders 7-segment bars instead of a font — the LCD readout,
// drawn natively so no font file (or attribution) is ever needed.
fun drawStampText(cv: android.graphics.Canvas, text: String, x: Float, y: Float, p: android.graphics.Paint, stroke: Boolean, lcd: Boolean = false) {
    if (lcd) {
        val f = StampLcd.font
        if (f != null) { p.typeface = f; cv.drawText(text, x, y, p); return }
        drawLcdText(cv, text, x, y, p)
        return
    }
    if (stroke) {
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = p.textSize / 9f
        cv.drawText(text, x, y, p)
        p.style = android.graphics.Paint.Style.FILL
    }
    cv.drawText(text, x, y, p)
}

// ─── Native 7-segment LCD digits (the LCD date readout) ─────────────────────
// Digit bars are drawn with plain rects — no font asset, no third-party code.
// If the user drops the OFL-licensed DSEG7 ttf into assets/fonts (see
// assets/fonts/README.txt) it is used instead — commercial use is allowed by
// the SIL OFL license, credit is optional, and cannot obligate attribution.
object StampLcd {
    var font: android.graphics.Typeface? = null
        private set

    fun load(ctx: android.content.Context) {
        if (font != null) return
        font = try {
            android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/DSEG7Classic-Bold.ttf")
        } catch (e: Throwable) {
            null
        }
    }
}

private const val SEG_A = 1; private const val SEG_B = 2; private const val SEG_C = 4
private const val SEG_D = 8; private const val SEG_E = 16; private const val SEG_F = 32
private const val SEG_G = 64

private fun lcdSegments(c: Char): Int = when (c) {
    '0' -> SEG_A or SEG_B or SEG_C or SEG_D or SEG_E or SEG_F
    '1' -> SEG_B or SEG_C
    '2' -> SEG_A or SEG_B or SEG_G or SEG_E or SEG_D
    '3' -> SEG_A or SEG_B or SEG_G or SEG_C or SEG_D
    '4' -> SEG_F or SEG_G or SEG_B or SEG_C
    '5' -> SEG_A or SEG_F or SEG_G or SEG_C or SEG_D
    '6' -> SEG_A or SEG_F or SEG_G or SEG_E or SEG_C or SEG_D
    '7' -> SEG_A or SEG_B or SEG_C
    '8' -> SEG_A or SEG_B or SEG_C or SEG_D or SEG_E or SEG_F or SEG_G
    '9' -> SEG_A or SEG_B or SEG_C or SEG_D or SEG_F or SEG_G
    else -> -1
}

// Width of a full LCD digit cell in px for a given digit height.
fun lcdDigitAdvance(h: Float): Float = h * 0.64f

fun lcdTextWidth(text: String, h: Float, letterSpacing: Float = 0f): Float {
    var w = 0f
    for (ch in text) {
        w += when (ch) {
            ' ' -> lcdCellW(h) * 0.55f
            '.', ':' -> lcdCellW(h) * 0.42f + lcdDigitAdvance(h) * 0.16f
            else -> lcdDigitAdvance(h)
        }
        w += h * letterSpacing * 0.3f
    }
    return w
}

private fun lcdCellW(h: Float): Float = h * 0.62f
private fun lcdDigitW(h: Float): Float = h * 0.62f

fun drawLcdText(cv: android.graphics.Canvas, text: String, x: Float, baseY: Float, p: android.graphics.Paint) {
    val h = p.textSize * 0.96f
    val w0 = lcdDigitW(h)
    val t = h * 0.15f
    val gap = t * 0.5f
    val cy = baseY - h
    var cx = x
    val rect = android.graphics.RectF()
    fun bar(lx: Float, ly: Float, ww: Float, hh: Float) {
        rect.set(lx, ly, lx + ww, ly + hh)
        cv.drawRect(rect, p)
    }
    for (ch in text) {
        val segs = lcdSegments(ch)
        if (segs >= 0) {
            // A top
            if (segs and SEG_A != 0) bar(cx + gap, cy, w0 - gap * 2f, t)
            // F top-left, B top-right
            if (segs and SEG_F != 0) bar(cx, cy + gap, t, h / 2f - gap * 1.5f)
            if (segs and SEG_B != 0) bar(cx + w0 - t, cy + gap, t, h / 2f - gap * 1.5f)
            // G middle
            if (segs and SEG_G != 0) bar(cx + gap, cy + h / 2f - t * 0.5f, w0 - gap * 2f, t)
            // E bottom-left, C bottom-right
            if (segs and SEG_E != 0) bar(cx, cy + h / 2f + gap * 0.5f, t, h / 2f - gap * 1.5f)
            if (segs and SEG_C != 0) bar(cx + w0 - t, cy + h / 2f + gap * 0.5f, t, h / 2f - gap * 1.5f)
            // D bottom
            if (segs and SEG_D != 0) bar(cx + gap, cy + h - t, w0 - gap * 2f, t)
            cx += lcdDigitAdvance(h)
        } else if (ch == '.' || ch == ':') {
            // small square dots, bottom-aligned like a printed data readout
            val s = t * 0.9f
            val dotY = cy + h - s * 2.4f
            bar(cx + lcdCellW(h) * 0.08f, dotY, s, s)
            if (ch == ':') bar(cx + lcdCellW(h) * 0.08f, dotY - s * 1.7f, s, s)
            cx += lcdCellW(h) * 0.42f + lcdDigitW(h) * 0.16f
        } else {
            cv.drawText(ch.toString(), cx, baseY, p)
            cx += p.measureText(ch.toString()) + h * 0.1f
        }
    }
}

// Stamp position: 0 bottom-right (default), 1 bottom-left, 2 top-right, 3 top-left.
fun dateStampPosName(pos: Int): String = when (pos) { 1 -> "B·L"; 2 -> "T·R"; 3 -> "T·L"; else -> "B·R" }
enum class FrameType {
    NONE,
    CLASSIC_POLAROID, INSTAX, FILM_35MM, CARTRIDGE_110, THICK_MATTE, MINIMAL_MAT, VELVET_CINEMA,
    FILM_8MM, SLIDE_MOUNT
}
enum class FrameMode { OVERLAY, EXTENDED, EXTENDED_OVERLAY }

// ── Per-frame editable parameters ─────────────────────────────────────────────
data class FrameConfig(
    val p1:Float=0.5f, val p2:Float=0.5f, val p3:Float=0.5f, val p4:Float=0.5f,
    val borderWidth:Float=0.06f, val bottomSpace:Float=0.18f,
    val paperColor:Long=0xFFEDE6D6, val cornerRadius:Float=0f,
    val textureWear:Float=0f, val shadowDepth:Float=0f,
    val overlayColor:Long=0xFFFFFFFF, val overlayAlpha:Float=0f,
    val polarFrameCode:String=""
)

// ── Frame system — Polar Triangle ────────────────────────────────────────────
enum class PolarFrameCat { CLASSIC, VINTAGE, PASTEL, POP, MINIMAL }

data class PolarFrame(
    val code: String,
    val cat: PolarFrameCat,
    val color: Int,
    val bottomPct: Float,
    val borderPct: Float,
    val corner: Float = 0f,
    val wear: Float = 0f,
    val shadow: Float = 0f
)

val POLAR_FRAMES = listOf(
    PolarFrame("PCM", PolarFrameCat.CLASSIC, 0xFFF5F5F0.toInt(), 0.18f, 0.06f),
    PolarFrame("THK", PolarFrameCat.CLASSIC, 0xFFFAFAFA.toInt(), 0.16f, 0.10f, 2f),
    PolarFrame("THN", PolarFrameCat.CLASSIC, 0xFFF8F8F8.toInt(), 0.15f, 0.03f),
    PolarFrame("SFT", PolarFrameCat.CLASSIC, 0xFFF2F0EC.toInt(), 0.17f, 0.055f, 4f, 0.02f, 0.08f),
    PolarFrame("AGD", PolarFrameCat.VINTAGE, 0xFFD8C8A0.toInt(), 0.20f, 0.07f, wear=0.25f, shadow=0.05f),
    PolarFrame("BRN", PolarFrameCat.VINTAGE, 0xFFC0A870.toInt(), 0.19f, 0.065f, wear=0.35f, shadow=0.08f),
    PolarFrame("RET", PolarFrameCat.VINTAGE, 0xFFE8D8B8.toInt(), 0.22f, 0.08f, 2f, 0.30f, 0.06f),
    PolarFrame("OLD", PolarFrameCat.VINTAGE, 0xFFD0C090.toInt(), 0.20f, 0.07f, wear=0.50f, shadow=0.12f),
    PolarFrame("PNK", PolarFrameCat.PASTEL, 0xFFF8D0D8.toInt(), 0.18f, 0.06f, 3f),
    PolarFrame("BLU", PolarFrameCat.PASTEL, 0xFFD0E0F0.toInt(), 0.18f, 0.06f, 3f),
    PolarFrame("MNT", PolarFrameCat.PASTEL, 0xFFD0F0E0.toInt(), 0.18f, 0.06f, 3f),
    PolarFrame("LAV", PolarFrameCat.PASTEL, 0xFFE0D0F0.toInt(), 0.18f, 0.06f, 3f),
    PolarFrame("PCH", PolarFrameCat.PASTEL, 0xFFF0D8C8.toInt(), 0.18f, 0.06f, 3f),
    PolarFrame("YLW", PolarFrameCat.PASTEL, 0xFFF8F0C8.toInt(), 0.18f, 0.06f, 3f),
    PolarFrame("NEO", PolarFrameCat.POP, 0xFFFF4080.toInt(), 0.16f, 0.05f, 6f),
    PolarFrame("COR", PolarFrameCat.POP, 0xFFFF6B50.toInt(), 0.17f, 0.055f, 4f),
    PolarFrame("LIM", PolarFrameCat.POP, 0xFF80E060.toInt(), 0.16f, 0.05f, 5f),
    PolarFrame("SKY", PolarFrameCat.POP, 0xFF40C0FF.toInt(), 0.17f, 0.055f, 4f),
    PolarFrame("SOL", PolarFrameCat.POP, 0xFFFFD040.toInt(), 0.16f, 0.05f, 6f),
    PolarFrame("ULM", PolarFrameCat.POP, 0xFFB040FF.toInt(), 0.17f, 0.055f, 4f),
    PolarFrame("AIR", PolarFrameCat.MINIMAL, 0xFFFAFAFA.toInt(), 0.10f, 0.015f),
    PolarFrame("EDG", PolarFrameCat.MINIMAL, 0xFFF0F0F0.toInt(), 0.14f, 0.04f),
    PolarFrame("BOX", PolarFrameCat.MINIMAL, 0xFFECECEC.toInt(), 0.15f, 0.05f, 2f),
    PolarFrame("GLD", PolarFrameCat.VINTAGE, 0xFFE9CFA6.toInt(), 0.19f, 0.07f, wear=0.28f, shadow=0.07f),
    PolarFrame("JET", PolarFrameCat.MINIMAL, 0xFF191919.toInt(), 0.16f, 0.045f, 2f),
    PolarFrame("ROY", PolarFrameCat.POP, 0xFF4068E0.toInt(), 0.17f, 0.055f, 4f),
    PolarFrame("CRS", PolarFrameCat.POP, 0xFF20C0B0.toInt(), 0.16f, 0.05f, 5f),
    PolarFrame("GRF", PolarFrameCat.MINIMAL, 0xFFD8D8D8.toInt(), 0.13f, 0.03f, 1f)
)

fun polarFrameByCode(code: String): PolarFrame = POLAR_FRAMES.first { it.code == code }
fun polarFramesByCategory(cat: PolarFrameCat): List<PolarFrame> = POLAR_FRAMES.filter { it.cat == cat }

data class FramePreset(val frameType:FrameType=FrameType.NONE,val frameConfig:FrameConfig=FrameConfig(),val frameMode:FrameMode=FrameMode.OVERLAY,val polarFrameCode:String=""); fun frameParamLabels(ft:FrameType):List<String> = when(ft){
    FrameType.INSTAX           -> listOf("SIZE","TONE","BORDER")
    FrameType.FILM_35MM        -> listOf("BORDER","FILM BASE","SPROCKETS")
    FrameType.CARTRIDGE_110    -> listOf("THICKNESS","CORNER")
    FrameType.CLASSIC_POLAROID -> listOf("SIZE","WARMTH","SHADOW","GRAIN")
    FrameType.MINIMAL_MAT      -> listOf("WIDTH","TONE","SHADOW")
    FrameType.THICK_MATTE      -> listOf("THICKNESS","TONE","DEPTH")
    FrameType.VELVET_CINEMA    -> listOf("DARKNESS","HIGHLIGHT")
    FrameType.FILM_8MM         -> listOf("BORDER","SPROCKETS","GATE")
    FrameType.SLIDE_MOUNT      -> listOf("THICKNESS","BEVEL","EDGE")
    else -> emptyList()
}

// ─────────────────────────────────────────────────────────────────────────────

fun buildFilmMatrix(
    expEV:Float=0f,contrast:Float=0f,blacks:Float=0f,shadows:Float=0f,
    temp:Float=0f,tint:Float=0f,saturation:Float=0f,vibrance:Float=0f,
    shadowHue:Float=0f,shadowSatPct:Float=0f,highlightHue:Float=0f,highlightSatPct:Float=0f,
    redSatBoost:Float=0f,greenShift:Float=0f,blueHueShift:Float=0f
): FloatArray {
    val cm = android.graphics.ColorMatrix()
    val es = Math.pow(2.0,expEV.toDouble()).toFloat()
    cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(es,0f,0f,0f,0f,0f,es,0f,0f,0f,0f,0f,es,0f,0f,0f,0f,0f,1f,0f)))
    val cs=1f+contrast*0.005f;val co=128f*(1f-cs)
    cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(cs,0f,0f,0f,co,0f,cs,0f,0f,co,0f,0f,cs,0f,co,0f,0f,0f,1f,0f)))
    val lift=blacks*0.45f+shadows*0.12f
    cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f,0f,0f,0f,lift,0f,1f,0f,0f,lift,0f,0f,1f,0f,lift,0f,0f,0f,1f,0f)))
    val ws=temp*0.22f
    cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f,0f,0f,0f,ws,0f,1f,0f,0f,ws*0.1f,0f,0f,1f,0f,-ws,0f,0f,0f,1f,0f)))
    val ts=tint*0.15f
    cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f,0f,0f,0f,ts*0.6f,0f,1f,0f,0f,-ts*1.8f,0f,0f,1f,0f,ts*0.5f,0f,0f,0f,1f,0f)))
    val satMat=android.graphics.ColorMatrix();satMat.setSaturation((1f+(saturation+vibrance*0.6f)/100f).coerceIn(0f,3f))
    cm.postConcat(satMat)
    if(redSatBoost!=0f){val rs=redSatBoost*0.004f;cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f+rs,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f)))}
    if(greenShift!=0f){val gs=greenShift*0.003f;cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f,-gs*0.3f,0f,0f,0f,gs*0.2f,1f,gs*0.2f,0f,0f,0f,-gs*0.3f,1f,0f,0f,0f,0f,0f,1f,0f)))}
    if(blueHueShift!=0f){val bs=blueHueShift*0.002f;cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f,0f,-bs*0.5f,0f,0f,0f,1f,bs*0.3f,0f,0f,0f,0f,1f,0f,0f,0f,0f,0f,1f,0f)))}
    if(shadowSatPct>0f){val r=Math.toRadians(shadowHue.toDouble());val i=shadowSatPct*1.5f;val sr=(Math.cos(r)*i).toFloat();val sg=(Math.sin(r+Math.PI/3)*i*0.5f).toFloat();val sb=(-Math.cos(r+Math.PI)*i*0.7f).toFloat();cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f,0f,0f,0f,sr,0f,1f,0f,0f,sg,0f,0f,1f,0f,sb,0f,0f,0f,1f,0f)))}
    if(highlightSatPct>0f){val r=Math.toRadians(highlightHue.toDouble());val i=highlightSatPct*0.9f;val hr=(Math.cos(r)*i).toFloat();val hb=-(Math.cos(r)*i*0.5f).toFloat();cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f,0f,0f,0f,hr,0f,1f,0f,0f,hr*0.2f,0f,0f,1f,0f,hb,0f,0f,0f,1f,0f)))}
    return cm.array.copyOf()
}

data class FilmProfile(
    val id:String,val name:String,val icon:String,val matrixValues:FloatArray,
    val previewTint:Color=Color.Transparent,val previewTintAlpha:Float=0f,
    val warmthPreview:Float=0f,val fade:Float=0f,val isMonochrome:Boolean=false,
    val defaultGrain:Float=0f,val grainPixelSize:Float=1f,val defaultVignette:Float=0f,
    val defaultLightLeak:Float=0f,val leakColor:Color=Color(0xFFFF6600),val description:String=""
)

val FILM_PROFILES:List<FilmProfile> by lazy {
    val id=FloatArray(20).also{android.graphics.ColorMatrix().let{cm->cm.reset();System.arraycopy(cm.array,0,it,0,20)}}
    listOf(
        FilmProfile("normal","NONE","○",id,description="No filter"),
        FilmProfile("cpm35","CPM","◈",buildFilmMatrix(expEV=0.5f,contrast=-25f,blacks=30f,shadows=40f,temp=-5f,tint=-15f,saturation=-15f,vibrance=25f,shadowHue=160f,shadowSatPct=8f,highlightHue=50f,highlightSatPct=5f,greenShift=40f),Color(0xFF77CC88),0.09f,fade=0.18f,defaultGrain=0.35f,grainPixelSize=1.0f,defaultVignette=0.35f,description="WARM (800T)"),
        FilmProfile("dclassic","D","◈",buildFilmMatrix(expEV=0.2f,contrast=30f,blacks=-10f,shadows=-15f,temp=-8f,tint=5f,saturation=5f,vibrance=25f,shadowHue=215f,shadowSatPct=10f,highlightHue=45f,highlightSatPct=5f,blueHueShift=-15f,redSatBoost=20f),Color(0xFF002299),0.07f,defaultGrain=0.25f,grainPixelSize=0.9f,defaultVignette=0.25f,description="DAYLIGHT GOLD"),
        FilmProfile("fqsr","FQSR","◈",buildFilmMatrix(expEV=-0.3f,contrast=40f,blacks=-20f,shadows=-15f,temp=10f,tint=-5f,saturation=-5f,vibrance=15f,shadowHue=40f,shadowSatPct=5f,highlightHue=50f,highlightSatPct=12f),Color(0xFFFFAA00),0.11f,warmthPreview=0.1f,fade=0.05f,defaultGrain=0.40f,grainPixelSize=1.1f,defaultVignette=0.3f,defaultLightLeak=0.12f,leakColor=Color(0xFFFF8800),description="PORTRA SOFT 400"),
        FilmProfile("fxnr","FXN","◈",buildFilmMatrix(expEV=0.1f,contrast=35f,blacks=-15f,shadows=-10f,temp=6f,tint=-8f,saturation=-10f,vibrance=15f,shadowHue=150f,shadowSatPct=5f,highlightHue=40f,highlightSatPct=8f,redSatBoost=15f,blueHueShift=-25f,greenShift=30f),Color(0xFF00BB77),0.08f,warmthPreview=0.06f,defaultGrain=0.20f,grainPixelSize=0.9f,defaultVignette=0.28f,description="CLASSIC NEG"),
        FilmProfile("hoga","HOGA","◈",buildFilmMatrix(expEV=0.6f,contrast=-15f,blacks=25f,shadows=30f,temp=-5f,tint=20f,saturation=-10f,vibrance=15f,shadowHue=280f,shadowSatPct=10f,highlightHue=200f,highlightSatPct=5f,blueHueShift=-10f),Color(0xFFCC55BB),0.12f,fade=0.15f,defaultGrain=0.30f,grainPixelSize=0.8f,defaultVignette=0.50f,description="AIRY VIOLET"),
        FilmProfile("s67","S67","◈",buildFilmMatrix(expEV=0.2f,contrast=10f,blacks=0f,shadows=15f,temp=-10f,tint=5f,saturation=-5f,vibrance=20f,shadowHue=210f,shadowSatPct=12f,highlightHue=200f,highlightSatPct=5f,blueHueShift=-5f),Color(0xFF2244AA),0.07f,defaultGrain=0.15f,grainPixelSize=0.7f,defaultVignette=0.20f,description="MEDIUM FORMAT"),
        FilmProfile("dhalf","D HALF","◈",buildFilmMatrix(expEV=0.3f,contrast=-15f,blacks=20f,shadows=35f,temp=8f,tint=-12f,saturation=-10f,vibrance=15f,shadowHue=120f,shadowSatPct=5f,highlightHue=50f,highlightSatPct=8f,greenShift=20f),Color(0xFF88AA44),0.09f,warmthPreview=0.08f,fade=0.12f,defaultGrain=0.25f,grainPixelSize=0.9f,defaultVignette=0.28f,description="OLIVE FADE"),
        FilmProfile("grf","GRF","◈",buildFilmMatrix(expEV=-0.2f,contrast=45f,blacks=-25f,shadows=-20f,temp=-5f,tint=10f,saturation=-10f,vibrance=20f,shadowHue=220f,shadowSatPct=12f,highlightHue=50f,highlightSatPct=5f,redSatBoost=25f,blueHueShift=-15f),Color(0xFF223366),0.06f,defaultGrain=0.40f,grainPixelSize=1.2f,defaultVignette=0.38f,description="STREET RAW"),
        FilmProfile("xproc","XPROC","⧉",buildFilmMatrix(expEV=0.3f,contrast=40f,blacks=-15f,shadows=-10f,temp=-12f,tint=-14f,saturation=30f,vibrance=20f,shadowHue=145f,shadowSatPct=18f,highlightHue=52f,highlightSatPct=12f,redSatBoost=12f,blueHueShift=-28f,greenShift=35f),Color(0xFF33BB66),0.09f,warmthPreview=0.05f,fade=0.06f,defaultGrain=0.45f,grainPixelSize=1.0f,defaultVignette=0.30f,description="CROSS-PROCESS 400"),
        FilmProfile("dispo","DISPO","D",buildFilmMatrix(expEV=0.35f,contrast=0f,blacks=30f,shadows=45f,temp=10f,tint=-10f,saturation=-8f,vibrance=25f,highlightHue=50f,highlightSatPct=8f,blueHueShift=-10f),Color(0xFFFE6B35),0.32f,warmthPreview=0.18f,fade=0.28f,defaultGrain=0.50f,grainPixelSize=1.2f,defaultVignette=0.45f,description="DISPOSABLE 400"),
        FilmProfile("dom","DOM","◉",buildFilmMatrix(expEV=0.2f,contrast=30f,blacks=-10f,shadows=-5f,temp=-8f,tint=-5f,saturation=30f,vibrance=15f,shadowHue=160f,shadowSatPct=12f,highlightHue=200f,highlightSatPct=6f,greenShift=20f),Color(0xFF22CC55),0.09f,defaultGrain=0.35f,grainPixelSize=1.1f,defaultVignette=0.55f,description="PLASTIC FANTASTIC"),
        FilmProfile("nt16","NT16","❋",buildFilmMatrix(expEV=0.1f,contrast=15f,blacks=5f,shadows=10f,temp=5f,tint=0f,saturation=-10f,vibrance=15f,shadowSatPct=8f,highlightHue=40f,highlightSatPct=4f,blueHueShift=-10f),Color(0xFF3366CC),0.22f,warmthPreview=0.05f,defaultGrain=0.12f,grainPixelSize=0.7f,defaultVignette=0.15f,description="TOY 800 (TINY SILVER)"),
        FilmProfile("toycam","TOY CAM","◉",buildFilmMatrix(expEV=0.7f,contrast=-10f,blacks=30f,shadows=35f,temp=8f,tint=8f,saturation=15f,vibrance=20f,shadowHue=300f,shadowSatPct=8f,highlightHue=45f,highlightSatPct=6f),Color(0xFFCC66BB),0.20f,warmthPreview=0.12f,fade=0.16f,defaultGrain=0.30f,grainPixelSize=0.9f,defaultVignette=0.55f,description="PLASTIC FUN"),
        FilmProfile("instant","INSTANT","▰",buildFilmMatrix(expEV=0.4f,contrast=20f,blacks=15f,shadows=10f,temp=-4f,tint=-6f,saturation=-12f,vibrance=18f,shadowHue=210f,shadowSatPct=6f,highlightHue=40f,highlightSatPct=8f),Color(0xFF88AACC),0.10f,warmthPreview=0.05f,fade=0.20f,defaultGrain=0.18f,grainPixelSize=0.8f,defaultVignette=0.30f,description="INSTANT FILM"),
        FilmProfile("ccd","CCD","▚",buildFilmMatrix(expEV=-0.1f,contrast=45f,blacks=-25f,shadows=-15f,temp=-5f,tint=-4f,saturation=28f,vibrance=15f,shadowHue=220f,shadowSatPct=10f,highlightHue=200f,highlightSatPct=5f,redSatBoost=18f),Color(0xFF2255AA),0.06f,fade=0.03f,defaultGrain=0.15f,grainPixelSize=0.7f,defaultVignette=0.25f,description="COMPACT DIGITAL"),
        FilmProfile("halfframe","HALF FRAME","½",buildFilmMatrix(expEV=0.3f,contrast=-12f,blacks=22f,shadows=25f,temp=6f,tint=-8f,saturation=-15f,vibrance=12f,shadowHue=90f,shadowSatPct=5f,highlightHue=40f,highlightSatPct=6f,blueHueShift=-14f),Color(0xFF99BB55),0.09f,fade=0.13f,defaultGrain=0.22f,grainPixelSize=0.9f,defaultVignette=0.20f,description="HALF FRAME 220"),
        FilmProfile("pinhole","PINHOLE","⊙",buildFilmMatrix(expEV=0.5f,contrast=-25f,blacks=15f,shadows=40f,temp=12f,tint=-5f,saturation=-10f,vibrance=25f,shadowHue=30f,shadowSatPct=14f,highlightHue=40f,highlightSatPct=10f),Color(0xFFFFBB66),0.28f,warmthPreview=0.20f,fade=0.20f,defaultGrain=0.35f,grainPixelSize=1.3f,defaultVignette=0.85f,description="STENOPE 6×6"),
        FilmProfile("slide","SLIDE","▣",buildFilmMatrix(expEV=0.2f,contrast=50f,blacks=-30f,shadows=-25f,temp=-8f,tint=-10f,saturation=35f,vibrance=15f,shadowHue=150f,shadowSatPct=15f,highlightHue=50f,highlightSatPct=12f,redSatBoost=15f,blueHueShift=-25f,greenShift=30f),Color(0xFF33CC88),0.10f,fade=0.04f,defaultGrain=0.30f,grainPixelSize=1.0f,defaultVignette=0.35f,description="E-6 SLIDE 100"),
        FilmProfile("gold80","GOLDEN 80S","✦",buildFilmMatrix(expEV=0.1f,contrast=-5f,blacks=20f,shadows=25f,temp=18f,tint=5f,saturation=5f,vibrance=25f,shadowHue=35f,shadowSatPct=10f,highlightHue=40f,highlightSatPct=8f),Color(0xFFFFB84D),0.30f,warmthPreview=0.28f,fade=0.10f,defaultGrain=0.20f,grainPixelSize=0.9f,defaultVignette=0.25f,defaultLightLeak=0.06f,leakColor=Color(0xFFFFB84D),description="WARM GOLDEN 80S"),
        FilmProfile("dcr","DCR TAPE","▣",buildFilmMatrix(expEV=0.15f,contrast=25f,blacks=-5f,shadows=0f,temp=5f,tint=-10f,saturation=-20f,vibrance=10f,shadowHue=140f,shadowSatPct=6f,highlightHue=190f,highlightSatPct=5f,blueHueShift=-15f,greenShift=18f),Color(0xFF77AA77),0.14f,fade=0.12f,defaultGrain=0.35f,grainPixelSize=1.1f,defaultVignette=0.30f,description="88 CAMCORDER LO-FI"),
        FilmProfile("sr135","135 SR","▪",buildFilmMatrix(expEV=0.1f,contrast=30f,blacks=-5f,shadows=5f,temp=8f,tint=-2f,saturation=-12f,vibrance=12f,shadowHue=40f,shadowSatPct=6f,highlightHue=40f,highlightSatPct=4f),Color(0xFFCCAA66),0.12f,warmthPreview=0.08f,fade=0.05f,defaultGrain=0.18f,grainPixelSize=0.8f,defaultVignette=0.18f,description="MUTED 35MM AGED"),
        FilmProfile("gsp","GSP SOFT","✧",buildFilmMatrix(expEV=0.25f,contrast=-20f,blacks=15f,shadows=30f,temp=4f,tint=5f,saturation=-8f,vibrance=18f,shadowHue=120f,shadowSatPct=8f,highlightHue=150f,highlightSatPct=6f,greenShift=14f),Color(0xFF88CC99),0.16f,fade=0.18f,defaultGrain=0.12f,grainPixelSize=0.7f,defaultVignette=0.30f,defaultLightLeak=0.08f,leakColor=Color(0xFF55AA77),description="SOFT GREEN 120"),
        FilmProfile("paf","PASTEL","❀",buildFilmMatrix(expEV=0.2f,contrast=-15f,blacks=20f,shadows=25f,temp=6f,tint=8f,saturation=-18f,vibrance=20f,shadowHue=330f,shadowSatPct=10f,highlightHue=30f,highlightSatPct=8f),Color(0xFFFFB3C0),0.14f,warmthPreview=0.10f,fade=0.15f,defaultGrain=0.10f,grainPixelSize=0.7f,defaultVignette=0.22f,description="DREAMY PASTEL"),
        FilmProfile("kv80","CINE 80","◈",buildFilmMatrix(expEV=-0.1f,contrast=45f,blacks=-20f,shadows=-10f,temp=-6f,tint=-8f,saturation=20f,vibrance=15f,shadowHue=200f,shadowSatPct=12f,highlightHue=45f,highlightSatPct=10f,redSatBoost=15f),Color(0xFF55AA99),0.14f,fade=0.05f,defaultGrain=0.30f,grainPixelSize=1.0f,defaultVignette=0.35f,description="CINEMATIC TEAL"),
        FilmProfile("dcln","D CLASS","◯",buildFilmMatrix(expEV=0.05f,contrast=35f,blacks=-10f,shadows=-5f,temp=5f,tint=-2f,saturation=-5f,vibrance=12f,shadowHue=200f,shadowSatPct=6f,highlightHue=45f,highlightSatPct=5f),Color(0xFFAA9966),0.10f,fade=0.06f,defaultGrain=0.10f,grainPixelSize=0.7f,defaultVignette=0.20f,description="CLEAN 90S COMPACT")
    )
}

private fun hslToRgb(hDeg:Float,s:Float,l:Float):IntArray {
    val h=((hDeg%360f+360f)%360f)/60f;val c=(1f-kotlin.math.abs(2f*l-1f))*s;val x=c*(1f-kotlin.math.abs(h%2f-1f))
    val(r,g,b)=when(h.toInt()){0->Triple(c,x,0f);1->Triple(x,c,0f);2->Triple(0f,c,x);3->Triple(0f,x,c);4->Triple(x,0f,c);else->Triple(c,0f,x)}
    val m=l-c/2f;return intArrayOf(((r+m)*255).toInt().coerceIn(0,255),((g+m)*255).toInt().coerceIn(0,255),((b+m)*255).toInt().coerceIn(0,255))
}

private fun renderLeak(canvas:android.graphics.Canvas,w:Float,h:Float,amt:Float,colorMode:LightLeakColor,seed:Long) {
    val rng=kotlin.random.Random(seed);val edge=rng.nextInt(4);val pos=rng.nextFloat()
    val ox=when(edge){1->w;3->0f;else->w*pos};val oy=when(edge){0->0f;2->h;else->h*pos}
    val spread=(w+h)*(0.28f+rng.nextFloat()*0.36f)
    val layers=when(colorMode){
        LightLeakColor.ORANGE->listOf(intArrayOf(255,38,0),intArrayOf(255,108,0),intArrayOf(255,192,55))
        LightLeakColor.BLUE->listOf(intArrayOf(0,18,210),intArrayOf(0,92,255),intArrayOf(52,172,255))
        LightLeakColor.RAINBOW->listOf(intArrayOf(255,0,50),intArrayOf(255,92,0),intArrayOf(215,205,0),intArrayOf(0,190,65),intArrayOf(0,72,255),intArrayOf(140,0,210))
        LightLeakColor.RANDOM->{val hue=rng.nextFloat()*360f;listOf(hslToRgb(hue,1f,0.32f),hslToRgb(hue+18f,0.92f,0.54f),hslToRgb(hue+34f,0.78f,0.70f))}
    }
    val n=layers.size
    for((i,rgb) in layers.withIndex()){val frac=if(n>1)i.toFloat()/(n-1) else 0.5f
        val dx=if(edge==0||edge==2)(frac-0.5f)*spread*0.38f else 0f;val dy=if(edge==1||edge==3)(frac-0.5f)*spread*0.38f else 0f
        val layerR=spread*(0.62f+frac*0.55f);val layerA=amt*(0.88f-frac*0.30f);val p=Paint()
        p.shader=RadialGradient(ox+dx,oy+dy,layerR,intArrayOf(android.graphics.Color.argb((layerA*235).toInt().coerceIn(0,255),rgb[0],rgb[1],rgb[2]),android.graphics.Color.argb((layerA*72).toInt().coerceIn(0,255),rgb[0],rgb[1],rgb[2]),android.graphics.Color.TRANSPARENT),floatArrayOf(0f,0.42f,1f),Shader.TileMode.CLAMP)
        p.xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN);canvas.drawRect(0f,0f,w,h,p)}
    val hp=Paint();hp.shader=RadialGradient(ox,oy,spread*0.16f,intArrayOf(android.graphics.Color.argb((amt*205).toInt().coerceIn(0,255),255,228,182),android.graphics.Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
    hp.xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN);canvas.drawRect(0f,0f,w,h,hp)
    if(rng.nextFloat()>0.40f){val sx=w*rng.nextFloat();val sy=h*rng.nextFloat();val sr=spread*(0.18f+rng.nextFloat()*0.22f);val fc=layers[0];val sp=Paint()
        sp.shader=RadialGradient(sx,sy,sr,intArrayOf(android.graphics.Color.argb((amt*55).toInt().coerceIn(0,255),fc[0],fc[1],fc[2]),android.graphics.Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        sp.xfermode=PorterDuffXfermode(PorterDuff.Mode.SCREEN);canvas.drawRect(0f,0f,w,h,sp)}
}

object FilmEngine {
    fun applyToCapture(source:android.graphics.Bitmap,profile:FilmProfile,grainAmt:Float=-1f,leakAmt:Float=-1f,frameType:FrameType=FrameType.NONE,showTimestamp:Boolean=false,leakColorMode:LightLeakColor=LightLeakColor.ORANGE,frameConfig:FrameConfig=FrameConfig(),frameMode:FrameMode=FrameMode.OVERLAY,dateStyle:DateStampStyle=DateStampStyle.ORANGE_FILM,dateFormat:DateFormatType=DateFormatType.DD_MM_YY,dateShowTime:Boolean=true,datePos:Int=0,dateText:String?=null):android.graphics.Bitmap {
        var bm=source
        if(profile.id!="normal"){
            val w=bm.width;val h=bm.height
            val result=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888)
            val canvas=android.graphics.Canvas(result);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            paint.colorFilter=ColorMatrixColorFilter(android.graphics.ColorMatrix(profile.matrixValues))
            canvas.drawBitmap(bm,0f,0f,paint);paint.colorFilter=null
            val grain=if(grainAmt>=0f)grainAmt else profile.defaultGrain
            if(grain>0f){val rnd=Random();val gp=Paint().apply{isAntiAlias=false};val px=profile.grainPixelSize
                repeat((w*h*grain*0.02f).toInt().coerceAtMost(100000)){val brt=rnd.nextFloat();val a=(rnd.nextFloat()*grain*170).toInt();val v=(brt*255).toInt();gp.color=android.graphics.Color.argb(a,v,v,v);canvas.drawCircle(rnd.nextFloat()*w,rnd.nextFloat()*h,px*0.5f,gp)}
                repeat((w*h*grain*0.007f).toInt().coerceAtMost(30000)){gp.color=android.graphics.Color.argb((rnd.nextFloat()*grain*80).toInt(),0,0,0);canvas.drawCircle(rnd.nextFloat()*w,rnd.nextFloat()*h,px*0.4f,gp)}}
            if(profile.defaultVignette>0f){val vp=Paint();vp.shader=RadialGradient(w/2f,h/2f,maxOf(w,h)*0.72f,intArrayOf(android.graphics.Color.TRANSPARENT,android.graphics.Color.argb((profile.defaultVignette*195).toInt(),0,0,0)),floatArrayOf(0.42f,1f),Shader.TileMode.CLAMP);canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),vp)}
            val leak=if(leakAmt>=0f)leakAmt else profile.defaultLightLeak
            if(leak>0f){renderLeak(canvas,w.toFloat(),h.toFloat(),leak,leakColorMode,System.currentTimeMillis())}
            bm=result
        }
        if(showTimestamp)bm=applyTimestamp(bm,dateStyle,dateFormat,dateShowTime,datePos,dateText)
        if(frameType!=FrameType.NONE)bm=applyFrame(bm,frameType,frameConfig,frameMode)
        return bm
    }

    private fun applyFrame(source:android.graphics.Bitmap,ft:FrameType,c:FrameConfig,frameMode:FrameMode):android.graphics.Bitmap{
        if(c.polarFrameCode.isNotEmpty()){
            return if(frameMode==FrameMode.OVERLAY) polarFrameApply(source,c)
            else polarFrameApplyExtended(source,c)
        }
        if(ft==FrameType.NONE)return source
        val w=source.width;val h=source.height
        val result=android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888)
        val cv=android.graphics.Canvas(result)
        val win=FrameDraw.frameWindow(ft,c,w.toFloat(),h.toFloat(),frameMode)
        cv.drawColor(FrameDraw.paperColor(ft,c))
        FrameDraw.paperTint(cv,w.toFloat(),h.toFloat(),c.overlayColor.toInt(),c.overlayAlpha)
        FrameDraw.paperGrain(cv,w.toFloat(),h.toFloat(),0.25f+c.textureWear*0.75f,ft.hashCode()+w*31+h)
        FrameDraw.photoShadow(cv,win,w.toFloat(),c.shadowDepth)
        val r=if(ft==FrameType.CLASSIC_POLAROID) c.cornerRadius else 0f
        val imgP=Paint(Paint.ANTI_ALIAS_FLAG)
        if(r>0f){
            val path=android.graphics.Path().apply{addRoundRect(android.graphics.RectF(win[0],win[1],win[2],win[3]),r,r,android.graphics.Path.Direction.CW)}
            cv.save();cv.clipPath(path)
            cv.drawBitmap(source,null,android.graphics.RectF(win[0],win[1],win[2],win[3]),imgP)
            cv.restore()
        }else{
            cv.drawBitmap(source,null,android.graphics.RectF(win[0],win[1],win[2],win[3]),imgP)
        }
        FrameDraw.drawAccents(cv,w.toFloat(),h.toFloat(),ft,win,SimpleDateFormat("yy.MM.dd  HH:mm",Locale.getDefault()).format(Date()))
        return result
    }

    private fun polarFrameApply(s:android.graphics.Bitmap,c:FrameConfig):android.graphics.Bitmap {
        val w=s.width;val h=s.height
        val bx=(w*c.borderWidth).toInt()
        val by=(w*c.bottomSpace).toInt()
        val fw=w+bx*2;val fh=h+bx+by
        val result=android.graphics.Bitmap.createBitmap(fw,fh,android.graphics.Bitmap.Config.ARGB_8888)
        val cv=android.graphics.Canvas(result)
        cv.drawColor(c.paperColor.toInt())
        FrameDraw.paperTint(cv,fw.toFloat(),fh.toFloat(),c.overlayColor.toInt(),c.overlayAlpha)
        FrameDraw.paperGrain(cv,fw.toFloat(),fh.toFloat(),0.25f+c.textureWear*0.75f,c.polarFrameCode.hashCode()+fw*7+fh)
        FrameDraw.photoShadow(cv,floatArrayOf(bx.toFloat(),bx.toFloat(),bx.toFloat()+w,bx.toFloat()+h),fw.toFloat(),c.shadowDepth)
        cv.drawBitmap(s,bx.toFloat(),bx.toFloat(),null)
        FrameDraw.drawAccents(cv,fw.toFloat(),fh.toFloat(),FrameType.CLASSIC_POLAROID,
            floatArrayOf(bx.toFloat(),bx.toFloat(),bx.toFloat()+w,bx.toFloat()+h),
            SimpleDateFormat("MMM yyyy",Locale.getDefault()).format(Date()))
        return result
    }

    private fun polarFrameApplyExtended(s:android.graphics.Bitmap,c:FrameConfig):android.graphics.Bitmap {
        val w=s.width;val h=s.height
        val bx=(w*c.borderWidth).toInt()
        val by=(w*c.bottomSpace).toInt()
        val fw=w+bx*2;val fh=h+bx+by
        val result=android.graphics.Bitmap.createBitmap(fw,fh,android.graphics.Bitmap.Config.ARGB_8888)
        val cv=android.graphics.Canvas(result)
        // Paper background fills the whole frame (acts as border)
        cv.drawColor(c.paperColor.toInt())
        FrameDraw.paperTint(cv,fw.toFloat(),fh.toFloat(),c.overlayColor.toInt(),c.overlayAlpha)
        FrameDraw.paperGrain(cv,fw.toFloat(),fh.toFloat(),0.25f+c.textureWear*0.75f,c.polarFrameCode.hashCode()+fw*7+fh)
        // Scaled-down photo centered inside the frame
        val scale=0.82f
        val sw=(w*scale).toInt();val sh=(h*scale).toInt()
        val sx=((fw-sw)/2f);val sy=((fh-sh)/2f)
        FrameDraw.photoShadow(cv,floatArrayOf(sx,sy,sx+sw,sy+sh),fw.toFloat(),c.shadowDepth)
        cv.drawBitmap(s,null,android.graphics.RectF(sx,sy,sx+sw,sy+sh),null)
        FrameDraw.drawAccents(cv,fw.toFloat(),fh.toFloat(),FrameType.CLASSIC_POLAROID,
            floatArrayOf(sx,sy,sx+sw,sy+sh),
            SimpleDateFormat("MMM yyyy",Locale.getDefault()).format(Date()))
        return result
    }

    private fun applyTimestamp(source:android.graphics.Bitmap, style: DateStampStyle = DateStampStyle.ORANGE_FILM, fmt: DateFormatType = DateFormatType.DD_MM_YY, showTime: Boolean = true, pos: Int = 0, customDate: String? = null):android.graphics.Bitmap {
        val result=source.copy(android.graphics.Bitmap.Config.ARGB_8888,true);val canvas=android.graphics.Canvas(result);val w=result.width;val h=result.height
        val custom = customDate?.takeIf { it.isNotBlank() }
        val text = custom ?: run {
            val pat=when(fmt){DateFormatType.DD_MM_YY->"yy.MM.dd";DateFormatType.MM_DD_YY->"MM.dd.yy";DateFormatType.YYYY_MM_DD->"yyyy.MM.dd";DateFormatType.MM_YY->"MM.yy"}
            val tpat=if(showTime)"  HH:mm" else""
            SimpleDateFormat(pat+tpat,Locale.getDefault()).format(Date())
        }
        val tp=Paint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize=w*0.026f
        val vis = dateStampVisual(style)
        tp.typeface=vis.typeface
        tp.color=vis.color
        if(vis.shadowColor!=0)tp.setShadowLayer(vis.shadowRadius,vis.shadowDx,vis.shadowDy,vis.shadowColor) else tp.clearShadowLayer()
        if(vis.letterSpacing>0f)tp.letterSpacing=vis.letterSpacing
        if(vis.lcd && StampLcd.font!=null)tp.typeface=StampLcd.font
        val size=tp.textSize
        val tw=if(vis.lcd && StampLcd.font==null) lcdTextWidth(text, size*0.96f, vis.letterSpacing) else tp.measureText(text)
        val pad=w*0.015f
        val x = when(pos){1->w*0.03f;2->w-tw-w*0.025f;3->w*0.03f;else->w-tw-w*0.025f}
        val y = when(pos){0->h-h*0.022f;1->h-h*0.022f;else->size*1.4f+pad}
        if(vis.chipColor!=0){
            canvas.drawRoundRect(x-pad,y-size*0.8f,x+tw+pad,y+size*0.3f,4f,4f,
                Paint().apply{color=vis.chipColor;setShadowLayer(3f,1f,1f,android.graphics.Color.argb(80,0,0,0))})
        }
        if(vis.rotation!=0f){
            canvas.save()
            canvas.rotate(vis.rotation,w/2f,h/2f)
            drawStampText(canvas,text,x,y,tp,vis.stroke,vis.lcd)
            canvas.restore()
        } else drawStampText(canvas,text,x,y,tp,vis.stroke,vis.lcd)
        return result
    }

}

@Composable
fun FilmOverlay(profile:FilmProfile,modifier:Modifier=Modifier,grainAmt:Float=-1f,leakAmt:Float=-1f,leakColorMode:LightLeakColor=LightLeakColor.ORANGE){
    if(profile.id=="normal"&&grainAmt<=0f&&leakAmt<=0f)return
    if(profile.isMonochrome)Canvas(modifier){drawRect(Color.Gray,blendMode=BlendMode.Color)}
    if(profile.previewTintAlpha>0f)Canvas(modifier){drawRect(profile.previewTint.copy(alpha=profile.previewTintAlpha*0.55f))}
    if(profile.warmthPreview!=0f){val wc=if(profile.warmthPreview>0f)Color(0xFFFF8800)else Color(0xFF0033FF);Canvas(modifier){drawRect(wc.copy(alpha=kotlin.math.abs(profile.warmthPreview)*0.09f))}}
    if(profile.fade>0f)Canvas(modifier){drawRect(Color.White.copy(alpha=profile.fade*0.13f))}
    val lk=if(leakAmt>=0f)leakAmt else profile.defaultLightLeak
    if(lk>0f){val lkSeed=remember{System.currentTimeMillis()};Canvas(modifier){drawIntoCanvas{c->renderLeak(c.nativeCanvas,size.width,size.height,lk,leakColorMode,lkSeed)}}}
    if(profile.defaultVignette>0f){Canvas(modifier){drawIntoCanvas{c->val p=android.graphics.Paint().apply{shader=RadialGradient(size.width/2,size.height/2,maxOf(size.width,size.height)*0.72f,intArrayOf(android.graphics.Color.TRANSPARENT,android.graphics.Color.argb((profile.defaultVignette*185).toInt(),0,0,0)),floatArrayOf(0.42f,1f),Shader.TileMode.CLAMP)};c.nativeCanvas.drawRect(0f,0f,size.width,size.height,p)}}}
    val gr=if(grainAmt>=0f)grainAmt else profile.defaultGrain
    if(gr>0f){
        // Grain is static by default (Settings → Film grain animation). A static
        // overlay renders once and never recomposes — the old "while(true){delay(42)}"
        // loop regenerated thousands of points at ~24Hz forever, even with the sheet
        // closed, stalling the whole app and the live feed.
        val grainContext = LocalContext.current
        val grainMotion = remember { AppSettings.grainAnim(grainContext) }
        var seed by remember{mutableIntStateOf(0)}
        if (grainMotion) { LaunchedEffect(Unit){ while(true){ delay(80); seed=(seed+1)%9999 } } }
        Canvas(modifier){
            val rnd=java.util.Random(seed.toLong())
            val cnt=minOf((size.width*size.height*gr*0.0015f).toInt(),1100)
            val pts=ArrayList<Offset>(cnt)
            repeat(cnt){pts.add(Offset(rnd.nextFloat()*size.width,rnd.nextFloat()*size.height))}
            drawPoints(pts,androidx.compose.ui.graphics.PointMode.Points,Color.White.copy(gr*0.30f),strokeWidth=profile.grainPixelSize.dp.toPx()*1.4f)
            val pts2=ArrayList<Offset>(cnt/2)
            repeat(cnt/2){pts2.add(Offset(rnd.nextFloat()*size.width,rnd.nextFloat()*size.height))}
            drawPoints(pts2,androidx.compose.ui.graphics.PointMode.Points,Color.Black.copy(gr*0.12f),strokeWidth=profile.grainPixelSize.dp.toPx())
        }
    }
}
