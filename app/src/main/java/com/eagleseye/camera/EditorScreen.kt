package com.eagleseye.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import com.eagleseye.camera.editor.EditRecipe
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.eagleseye.camera.editor.EdCpuEngine
import com.eagleseye.camera.editor.OffscreenEditor
import com.eagleseye.camera.film.FilmBrand
import com.eagleseye.camera.film.FilmCatalog
import com.eagleseye.camera.editor.EditorCurves
import com.eagleseye.camera.engine.MiDasEngine
import com.eagleseye.camera.engine.HairSegEngine
import com.eagleseye.camera.engine.DepthFusionEngine
import com.eagleseye.camera.engine.BirefNetEngine
import com.eagleseye.camera.editor.EditorOp
import com.eagleseye.camera.engine.FrameDraw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private const val PREVIEW_MAX = 2200
private const val EXPORT_MAX = 4096

// ── State models ─────────────────────────────────────────────────────────────
private enum class Tool { NONE, TUNE, WB, CURVES, HSL, STRUCTURE, SELECT, MASK, VIGNETTE, DETAIL, GRAIN, TILT, BOKEH, LOOK, EFFECT, FILM, FRAME, CROP, ROTATE, GRADING, OPTICS, STRAIGHTEN, PERSPECTIVE, MARKUP, RECIPES, PRESETS }

/** Master-spec workflow sections — the Photo Studio is organized around these,
 *  not around flat tools: LIGHT · COLOR · EFFECTS · DETAIL · OPTICS · GEOMETRY
 *  · LOCAL (masks) · CREATIVE · PRESETS · RECIPES. */
private enum class Section(val label: String, val icon: ImageVector) {
    LIGHT("LIGHT", Icons.Default.WbSunny), COLOR("COLOR", Icons.Default.ColorLens), EFFECTS("EFFECTS", Icons.Default.AutoAwesome),
    DETAIL("DETAIL", Icons.Default.Details), OPTICS("OPTICS", Icons.Default.Lens), GEOMETRY("GEOMETRY", Icons.Default.Transform),
    LOCAL("LOCAL", Icons.Default.Gradient), CREATIVE("CREATIVE", Icons.Default.Style),
    PRESETS("PRESETS", Icons.Default.AutoAwesomeMotion), RECIPES("RECIPES", Icons.Default.MenuBook)
}

private fun Tool.section(): Section = when (this) {
    Tool.TUNE, Tool.CURVES -> Section.LIGHT
    Tool.WB, Tool.HSL, Tool.GRADING -> Section.COLOR
    Tool.STRUCTURE, Tool.VIGNETTE, Tool.GRAIN, Tool.TILT, Tool.BOKEH -> Section.EFFECTS
    Tool.DETAIL -> Section.DETAIL
    Tool.OPTICS -> Section.OPTICS
    Tool.CROP, Tool.ROTATE, Tool.STRAIGHTEN, Tool.PERSPECTIVE -> Section.GEOMETRY
    Tool.SELECT, Tool.MASK -> Section.LOCAL
    Tool.LOOK, Tool.EFFECT, Tool.FILM, Tool.FRAME, Tool.MARKUP -> Section.CREATIVE
    Tool.PRESETS -> Section.PRESETS
    else -> Section.RECIPES
}

private val SECTION_TOOLS: Map<Section, List<Tool>> = mapOf(
    Section.LIGHT to listOf(Tool.TUNE, Tool.CURVES),
    Section.COLOR to listOf(Tool.WB, Tool.HSL, Tool.GRADING),
    Section.EFFECTS to listOf(Tool.STRUCTURE, Tool.VIGNETTE, Tool.GRAIN, Tool.TILT, Tool.BOKEH),
    Section.DETAIL to listOf(Tool.DETAIL),
    Section.OPTICS to listOf(Tool.OPTICS),
    Section.GEOMETRY to listOf(Tool.CROP, Tool.ROTATE, Tool.STRAIGHTEN, Tool.PERSPECTIVE),
    Section.LOCAL to listOf(Tool.SELECT, Tool.MASK),
    Section.CREATIVE to listOf(Tool.LOOK, Tool.EFFECT, Tool.FILM, Tool.FRAME, Tool.MARKUP),
    Section.PRESETS to listOf(Tool.PRESETS),
    Section.RECIPES to listOf(Tool.RECIPES)
)

private val TOOL_ICON = mapOf(
    Tool.TUNE to Icons.Default.Tune, Tool.CURVES to Icons.Default.ShowChart, Tool.WB to Icons.Default.WbAuto, Tool.HSL to Icons.Default.Palette,
    Tool.GRADING to Icons.Default.Colorize, Tool.STRUCTURE to Icons.Default.Contrast, Tool.VIGNETTE to Icons.Default.BlurCircular, Tool.GRAIN to Icons.Default.BlurOn,
    Tool.TILT to Icons.Default.Straighten, Tool.BOKEH to Icons.Default.Filter, Tool.DETAIL to Icons.Default.Details, Tool.OPTICS to Icons.Default.Lens,
    Tool.CROP to Icons.Default.CropFree, Tool.ROTATE to Icons.Default.RotateRight, Tool.STRAIGHTEN to Icons.Default.Straighten, Tool.PERSPECTIVE to Icons.Default.CropRotate,
    Tool.SELECT to Icons.Default.SelectAll, Tool.MASK to Icons.Default.Brush, Tool.LOOK to Icons.Default.AutoStories, Tool.EFFECT to Icons.Default.FilterVintage,
    Tool.FILM to Icons.Default.Movie, Tool.FRAME to Icons.Default.AspectRatio, Tool.MARKUP to Icons.Default.Edit,
    Tool.PRESETS to Icons.Default.AutoAwesomeMotion, Tool.RECIPES to Icons.Default.MenuBook, Tool.NONE to Icons.Default.Close
)

private val TOOL_LABEL = mapOf(
    Tool.TUNE to "LIGHT", Tool.CURVES to "CURVES", Tool.WB to "WB", Tool.HSL to "HSL",
    Tool.GRADING to "GRADING", Tool.STRUCTURE to "STRUCTURE", Tool.VIGNETTE to "VIGNETTE",
    Tool.GRAIN to "GRAIN", Tool.TILT to "TILT", Tool.BOKEH to "LENS", Tool.DETAIL to "DETAIL",
    Tool.OPTICS to "OPTICS", Tool.CROP to "CROP", Tool.ROTATE to "ROTATE",
    Tool.STRAIGHTEN to "LEVEL", Tool.PERSPECTIVE to "PERSPECTIVE", Tool.SELECT to "SELECT",
    Tool.MASK to "MASK", Tool.LOOK to "LOOKS", Tool.EFFECT to "EFFECTS", Tool.FILM to "FILM",
    Tool.FRAME to "FRAME", Tool.MARKUP to "MARKUP", Tool.PRESETS to "PRESETS",
    Tool.RECIPES to "RECIPES", Tool.NONE to "CLOSE"
)

/** One-tap full-state looks. They set the same state the panels edit, so the
 *  preview pipeline, undo/redo, recipes and export treat them identically to
 *  hand-tuned edits. */
private data class PresetDef(
    val name: String,
    val tune: TuneParams = TuneParams(), val wb: WbParams = WbParams(),
    val vig: VigParams = VigParams(), val tilt: TiltParams = TiltParams(),
    val grain: Float = 0f, val structure: Float = 0f,
    val lookName: String? = null, val lookStr: Float = 1f,
    val fxName: String? = null, val fxVal: Float = 0.5f,
    val split: SplitParams = SplitParams(),
    val detail: DetailParams = DetailParams()
)

private val PRESETS = listOf(
    PresetDef("PUNCH",
        tune = TuneParams(b = 0.06f, c = 0.25f, s = 0.18f, highlights = -0.15f, shadows = 0.12f, exposure = 0.06f, whites = 0.08f, vibrance = 0.22f),
        detail = DetailParams(amount = 0.35f, radius = 0.3f, detail = 0.5f, masking = 0.15f)),
    PresetDef("MATTE FILM",
        tune = TuneParams(b = 0.05f, c = 0.12f, s = -0.12f, highlights = -0.2f, shadows = 0.25f, exposure = 0.08f, whites = 0.1f, blacks = 0.25f),
        grain = 0.3f, structure = 0.15f, fxName = "bleach", fxVal = 0.55f),
    PresetDef("GOLDEN HOUR",
        tune = TuneParams(warmth = 0.22f, b = 0.08f, s = 0.08f, highlights = -0.1f, shadows = 0.15f, vibrance = 0.12f),
        wb = WbParams(temp = 0.15f)),
    PresetDef("COOL CINEMA",
        tune = TuneParams(warmth = -0.18f, c = 0.18f, s = -0.05f, highlights = 0.1f, shadows = -0.1f),
        grain = 0.2f,
        split = SplitParams(shB = 0.18f, hiB = 0.12f, hiR = 0.06f, bal = 0f, str = 0.5f)),
    PresetDef("MONO", tune = TuneParams(c = 0.15f, exposure = 0.05f), grain = 0.18f, lookName = "MONO"),
    PresetDef("NOIR",
        tune = TuneParams(c = 0.3f, highlights = -0.2f, blacks = -0.15f),
        vig = VigParams(on = true, amount = 0.8f, radius = 0.75f, softness = 0.45f),
        lookName = "NOIR"),
    PresetDef("VELVIA", tune = TuneParams(vibrance = 0.25f, s = 0.15f, c = 0.2f), fxName = "velvia", fxVal = 1f),
    PresetDef("PORTRA SNAP", tune = TuneParams(warmth = 0.08f, exposure = 0.05f, s = -0.05f), grain = 0.12f, fxName = "portra800", fxVal = 1f),
    PresetDef("INSTANT", grain = 0.2f, structure = 0.2f,
        vig = VigParams(on = true, amount = 0.35f, radius = 0.8f, softness = 0.5f),
        fxName = "instant", fxVal = 0.8f),
    PresetDef("VINTAGE FADE",
        tune = TuneParams(b = 0.1f, c = -0.05f, s = -0.15f, warmth = 0.1f, blacks = 0.3f),
        grain = 0.35f, structure = 0.2f, fxName = "bleach", fxVal = 0.75f),
    PresetDef("ASTRO",
        tune = TuneParams(c = 0.3f, s = 0.1f, blacks = -0.2f, highlights = 0.15f),
        fxName = "obsidian", fxVal = 0.9f),
    PresetDef("NEON", tune = TuneParams(s = 0.2f, vibrance = 0.15f), fxName = "false_color", fxVal = 0.8f)
)

private fun applyPreset(ctx: Ctx, p: PresetDef) {
    ctx.tune.value = p.tune; ctx.wb.value = p.wb
    ctx.vig.value = p.vig; ctx.tilt.value = p.tilt
    ctx.grain.value = p.grain; ctx.structure.value = p.structure
    ctx.split.value = p.split; ctx.detail.value = p.detail
    val li = p.lookName?.let { n -> LOOKS.indexOfFirst { it.first == n } } ?: -1
    ctx.lookIdx.value = if (li >= 0) li else 0
    ctx.lookStr.value = p.lookStr
    val fi = p.fxName?.let { n -> FX.indexOfFirst { it.id == n } } ?: -1
    ctx.fxIdx.value = fi
    ctx.fxVal.value = p.fxVal
    commit(ctx)
    requestPreview(ctx)
}

private data class TuneParams(
    val b: Float = 0f, val c: Float = 0f, val s: Float = 0f,
    val warmth: Float = 0f, val highlights: Float = 0f, val shadows: Float = 0f, val amb: Float = 0f,
    val exposure: Float = 0f, val whites: Float = 0f, val blacks: Float = 0f, val vibrance: Float = 0f
)

private data class VigParams(val on: Boolean = false, val amount: Float = 0.4f, val radius: Float = 0.65f, val softness: Float = 0.5f)

private data class TiltParams(val on: Boolean = false, val focusY: Float = 0.5f, val width: Float = 0.25f, val feather: Float = 0.35f)

private data class SelSpot(val x: Float, val y: Float, val r: Float = 0.12f, val b: Float = 0f, val s: Float = 1f, val t: Float = 0f)

private data class WbParams(val temp: Float = 0f, val tint: Float = 0f)
private data class HslZone(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f)
private data class HslMix(val zones: List<HslZone> = List(8) { HslZone() })
private data class SplitParams(
    val shR: Float = 0f, val shG: Float = 0f, val shB: Float = 0f,
    val midR: Float = 0f, val midG: Float = 0f, val midB: Float = 0f,
    val hiR: Float = 0f, val hiG: Float = 0f, val hiB: Float = 0f,
    val bal: Float = 0f, val str: Float = 0f)
private data class DetailParams(
    val amount: Float = 0.3f, val radius: Float = 0.3f, val detail: Float = 0.5f,
    val masking: Float = 0f, val lumNR: Float = 0f, val colorNR: Float = 0f)
private data class FxLocalParams(val text: Float = 0f, val clarity: Float = 0f, val dehaze: Float = 0f)
private data class OptParams(val distortion: Float = 0f, val ca: Float = 0f)
private data class GeomParams(val angle: Float = 0f, val perspV: Float = 0f, val perspH: Float = 0f)
private data class LensParams(
    val on: Boolean = false, val focusY: Float = 0.5f, val width: Float = 0.22f,
    val feather: Float = 0.18f, val amount: Float = 0.5f,
    val useDepth: Boolean = false, val focusDepth: Float = 0.5f,
    val range: Float = 0.35f, val blades: Float = 0f,
    val autoFocus: Boolean = true)

private data class MaskBrush(val x: Float, val y: Float, val r: Float, val erase: Boolean)

private data class MaskEntry(
    var name: String = "MASK 1",
    var mode: Int = 0, var erase: Boolean = false,
    var size: Float = 0.09f, var feather: Float = 0.25f, var flow: Float = 0.8f,
    var bright: Float = 0f, var sat: Float = 0f, var warm: Float = 0f, var strength: Float = 1f,
    var expos: Float = 0f, var contr: Float = 0f,
    var inverted: Boolean = false, var visible: Boolean = true, var show: Boolean = false,
    var gx0: Float = -1f, var gy0: Float = -1f, var gx1: Float = -1f, var gy1: Float = -1f,
    var radial: Boolean = false, var aiMode: Int = 0,
    var rangeMin: Float = -1f, var rangeMax: Float = -1f,
    var rev: Int = 0,
    var aiBmp: Bitmap? = null,
    val strokes: SnapshotStateList<MaskBrush> = mutableStateListOf(),
    val erases: SnapshotStateList<MaskBrush> = mutableStateListOf(),
)
private data class MaskParams(
    val mode: Int = 0, val size: Float = 0.09f, val feather: Float = 0.25f,
    val bright: Float = 0f, val sat: Float = 0f, val warm: Float = 0f,
    val strength: Float = 1f, val inverted: Boolean = false, val show: Boolean = true,
    val erase: Boolean = false,
    val gx0: Float = -1f, val gy0: Float = -1f, val gx1: Float = -1f, val gy1: Float = -1f)

// ── Markup (pencil) ───────────────────────────────────────────────────────────
private data class MarkStroke(val x: Float, val y: Float, val w: Float, val color: Int, val erase: Boolean)
private data class MarkEntry(
    var name: String = "INK 1",
    var color: Int = 0xFF1A1A1A.toInt(),
    var size: Float = 0.012f,
    var visible: Boolean = true,
    val strokes: SnapshotStateList<MarkStroke> = mutableStateListOf()
)

private fun MaskParams.gradientSet(): Boolean =
    gx0 >= 0f && gy0 >= 0f && gx1 >= 0f && gy1 >= 0f

private data class EditSnap(
    val tune: TuneParams = TuneParams(), val vig: VigParams = VigParams(), val tilt: TiltParams = TiltParams(),
    val grain: Float = 0f,
    val c0: List<FloatArray>, val c1: List<FloatArray>, val c2: List<FloatArray>, val c3: List<FloatArray>,
    val spots: List<SelSpot>, val sel: Int,
    val look: Int, val lookStr: Float, val fx: Int, val fxVal: Float,
    val heal: Boolean = false,
    val frame: FrameType, val cfg: FrameConfig, val mix: Float, val stamp: Boolean,
    val rotQ: Int, val flipH: Boolean, val flipV: Boolean, val crop: RectF, val aspect: Float,
    val wb: WbParams = WbParams(), val hsl: HslMix = HslMix(), val split: SplitParams = SplitParams(),
    val structure: Float = 0f, val geom: GeomParams = GeomParams(), val lens: LensParams = LensParams(),
    val detail: DetailParams = DetailParams(), val fxl: FxLocalParams = FxLocalParams(), val opts: OptParams = OptParams(),
    val mask: MaskParams = MaskParams(), val mstrokes: List<MaskBrush> = emptyList(),
    val masks: List<MaskEntry> = emptyList(),
    val marks: List<MarkEntry> = emptyList(),
    val filmId: String? = null, val markMix: Float = 1f
)

private data class FxDef(val id: String, val label: String, val param: String, val pLabel: String, val r0: Float, val r1: Float, val def: Float)

private val FX = listOf(
    FxDef("vhs", "VHS", "tracking", "TRACKING", 0f, 1f, 0.35f),
    FxDef("crt", "CRT", "intensity", "INTENSITY", 0f, 1f, 0.5f),
    FxDef("grain", "GRAIN", "intensity", "AMOUNT", 0f, 1f, 0.35f),
    FxDef("dust", "DUST", "intensity", "AMOUNT", 0f, 1f, 0.5f),
    FxDef("tilt_shift", "TILT SHIFT", "width", "WIDTH", 0.05f, 0.9f, 0.3f),
    FxDef("halftone", "HALFTONE", "dotSize", "DOT", 2f, 26f, 7f),
    FxDef("cmyk_dots", "CMYK", "dotSize", "DOT", 2f, 26f, 6f),
    FxDef("fat_pixel", "PIXEL", "size", "SIZE", 2f, 26f, 6f),
    FxDef("glitch", "GLITCH", "intensity", "AMOUNT", 0f, 1f, 0.6f),
    FxDef("1bit", "1BIT", "scale", "SCALE", 1f, 12f, 3f),
    FxDef("terminal", "TERMINAL", "color", "COLOR", 0f, 1f, 0.5f),
    FxDef("duotone", "DUOTONE", "none", "AMOUNT", 0f, 1f, 1f),
    FxDef("bw_grain", "B&W", "intensity", "AMOUNT", 0f, 1f, 0.8f),
    FxDef("color_shift", "SHIFT", "intensity", "STRENGTH", 0f, 1f, 0.6f),
    FxDef("bleach", "BLEACH", "intensity", "STRENGTH", 0f, 1f, 0.7f),
    FxDef("cross", "CROSS", "intensity", "STRENGTH", 0f, 1f, 0.7f),
    FxDef("instant", "INSTANT", "intensity", "STRENGTH", 0f, 1f, 0.8f),
    FxDef("retro", "RETRO", "intensity", "STRENGTH", 0f, 1f, 0.7f),
    FxDef("false_color", "FALSE COLOR", "intensity", "STRENGTH", 0f, 1f, 0.7f),
    FxDef("kaleido", "KALEIDO", "slices", "SLICES", 2f, 12f, 6f),
    FxDef("velvia", "VELVIA", "intensity", "STRENGTH", 0f, 1f, 0.8f),
    FxDef("portra800", "PORTRA 800", "intensity", "STRENGTH", 0f, 1f, 0.8f),
    FxDef("winter", "WINTER", "intensity", "STRENGTH", 0f, 1f, 0.7f),
    FxDef("obsidian", "OBSIDIAN", "intensity", "STRENGTH", 0f, 1f, 0.8f),
    FxDef("infrared", "INFRARED", "intensity", "STRENGTH", 0f, 1f, 0.8f),
    FxDef("streak", "STREAK", "intensity", "AMOUNT", 0f, 1f, 0.6f),
    FxDef("teletext", "TELETEXT", "intensity", "AMOUNT", 0f, 1f, 0.6f)
)

private val LOOKS = listOf(
    "ORIGINAL" to emptyList<Pair<String, Float>>(),
    "MONO" to listOf("bw_grain" to 0.9f),
    "NOIR" to listOf("bw_grain" to 0.55f, "vignette" to 0.9f),
    "VELVIA" to listOf("velvia" to 1f),
    "PORTRA" to listOf("portra800" to 1f),
    "FADE" to listOf("bleach" to 0.8f),
    "COLD" to listOf("winter" to 0.8f),
    "GOLDEN" to listOf("retro" to 0.8f),
    "ASTRO" to listOf("obsidian" to 0.8f),
    "PASTEL" to listOf("instant" to 0.7f),
    "NEON" to listOf("false_color" to 0.7f),
    "BW GRAIN" to listOf("bw_grain" to 0.6f, "grain" to 0.45f)
)

private val FRAME_TYPES = listOf(
    FrameType.CLASSIC_POLAROID to "POLAROID",
    FrameType.MINIMAL_MAT to "MAT",
    FrameType.FILM_35MM to "35MM",
    FrameType.THICK_MATTE to "MATTE",
    FrameType.CARTRIDGE_110 to "110",
    FrameType.VELVET_CINEMA to "VELVET",
    FrameType.FILM_8MM to "8MM",
    FrameType.SLIDE_MOUNT to "SLIDE",
    FrameType.INSTAX to "INSTAX"
)

private const val MIN_CROP = 0.06f

private fun dateStampText(): String = SimpleDateFormat("yy.MM.dd  HH:mm", Locale.US).format(Date())

private fun fxOp(id: String, v: Float): EditorOp {
    val op = EditorOp(id)
    when (id) {
        "1bit" -> op.p("scale", v)
        "halftone", "cmyk_dots" -> op.p("dotSize", v)
        "fat_pixel" -> op.p("size", v).p("glitch", 0.25f)
        "tilt_shift" -> op.p("focusY", 0.5f).p("width", v).p("feather", 0.3f)
        "teletext" -> op.p("intensity", v).p("uSize", 6f)
        "vhs" -> op.p("tracking", v)
        "kaleido" -> op.p("slices", v.coerceIn(2f, 12f)).p("intensity", 1f)
        else -> op.p("intensity", v)
    }
    return op
}

private fun vignetteOp(on: Boolean, amount: Float, radius: Float, soft: Float): EditorOp? =
    if (!on) null
    else EditorOp("vignette").p("radius", radius.coerceIn(0.2f, 1.5f)).p("softness", soft).p("intensity", amount)

// ── Shared app-wide editor knob state ────────────────────────────────────────
private class Ctx(
    val context: Context, val scope: CoroutineScope,
    val base: MutableState<Bitmap?>, val oriented: MutableState<Bitmap?>,
    val loadFailed: MutableState<Boolean>, val saving: MutableState<Boolean>,
    val rotQ: MutableState<Int>, val flipH: MutableState<Boolean>, val flipV: MutableState<Boolean>,
    val crop: MutableState<RectF>, val cropAspect: MutableState<Float>,
    val tune: MutableState<TuneParams>, val vig: MutableState<VigParams>, val tilt: MutableState<TiltParams>,
    val wb: MutableState<WbParams>, val hsl: MutableState<HslMix>, val split: MutableState<SplitParams>,
    val structure: MutableState<Float>, val geom: MutableState<GeomParams>, val lens: MutableState<LensParams>,
    val mask: MutableState<MaskParams>, val mstrokes: SnapshotStateList<MaskBrush>, val maskBmp: MutableState<Bitmap?>,
    val previewBmp: MutableState<Bitmap?>, val previewBusy: MutableState<Boolean>, val previewPending: MutableState<Boolean>,
    val previewSrc: MutableState<Bitmap?>, val previewOld: MutableState<Bitmap?>,
    val depthBmp: MutableState<Bitmap?>, val depthLoading: MutableState<Boolean>,
    val depthFail: MutableState<String>,
    val grain: MutableState<Float>,
    val detail: MutableState<DetailParams>, val fxl: MutableState<FxLocalParams>, val opts: MutableState<OptParams>,
    val curves: Array<SnapshotStateList<FloatArray>>, val curveChan: MutableState<Int>,
    val spots: SnapshotStateList<SelSpot>, val selIdx: MutableState<Int>,
    val lookIdx: MutableState<Int>, val lookStr: MutableState<Float>,
    val fxIdx: MutableState<Int>, val fxVal: MutableState<Float>,
    val frameType: MutableState<FrameType>, val frameCfg: MutableState<FrameConfig>,
    val frameMix: MutableState<Float>, val stamp: MutableState<Boolean>, val overlay: MutableState<Bitmap?>,
    val tool: MutableState<Tool>, val compare: MutableState<Boolean>,
    val undo: SnapshotStateList<EditSnap>, val redo: SnapshotStateList<EditSnap>,
    var saveNow: (Boolean) -> Unit, val onClose: () -> Unit,
    val maskList: SnapshotStateList<MaskEntry> = mutableStateListOf(),
    val maskIdx: MutableState<Int> = mutableStateOf(0),
    val maskCache: MutableMap<Int, Pair<Int, Bitmap?>> = mutableMapOf(),
    val filmId: MutableState<String?> = mutableStateOf(null),
    val renderDiag: MutableState<String> = mutableStateOf(""),
    val markList: SnapshotStateList<MarkEntry> = mutableStateListOf(),
    val markIdx: MutableState<Int> = mutableStateOf(0),
    val markBmp: MutableState<Bitmap?> = mutableStateOf(null),
    val markColor: MutableState<Int> = mutableStateOf(0xFF1A1A1A.toInt()),
    val markSize: MutableState<Float> = mutableStateOf(0.012f),
    val markMix: MutableState<Float> = mutableStateOf(1f),
    val cropKeepUntil: MutableState<Long> = mutableStateOf(0L),
    val markErase: MutableState<Boolean> = mutableStateOf(false),
    val recipes: SnapshotStateList<Pair<String, String>> = mutableStateListOf(),
    val fastPreview: MutableState<Boolean> = mutableStateOf(false),
    val fastSrc: MutableState<Bitmap?> = mutableStateOf(null),
    val section: MutableState<Section> = mutableStateOf(Section.LIGHT),
    val fullscreen: MutableState<Boolean> = mutableStateOf(false),
    val heal: MutableState<Boolean> = mutableStateOf(false),
)

@Composable
fun EditorScreen(uri: Uri, onClose: () -> Unit, onSaved: () -> Unit, editMode: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ctx = remember(uri) {
        Ctx(
            context, scope,
            mutableStateOf(null), mutableStateOf(null), mutableStateOf(false), mutableStateOf(false),
            mutableStateOf(0), mutableStateOf(false), mutableStateOf(false),
            mutableStateOf(RectF(0f, 0f, 1f, 1f)), mutableStateOf(0f),
            mutableStateOf(TuneParams()), mutableStateOf(VigParams()), mutableStateOf(TiltParams()),
            mutableStateOf(WbParams()), mutableStateOf(HslMix()), mutableStateOf(SplitParams()),
            mutableStateOf(0f), mutableStateOf(GeomParams()), mutableStateOf(LensParams()),
            mutableStateOf(MaskParams()), mutableStateListOf(), mutableStateOf(null),
            mutableStateOf(null), mutableStateOf(false), mutableStateOf(false),
            mutableStateOf(null), mutableStateOf(null),
            mutableStateOf(null), mutableStateOf(false),
            mutableStateOf(""),
            mutableStateOf(0f),
            mutableStateOf(DetailParams()), mutableStateOf(FxLocalParams()), mutableStateOf(OptParams()),
            arrayOf(
                mutableStateListOf(floatArrayOf(0f, 0f), floatArrayOf(255f, 255f)),
                mutableStateListOf(floatArrayOf(0f, 0f), floatArrayOf(255f, 255f)),
                mutableStateListOf(floatArrayOf(0f, 0f), floatArrayOf(255f, 255f)),
                mutableStateListOf(floatArrayOf(0f, 0f), floatArrayOf(255f, 255f)),
            ),
            mutableStateOf(0), mutableStateListOf(), mutableStateOf(-1),
            mutableStateOf(0), mutableStateOf(1f), mutableStateOf(-1), mutableStateOf(0.5f),
            mutableStateOf(FrameType.NONE), mutableStateOf(FrameConfig(borderWidth = 0.08f, bottomSpace = 0.16f)),
            mutableStateOf(1f), mutableStateOf(false), mutableStateOf(null),
            mutableStateOf(Tool.NONE), mutableStateOf(false),
            mutableStateListOf(), mutableStateListOf(),
            { _ -> }, onClose
        )
    }
    val undoList = ctx.undo

    // Back: exit fullscreen → close the active tool → leave the editor.
    BackHandler {
        when {
            ctx.fullscreen.value -> ctx.fullscreen.value = false
            ctx.tool.value != Tool.NONE -> ctx.tool.value = Tool.NONE
            else -> ctx.onClose()
        }
    }

    val opened = remember(uri) { resolveEditState(context, uri) }
    val srcUri = opened?.first ?: uri

    LaunchedEffect(uri) {
        val bm = withContext(Dispatchers.Default) { decodeForUri(context, srcUri, PREVIEW_MAX) }
        if (bm == null) ctx.loadFailed.value = true else ctx.base.value = bm
        val doc = opened?.second
        if (bm != null && doc != null) applyRecipe(ctx, doc)
    }

    LaunchedEffect(ctx.base.value, ctx.rotQ.value, ctx.flipH.value, ctx.flipV.value) {
        val b = ctx.base.value ?: return@LaunchedEffect
        val m = Matrix().apply {
            postRotate(90f * ctx.rotQ.value)
            if (ctx.flipH.value) postScale(-1f, 1f)
            if (ctx.flipV.value) postScale(1f, -1f)
        }
        val nb = runCatching { Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true) }.getOrNull() ?: b
        val old = ctx.oriented.value
        ctx.oriented.value = nb
        if (old !== nb && old != null) old.recycle()
        // A restore/recipe just armed this window: keep its crop rect instead of
        // wiping to full (the effect re-runs whenever rotQ/flip change back).
        if (android.os.SystemClock.elapsedRealtime() > ctx.cropKeepUntil.value)
            ctx.crop.value = RectF(0f, 0f, 1f, 1f)
        withContext(Dispatchers.Default) {
            // Render the preview at near-display resolution so edits are NOT
            // upscaled onto the canvas (the old 1400px cap stretched every edit
            // and destroyed texture). 2400 covers high-res phone screens with
            // little-to-no upscaling; the gesture fast-path below stays lower.
            val mx = 2400
            val sc = kotlin.math.min(1f, mx.toFloat() / kotlin.math.max(nb.width, nb.height))
            val ps = if (sc < 1f) Bitmap.createScaledBitmap(nb, (nb.width * sc).toInt().coerceAtLeast(1), (nb.height * sc).toInt().coerceAtLeast(1), true)
                     else nb
            val po = ctx.previewSrc.value
            ctx.previewSrc.value = ps
            if (po != null && po !== nb && po !== ps) po.recycle()
        }
    }

    LaunchedEffect(ctx.oriented.value) {
        val o = ctx.oriented.value ?: return@LaunchedEffect
        ctx.depthLoading.value = true
        ctx.depthBmp.value = null
        ctx.depthFail.value = ""
        withContext(Dispatchers.Default) {
            val res = runCatching {
                val model = MiDasEngine(ctx.context)
                try {
                    val n = 256
                    // Aspect-correct depth: the model takes a SQUARE input, so feed it a
                    // square center-crop of the photo, then stretch the square depth map
                    // back to the photo's aspect. The stored depthBmp then aligns 1:1
                    // with the photo under identity UV sampling — tap-to-focus and the
                    // bokeh mask hit the correct scene points (a raw 256×256 stretch
                    // squashes the scene horizontally and misplaces taps/blur).
                    val pw = o.width; val ph = o.height
                    val side = kotlin.math.min(pw, ph)
                    val sx = (pw - side) / 2; val sy = (ph - side) / 2
                    val inner = Bitmap.createBitmap(o, sx, sy, side, side)
                    val square = Bitmap.createScaledBitmap(inner, n, n, true)
                    val px = IntArray(n * n); square.getPixels(px, 0, n, 0, 0, n, n)
                    if (square !== o) square.recycle()
                    val rgb = ByteArray(n * n * 3)
                    var i = 0
                    while (i < px.size) {
                        val c = px[i]
                        rgb[i * 3] = (c shr 16 and 0xFF).toByte()
                        rgb[i * 3 + 1] = (c shr 8 and 0xFF).toByte()
                        rgb[i * 3 + 2] = (c and 0xFF).toByte()
                        i++
                    }
                    val out = model.run(java.nio.ByteBuffer.wrap(rgb))
                    val ow = model.outputWidth; val oh = model.outputHeight
                    val cnt = ow * oh
                    var f = FloatArray(cnt)
                    val fs = java.nio.ByteBuffer.allocateDirect(cnt * 4).order(java.nio.ByteOrder.nativeOrder())
                    fs.put(out); fs.rewind()
                    var k = 0; while (k < cnt) { f[k] = fs.getFloat(); k++ }

                    // ── Multi-cue fusion ──────────────────────────────────────────
                    // Refine the raw MiDaS depth with RGB edges/colour/texture, fill
                    // holes, and apply a soft SINet subject prior so the bokeh never
                    // hard-cuts the subject or hair. Best-effort: if SINet's asset is
                    // missing it is skipped and depth-only fusion still runs.
                    var segF: FloatArray? = null
                    var segW = 0; var segH = 0
                    runCatching {
                        val sNet = HairSegEngine(ctx.context)
                        try {
                            if (sNet.isAvailable) {
                                val sn = HairSegEngine.PREVIEW_INPUT_SIZE
                                val srgb = ByteArray(sn * sn * 3)
                                var j = 0
                                for (yy in 0 until sn) for (xx in 0 until sn) {
                                    val sx = ((xx * n) / sn).coerceIn(0, n - 1)
                                    val sy = ((yy * n) / sn).coerceIn(0, n - 1)
                                    val c = px[sy * n + sx]
                                    srgb[j++] = (c shr 16 and 0xFF).toByte()
                                    srgb[j++] = (c shr 8 and 0xFF).toByte()
                                    srgb[j++] = (c and 0xFF).toByte()
                                }
                                val mb = sNet.run(java.nio.ByteBuffer.wrap(srgb))
                                segF = DepthFusionEngine.segFromMask(mb, sNet.outputWidth * sNet.outputHeight)
                                segW = sNet.outputWidth; segH = sNet.outputHeight
                            }
                        } finally { runCatching { sNet.close() } }
                    }
                    val fused = DepthFusionEngine.refine(f, ow, oh, px, segF, segW, segH)
                    f = fused.depth
                    // Reverse the center-crop onto the depth map so it matches the photo.
                    val aspect = pw.toFloat() / ph.toFloat()
                    val tw = (ow * kotlin.math.max(aspect, 1f)).toInt().coerceAtLeast(1)
                    val th = (oh * kotlin.math.max(1f / aspect, 1f)).toInt().coerceAtLeast(1)
                    val tgt = FloatArray(tw * th)
                    var t = 0
                    for (y in 0 until th) {
                        val uy = y / th.toFloat()
                        val my = if (aspect < 1f) 0.5f + (uy - 0.5f) / aspect else uy
                        val syi = (my * oh).toInt().coerceIn(0, oh - 1)
                        for (x in 0 until tw) {
                            val ux = x / tw.toFloat()
                            val mx = if (aspect >= 1f) 0.5f + (ux - 0.5f) * aspect else ux
                            val sxi = (mx * ow).toInt().coerceIn(0, ow - 1)
                            tgt[t++] = f[syi * ow + sxi]
                        }
                    }
                    val tcnt = tw * th
                    val sorted = tgt.sorted()
                    val lo = sorted[(tcnt * 0.01f).toInt().coerceIn(0, tcnt - 1)]
                    val hi = sorted[(tcnt * 0.99f).toInt().coerceIn(0, tcnt - 1)]
                    val span = (hi - lo).coerceAtLeast(1e-5f)
                    val db = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                    val opx = IntArray(tcnt)
                    k = 0; while (k < tcnt) {
                        val v = ((tgt[k] - lo) / span).coerceIn(0f, 1f)
                        val b = (v * 255f).toInt()
                        opx[k] = 0xFF000000.toInt() or (b shl 16) or (b shl 8) or b
                        k++
                    }
                    db.setPixels(opx, 0, tw, 0, 0, tw, th)
                    db
                } finally { runCatching { model.close() } }
            }.let { r ->
                if (r.isSuccess) ctx.depthBmp.value = r.getOrNull()
                else ctx.depthFail.value = (r.exceptionOrNull()?.message ?: r.exceptionOrNull()?.javaClass?.simpleName ?: "unknown").take(120)
                ctx.depthLoading.value = false
            }
        }
    }

    LaunchedEffect(ctx.depthBmp.value) {
        if (ctx.depthBmp.value != null) { requestPreview(ctx) }
    }

    LaunchedEffect(ctx.oriented.value, ctx.frameType.value, ctx.frameCfg.value, ctx.stamp.value) {
        val o = ctx.oriented.value ?: return@LaunchedEffect
        if (ctx.frameType.value == FrameType.NONE) {
            ctx.overlay.value?.recycle()
            ctx.overlay.value = null
            return@LaunchedEffect
        }
        val nb = withContext(Dispatchers.Default) {
            buildFrameOverlay(o.width, o.height, ctx.frameType.value, ctx.frameCfg.value, if (ctx.stamp.value) dateStampText() else "")
        }
        val old = ctx.overlay.value
        ctx.overlay.value = nb
        old?.recycle()
    }

    LaunchedEffect(
        ctx.tune.value, ctx.vig.value, ctx.tilt.value,
        ctx.wb.value, ctx.hsl.value, ctx.split.value, ctx.structure.value, ctx.geom.value, ctx.lens.value,
        ctx.grain.value,
        ctx.detail.value, ctx.fxl.value, ctx.opts.value, ctx.markList.toList(), ctx.markMix.value,
        ctx.lookIdx.value, ctx.lookStr.value, ctx.fxIdx.value, ctx.fxVal.value,
        ctx.frameType.value, ctx.frameMix.value, ctx.compare.value, ctx.overlay.value,
        ctx.maskList.toList(), ctx.maskIdx.value
    ) {
        requestPreview(ctx)
    }

    ctx.saveNow = { share ->
        ctx.saving.value = true
        scope.launch {
            try {
                val ok = withContext(Dispatchers.Default) { renderAndSave(context, srcUri, ctx, editMode) }
                if (ok == null) Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                else {
                    Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
                    if (share) {
                        val si = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, ok)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(Intent.createChooser(si, "Share")) }
                    }
                    onSaved()
                }
            } finally {
                ctx.saving.value = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0E))) {
        if (ctx.fullscreen.value) {
            // Master-spec clean canvas: the image alone, edge to edge. Tap to exit.
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {
                ctx.fullscreen.value = false
            }) {
                val pv = ctx.previewBmp.value
                if (pv != null) {
                    Image(bitmap = pv.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else if (ctx.oriented.value != null) {
                    Image(bitmap = ctx.oriented.value!!.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                if (ctx.compare.value) {
                    Text("ORIGINAL", color = Color.White.copy(0.9f), fontSize = 10.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
                            .clip(RoundedCornerShape(50)).background(Color.Black.copy(0.6f)).padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(ctx)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val pv = ctx.previewBmp.value
                    if (pv != null) {
                        Image(bitmap = pv.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else if (ctx.oriented.value != null) {
                        Image(bitmap = ctx.oriented.value!!.asImageBitmap(), contentDescription = null,
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                    if (ctx.tool.value == Tool.CROP && ctx.oriented.value != null) CropOverlay(ctx)
                    if (ctx.tool.value == Tool.SELECT && ctx.spots.isNotEmpty()) SelectOverlay(ctx)
                    if (ctx.tool.value == Tool.MASK && ctx.oriented.value != null) MaskOverlay(ctx)
                    if (ctx.tool.value == Tool.BOKEH && ctx.lens.value.on && ctx.lens.value.useDepth) LensOverlay(ctx)
                    if (ctx.tool.value == Tool.STRAIGHTEN) StraightenOverlay(ctx)
                    if (ctx.tool.value == Tool.PERSPECTIVE) PerspectiveOverlay(ctx)
                    if (ctx.tool.value == Tool.MARKUP) MarkupOverlay(ctx)
                    val diag = ctx.renderDiag.value
                    if (diag.isNotEmpty()) {
                        Text(diag, color = when (diag) {
                            "GL" -> Color(0xFF4CD964); "CPU" -> Color(0xFFFFC94D); else -> Color(0xFFFF5A52)
                        }, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)
                                .clip(RoundedCornerShape(50)).background(Color.Black.copy(0.6f)).padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    if (ctx.compare.value) {
                        Text("ORIGINAL", color = Color.White.copy(0.9f), fontSize = 10.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
                                .clip(RoundedCornerShape(50)).background(Color.Black.copy(0.6f)).padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    if (ctx.loadFailed.value) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CAN'T OPEN THIS PHOTO", color = Color.White.copy(0.85f), fontSize = 13.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(6.dp))
                            Text("The file could not be decoded. Go back and pick another one.",
                                color = Color.White.copy(0.45f), fontSize = 11.sp)
                        }
                    } else if (ctx.oriented.value == null) {
                        CircularProgressIndicator(color = EagleGold, modifier = Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
                if (ctx.tool.value == Tool.NONE) SectionBar(ctx)
                else {
                    Column {
                        SubToolRow(ctx)
                        ToolPanel(ctx)
                    }
                }
            }
        }
        if (ctx.saving.value) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.72f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = EagleGold, modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Rendering…", color = Color.White.copy(0.85f), fontSize = 13.sp)
                }
            }
        }
    }
}

// ── Chain building / GL push ─────────────────────────────────────────────────
private fun chainOps(ctx: Ctx, incFrame: Boolean): List<EditorOp> {
    val out = mutableListOf<EditorOp>()
    val t = ctx.tune.value
    out += EditorOp("tune")
        .p("brightness", 1f + t.b)
        .p("contrast", 1f + t.c)
        .p("saturation", t.s)
        .p("warmth", t.warmth)
        .p("highlights", t.highlights)
        .p("shadows", t.shadows)
        .p("ambience", t.amb)
        .p("exposure", t.exposure)
        .p("whites", t.whites)
        .p("blacks", t.blacks)
        .p("vibrance", t.vibrance)
    val wbv = ctx.wb.value
    if (wbv.temp != 0f || wbv.tint != 0f) out += EditorOp("wb").p("temp", wbv.temp).p("tint", wbv.tint)
    ctx.filmId.value?.let { fid ->
        FilmCatalog.findById(fid)?.let { f ->
            out += EditorOp("film_grade")
                .p("sat", f.saturation).p("contrast", f.contrast)
                .p("lift", f.shadowLift).p("rolloff", f.highlightRolloff)
        }
    }
    val curvesActive = (0 until 4).any { ch -> ctx.curves[ch].any { it[0] > 1f || it[1] < 254f } }
    if (curvesActive) out += EditorOp("curves").p("channel", if (ctx.curveChan.value == 0) 0f else 1f).p("mix", 1f)
    if (ctx.spots.isNotEmpty()) {
        val heal = ctx.heal.value
        val op = EditorOp(if (heal) "heal" else "select")
        ctx.spots.take(6).forEachIndexed { i, s ->
            op.p("p${i}x", s.x).p("p${i}y", s.y).p("p${i}r", s.r)
            if (heal) op.p("p${i}w", s.b.coerceIn(0f, 1f))
            else op.p("p${i}w", 1f).p("p${i}b", s.b).p("p${i}s", s.s).p("p${i}t", s.t)
        }
        out += op
    }
    val zm = ctx.hsl.value.zones
    if (zm.any { it.hue != 0f || it.sat != 0f || it.lum != 0f }) {
        val op = EditorOp("hsl")
        zm.take(8).forEachIndexed { i, z -> op.p("h$i", z.hue).p("s$i", z.sat).p("l$i", z.lum) }
        out += op
    }
    val spv = ctx.split.value
    if (spv.str > 0f) out += EditorOp("split")
        .p("shR", spv.shR).p("shG", spv.shG).p("shB", spv.shB)
        .p("midR", spv.midR).p("midG", spv.midG).p("midB", spv.midB)
        .p("hiR", spv.hiR).p("hiG", spv.hiG).p("hiB", spv.hiB)
        .p("balance", spv.bal).p("strength", spv.str)
    if (ctx.structure.value > 0.005f) out += EditorOp("structure").p("amount", ctx.structure.value)
    val gmv = ctx.geom.value
    if (gmv.angle != 0f || gmv.perspV != 0f || gmv.perspH != 0f)
        out += EditorOp("geom").p("angle", gmv.angle * 0.01745f).p("perspV", gmv.perspV).p("perspH", gmv.perspH)
    val lbv = ctx.lens.value
    if (lbv.on && lbv.useDepth && ctx.depthBmp.value != null && lbv.amount > 0.005f) {
        // Auto-focus on the nearest subject until the user taps the photo —
        // with a raw 0.5 default the whole frame lands outside the sharp band
        // and everything gets blurred into a dark mush (looks like artifacts).
        val fdep = if (lbv.autoFocus) autoSubjectDepth(ctx.depthBmp.value) else lbv.focusDepth
        out += EditorOp("depthblurf").p("amount", lbv.amount * 1.3f)
            .p("focusDepth", fdep).p("range", lbv.range).p("blades", lbv.blades)
    }
    else if (lbv.on && !lbv.useDepth && lbv.amount > 0.005f)
        out += EditorOp("lensblur").p("focusY", lbv.focusY).p("focusW", lbv.width).p("feather", lbv.feather).p("amount", lbv.amount)
    val v = ctx.vig.value
    vignetteOp(v.on, v.amount, v.radius, v.softness)?.let { out += it }
    val dp = ctx.detail.value
    if (dp.amount > 0.01f || dp.lumNR > 0.01f || dp.colorNR > 0.01f)
        out += EditorOp("detail")
            .p("amount", dp.amount).p("radius", dp.radius).p("detail", dp.detail)
            .p("masking", dp.masking).p("lumNR", dp.lumNR).p("colorNR", dp.colorNR)
    val fx = ctx.fxl.value
    if (fx.text != 0f || fx.clarity != 0f || fx.dehaze != 0f)
        out += EditorOp("effects").p("text", fx.text).p("clarity", fx.clarity).p("dehaze", fx.dehaze)
    val opv = ctx.opts.value
    if (opv.distortion != 0f || opv.ca != 0f)
        out += EditorOp("optics").p("distortion", opv.distortion).p("ca", opv.ca)
    if (ctx.grain.value > 0f) out += EditorOp("grain").p("intensity", ctx.grain.value).p("uSize", 5f)
    val tl = ctx.tilt.value
    if (tl.on) out += EditorOp("tilt").p("focusY", tl.focusY).p("width", tl.width).p("feather", tl.feather)
    LOOKS[ctx.lookIdx.value].second.forEach { (id, vv) -> out += fxOp(id, vv * ctx.lookStr.value) }
    if (ctx.fxIdx.value >= 0) out += fxOp(FX[ctx.fxIdx.value].id, ctx.fxVal.value)
    ctx.maskList.forEachIndexed { mi, me ->
        if (!me.visible) return@forEachIndexed
        val ebm = entryMaskBitmap(ctx, me, mi) ?: return@forEachIndexed
        out += EditorOp("mask", mutableMapOf(
            "brightness" to me.bright, "saturation" to me.sat, "warmth" to me.warm,
            "exposure" to me.expos, "contrast" to me.contr,
            "strength" to me.strength, "inverted" to if (me.inverted) 1f else 0f,
            "show" to 0f
        ), ebm)
    }
    ctx.markList.forEachIndexed { mi, me ->
        if (!me.visible || me.strokes.isEmpty()) return@forEachIndexed
        val mb = ctx.markBmp.value ?: return@forEachIndexed
        out += EditorOp("markup", mutableMapOf("mix" to ctx.markMix.value), mb)
    }
    if (incFrame && ctx.frameType.value != FrameType.NONE && ctx.overlay.value != null) {
        out += EditorOp("frame", mutableMapOf("mix" to ctx.frameMix.value), ctx.overlay.value)
    }
    return out
}


// Junk-frame guard: rejects rendered frames that are pure black OR drastically
// darker than the source across the whole frame while the source is a normal
// bright photo. Covers both "read back zeros" and "read back driver garbage"
// cases so neither black nor colorful artifacts can ever be published.
private fun isJunkBitmap(bm: Bitmap, src: Bitmap?): Boolean {
    if (bm.width <= 0 || bm.height <= 0) return true
    val gx = maxOf(1, bm.width / 8); val gy = maxOf(1, bm.height / 8)
    var dark = 0; var sumL = 0L
    for (y in 0 until 8) for (x in 0 until 8) {
        val px = bm.getPixel((x * gx).coerceAtMost(bm.width - 1), (y * gy).coerceAtMost(bm.height - 1))
        val l = (px shr 16 and 0xFF) * 3L + (px shr 8 and 0xFF) * 6L + (px and 0xFF)
        sumL += l
        if (l < 8) dark++
    }
    if (dark * 100 >= 64 * 80) return true // >=80% of the frame is pure black
    if (src != null && src.width > 0 && src.height > 0) {
        val sx = maxOf(1, src.width / 8); val sy = maxOf(1, src.height / 8)
        var srcL = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val px = src.getPixel((x * sx).coerceAtMost(src.width - 1), (y * sy).coerceAtMost(src.height - 1))
            srcL += (px shr 16 and 0xFF) * 3L + (px shr 8 and 0xFF) * 6L + (px and 0xFF)
        }
        // Output ~4x darker on average than a bright source = failed render/readback.
        if (srcL / 64 > 48 * 255 && sumL / 64 < 12 * 255) return true
    }
    return false
}

private fun pushCurvesOp(ctx: Ctx) {
    requestPreview(ctx)
}

/** Renders the current chain off-screen (the exact path the export uses) and
 *  publishes the bitmap so the Compose Image preview never depends on the
 *  GL window surface. A render already in flight marks a pending flag and is
 *  re-run afterwards with the newest state — the final adjustment is never
 *  dropped. Bitmaps are recycled two generations back to bound memory. */
private fun requestPreview(ctx: Ctx) {
    if (ctx.previewBusy.value) { ctx.previewPending.value = true; return }
    val src = ctx.previewSrc.value ?: ctx.oriented.value ?: return
    // Dual-resolution: while the user is mid-gesture (drag / paint / scrub) the
    // preview renders from a small downscale for latency; on gesture end the
    // full-size preview is re-rendered (fastPreview reset by the gesture code).
    val o = if (ctx.fastPreview.value) {
        var fs = ctx.fastSrc.value
        if (fs == null || fs.width != src.width) {
            val sc = kotlin.math.min(1f, 1280f / kotlin.math.max(src.width, src.height))
            fs = if (sc < 1f) Bitmap.createScaledBitmap(src, (src.width * sc).toInt().coerceAtLeast(1), (src.height * sc).toInt().coerceAtLeast(1), true) else src
            ctx.fastSrc.value = fs
        }
        fs
    } else src
    ctx.previewBusy.value = true
    ctx.scope.launch {
        try {
            if (ctx.compare.value) {
                val old = ctx.previewBmp.value
                ctx.previewBmp.value = null
                old?.recycle()
            } else {
                val chain = chainOps(ctx, incFrame = true)
                val (bm, tag) = renderChainToBitmap(o, chain, ctx.curves.map { it.toList() }, ctx.depthBmp.value)
                ctx.renderDiag.value = tag
                // Guard: never publish a junk frame mid-edit (headless GL can
                // occasionally read back black or garbage on a lost/incomplete
                // context). Keep the prior preview so the editor never flashes
                // black or artifacts while an edit is ongoing.
                if (bm != null && !isJunkBitmap(bm, o)) {
                    val old = ctx.previewBmp.value
                    val oo = ctx.previewOld.value
                    if (oo != null && oo !== old) oo.recycle()
                    ctx.previewOld.value = old
                    ctx.previewBmp.value = bm
                } else {
                    bm?.recycle()
                }
            }
        } finally {
            ctx.previewBusy.value = false
            if (ctx.previewPending.value) { ctx.previewPending.value = false; requestPreview(ctx) }
        }
    }
}

private fun snapshot(ctx: Ctx) = EditSnap(
    tune = ctx.tune.value, vig = ctx.vig.value, tilt = ctx.tilt.value,
    grain = ctx.grain.value,
    c0 = ctx.curves[0].map { it.copyOf() }, c1 = ctx.curves[1].map { it.copyOf() },
    c2 = ctx.curves[2].map { it.copyOf() }, c3 = ctx.curves[3].map { it.copyOf() },
    spots = ctx.spots.map { it }, sel = ctx.selIdx.value,
    look = ctx.lookIdx.value, lookStr = ctx.lookStr.value, fx = ctx.fxIdx.value, fxVal = ctx.fxVal.value,
    heal = ctx.heal.value,
    frame = ctx.frameType.value, cfg = ctx.frameCfg.value, mix = ctx.frameMix.value, stamp = ctx.stamp.value,
    rotQ = ctx.rotQ.value, flipH = ctx.flipH.value, flipV = ctx.flipV.value,
    crop = RectF(ctx.crop.value), aspect = ctx.cropAspect.value,
    wb = ctx.wb.value, hsl = ctx.hsl.value, split = ctx.split.value,
    structure = ctx.structure.value, geom = ctx.geom.value, lens = ctx.lens.value,
    detail = ctx.detail.value, fxl = ctx.fxl.value, opts = ctx.opts.value,
    mask = ctx.mask.value, mstrokes = ctx.mstrokes.toList(),
    masks = ctx.maskList.map {
        it.copy(
            strokes = mutableStateListOf<MaskBrush>().also { l -> l.addAll(it.strokes) },
            erases = mutableStateListOf<MaskBrush>().also { l -> l.addAll(it.erases) }
        )
    },
    marks = ctx.markList.map {
        it.copy(strokes = mutableStateListOf<MarkStroke>().also { l -> l.addAll(it.strokes) })
    },
    filmId = ctx.filmId.value, markMix = ctx.markMix.value
)

private fun restore(ctx: Ctx, s: EditSnap) {
    ctx.tune.value = s.tune; ctx.vig.value = s.vig; ctx.tilt.value = s.tilt
    ctx.grain.value = s.grain
    ctx.lookIdx.value = s.look; ctx.lookStr.value = s.lookStr
    ctx.fxIdx.value = s.fx; ctx.fxVal.value = s.fxVal
    ctx.frameType.value = s.frame; ctx.frameCfg.value = s.cfg
    ctx.frameMix.value = s.mix; ctx.stamp.value = s.stamp
    ctx.rotQ.value = s.rotQ; ctx.flipH.value = s.flipH; ctx.flipV.value = s.flipV
    ctx.cropKeepUntil.value = android.os.SystemClock.elapsedRealtime() + 700
    ctx.crop.value = s.crop; ctx.cropAspect.value = s.aspect
    ctx.wb.value = s.wb; ctx.hsl.value = s.hsl; ctx.split.value = s.split
    ctx.structure.value = s.structure; ctx.geom.value = s.geom; ctx.lens.value = s.lens
    ctx.detail.value = s.detail; ctx.fxl.value = s.fxl; ctx.opts.value = s.opts
    ctx.mask.value = s.mask
    ctx.mstrokes.clear(); ctx.mstrokes.addAll(s.mstrokes)
    ctx.maskList.clear()
    s.masks.forEach { m ->
        ctx.maskList.add(
            m.copy(
                strokes = mutableStateListOf<MaskBrush>().also { l -> l.addAll(m.strokes) },
                erases = mutableStateListOf<MaskBrush>().also { l -> l.addAll(m.erases) }
            )
        )
    }
    ctx.maskIdx.value = ctx.maskIdx.value.coerceIn(0, (ctx.maskList.size - 1).coerceAtLeast(0))
    ctx.maskCache.clear()
    ctx.markList.clear()
    s.marks.forEach { m ->
        ctx.markList.add(
            m.copy(strokes = mutableStateListOf<MarkStroke>().also { l -> l.addAll(m.strokes) })
        )
    }
    ctx.markIdx.value = ctx.markIdx.value.coerceIn(0, (ctx.markList.size - 1).coerceAtLeast(0))
    ctx.filmId.value = s.filmId
    ctx.markMix.value = s.markMix
    rebuildMarkupBitmap(ctx)
    listOf(s.c0, s.c1, s.c2, s.c3).forEachIndexed { i, l ->
        ctx.curves[i].clear(); ctx.curves[i].addAll(l)
    }
    ctx.spots.clear(); ctx.spots.addAll(s.spots); ctx.selIdx.value = s.sel
    ctx.heal.value = s.heal
    requestPreview(ctx)
}

private fun undoStep(ctx: Ctx) {
    if (ctx.undo.isEmpty()) return
    ctx.redo.add(0, snapshot(ctx))
    restore(ctx, ctx.undo.removeAt(ctx.undo.size - 1))
}

private fun redoStep(ctx: Ctx) {
    if (ctx.redo.isEmpty()) return
    ctx.undo.add(snapshot(ctx))
    restore(ctx, ctx.redo.removeAt(ctx.redo.size - 1))
}

private fun commit(ctx: Ctx) {
    ctx.undo.add(snapshot(ctx))
    if (ctx.undo.size > 20) ctx.undo.removeAt(0)
    ctx.redo.clear()
}

// ── export pipeline ──────────────────────────────────────────────────────────
// Decode at a bounded size AND honour EXIF rotation: BitmapFactory ignores
// orientation, so without this every portrait photo opens sideways in the
// editor/save on every device.
internal fun decodeForUri(context: Context, uri: Uri, max: Int): Bitmap? =
    runCatching {
        val r = context.contentResolver
        fun dec(o: BitmapFactory.Options): Bitmap? = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        dec(b)
        var s = 1
        while (b.outWidth / (s * 2) >= max || b.outHeight / (s * 2) >= max) s *= 2
        val bm = dec(BitmapFactory.Options().apply { inSampleSize = s }) ?: return@runCatching null
        val rot = r.openInputStream(uri)?.use {
            when (androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        if (rot == 0) bm
        else {
            val m = Matrix(); m.postRotate(rot.toFloat())
            val r2 = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
            if (r2 !== bm) bm.recycle()
            r2
        }
    }.getOrNull()

/** Auto focus plane: the nearest object (largest depth value) — what the live
 *  camera's depth engine does by default so portraits are sharp out of the box. */
private fun autoSubjectDepth(depth: Bitmap?): Float {
    if (depth == null) return 0.5f
    var best = 0f
    for (y in 0 until 32) for (x in 0 until 32) {
        val v = (depth.getPixel((x * depth.width) / 32, (y * depth.height) / 32) shr 16 and 0xFF) / 255f
        if (v > best) best = v
    }
    return best.coerceIn(0.05f, 0.95f)
}

/** Renders the chain on the GPU (headless GL editor — the export path),
 *  falling back to the CPU engine only if the GL context is unavailable.
 *  Returns (bitmap, engine tag) — the tag is shown on screen as a small
 *  diagnostic badge so render issues are visible without adb. */
private suspend fun renderChainToBitmap(
    src: Bitmap, ops: List<EditorOp>, curves: List<List<FloatArray>>, depth: Bitmap?
): Pair<Bitmap?, String> = withContext(Dispatchers.Default) {
    val gl = runCatching { OffscreenEditor.render(src, ops, curves, depth) }
    val glBmp = gl.getOrNull()
    if (gl.isSuccess && glBmp != null) return@withContext glBmp to "GL"
    android.util.Log.w("EAGLES_EDIT", "GPU render failed: ${gl.exceptionOrNull()?.message}")
    val cpu = runCatching { EdCpuEngine.render(src, ops, curves, depth) }
    val cpuBmp = cpu.getOrNull()
    if (cpu.isSuccess && cpuBmp != null) cpuBmp to "CPU"
    else {
        android.util.Log.w("EAGLES_EDIT", "CPU render failed: ${cpu.exceptionOrNull()?.message}")
        null to "FAIL"
    }
}

private suspend fun renderAndSave(context: Context, uri: Uri, ctx: Ctx, editMode: Boolean = false): Uri? = try {
    val full = decodeForUri(context, uri, EXPORT_MAX)
    if (full == null) return null
    val m = Matrix().apply {
        postRotate(90f * ctx.rotQ.value)
        if (ctx.flipH.value) postScale(-1f, 1f)
        if (ctx.flipV.value) postScale(1f, -1f)
    }
    val orientedFull = runCatching { Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true) }.getOrNull() ?: return null
    val chain = chainOps(ctx, incFrame = false).toMutableList()
    if (ctx.frameType.value != FrameType.NONE) {
        val ov = buildFrameOverlay(orientedFull.width, orientedFull.height, ctx.frameType.value, ctx.frameCfg.value, if (ctx.stamp.value) dateStampText() else "")
        chain.add(EditorOp("frame", mutableMapOf("mix" to ctx.frameMix.value), ov))
    }
    val (renderedRaw, _) = renderChainToBitmap(orientedFull, chain, ctx.curves.map { it.toList() }, ctx.depthBmp.value)
    val rendered = renderedRaw ?: return null
    // Never write a junk/black frame to the gallery: a failed GL readback must
    // not produce a black JPEG that later shows up as a black thumbnail.
    if (isJunkBitmap(rendered, orientedFull)) { rendered.recycle(); return null }
    val w = rendered.width.toFloat(); val h = rendered.height.toFloat()
    val sx = (ctx.crop.value.left * w).toInt().coerceIn(0, rendered.width - 1)
    val sy = (ctx.crop.value.top * h).toInt().coerceIn(0, rendered.height - 1)
    val sw = (ctx.crop.value.width() * w).toInt().coerceIn(1, rendered.width - sx)
    val sh = (ctx.crop.value.height() * h).toInt().coerceIn(1, rendered.height - sy)
    val final = Bitmap.createBitmap(rendered, sx, sy, sw, sh)
    val vals = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "${if (editMode) "EaglesEye_edit_" else "EaglesEye_"}${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/EaglesEye")
    }
    val u = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, vals) ?: return null
    context.contentResolver.openOutputStream(u)?.use { final.compress(Bitmap.CompressFormat.JPEG, 94, it) } ?: return null
    runCatching { saveEditState(context, u.toString(), uri.toString(), recipeDoc(ctx)) }
    u
} catch (e: Throwable) {
    null
}

// ── Non-destructive edit persistence ─────────────────────────────────────────
// Every exported copy remembers the ORIGINAL source photo and the full recipe
// behind it. Re-opening a saved edit decodes the root photo and replays the
// recipe, so the edit state stays editable instead of baking pixels forever.
private fun resolveEditState(context: Context, uri: Uri): Pair<Uri, String?>? = runCatching {
    val p = context.getSharedPreferences("edit_states", Context.MODE_PRIVATE)
    val map = JSONObject(p.getString("map", "{}") ?: "{}")
    var cur = uri; var doc: String? = null
    for (i in 0 until 8) {
        val e = map.optJSONObject(cur.toString()) ?: break
        if (doc == null) doc = e.optString("doc", "").ifEmpty { null }
        val s = e.optString("src", "")
        if (s.isEmpty()) break
        val next = Uri.parse(s)
        if (next == cur) break
        cur = next
    }
    if (cur == uri) null else cur to doc
}.getOrNull()

private fun saveEditState(context: Context, outUri: String, srcUri: String, doc: String) {
    runCatching {
        if (doc.isEmpty()) return
        val p = context.getSharedPreferences("edit_states", Context.MODE_PRIVATE)
        val map = JSONObject(p.getString("map", "{}") ?: "{}")
        map.put(outUri, JSONObject().apply { put("src", srcUri); put("doc", doc) })
        p.edit().putString("map", map.toString()).apply()
    }
}
// ── Frame overlay bitmap (mirrors FrameTextureGenerator.generate) ────────────
internal fun buildFrameOverlay(w: Int, h: Int, ft: FrameType, cfg: FrameConfig, stamp: String): Bitmap {
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bm)
    cv.drawColor(0x00000000.toInt(), PorterDuff.Mode.CLEAR)
    val win = FrameDraw.frameWindow(ft, cfg, w.toFloat(), h.toFloat())
    val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FrameDraw.paperColor(ft, cfg) }
    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paper)
    FrameDraw.paperTint(cv, w.toFloat(), h.toFloat(), cfg.overlayColor.toInt(), cfg.overlayAlpha)
    FrameDraw.paperGrain(cv, w.toFloat(), h.toFloat(), 0.25f + cfg.textureWear * 0.75f, ft.hashCode() + w * 31 + h)
    FrameDraw.photoShadow(cv, win, w.toFloat(), cfg.shadowDepth)
    FrameDraw.drawAccents(cv, w.toFloat(), h.toFloat(), ft, win, "  $stamp  ")
    val grad = Paint(Paint.ANTI_ALIAS_FLAG)
    grad.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
        intArrayOf(0x14FFFFFF.toInt(), 0x06000000.toInt()), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), grad)
    val baseR = when (ft) {
        FrameType.CLASSIC_POLAROID -> w * 0.016f
        FrameType.INSTAX -> w * 0.012f
        FrameType.THICK_MATTE -> w * 0.024f
        FrameType.MINIMAL_MAT -> w * 0.016f
        FrameType.CARTRIDGE_110 -> w * 0.006f
        else -> 0f
    }
    val r = if (cfg.cornerRadius > 0f) w * cfg.cornerRadius else baseR
    val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    cv.drawRoundRect(RectF(win[0], win[1], win[2], win[3]), r, r, clear)
    val ed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = w * 0.005f; color = android.graphics.Color.argb(42, 0, 0, 0)
    }
    val el = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = w * 0.0016f; color = android.graphics.Color.argb(22, 255, 255, 255)
    }
    val er = r + w * 0.004f
    cv.drawRoundRect(RectF(win[0], win[1], win[2], win[3]), er, er, ed)
    cv.drawRoundRect(RectF(win[0] + w * 0.002f, win[1] + w * 0.002f, win[2] - w * 0.002f, win[3] - w * 0.002f), er, er, el)
    if (stamp.isNotBlank()) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6E8CD98.toInt(); textSize = (win[3] - win[1]) * 0.028f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        val pad = w * 0.018f
        cv.drawText(stamp, win[2] - pad - tp.measureText(stamp), win[3] - pad, tp)
    }
    return bm
}

// ── Per-mask bitmaps (strokes + erases + gradient -> alpha channel) ──────────
private fun entryMaskBitmap(ctx: Ctx, e: MaskEntry, idx: Int): Bitmap? {
    val o = ctx.oriented.value ?: return null
    val cached = ctx.maskCache[idx]
    if (cached != null && cached.first == e.rev) return cached.second
    val b = buildMaskBitmap(ctx, o.width, o.height, e)
    ctx.maskCache[idx] = e.rev to b
    if (cached != null && cached.second != b) cached.second?.recycle()
    return b
}

private fun buildMaskBitmap(ctx: Ctx, w: Int, h: Int, e: MaskEntry): Bitmap? {
    // AI modes need the depth map (modes 1/2) or a segmentation bitmap (modes 3/4).
    // Degrade to null (mask skipped) when the required source is missing.
    val db = ctx.depthBmp.value
    if ((e.aiMode == 1 || e.aiMode == 2) && db == null) return null
    if ((e.aiMode == 3 || e.aiMode == 4) && e.aiBmp == null) return null
    val mvg = e.gx0 >= 0f && e.gy0 >= 0f && e.gx1 >= 0f && e.gy1 >= 0f
    val hasPaint = e.strokes.isNotEmpty() || e.erases.isNotEmpty()
    if (hasPaint && !mvg && e.aiMode == 0) return buildMaskPaint(w, h, e)
    if (!hasPaint && !mvg && e.aiMode == 0) return null
    val mx = 1024f
    val sc = kotlin.math.min(1f, mx / kotlin.math.max(w, h))
    val mw = kotlin.math.max(1, (w * sc).toInt())
    val mh = kotlin.math.max(1, (h * sc).toInt())
    val bm = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
    val px = IntArray(mw * mh)
    val fe = e.feather
    val fl = e.flow
    var i = 0
    for (y in 0 until mh) {
        val uy = (y + 0.5f) / mh
        for (x in 0 until mw) {
            val ux = (x + 0.5f) / mw
            var a = 0f
            // Depth-gated full fill: aiMode 1 = subject (near), 2 = sky (far + blue).
            if (e.aiMode != 0 && db != null) {
                val pxd = (ux * db.width).toInt().coerceIn(0, db.width - 1)
                val pyd = (uy * db.height).toInt().coerceIn(0, db.height - 1)
                val d = (db.getPixel(pxd, pyd) shr 16 and 0xFF) / 255f
                a = when (e.aiMode) {
                    1 -> (d - 0.42f) / 0.28f
                    else -> {
                        val top = 1f - uy * 0.4f
                        ((0.45f - d) / 0.18f) * top
                    }
                }
                a = a.coerceIn(0f, 1f)
                if (a > 0f && e.aiMode == 2) {
                    val c = db.getPixel(pxd, pyd)
                    val rr = c shr 16 and 0xFF; val gg = c shr 8 and 0xFF; val bb = c and 0xFF
                    if (bb > maxOf(rr, gg) && bb > 90) a = minOf(1f, a * 1.35f)
                }
            }
            if ((e.aiMode == 3 || e.aiMode == 4) && e.aiBmp != null) {
                val ab = e.aiBmp!!
                val pxa = (ux * ab.width).toInt().coerceIn(0, ab.width - 1)
                val pya = (uy * ab.height).toInt().coerceIn(0, ab.height - 1)
                val sa = (ab.getPixel(pxa, pya) ushr 24 and 0xFF) / 255f
                if (sa > a) a = sa
            }
            if (e.rangeMin >= 0f && e.rangeMax >= 0f && db != null) {
                val pxd = (ux * db.width).toInt().coerceIn(0, db.width - 1)
                val pyd = (uy * db.height).toInt().coerceIn(0, db.height - 1)
                val d = (db.getPixel(pxd, pyd) shr 16 and 0xFF) / 255f
                val wl = (d - (e.rangeMin - 0.06f)) / 0.06f
                val wh = ((e.rangeMax + 0.06f) - d) / 0.06f
                a *= wl.coerceIn(0f, 1f) * wh.coerceIn(0f, 1f)
            }
            if (mvg) {
                val dx = e.gx1 - e.gx0; val dy = e.gy1 - e.gy0
                val g = dx * dx + dy * dy
                if (g > 1e-6f) {
                    if (e.radial) {
                        // Radial gradient: center (gx0,gy0), radius = drag distance.
                        val r = kotlin.math.sqrt(g)
                        val d = kotlin.math.sqrt((ux - e.gx0) * (ux - e.gx0) + (uy - e.gy0) * (uy - e.gy0))
                        val fw = fe * 0.5f
                        val edge = r - r * fw
                        val a2 = if (d >= r) 0f else if (d <= edge) 1f else (r - d) / (r - edge)
                        if (a2 > a) a = a2
                    } else {
                        val t = ((ux - e.gx0) * dx + (uy - e.gy0) * dy) / g
                        val fw = fe * 0.35f
                        val a2 = when {
                            t <= 0f -> (1f + t / fw).coerceIn(0f, 1f)
                            t >= 1f -> (1f - (t - 1f) / fw).coerceIn(0f, 1f)
                            else -> 1f - t
                        }
                        if (a2 > a) a = a2
                    }
                }
            }
            for (st in e.strokes) {
                val rpx = st.r * w
                val e0 = rpx * (1f - fe)
                val dxp = ux * w - st.x * w
                val dyp = uy * h - st.y * h
                val d = kotlin.math.sqrt(dxp * dxp + dyp * dyp)
                val wgt = if (d >= rpx) 0f else if (d <= e0) 1f else (rpx - d) / (rpx - e0)
                if (wgt > 0f) a = a + wgt * fl * (1f - a)
            }
            for (st in e.erases) {
                val rpx = st.r * w
                val e0 = rpx * (1f - fe)
                val dxp = ux * w - st.x * w
                val dyp = uy * h - st.y * h
                val d = kotlin.math.sqrt(dxp * dxp + dyp * dyp)
                val wgt = if (d >= rpx) 0f else if (d <= e0) 1f else (rpx - d) / (rpx - e0)
                if (wgt > 0f) a = a * (1f - wgt)
            }
            val av = (a * 255f).toInt().coerceIn(0, 255)
            px[i++] = (av shl 24) or 0xFFFFFF
        }
    }
    bm.setPixels(px, 0, mw, 0, 0, mw, mh)
    return bm
}

// Strokes-only fast path (no depth/gradient): paint a scaled-down mask.
private fun buildMaskPaint(w: Int, h: Int, e: MaskEntry): Bitmap? {
    val mx = 1024f
    val sc = kotlin.math.min(1f, mx / kotlin.math.max(w, h))
    val mw = kotlin.math.max(1, (w * sc).toInt())
    val mh = kotlin.math.max(1, (h * sc).toInt())
    val bm = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
    val px = IntArray(mw * mh)
    val fe = e.feather
    val fl = e.flow
    var i = 0
    for (y in 0 until mh) {
        val uy = (y + 0.5f) / mh
        for (x in 0 until mw) {
            val ux = (x + 0.5f) / mw
            var a = 0f
            for (st in e.strokes) {
                val rpx = st.r * w
                val e0 = rpx * (1f - fe)
                val dxp = ux * w - st.x * w
                val dyp = uy * h - st.y * h
                val d = kotlin.math.sqrt(dxp * dxp + dyp * dyp)
                val wgt = if (d >= rpx) 0f else if (d <= e0) 1f else (rpx - d) / (rpx - e0)
                if (wgt > 0f) a = a + wgt * fl * (1f - a)
            }
            for (st in e.erases) {
                val rpx = st.r * w
                val e0 = rpx * (1f - fe)
                val dxp = ux * w - st.x * w
                val dyp = uy * h - st.y * h
                val d = kotlin.math.sqrt(dxp * dxp + dyp * dyp)
                val wgt = if (d >= rpx) 0f else if (d <= e0) 1f else (rpx - d) / (rpx - e0)
                if (wgt > 0f) a = a * (1f - wgt)
            }
            val av = (a * 255f).toInt().coerceIn(0, 255)
            px[i++] = (av shl 24) or 0xFFFFFF
        }
    }
    bm.setPixels(px, 0, mw, 0, 0, mw, mh)
    return bm
}

/** Renders all markup layers into one ink bitmap (transparent where empty),
 *  sized to the oriented photo (bounded at 2.5k for memory). Cache invalidation
 *  piggybacks on the same [rev] trick as masks via markBmp replacement. */
private fun rebuildMarkupBitmap(ctx: Ctx) {
    val o = ctx.oriented.value ?: return
    val any = ctx.markList.any { it.visible && it.strokes.isNotEmpty() }
    if (!any) {
        ctx.markBmp.value?.recycle(); ctx.markBmp.value = null
        return
    }
    val mx = 2560f
    val sc = kotlin.math.min(1f, mx / kotlin.math.max(o.width, o.height))
    val w = kotlin.math.max(1, (o.width * sc).toInt())
    val h = kotlin.math.max(1, (o.height * sc).toInt())
    val old = ctx.markBmp.value
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bm)
    for (me in ctx.markList) {
        if (!me.visible) continue
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        var prev: Pair<Float, Float>? = null
        for (st in me.strokes) {
            paint.color = st.color
            paint.strokeWidth = st.w * o.width * sc
            paint.xfermode = if (st.erase && prev != null) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
            val px = st.x * w; val py = st.y * h
            prev?.let { cv.drawLine(it.first, it.second, px, py, paint) }
            prev = px to py
        }
    }
    ctx.markBmp.value = bm
    if (old != null && old !== bm) old.recycle()
}

// ── Recipes: full-state JSON codec (clipboard round-trip) ────────────────────
private fun recipeDoc(ctx: Ctx): String = runCatching {
    val d = EditRecipe.newDoc("RECIPE")
    val t = ctx.tune.value
    val tune = JSONObject()
    EditRecipe.putF(tune, "b", t.b); EditRecipe.putF(tune, "c", t.c); EditRecipe.putF(tune, "s", t.s)
    EditRecipe.putF(tune, "warmth", t.warmth); EditRecipe.putF(tune, "highlights", t.highlights)
    EditRecipe.putF(tune, "shadows", t.shadows); EditRecipe.putF(tune, "amb", t.amb)
    EditRecipe.putF(tune, "exposure", t.exposure); EditRecipe.putF(tune, "whites", t.whites)
    EditRecipe.putF(tune, "blacks", t.blacks); EditRecipe.putF(tune, "vibrance", t.vibrance)
    d.put("tune", tune)
    val w = ctx.wb.value
    d.put("wb", JSONObject().apply { EditRecipe.putF(this, "temp", w.temp); EditRecipe.putF(this, "tint", w.tint) })
    val hs = ctx.hsl.value.zones
    d.put("hsl", JSONObject().apply {
        EditRecipe.putArr(this, "h", EditRecipe.arr(FloatArray(8) { if (it < hs.size) hs[it].hue else 0f }))
        EditRecipe.putArr(this, "s", EditRecipe.arr(FloatArray(8) { if (it < hs.size) hs[it].sat else 0f }))
        EditRecipe.putArr(this, "l", EditRecipe.arr(FloatArray(8) { if (it < hs.size) hs[it].lum else 0f }))
    })
    val sp = ctx.split.value
    d.put("split", JSONObject().apply {
        EditRecipe.putF(this, "shR", sp.shR); EditRecipe.putF(this, "shG", sp.shG); EditRecipe.putF(this, "shB", sp.shB)
        EditRecipe.putF(this, "midR", sp.midR); EditRecipe.putF(this, "midG", sp.midG); EditRecipe.putF(this, "midB", sp.midB)
        EditRecipe.putF(this, "hiR", sp.hiR); EditRecipe.putF(this, "hiG", sp.hiG); EditRecipe.putF(this, "hiB", sp.hiB)
        EditRecipe.putF(this, "balance", sp.bal); EditRecipe.putF(this, "strength", sp.str)
    })
    val dp = ctx.detail.value
    d.put("detail", JSONObject().apply {
        EditRecipe.putF(this, "amount", dp.amount); EditRecipe.putF(this, "radius", dp.radius)
        EditRecipe.putF(this, "detail", dp.detail); EditRecipe.putF(this, "masking", dp.masking)
        EditRecipe.putF(this, "lum", dp.lumNR); EditRecipe.putF(this, "color", dp.colorNR)
    })
    val fx = ctx.fxl.value
    d.put("fxl", JSONObject().apply {
        EditRecipe.putF(this, "text", fx.text); EditRecipe.putF(this, "clarity", fx.clarity); EditRecipe.putF(this, "dehaze", fx.dehaze)
    })
    val opv = ctx.opts.value
    d.put("optics", JSONObject().apply {
        EditRecipe.putF(this, "distortion", opv.distortion); EditRecipe.putF(this, "ca", opv.ca)
    })
    EditRecipe.putF(d, "grain", ctx.grain.value)
    EditRecipe.putF(d, "structure", ctx.structure.value)
    val v = ctx.vig.value
    d.put("vig", JSONObject().apply {
        EditRecipe.putB(this, "on", v.on); EditRecipe.putF(this, "amount", v.amount)
        EditRecipe.putF(this, "radius", v.radius); EditRecipe.putF(this, "softness", v.softness)
    })
    val tl = ctx.tilt.value
    d.put("tilt", JSONObject().apply {
        EditRecipe.putB(this, "on", tl.on); EditRecipe.putF(this, "focusY", tl.focusY)
        EditRecipe.putF(this, "width", tl.width); EditRecipe.putF(this, "feather", tl.feather)
    })
    val g = ctx.geom.value
    d.put("geom", JSONObject().apply {
        EditRecipe.putF(this, "angle", g.angle); EditRecipe.putF(this, "perspV", g.perspV); EditRecipe.putF(this, "perspH", g.perspH)
    })
    val l = ctx.lens.value
    d.put("lens", JSONObject().apply {
        EditRecipe.putB(this, "on", l.on); EditRecipe.putF(this, "focusY", l.focusY)
        EditRecipe.putF(this, "width", l.width); EditRecipe.putF(this, "feather", l.feather)
        EditRecipe.putF(this, "amount", l.amount); EditRecipe.putB(this, "useDepth", l.useDepth)
        EditRecipe.putF(this, "focusDepth", l.focusDepth); EditRecipe.putF(this, "range", l.range)
        EditRecipe.putF(this, "blades", l.blades); EditRecipe.putB(this, "autoFocus", l.autoFocus)
    })
    d.put("curves", JSONArray().apply {
        for (ch in 0 until 4) put(EditRecipe.pointsArr(ctx.curves[ch].map { it.copyOf() }))
    })
    d.put("select", JSONArray().apply {
        for (s in ctx.spots.take(6)) put(JSONArray().apply {
            put(s.x.toDouble()); put(s.y.toDouble()); put(s.r.toDouble()); put(s.b.toDouble()); put(s.s.toDouble()); put(s.t.toDouble())
        })
    })
    d.put("heal", ctx.heal.value)
    d.put("look", JSONObject().apply {
        EditRecipe.putF(this, "idx", ctx.lookIdx.value.toFloat())
        EditRecipe.putF(this, "str", ctx.lookStr.value)
    })
    d.put("fx", JSONObject().apply {
        EditRecipe.putF(this, "idx", ctx.fxIdx.value.toFloat())
        EditRecipe.putF(this, "val", ctx.fxVal.value)
    })
    d.put("film", ctx.filmId.value ?: JSONObject.NULL)
    EditRecipe.putF(d, "markMix", ctx.markMix.value)
    d.put("geo", JSONObject().apply {
        put("rotQ", ctx.rotQ.value)
        EditRecipe.putB(this, "flipH", ctx.flipH.value); EditRecipe.putB(this, "flipV", ctx.flipV.value)
        val c = ctx.crop.value
        EditRecipe.putF(this, "cropL", c.left); EditRecipe.putF(this, "cropT", c.top)
        EditRecipe.putF(this, "cropR", c.right); EditRecipe.putF(this, "cropB", c.bottom)
        EditRecipe.putF(this, "aspect", ctx.cropAspect.value)
    })
    d.put("frame", JSONObject().apply {
        put("type", ctx.frameType.value.name)
        EditRecipe.putF(this, "mix", ctx.frameMix.value); EditRecipe.putB(this, "stamp", ctx.stamp.value)
        val cfg = ctx.frameCfg.value
        put("cfg", JSONObject().apply {
            EditRecipe.putF(this, "p1", cfg.p1); EditRecipe.putF(this, "p2", cfg.p2)
            EditRecipe.putF(this, "p3", cfg.p3); EditRecipe.putF(this, "p4", cfg.p4)
            EditRecipe.putF(this, "borderWidth", cfg.borderWidth); EditRecipe.putF(this, "bottomSpace", cfg.bottomSpace)
            put("paperColor", cfg.paperColor)
            EditRecipe.putF(this, "cornerRadius", cfg.cornerRadius)
            EditRecipe.putF(this, "textureWear", cfg.textureWear); EditRecipe.putF(this, "shadowDepth", cfg.shadowDepth)
            put("overlayColor", cfg.overlayColor)
            EditRecipe.putF(this, "overlayAlpha", cfg.overlayAlpha)
            put("polarFrameCode", cfg.polarFrameCode)
        })
    })
    d.put("masks", JSONArray().apply {
        for (m in ctx.maskList) {
            put(JSONObject().apply {
                EditRecipe.putF(this, "mode", m.mode.toFloat()); EditRecipe.putF(this, "size", m.size)
                EditRecipe.putF(this, "feather", m.feather); EditRecipe.putF(this, "flow", m.flow)
                EditRecipe.putF(this, "bright", m.bright); EditRecipe.putF(this, "sat", m.sat)
                EditRecipe.putF(this, "warm", m.warm); EditRecipe.putF(this, "strength", m.strength)
                EditRecipe.putF(this, "expos", m.expos); EditRecipe.putF(this, "contr", m.contr)
                EditRecipe.putB(this, "inverted", m.inverted); EditRecipe.putB(this, "visible", m.visible)
                EditRecipe.putB(this, "show", m.show)
                EditRecipe.putF(this, "gx0", m.gx0); EditRecipe.putF(this, "gy0", m.gy0)
                EditRecipe.putF(this, "gx1", m.gx1); EditRecipe.putF(this, "gy1", m.gy1)
                EditRecipe.putB(this, "radial", m.radial); EditRecipe.putF(this, "aiMode", m.aiMode.toFloat())
                EditRecipe.putF(this, "rangeMin", m.rangeMin); EditRecipe.putF(this, "rangeMax", m.rangeMax)
                EditRecipe.putArr(this, "strokes", EditRecipe.brushesArr(m.strokes.map { floatArrayOf(it.x, it.y, it.r) }))
                EditRecipe.putArr(this, "erases", EditRecipe.brushesArr(m.erases.map { floatArrayOf(it.x, it.y, it.r) }))
            })
        }
    })
    d.put("marks", JSONArray().apply {
        for (m in ctx.markList) {
            put(JSONObject().apply {
                EditRecipe.putF(this, "color", m.color.toFloat()); EditRecipe.putF(this, "size", m.size)
                EditRecipe.putB(this, "visible", m.visible)
                EditRecipe.putArr(this, "strokes", EditRecipe.markStrokesArr(m.strokes.map {
                    floatArrayOf(it.x, it.y, it.w, it.color.toFloat(), if (it.erase) 1f else 0f)
                }))
            })
        }
    })
    d.toString()
}.getOrNull() ?: ""

private fun applyRecipe(ctx: Ctx, text: String): String {
    val d = EditRecipe.parse(text) ?: return "BAD RECIPE"
    d.optJSONObject("tune")?.let { tune ->
        val t = ctx.tune.value
        ctx.tune.value = t.copy(
            b = EditRecipe.f(tune, "b", t.b), c = EditRecipe.f(tune, "c", t.c), s = EditRecipe.f(tune, "s", t.s),
            warmth = EditRecipe.f(tune, "warmth", t.warmth), highlights = EditRecipe.f(tune, "highlights", t.highlights),
            shadows = EditRecipe.f(tune, "shadows", t.shadows), amb = EditRecipe.f(tune, "amb", t.amb),
            exposure = EditRecipe.f(tune, "exposure", t.exposure), whites = EditRecipe.f(tune, "whites", t.whites),
            blacks = EditRecipe.f(tune, "blacks", t.blacks), vibrance = EditRecipe.f(tune, "vibrance", t.vibrance)
        )
    }
    d.optJSONObject("wb")?.let { wb ->
        val w = ctx.wb.value
        ctx.wb.value = w.copy(temp = EditRecipe.f(wb, "temp", w.temp), tint = EditRecipe.f(wb, "tint", w.tint))
    }
    d.optJSONObject("hsl")?.let { hsl ->
        val h = EditRecipe.fa(hsl, "h"); val s = EditRecipe.fa(hsl, "s"); val l = EditRecipe.fa(hsl, "l")
        val zones = mutableListOf<HslZone>()
        for (i in 0 until 8) zones.add(HslZone(h[i], s[i], l[i]))
        ctx.hsl.value = HslMix(zones)
    }
    d.optJSONObject("split")?.let { sp ->
        val cur = ctx.split.value
        ctx.split.value = cur.copy(
            shR = EditRecipe.f(sp, "shR", cur.shR), shG = EditRecipe.f(sp, "shG", cur.shG), shB = EditRecipe.f(sp, "shB", cur.shB),
            midR = EditRecipe.f(sp, "midR", cur.midR), midG = EditRecipe.f(sp, "midG", cur.midG), midB = EditRecipe.f(sp, "midB", cur.midB),
            hiR = EditRecipe.f(sp, "hiR", cur.hiR), hiG = EditRecipe.f(sp, "hiG", cur.hiG), hiB = EditRecipe.f(sp, "hiB", cur.hiB),
            bal = EditRecipe.f(sp, "balance", cur.bal), str = EditRecipe.f(sp, "strength", cur.str)
        )
    }
    d.optJSONObject("detail")?.let { dp ->
        val cur = ctx.detail.value
        ctx.detail.value = cur.copy(
            amount = EditRecipe.f(dp, "amount", cur.amount), radius = EditRecipe.f(dp, "radius", cur.radius),
            detail = EditRecipe.f(dp, "detail", cur.detail), masking = EditRecipe.f(dp, "masking", cur.masking),
            lumNR = EditRecipe.f(dp, "lum", cur.lumNR), colorNR = EditRecipe.f(dp, "color", cur.colorNR)
        )
    }
    d.optJSONObject("fxl")?.let { fx ->
        val cur = ctx.fxl.value
        ctx.fxl.value = cur.copy(
            text = EditRecipe.f(fx, "text", cur.text), clarity = EditRecipe.f(fx, "clarity", cur.clarity),
            dehaze = EditRecipe.f(fx, "dehaze", cur.dehaze)
        )
    }
    d.optJSONObject("optics")?.let { opv ->
        val cur = ctx.opts.value
        ctx.opts.value = cur.copy(
            distortion = EditRecipe.f(opv, "distortion", cur.distortion), ca = EditRecipe.f(opv, "ca", cur.ca)
        )
    }
    ctx.grain.value = EditRecipe.f(d, "grain", ctx.grain.value)
    ctx.structure.value = EditRecipe.f(d, "structure", ctx.structure.value)
    ctx.markMix.value = EditRecipe.f(d, "markMix", ctx.markMix.value)
    d.optJSONObject("geo")?.let { g ->
        ctx.cropKeepUntil.value = android.os.SystemClock.elapsedRealtime() + 700
        ctx.rotQ.value = EditRecipe.i(g, "rotQ", ctx.rotQ.value)
        ctx.flipH.value = EditRecipe.b(g, "flipH", ctx.flipH.value)
        ctx.flipV.value = EditRecipe.b(g, "flipV", ctx.flipV.value)
        ctx.cropAspect.value = EditRecipe.f(g, "aspect", ctx.cropAspect.value)
        if (g.has("cropL")) ctx.crop.value = RectF(
            EditRecipe.f(g, "cropL", 0f), EditRecipe.f(g, "cropT", 0f),
            EditRecipe.f(g, "cropR", 1f), EditRecipe.f(g, "cropB", 1f)
        )
    }
    d.optJSONObject("vig")?.let { v ->
        val cur = ctx.vig.value
        ctx.vig.value = cur.copy(
            on = EditRecipe.b(v, "on", cur.on), amount = EditRecipe.f(v, "amount", cur.amount),
            radius = EditRecipe.f(v, "radius", cur.radius), softness = EditRecipe.f(v, "softness", cur.softness)
        )
    }
    d.optJSONObject("tilt")?.let { tl ->
        val cur = ctx.tilt.value
        ctx.tilt.value = cur.copy(
            on = EditRecipe.b(tl, "on", cur.on), focusY = EditRecipe.f(tl, "focusY", cur.focusY),
            width = EditRecipe.f(tl, "width", cur.width), feather = EditRecipe.f(tl, "feather", cur.feather)
        )
    }
    d.optJSONObject("geom")?.let { g ->
        val cur = ctx.geom.value
        ctx.geom.value = cur.copy(
            angle = EditRecipe.f(g, "angle", cur.angle), perspV = EditRecipe.f(g, "perspV", cur.perspV),
            perspH = EditRecipe.f(g, "perspH", cur.perspH)
        )
    }
    d.optJSONObject("lens")?.let { l ->
        val cur = ctx.lens.value
        ctx.lens.value = cur.copy(
            on = EditRecipe.b(l, "on", cur.on), focusY = EditRecipe.f(l, "focusY", cur.focusY),
            width = EditRecipe.f(l, "width", cur.width), feather = EditRecipe.f(l, "feather", cur.feather),
            amount = EditRecipe.f(l, "amount", cur.amount), useDepth = EditRecipe.b(l, "useDepth", cur.useDepth),
            focusDepth = EditRecipe.f(l, "focusDepth", cur.focusDepth), range = EditRecipe.f(l, "range", cur.range),
            blades = EditRecipe.f(l, "blades", cur.blades), autoFocus = EditRecipe.b(l, "autoFocus", cur.autoFocus)
        )
    }
    d.optJSONArray("curves")?.let { ca ->
        for (ch in 0 until minOf(4, ca.length())) {
            val arr = ca.optJSONArray(ch) ?: continue
            ctx.curves[ch].clear()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONArray(i) ?: continue
                ctx.curves[ch].add(floatArrayOf(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
            }
        }
    }
    d.optJSONArray("select")?.let { sa ->
        ctx.spots.clear()
        for (i in 0 until minOf(6, sa.length())) {
            val p = sa.optJSONArray(i) ?: continue
            ctx.spots.add(SelSpot(
                p.getDouble(0).toFloat(), p.getDouble(1).toFloat(),
                p.getDouble(2).toFloat(), p.getDouble(3).toFloat(),
                p.getDouble(4).toFloat(), p.getDouble(5).toFloat()
            ))
        }
    }
    ctx.heal.value = d.optBoolean("heal", ctx.heal.value)
    d.optJSONObject("look")?.let { lo ->
        ctx.lookIdx.value = EditRecipe.i(lo, "idx", ctx.lookIdx.value)
        ctx.lookStr.value = EditRecipe.f(lo, "str", ctx.lookStr.value)
    }
    d.optJSONObject("fx")?.let { fo ->
        ctx.fxIdx.value = EditRecipe.i(fo, "idx", ctx.fxIdx.value)
        ctx.fxVal.value = EditRecipe.f(fo, "val", ctx.fxVal.value)
    }
    d.opt("film")?.let { if (it is String) ctx.filmId.value = it }
    d.optJSONObject("frame")?.let { fr ->
        runCatching { ctx.frameType.value = FrameType.valueOf(fr.optString("type", FrameType.NONE.name)) }
        ctx.frameMix.value = EditRecipe.f(fr, "mix", ctx.frameMix.value)
        ctx.stamp.value = EditRecipe.b(fr, "stamp", ctx.stamp.value)
        fr.optJSONObject("cfg")?.let { cf ->
            val cur = ctx.frameCfg.value
            ctx.frameCfg.value = FrameConfig(
                p1 = EditRecipe.f(cf, "p1", cur.p1), p2 = EditRecipe.f(cf, "p2", cur.p2),
                p3 = EditRecipe.f(cf, "p3", cur.p3), p4 = EditRecipe.f(cf, "p4", cur.p4),
                borderWidth = EditRecipe.f(cf, "borderWidth", cur.borderWidth),
                bottomSpace = EditRecipe.f(cf, "bottomSpace", cur.bottomSpace),
                paperColor = cf.optLong("paperColor", cur.paperColor),
                cornerRadius = EditRecipe.f(cf, "cornerRadius", cur.cornerRadius),
                textureWear = EditRecipe.f(cf, "textureWear", cur.textureWear),
                shadowDepth = EditRecipe.f(cf, "shadowDepth", cur.shadowDepth),
                overlayColor = cf.optLong("overlayColor", cur.overlayColor),
                overlayAlpha = EditRecipe.f(cf, "overlayAlpha", cur.overlayAlpha),
                polarFrameCode = cf.optString("polarFrameCode", cur.polarFrameCode)
            )
        }
    }
    d.optJSONArray("masks")?.let { ma ->
        ctx.maskList.clear()
        for (i in 0 until ma.length()) {
            val m = ma.optJSONObject(i) ?: continue
            ctx.maskList.add(MaskEntry(
                name = "MASK ${i + 1}",
                mode = EditRecipe.i(m, "mode", 0), size = EditRecipe.f(m, "size", 0.09f),
                feather = EditRecipe.f(m, "feather", 0.25f), flow = EditRecipe.f(m, "flow", 0.8f),
                bright = EditRecipe.f(m, "bright", 0f), sat = EditRecipe.f(m, "sat", 0f),
                warm = EditRecipe.f(m, "warm", 0f), strength = EditRecipe.f(m, "strength", 1f),
                expos = EditRecipe.f(m, "expos", 0f), contr = EditRecipe.f(m, "contr", 0f),
                inverted = EditRecipe.b(m, "inverted", false), visible = EditRecipe.b(m, "visible", true),
                show = EditRecipe.b(m, "show", false),
                gx0 = EditRecipe.f(m, "gx0", -1f), gy0 = EditRecipe.f(m, "gy0", -1f),
                gx1 = EditRecipe.f(m, "gx1", -1f), gy1 = EditRecipe.f(m, "gy1", -1f),
                radial = EditRecipe.b(m, "radial", false), aiMode = EditRecipe.i(m, "aiMode", 0),
                rangeMin = EditRecipe.f(m, "rangeMin", -1f), rangeMax = EditRecipe.f(m, "rangeMax", -1f)
            ).also { e ->
                e.strokes.addAll(EditRecipe.brushes(m, "strokes").map { MaskBrush(it[0], it[1], it[2], false) })
                e.erases.addAll(EditRecipe.brushes(m, "erases").map { MaskBrush(it[0], it[1], it[2], true) })
            })
        }
        ctx.maskIdx.value = ctx.maskIdx.value.coerceIn(0, (ctx.maskList.size - 1).coerceAtLeast(0))
    }
    d.optJSONArray("marks")?.let { ma ->
        ctx.markList.clear()
        for (i in 0 until ma.length()) {
            val m = ma.optJSONObject(i) ?: continue
            ctx.markList.add(MarkEntry(
                name = "INK ${i + 1}",
                color = EditRecipe.i(m, "color", 0xFF1A1A1A.toInt()),
                size = EditRecipe.f(m, "size", 0.012f),
                visible = EditRecipe.b(m, "visible", true)
            ).also { e ->
                e.strokes.addAll(EditRecipe.markStrokes(m, "strokes").map {
                    MarkStroke(
                        it[0] / 1e6f, it[1] / 1e6f, it[2] / 1e6f,
                        it[3].toInt(), it[4] != 0L
                    )
                })
            })
        }
        ctx.markIdx.value = ctx.markIdx.value.coerceIn(0, (ctx.markList.size - 1).coerceAtLeast(0))
        rebuildMarkupBitmap(ctx)
    }
    ctx.maskCache.clear()
    commit(ctx)
    requestPreview(ctx)
    return "LOADED"
}

private fun resetAllEdits(ctx: Ctx) {
    ctx.tune.value = TuneParams(); ctx.wb.value = WbParams(); ctx.hsl.value = HslMix(); ctx.split.value = SplitParams()
    ctx.structure.value = 0f; ctx.geom.value = GeomParams(); ctx.lens.value = LensParams()
    ctx.vig.value = VigParams(); ctx.tilt.value = TiltParams(); ctx.grain.value = 0f
    ctx.detail.value = DetailParams(); ctx.fxl.value = FxLocalParams(); ctx.opts.value = OptParams()
    ctx.lookIdx.value = 0; ctx.lookStr.value = 1f; ctx.fxIdx.value = -1; ctx.fxVal.value = 0.5f
    ctx.filmId.value = null
    ctx.frameType.value = FrameType.NONE; ctx.frameMix.value = 1f; ctx.stamp.value = false
    ctx.rotQ.value = 0; ctx.flipH.value = false; ctx.flipV.value = false
    ctx.crop.value = RectF(0f, 0f, 1f, 1f)
    for (ch in 0 until 4) { ctx.curves[ch].clear(); ctx.curves[ch].add(floatArrayOf(0f, 0f)); ctx.curves[ch].add(floatArrayOf(255f, 255f)) }
    ctx.spots.clear(); ctx.selIdx.value = -1; ctx.heal.value = false
    ctx.maskList.clear(); ctx.maskIdx.value = 0; ctx.maskCache.clear()
    ctx.markList.clear(); ctx.markIdx.value = 0; ctx.markMix.value = 1f; rebuildMarkupBitmap(ctx)
    commit(ctx)
    requestPreview(ctx)
}

// ── Presets: one-tap full looks ──────────────────────────────────────────────
@Composable
private fun PresetsPanel(ctx: Ctx) {
    ToolShell(ctx, "PRESETS") {
        Text(
            "ONE-TAP LOOKS — TAP TO APPLY, THEN FINE-TUNE IN ANY SECTION. PRESETS ARE REAL EDIT STATE: THEY UNDO, SAVE, SHARE AND EXPORT LIKE HAND-TUNED WORK.",
            color = Color.White.copy(0.3f), fontSize = 9.sp, lineHeight = 13.sp, letterSpacing = 0.6.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { p ->
                val src = remember { MutableInteractionSource() }
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(0.07f))
                        .clickable(src, null) { applyPreset(ctx, p) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("❖", color = EagleGold, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(p.name, color = Color.White.copy(0.85f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("RESET ALL", false) { resetAllEdits(ctx) }
            EdChip("RECIPES →", false) { ctx.tool.value = Tool.RECIPES }
        }
    }
}

// ── Recipes ───────────────────────────────────────────────────────────────────
@Composable
private fun RecipesPanel(ctx: Ctx) {
    val context = LocalContext.current
    val cm = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    ToolShell(ctx, "RECIPES") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("COPY TO CLIPBOARD", false) {
                val doc = recipeDoc(ctx)
                cm.setPrimaryClip(ClipData.newPlainText("EaglesEye Recipe", doc))
                Toast.makeText(context, "Recipe copied (${doc.length} chars)", Toast.LENGTH_SHORT).show()
            }
            EdChip("LOAD FROM CLIPBOARD", false) {
                val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
                Toast.makeText(context, applyRecipe(ctx, clip), Toast.LENGTH_SHORT).show()
            }
            EdChip("RESET ALL", false) { resetAllEdits(ctx) }
            EdChip("SAVE", false) {
                val doc = recipeDoc(ctx)
                val n = ctx.recipes.size + 1
                ctx.recipes.add("RECIPE $n" to doc)
                if (ctx.recipes.size > 8) ctx.recipes.removeAt(0)
                Toast.makeText(context, "Saved RECIPE $n", Toast.LENGTH_SHORT).show()
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("SESSION RECIPES — TAP TO RE-APPLY", color = Color.White.copy(0.42f), fontSize = 9.sp, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ctx.recipes.forEachIndexed { i, (name, _) ->
                EdChip(name, false) { Toast.makeText(context, applyRecipe(ctx, ctx.recipes[i].second), Toast.LENGTH_SHORT).show() }
            }
            if (ctx.recipes.isEmpty()) Text("EMPTY — TAP SAVE OR COPY TO START", color = Color.White.copy(0.3f), fontSize = 9.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "RECIPES ARE PLAIN JSON — COPY IN THIS EDITOR, PASTE INTO ANY NOTES APP, SHARE TO ANY DEVICE. THE FULL EDIT STACK ROUND-TRIPS: LIGHT · COLOR · GRADING · CURVES · DETAIL · MASKING · INK · GEOMETRY · FILM.",
            color = Color.White.copy(0.3f), fontSize = 9.sp, lineHeight = 13.sp, letterSpacing = 0.6.sp
        )
    }
}

// ── UI bricks ─────────────────────────────────────────────────────────────────
@Composable
private fun TopBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.08f))
            .clickable(src, null) { onClick() }.padding(9.dp),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(17.dp)) }
}

@Composable
private fun TopBtn(text: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.08f))
            .clickable(src, null) { onClick() }.padding(horizontal = 11.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White.copy(0.9f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun EdChip(label: String, active: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) UiGoldGradient() else SolidColor(Color.White.copy(0.08f)))
            .clickable(src, null) { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (active) Color.Black else Color.White.copy(0.72f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EdSliderRow(label: String, value: Float, min: Float, max: Float, fmt: (Float) -> String = { "${(it * 100).toInt()}%" }, onChange: (Float) -> Unit = {}) {
    val neutral = min <= 0f && value == 0f
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White.copy(0.45f), fontSize = 9.5.sp, letterSpacing = 1.sp, modifier = Modifier.width(94.dp))
        Slider(
            value = value.coerceIn(min, max), onValueChange = onChange, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = EagleGold, activeTrackColor = EagleGold, inactiveTrackColor = Color.White.copy(0.12f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent
            )
        )
        Text(
            fmt(value),
            color = if (neutral) Color.White.copy(0.4f) else EagleGold,
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────
@Composable
private fun TopBar(ctx: Ctx) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TopBtn(Icons.Default.Close) { ctx.onClose() }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PHOTO STUDIO", color = Color.White.copy(0.88f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("EAGLES EDITOR · ${"%.0f".format((ctx.crop.value.width() * 100).coerceIn(0f, 999f))}%", color = Color.White.copy(0.35f), fontSize = 9.sp, letterSpacing = 1.2.sp)
        }
        TopBtn(Icons.Default.CompareArrows) { ctx.compare.value = !ctx.compare.value }
        TopBtn(Icons.Default.Fullscreen) { ctx.tool.value = Tool.NONE; ctx.fullscreen.value = !ctx.fullscreen.value }
        TopBtn(Icons.Default.Undo) { undoStep(ctx) }
        TopBtn(Icons.Default.Redo) { redoStep(ctx) }
        TopBtn("SHARE") { ctx.saveNow(true) }
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(UiGoldGradient())
                .clickable(remember { MutableInteractionSource() }, null) { ctx.saveNow(false) }
                .padding(horizontal = 13.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("SAVE", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        }
    }
}

// ── Tool rail ─────────────────────────────────────────────────────────────────
/** Bottom-most workflow bar: master-spec sections. Tapping a section opens its
 *  primary tool; the sub-tool row appears above the panel for the rest. */
@Composable
private fun SectionBar(ctx: Ctx) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)
            .uiGlass(RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (sec in Section.values()) {
            val on = ctx.section.value == sec
            val src = remember { MutableInteractionSource() }
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).background(
                    if (on) UiGoldGradient() else SolidColor(Color.Transparent)
                ).clickable(src, null) {
                    ctx.section.value = sec
                    ctx.tool.value = SECTION_TOOLS[sec]?.firstOrNull() ?: Tool.NONE
                }.padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(sec.icon, contentDescription = null, tint = if (on) Color.Black else Color.White.copy(0.8f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(1.dp))
                Text(sec.label, color = if (on) Color.Black else Color.White.copy(0.5f), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, maxLines = 1)
            }
        }
    }
}

/** Tool chips for the active section, shown above the panel. */
@Composable
private fun SubToolRow(ctx: Ctx) {
    val tools = SECTION_TOOLS[ctx.section.value] ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (t in tools) {
            val on = ctx.tool.value == t
            val src = remember { MutableInteractionSource() }
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) UiGoldGradient() else SolidColor(Color.White.copy(0.07f)))
                    .clickable(src, null) { ctx.tool.value = t }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(TOOL_ICON[t] ?: Icons.Default.Close, contentDescription = null, tint = if (on) Color.Black else Color.White.copy(0.75f), modifier = Modifier.size(16.dp))
                Text(TOOL_LABEL[t] ?: "", color = if (on) Color.Black else Color.White.copy(0.65f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, maxLines = 1)
            }
        }
        val src = remember { MutableInteractionSource() }
        Row(
            Modifier.clip(RoundedCornerShape(50))
                .background(Color.White.copy(0.06f))
                .clickable(src, null) { ctx.tool.value = Tool.NONE }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("✕", color = Color.White.copy(0.6f), fontSize = 10.sp)
            Text("CLOSE", color = Color.White.copy(0.55f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Panels ────────────────────────────────────────────────────────────────────
@Composable
private fun ToolShell(ctx: Ctx, title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .uiGlass(UiRadiusCard)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Color.White.copy(0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.weight(1f))
            TopBtn("CLOSE") { ctx.tool.value = Tool.NONE }
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(UiGoldGradient())
                    .clickable(remember { MutableInteractionSource() }, null) { commit(ctx); ctx.tool.value = Tool.NONE }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("APPLY", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 310.dp)
                .verticalScroll(rememberScrollState())
        ) {
            content()
        }
    }
}

@Composable
private fun ToolPanel(ctx: Ctx) {
    when (ctx.tool.value) {
        Tool.TUNE -> TunePanel(ctx)
        Tool.WB -> WbPanel(ctx)
        Tool.CURVES -> CurvesPanel(ctx)
        Tool.HSL -> HslPanel(ctx)
        Tool.STRUCTURE -> StructurePanel(ctx)
        Tool.SELECT -> SelectPanel(ctx)
        Tool.MASK -> MaskPanel(ctx)
        Tool.VIGNETTE -> VigPanel(ctx)
        Tool.DETAIL -> SharpenPanel(ctx)
        Tool.GRAIN -> GrainPanel(ctx)
        Tool.TILT -> TiltPanel(ctx)
        Tool.BOKEH -> BokehPanel(ctx)
        Tool.GRADING -> SplitPanel(ctx)
        Tool.OPTICS -> OpticsPanel(ctx)
        Tool.STRAIGHTEN -> StraightenPanel(ctx)
        Tool.PERSPECTIVE -> PerspectivePanel(ctx)
        Tool.MARKUP -> MarkupPanel(ctx)
        Tool.RECIPES -> RecipesPanel(ctx)
        Tool.PRESETS -> PresetsPanel(ctx)
        Tool.LOOK -> LookPanel(ctx)
        Tool.EFFECT -> EffectPanel(ctx)
        Tool.FILM -> FilmPanel(ctx)
        Tool.FRAME -> FramePanel(ctx)
        Tool.CROP -> CropPanel(ctx)
        Tool.ROTATE -> RotatePanel(ctx)
        Tool.NONE -> {}
    }
}

// ── Auto analysis: shared grayscale sampling for the AUTO/LEVEL chips ────────
private class GrayImg(val w: Int, val h: Int, val d: FloatArray)

private fun grayImg(bm: Bitmap?, maxSide: Int): GrayImg? {
    if (bm == null) return null
    return runCatching {
        val sc = kotlin.math.min(1f, maxSide.toFloat() / kotlin.math.max(bm.width, bm.height))
        val w = kotlin.math.max(4, (bm.width * sc).toInt())
        val h = kotlin.math.max(4, (bm.height * sc).toInt())
        val small = if (sc < 1f) Bitmap.createScaledBitmap(bm, w, h, true) else bm
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bm) small.recycle()
        GrayImg(w, h, FloatArray(w * h) { i ->
            val c = px[i]
            ((c ushr 16 and 0xFF) * 299 + (c ushr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000f
        })
    }.getOrNull()
}

/** Histogram-driven tone AUTO: exposure from the mean, contrast from the p5–p95
 *  spread, highlight/shadow recovery from the percentiles themselves. */
private fun autoTune(bm: Bitmap?): TuneParams? {
    val im = grayImg(bm, 320) ?: return null
    val n = im.d.size
    var sum = 0.0
    val hist = IntArray(256)
    for (v in im.d) {
        sum += v
        hist[(v * 255f).toInt().coerceIn(0, 255)]++
    }
    val mean = (sum / n).toFloat()
    fun pct(p: Float): Float {
        var t = n * p
        for (i in 0 until 256) { t -= hist[i]; if (t <= 0f) return i / 255f }
        return 1f
    }
    val lo = pct(0.05f); val hi = pct(0.95f)
    val range = (hi - lo).coerceIn(0.02f, 1f)
    return TuneParams(
        exposure = ((0.5f - mean) * 1.1f).coerceIn(-0.45f, 0.45f),
        c = ((0.72f - range) * 0.55f).coerceIn(-0.2f, 0.3f),
        highlights = (-(hi - 0.92f) * 3.5f).coerceIn(-0.35f, 0f),
        shadows = ((0.08f - lo) * 3.5f).coerceIn(0f, 0.4f),
        whites = (-(hi - 0.99f) * 3f).coerceIn(-0.2f, 0f),
        blacks = ((0.03f - lo) * 3f).coerceIn(0f, 0.2f)
    )
}

/** Laplacian-variance AUTO for the detail/sharpen block: a crisper source gets
 *  a gentler amount (it is already sharp), a soft one gets pushed harder. */
private fun autoDetail(bm: Bitmap?): DetailParams? {
    val im = grayImg(bm, 400) ?: return null
    val w = im.w; val h = im.h; val d = im.d
    if (w < 3 || h < 3) return null
    var sum = 0.0; var sum2 = 0.0; var n = 0
    var y = 1
    while (y < h - 1) {
        var x = 1
        while (x < w - 1) {
            val lp = 4.0 * d[y * w + x] - d[y * w + x - 1] - d[y * w + x + 1] -
                d[(y - 1) * w + x] - d[(y + 1) * w + x]
            sum += lp; sum2 += lp * lp; n++
            x += 2
        }
        y += 2
    }
    if (n == 0) return null
    val mean = sum / n
    val v = (sum2 / n - mean * mean).coerceAtLeast(0.0)
    val t = ((kotlin.math.log10(v + 1e-7) + 5.5) / 4.5).coerceIn(0.0, 1.0)
    return DetailParams(
        amount = (0.2 + t * 0.55).toFloat(),
        radius = 0.35f, detail = 0.5f, masking = 0.25f
    )
}

/** Horizon tilt estimate: finds the angle in [-15°, 15°] whose surface-normal
 *  gradient energy is largest (horizon edges dominate). Returns the tilt in
 *  the y-down clockwise-positive convention used by the geom op. */
private fun estimateTiltAngle(bm: Bitmap?): Float {
    val im = grayImg(bm, 200) ?: return 0f
    val w = im.w; val h = im.h; val d = im.d
    if (w < 8 || h < 8) return 0f
    var bestA = 0f; var bestE = -1.0
    var a = -15f
    while (a <= 15.001f) {
        val rad = Math.toRadians(a.toDouble())
        val nx = -kotlin.math.sin(rad); val ny = kotlin.math.cos(rad)
        var e = 0.0
        var y = 1
        while (y < h - 1) {
            var x = 1
            val row = y * w
            while (x < w - 1) {
                val gx = d[row + x + 1] - d[row + x - 1]
                val gy = d[row + x + w] - d[row + x - w]
                val proj = nx * gx + ny * gy
                e += if (proj < 0) -proj else proj
                x += 3
            }
            y += 3
        }
        if (e > bestE) { bestE = e; bestA = a }
        a += 0.25f
    }
    return bestA
}

@Composable
private fun TunePanel(ctx: Ctx) {
    val t = ctx.tune.value
    ToolShell(ctx, "LIGHT") {
        EdSliderRow("EXPOSURE", t.exposure, -1f, 1f, fmt = { "${(it * 2).toInt()}EV" }) { ctx.tune.value = t.copy(exposure = it) }
        EdSliderRow("BRIGHTNESS", t.b, -0.5f, 0.5f) { ctx.tune.value = t.copy(b = it) }
        EdSliderRow("CONTRAST", t.c, -0.5f, 0.5f) { ctx.tune.value = t.copy(c = it) }
        EdSliderRow("HIGHLIGHTS", t.highlights, -0.5f, 0.5f) { ctx.tune.value = t.copy(highlights = it) }
        EdSliderRow("SHADOWS", t.shadows, -0.5f, 0.5f) { ctx.tune.value = t.copy(shadows = it) }
        EdSliderRow("WHITES", t.whites, -0.5f, 0.5f) { ctx.tune.value = t.copy(whites = it) }
        EdSliderRow("BLACKS", t.blacks, -0.5f, 0.5f) { ctx.tune.value = t.copy(blacks = it) }
        EdSliderRow("SATURATION", t.s, -0.5f, 0.5f) { ctx.tune.value = t.copy(s = it) }
        EdSliderRow("VIBRANCE", t.vibrance, -0.5f, 0.5f) { ctx.tune.value = t.copy(vibrance = it) }
        EdSliderRow("WARMTH", t.warmth, -0.5f, 0.5f) { ctx.tune.value = t.copy(warmth = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("AUTO", false) { autoTune(ctx.oriented.value)?.let { ctx.tune.value = it } }
            EdChip("RESET", false) { ctx.tune.value = TuneParams() }
        }
    }
}

@Composable
private fun VigPanel(ctx: Ctx) {
    val v = ctx.vig.value
    ToolShell(ctx, "VIGNETTE") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip(if (v.on) "ON" else "OFF", v.on) { ctx.vig.value = v.copy(on = !v.on) }
        }
        if (v.on) {
            EdSliderRow("AMOUNT", v.amount, 0f, 0.9f) { ctx.vig.value = v.copy(amount = it) }
            EdSliderRow("RADIUS", v.radius, 0.2f, 1.2f) { ctx.vig.value = v.copy(radius = it) }
            EdSliderRow("SOFTNESS", v.softness, 0f, 1f) { ctx.vig.value = v.copy(softness = it) }
        }
    }
}

@Composable
private fun SharpenPanel(ctx: Ctx) {
    val d = ctx.detail.value
    ToolShell(ctx, "DETAIL") {
        EdSliderRow("SHARPEN AMOUNT", d.amount, 0f, 1.5f, fmt = { "${(it * 100).toInt()}%" }) { ctx.detail.value = d.copy(amount = it) }
        EdSliderRow("RADIUS", d.radius, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.detail.value = d.copy(radius = it) }
        EdSliderRow("DETAIL", d.detail, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.detail.value = d.copy(detail = it) }
        EdSliderRow("MASKING", d.masking, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.detail.value = d.copy(masking = it) }
        Text("NOISE REDUCTION", color = Color.White.copy(0.42f), fontSize = 10.sp, letterSpacing = 1.sp)
        EdSliderRow("LUMINANCE", d.lumNR, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.detail.value = d.copy(lumNR = it) }
        EdSliderRow("COLOR", d.colorNR, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.detail.value = d.copy(colorNR = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("AUTO", false) { autoDetail(ctx.oriented.value)?.let { ctx.detail.value = it } }
            EdChip("RESET", false) { ctx.detail.value = DetailParams() }
        }
    }
}

@Composable
private fun GrainPanel(ctx: Ctx) {
    ToolShell(ctx, "GRAIN") {
        EdSliderRow("AMOUNT", ctx.grain.value, 0f, 1f) { ctx.grain.value = it }
    }
}

@Composable
private fun TiltPanel(ctx: Ctx) {
    val t = ctx.tilt.value
    ToolShell(ctx, "TILT SHIFT") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip(if (t.on) "ON" else "OFF", t.on) { ctx.tilt.value = t.copy(on = !t.on) }
        }
        if (t.on) {
            EdSliderRow("FOCUS", t.focusY, 0f, 1f) { ctx.tilt.value = t.copy(focusY = it) }
            EdSliderRow("WIDTH", t.width, 0.05f, 0.95f) { ctx.tilt.value = t.copy(width = it) }
            EdSliderRow("FEATHER", t.feather, 0f, 1f) { ctx.tilt.value = t.copy(feather = it) }
        }
    }
}

// ── Optics: lens distortion + chromatic aberration ───────────────────────────
@Composable
private fun OpticsPanel(ctx: Ctx) {
    val o = ctx.opts.value
    ToolShell(ctx, "OPTICS") {
        EdSliderRow("DISTORTION", o.distortion, -1f, 1f, fmt = { "${(it * 40).toInt()}" }) { ctx.opts.value = o.copy(distortion = it) }
        EdSliderRow("CA", o.ca, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.opts.value = o.copy(ca = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("BARREL", false) { ctx.opts.value = o.copy(distortion = -0.35f) }
            EdChip("PINCUSHION", false) { ctx.opts.value = o.copy(distortion = 0.35f) }
            EdChip("RESET", false) { ctx.opts.value = OptParams() }
        }
    }
}

// ── Straighten: angle wheel + level line overlay ─────────────────────────────
@Composable
private fun StraightenPanel(ctx: Ctx) {
    val g = ctx.geom.value
    ToolShell(ctx, "STRAIGHTEN") {
        EdSliderRow("ANGLE", g.angle, -45f, 45f, fmt = { "${"%.1f".format(it)}°" }) { ctx.geom.value = g.copy(angle = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("LEVEL", false) {
                val bm = ctx.oriented.value
                if (bm != null) {
                    // Positive geom angle rotates content clockwise (y-down), so
                    // the measured clockwise-positive horizon tilt is negated to
                    // cancel it. Clamped by the estimator to the ±45° wheel.
                    ctx.geom.value = g.copy(angle = -estimateTiltAngle(bm))
                }
            }
            EdChip("RESET", false) { ctx.geom.value = g.copy(angle = 0f) }
        }
    }
}

@Composable
private fun StraightenOverlay(ctx: Ctx) {
    val g = ctx.geom.value
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                ctx.fastPreview.value = true
                var event = awaitPointerEvent()
                while (event.changes.any { it.pressed }) {
                    val p = event.changes.first().position
                    val dx = p.x - size.width / 2f
                    val dy = p.y - size.height / 2f
                    var deg = kotlin.math.atan2(dy, dx) * 180f / kotlin.math.PI.toFloat() + 90f
                    deg = ((deg + 180f) % 360f) - 180f
                    if (kotlin.math.abs(deg) < 1f) deg = 0f
                    ctx.geom.value = g.copy(angle = deg.coerceIn(-45f, 45f))
                    event = awaitPointerEvent()
                }
                ctx.fastPreview.value = false
                requestPreview(ctx)
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Faint rule-of-thirds grid — composition guide while leveling
            val gc = Color.White.copy(0.2f)
            val gw = 1.dp.toPx()
            (1..2).forEach { i ->
                drawLine(gc, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), gw)
                drawLine(gc, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), gw)
            }
            val a = g.angle * kotlin.math.PI.toFloat() / 180f
            val c = Offset(size.width / 2f, size.height / 2f)
            val half = size.width
            val vx = kotlin.math.cos(a) * half
            val vy = kotlin.math.sin(a) * half
            val nx = -kotlin.math.sin(a) * half * 0.25f
            val ny = kotlin.math.cos(a) * half * 0.25f
            drawLine(EagleGold.copy(0.9f), c + Offset(-vx, -vy), c + Offset(vx, vy), 2.dp.toPx())
            // Level bubble: 0 = centered, halves the length of the reference line.
            drawLine(Color.White.copy(0.35f), c + Offset(-vx, -vy), c + Offset(vx, vy), 1.dp.toPx())
            drawCircle(Color.White.copy(0.25f), 22.dp.toPx(), c + Offset(0f, 0f))
            drawCircle(if (kotlin.math.abs(g.angle) < 1f) Color(0xFF6FD66F) else EagleGold, 3.dp.toPx(), c + Offset(nx, ny))
        }
    }
}

// ── Perspective: vertical/horizontal keystone + grid ─────────────────────────
@Composable
private fun PerspectivePanel(ctx: Ctx) {
    val g = ctx.geom.value
    ToolShell(ctx, "PERSPECTIVE") {
        EdSliderRow("VERTICAL", g.perspV, -0.6f, 0.6f, fmt = { "${(it * 100).toInt()}" }) { ctx.geom.value = g.copy(perspV = it) }
        EdSliderRow("HORIZONTAL", g.perspH, -0.6f, 0.6f, fmt = { "${(it * 100).toInt()}" }) { ctx.geom.value = g.copy(perspH = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("RESET", false) { ctx.geom.value = g.copy(perspV = 0f, perspH = 0f) }
        }
    }
}

@Composable
private fun PerspectiveOverlay(ctx: Ctx) {
    val g = ctx.geom.value
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position
                ctx.fastPreview.value = true
                val pv0 = g.perspV; val ph0 = g.perspH
                var event = awaitPointerEvent()
                while (event.changes.any { it.pressed }) {
                    val p = event.changes.first().position
                    val dy = (p.y - start.y) / (size.height * 0.5f)
                    val dx = (p.x - start.x) / (size.width * 0.5f)
                    ctx.geom.value = g.copy(
                        perspV = (dy * -1f + pv0).coerceIn(-0.6f, 0.6f),
                        perspH = (dx + ph0).coerceIn(-0.6f, 0.6f)
                    )
                    event = awaitPointerEvent()
                }
                ctx.fastPreview.value = false
                requestPreview(ctx)
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val gv = g.perspV; val gh = g.perspH
            // Perspective-transformed grid mirroring FRAG_EDIT_GEOM.
            for (i in -6..6) {
                val t = i / 12f + 0.5f
                var p0 = Offset(t * size.width, 0f)
                var p1 = Offset(t * size.width, size.height)
                val c0x = p0.x / size.width - 0.5f
                val ca = c0x * (1f + gv * -0.25f)
                p0 = Offset((ca + 0.5f) * size.width, p0.y)
                p1 = Offset((ca + 0.5f) * size.width, p1.y)
                drawLine(Color.White.copy(0.13f), p0, p1, 1.dp.toPx())
            }
            for (i in -6..6) {
                val t = i / 12f + 0.5f
                var p0 = Offset(0f, t * size.height)
                var p1 = Offset(size.width, t * size.height)
                val c0y = p0.y / size.height - 0.5f
                val ca = c0y * (1f + gh * 0.25f)
                p0 = Offset(p0.x, (ca + 0.5f) * size.height)
                p1 = Offset(p1.x, (ca + 0.5f) * size.height)
                drawLine(Color.White.copy(0.13f), p0, p1, 1.dp.toPx())
            }
            drawLine(EagleGold.copy(0.7f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.dp.toPx())
            drawLine(EagleGold.copy(0.7f), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
        }
    }
}

// ── Markup: pencil / ink ──────────────────────────────────────────────────────
private val INK_COLORS = listOf(
    0xFF1A1A1A.toInt(), 0xFFFFFFFF.toInt(), 0xFFC9A96E.toInt(),
    0xFFE2574C.toInt(), 0xFF4C8BE2.toInt(), 0xFF4CAF50.toInt(),
    0xFFE8C94D.toInt(), 0xFFE25CA8.toInt()
)

@Composable
private fun MarkupPanel(ctx: Ctx) {
    val idx = ctx.markIdx.value
    ToolShell(ctx, "MARKUP") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ctx.markList.forEachIndexed { i, me ->
                EdChip(if (me.visible) me.name else "${me.name}  •  HIDDEN", i == idx) { ctx.markIdx.value = i }
            }
            if (ctx.markList.size < 4) {
                EdChip("＋ NEW", false) {
                    ctx.markList.add(MarkEntry(name = "INK ${ctx.markList.size + 1}"))
                    ctx.markIdx.value = ctx.markList.size - 1
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val e = ctx.markList.getOrNull(idx)
        if (e == null) {
            Text("TAP ＋ NEW TO ADD AN INK LAYER", color = Color.White.copy(0.35f), fontSize = 11.sp, letterSpacing = 1.sp)
            return@ToolShell
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (c in INK_COLORS) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .background(
                            if (ctx.markColor.value == c) Color(c).copy(alpha = 0.35f)
                            else Color.White.copy(0.06f)
                        )
                        .clickable(remember { MutableInteractionSource() }, null) { ctx.markColor.value = c }
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(c)).then(
                        if (c == 0xFFFFFFFF.toInt()) Modifier.border(1.dp, Color.White.copy(0.3f), CircleShape) else Modifier
                    ))
                }
            }
            EdChip("ERASE", ctx.markErase.value) { ctx.markErase.value = !ctx.markErase.value }
            EdChip("UNDO", false) {
                val cur = ctx.markList.getOrNull(ctx.markIdx.value) ?: return@EdChip
                cur.strokes.removeAt(cur.strokes.size - 1)
                rebuildMarkupBitmap(ctx)
                requestPreview(ctx)
            }
            EdChip("CLEAR", false) {
                val cur = ctx.markList.getOrNull(ctx.markIdx.value) ?: return@EdChip
                cur.strokes.clear()
                rebuildMarkupBitmap(ctx)
                requestPreview(ctx)
            }
            EdChip("DELETE", false) {
                if (ctx.markList.isNotEmpty()) {
                    ctx.markList.removeAt(ctx.markIdx.value)
                    ctx.markIdx.value = ctx.markIdx.value.coerceIn(0, (ctx.markList.size - 1).coerceAtLeast(0))
                    rebuildMarkupBitmap(ctx)
                    requestPreview(ctx)
                }
            }
        }
        EdSliderRow("SIZE", ctx.markSize.value, 0.002f, 0.05f, fmt = { "${(it * 1000).toInt()}" }) { ctx.markSize.value = it }
        EdSliderRow("MIX", ctx.markMix.value, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.markMix.value = it }
    }
}

@Composable
private fun MarkupOverlay(ctx: Ctx) {
    val img = ctx.oriented.value ?: return
    var tick by remember { mutableStateOf(0) }
    Box(
        Modifier.fillMaxSize().pointerInput(img) {
            awaitEachGesture {
                val iw = img.width.toFloat(); val ih = img.height.toFloat()
                val sc = kotlin.math.min(size.width / iw, size.height / ih)
                val ox = (size.width - iw * sc) / 2f
                val oy = (size.height - ih * sc) / 2f
                val down = awaitFirstDown(requireUnconsumed = false)
                val idx = ctx.markIdx.value
                val cur = ctx.markList.getOrNull(idx)
                if (cur == null) { return@awaitEachGesture }
                ctx.fastPreview.value = true
                var lx = ((down.position.x - ox) / sc / iw).coerceIn(0f, 1f)
                var ly = ((down.position.y - oy) / sc / ih).coerceIn(0f, 1f)
                cur.strokes.add(
                    MarkStroke(lx, ly, ctx.markSize.value, ctx.markColor.value, ctx.markErase.value)
                )
                tick++
                var event = awaitPointerEvent()
                while (event.changes.any { it.pressed }) {
                    val p = event.changes.first().position
                    val nx = ((p.x - ox) / sc / iw).coerceIn(0f, 1f)
                    val ny = ((p.y - oy) / sc / ih).coerceIn(0f, 1f)
                    val dxm = (nx - lx) * iw
                    val dym = (ny - ly) * ih
                    if (dxm * dxm + dym * dym > (iw * 0.0012f) * (iw * 0.0012f)) {
                        cur.strokes.add(MarkStroke(nx, ny, ctx.markSize.value, ctx.markColor.value, ctx.markErase.value))
                        lx = nx; ly = ny
                        tick++
                    }
                    event = awaitPointerEvent()
                }
                rebuildMarkupBitmap(ctx)
                ctx.fastPreview.value = false
                requestPreview(ctx)
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val tm = tick
            val iw = img.width.toFloat(); val ih = img.height.toFloat()
            val sc = kotlin.math.min(size.width / iw, size.height / ih)
            val ox = (size.width - iw * sc) / 2f
            val oy = (size.height - ih * sc) / 2f
            for (me in ctx.markList) {
                if (!me.visible) continue
                var prev: Offset? = null
                for (st in me.strokes) {
                    val p = Offset(ox + st.x * iw * sc, oy + st.y * ih * sc)
                    prev?.let {
                        drawLine(Color(st.color).copy(alpha = if (st.erase) 0f else 1f), it, p, st.w * iw * sc, StrokeCap.Round)
                    }
                    prev = p
                }
            }
            ctx.markList.forEach { me ->
                if (me.visible && me.strokes.isNotEmpty()) {
                    val head = me.strokes.last()
                    val p = Offset(ox + head.x * iw * sc, oy + head.y * ih * sc)
                    drawCircle(Color(head.color).copy(alpha = 0.9f), head.w * iw * sc, p)
                }
            }
        }
    }
}

private fun wbFromPhoto(bm: Bitmap?): Pair<Float, Float> {
    if (bm == null) return 0f to 0f
    var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
    var y = 0
    while (y < bm.height) {
        var x = 0
        while (x < bm.width) {
            val c = bm.getPixel(x, y)
            val rr = (c shr 16 and 0xFF).toDouble()
            val gg = (c shr 8 and 0xFF).toDouble()
            val bb = (c and 0xFF).toDouble()
            if (0.299 * rr + 0.587 * gg + 0.114 * bb > 42.0) { r += rr; g += gg; b += bb; n++ }
            x += 4
        }
        y += 4
    }
    if (n == 0) return 0f to 0f
    r /= n; g /= n; b /= n
    val temp = ((b - r) / 140.0).coerceIn(-1.0, 1.0).toFloat()
    val tint = ((g - (r + b) / 2.0) / 70.0 * 1.4).coerceIn(-1.0, 1.0).toFloat()
    return temp to tint
}

@Composable
private fun WbPanel(ctx: Ctx) {
    val w = ctx.wb.value
    ToolShell(ctx, "WHITE BALANCE") {
        EdSliderRow("TEMP", w.temp, -1f, 1f, fmt = { if (it >= 0f) "W${(it * 100).toInt()}" else "C${(-it * 100).toInt()}" }) { ctx.wb.value = w.copy(temp = it) }
        EdSliderRow("TINT", w.tint, -1f, 1f, fmt = { if (it >= 0f) "M${(it * 100).toInt()}" else "G${(-it * 100).toInt()}" }) { ctx.wb.value = w.copy(tint = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("AUTO", false) {
                val e = wbFromPhoto(ctx.oriented.value)
                ctx.wb.value = w.copy(temp = e.first, tint = e.second)
            }
            EdChip("RESET", false) { ctx.wb.value = WbParams() }
        }
    }
}

@Composable
private fun HslPanel(ctx: Ctx) {
    val hsl = ctx.hsl.value
    val zone = remember { mutableStateOf(0) }
    ToolShell(ctx, "HSL MIXER") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val names = listOf("R", "O", "Y", "G", "C", "B", "P", "M")
            for (i in 0..7) EdChip(names[i], zone.value == i) { zone.value = i }
        }
        val z = hsl.zones[zone.value]
        EdSliderRow("HUE", z.hue, -1f, 1f, fmt = { "${(it * 27).toInt()}°" }) {
            val nz = hsl.zones.toMutableList(); nz[zone.value] = z.copy(hue = it)
            ctx.hsl.value = hsl.copy(zones = nz)
        }
        EdSliderRow("SAT", z.sat, -1f, 1f, fmt = { "${(it * 50).toInt()}%" }) {
            val nz = hsl.zones.toMutableList(); nz[zone.value] = z.copy(sat = it)
            ctx.hsl.value = hsl.copy(zones = nz)
        }
        EdSliderRow("LUM", z.lum, -1f, 1f, fmt = { "${(it * 50).toInt()}%" }) {
            val nz = hsl.zones.toMutableList(); nz[zone.value] = z.copy(lum = it)
            ctx.hsl.value = hsl.copy(zones = nz)
        }
    }
}

// ── Color grading wheels: hue/sat dot per zone → RGB wash (-1..1 for additive) ─
private fun washRgb(h: Float, s: Float): FloatArray {
    val c = s.coerceIn(0f, 1f)
    val x = c * (1f - kotlin.math.abs((h * 6f) % 2f - 1f))
    val rgb = when ((h % 1f + 1f) % 1f) {
        in 0f..0.0833f -> floatArrayOf(c, x, 0f); in 0.0833f..0.25f -> floatArrayOf(x, c, 0f)
        in 0.25f..0.4167f -> floatArrayOf(0f, c, x); in 0.4167f..0.5833f -> floatArrayOf(0f, x, c)
        in 0.5833f..0.75f -> floatArrayOf(x, 0f, c); else -> floatArrayOf(c, 0f, x)
    }
    return floatArrayOf(rgb[0] * 2f - 1f, rgb[1] * 2f - 1f, rgb[2] * 2f - 1f)
}

private fun rgbToWash(v: FloatArray): Pair<Float, Float> {
    val r = v[0] * 0.5f + 0.5f; val g = v[1] * 0.5f + 0.5f; val b = v[2] * 0.5f + 0.5f
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
    if (d < 1e-4f) return 0f to 0f
    val h = when (mx) {
        r -> ((g - b) / d % 6f + 6f) % 6f / 6f
        g -> ((b - r) / d + 2f) / 6f
        else -> ((r - g) / d + 4f) / 6f
    }
    return h to (d / mx).coerceIn(0f, 1f)
}

@Composable
private fun GradeWheel(wash: FloatArray, onSet: (FloatArray) -> Unit) {
    val (hue, sat) = rgbToWash(wash)
    Box(
        Modifier.size(84.dp).clip(CircleShape).background(Color.White.copy(0.07f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var event = awaitPointerEvent()
                    while (event.changes.any { it.pressed }) {
                        val p = event.changes.first().position
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val v = p - c
                        val d = v.getDistance()
                        val rad = size.width / 2f * 0.9f
                        val sat = (d / rad).coerceIn(0f, 1f)
                        val ang = kotlin.math.atan2(v.y, v.x)
                        val h = ((ang / (2f * kotlin.math.PI.toFloat())) % 1f + 1f) % 1f
                        onSet(washRgb(h, sat))
                        event = awaitPointerEvent()
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.width / 2f
            drawCircle(Color.White.copy(0.12f), r)
            val n = 12
            for (i in 0 until n) {
                val h0 = i / n.toFloat()
                drawArc(
                    color = androidx.compose.ui.graphics.Color.hsv(h0 * 360f, 0.85f, 0.9f),
                    startAngle = h0 * 360f - 90f, sweepAngle = 360f / n,
                    useCenter = true, topLeft = Offset(0f, 0f), size = Size(size.width, size.height)
                )
            }
            val dotPos = c + Offset(
                kotlin.math.cos(hue * 2f * kotlin.math.PI.toFloat()) * sat * r * 0.9f,
                kotlin.math.sin(hue * 2f * kotlin.math.PI.toFloat()) * sat * r * 0.9f
            )
            drawCircle(
                androidx.compose.ui.graphics.Color.hsv(hue * 360f, sat, 1f),
                r * 0.22f, dotPos
            )
            drawCircle(Color.White, r * 0.24f, dotPos, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun SplitPanel(ctx: Ctx) {
    val sp = ctx.split.value
    ToolShell(ctx, "COLOR GRADING") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SHADOW", color = Color.White.copy(0.45f), fontSize = 9.sp, letterSpacing = 1.sp)
                GradeWheel(floatArrayOf(sp.shR, sp.shG, sp.shB)) { w -> ctx.split.value = sp.copy(shR = w[0], shG = w[1], shB = w[2]) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MIDTONE", color = Color.White.copy(0.45f), fontSize = 9.sp, letterSpacing = 1.sp)
                GradeWheel(floatArrayOf(sp.midR, sp.midG, sp.midB)) { w -> ctx.split.value = sp.copy(midR = w[0], midG = w[1], midB = w[2]) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("HIGHLIGHT", color = Color.White.copy(0.45f), fontSize = 9.sp, letterSpacing = 1.sp)
                GradeWheel(floatArrayOf(sp.hiR, sp.hiG, sp.hiB)) { w -> ctx.split.value = sp.copy(hiR = w[0], hiG = w[1], hiB = w[2]) }
            }
        }
        Spacer(Modifier.height(8.dp))
        EdSliderRow("BALANCE", sp.bal, -1f, 1f, fmt = { "${(it * 50).toInt()}%" }) { ctx.split.value = sp.copy(bal = it) }
        EdSliderRow("STRENGTH", sp.str, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.split.value = sp.copy(str = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("RESET", false) { ctx.split.value = SplitParams() }
        }
    }
}

@Composable
private fun StructurePanel(ctx: Ctx) {
    ToolShell(ctx, "STRUCTURE") {
        EdSliderRow("CLARITY", ctx.structure.value, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.structure.value = it }
    }
}

@Composable
private fun BokehPanel(ctx: Ctx) {
    val l = ctx.lens.value
    ToolShell(ctx, "LENS") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip(if (l.on) "ON" else "OFF", l.on) { ctx.lens.value = l.copy(on = !l.on) }
            EdChip("BAND", !l.useDepth) { ctx.lens.value = l.copy(useDepth = false) }
            EdChip("DEPTH AI", l.useDepth) { ctx.lens.value = l.copy(useDepth = true) }
        }
        if (l.on) {
            if (l.useDepth) {
                val db = ctx.depthBmp.value
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EdChip("ROUND", l.blades == 0f) { ctx.lens.value = l.copy(blades = 0f) }
                    EdChip("TRI", l.blades == 3f) { ctx.lens.value = l.copy(blades = 3f) }
                    EdChip("PENTA", l.blades == 5f) { ctx.lens.value = l.copy(blades = 5f) }
                    EdChip("HEX", l.blades == 6f) { ctx.lens.value = l.copy(blades = 6f) }
                }
                EdSliderRow("AMOUNT", l.amount, 0f, 1f) { ctx.lens.value = l.copy(amount = it) }
                EdSliderRow("DEPTH RANGE", l.range, 0.05f, 0.8f, fmt = { "${(it * 100).toInt()}%" }) { ctx.lens.value = l.copy(range = it) }
                Text(
                    when {
                        ctx.depthLoading.value -> "MAPPING DEPTH…"
                        db == null && ctx.depthFail.value.isNotEmpty() -> "DEPTH FAIL: ${ctx.depthFail.value}"
                        db == null -> "DEPTH AI UNAVAILABLE"
                        else -> if (l.autoFocus) "AUTO FOCUS (NEAREST SUBJECT)  ·  TAP PHOTO TO SET FOCUS"
                                else "TAP THE PHOTO TO SET FOCUS  ·  FOCUS ${"%.0f".format(l.focusDepth * 100)}"
                    },
                    color = if (db != null && !ctx.depthLoading.value) EagleGold else Color.White.copy(0.35f),
                    fontSize = 9.sp, letterSpacing = 1.sp
                )
            } else {
                EdSliderRow("AMOUNT", l.amount, 0f, 1f) { ctx.lens.value = l.copy(amount = it) }
                EdSliderRow("FOCUS", l.focusY, 0f, 1f) { ctx.lens.value = l.copy(focusY = it) }
                EdSliderRow("WIDTH", l.width, 0.05f, 0.95f) { ctx.lens.value = l.copy(width = it) }
                EdSliderRow("FEATHER", l.feather, 0f, 1f) { ctx.lens.value = l.copy(feather = it) }
            }
        }
    }
}

@Composable
private fun LensOverlay(ctx: Ctx) {
    val img = ctx.oriented.value ?: return
    val db = ctx.depthBmp.value ?: return
    var tick by remember { mutableStateOf(0) }
    Box(
        Modifier.fillMaxSize().pointerInput(img, db) {
            awaitEachGesture {
                val iw = img.width.toFloat(); val ih = img.height.toFloat()
                val sc = kotlin.math.min(size.width / iw, size.height / ih)
                val ox = (size.width - iw * sc) / 2f
                val oy = (size.height - ih * sc) / 2f
                val down = awaitFirstDown(requireUnconsumed = false)
                val ux = ((down.position.x - ox) / sc / iw).coerceIn(0f, 1f)
                val uy = ((down.position.y - oy) / sc / ih).coerceIn(0f, 1f)
                val px = (ux * db.width).toInt().coerceIn(0, db.width - 1)
                val py = (uy * db.height).toInt().coerceIn(0, db.height - 1)
                val v = (db.getPixel(px, py) shr 16 and 0xFF) / 255f
                ctx.lens.value = ctx.lens.value.copy(focusDepth = v, autoFocus = false)
                tick++
                requestPreview(ctx)
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val tm = tick
            val iw = img.width.toFloat(); val ih = img.height.toFloat()
            val sc = kotlin.math.min(size.width / iw, size.height / ih)
            val ox = (size.width - iw * sc) / 2f
            val oy = (size.height - ih * sc) / 2f
            val focus = ctx.lens.value.focusDepth
            var found: Offset? = null
            for (y in 0 until db.height step 2) {
                for (x in 0 until db.width step 2) {
                    val d = (db.getPixel(x, y) shr 16 and 0xFF) / 255f
                    if (kotlin.math.abs(d - focus) < 0.03f) {
                        found = Offset(ox + x / db.width.toFloat() * iw * sc, oy + y / db.height.toFloat() * ih * sc)
                        break
                    }
                }
                if (found != null) break
            }
            if (found != null) {
                drawCircle(EagleGold.copy(0.25f), 34.dp.toPx(), found)
                drawCircle(EagleGold, 1.5.dp.toPx(), found, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun LookPanel(ctx: Ctx) {
    ToolShell(ctx, "LOOKS") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, entry) in LOOKS.withIndex()) {
                val name = entry.first
                EdChip(name, ctx.lookIdx.value == i) { ctx.lookIdx.value = i }
            }
        }
        if (ctx.lookIdx.value != 0) EdSliderRow("STRENGTH", ctx.lookStr.value, 0f, 1.5f) { ctx.lookStr.value = it }
    }
}

/** Film-stock picker (photoncam port) — grade + B&W + grain/leak defaults. */
@Composable
private fun FilmPanel(ctx: Ctx) {
    ToolShell(ctx, "FILM") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("NONE", ctx.filmId.value == null) { ctx.filmId.value = null }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for (brand in FilmBrand.entries) {
                val stocks = FilmCatalog.byBrand(brand)
                if (stocks.isEmpty()) continue
                Text(
                    brand.displayName.uppercase(),
                    color = AppAccent.color,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp)
                )
                for (s in stocks) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .then(if (ctx.filmId.value == s.id) Modifier.background(AppAccent.color.copy(0.14f)) else Modifier.background(Color.White.copy(0.05f)))
                            .clickable(remember { MutableInteractionSource() }, null) { ctx.filmId.value = if (ctx.filmId.value == s.id) null else s.id }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(s.accentColor))
                        Spacer(Modifier.width(10.dp))
                        Text(s.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Text("ISO ${s.iso}", color = Color.White.copy(0.4f), fontSize = 9.sp)
                        Spacer(Modifier.weight(1f))
                        Text(s.category.displayName.uppercase(), color = Color.White.copy(0.3f), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectPanel(ctx: Ctx) {
    val fxl = ctx.fxl.value
    ToolShell(ctx, "EFFECTS") {
        EdSliderRow("TEXTURE", fxl.text, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.fxl.value = fxl.copy(text = it) }
        EdSliderRow("CLARITY", fxl.clarity, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.fxl.value = fxl.copy(clarity = it) }
        EdSliderRow("DEHAZE", fxl.dehaze, 0f, 1f, fmt = { "${(it * 100).toInt()}%" }) { ctx.fxl.value = fxl.copy(dehaze = it) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, fx) in FX.withIndex()) {
                EdChip(fx.label, ctx.fxIdx.value == i) {
                    ctx.fxIdx.value = if (ctx.fxIdx.value == i) -1 else i
                    ctx.fxVal.value = fx.def
                }
            }
        }
        if (ctx.fxIdx.value >= 0) {
            val fx = FX[ctx.fxIdx.value]
            EdSliderRow(fx.pLabel, ctx.fxVal.value.coerceIn(fx.r0, fx.r1), fx.r0, fx.r1) { ctx.fxVal.value = it }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EdChip("REMOVE", false) { ctx.fxIdx.value = -1 }
            }
        }
    }
}

@Composable
private fun FramePanel(ctx: Ctx) {
    ToolShell(ctx, "FRAMES") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("NONE", ctx.frameType.value == FrameType.NONE) { ctx.frameType.value = FrameType.NONE }
            for ((ft, name) in FRAME_TYPES) {
                EdChip(name, ctx.frameType.value == ft) { ctx.frameType.value = ft }
            }
        }
        if (ctx.frameType.value != FrameType.NONE) {
            val c = ctx.frameCfg.value
            EdSliderRow("SIZE", c.borderWidth, 0.02f, 0.2f) { ctx.frameCfg.value = c.copy(borderWidth = it) }
            EdSliderRow("BOTTOM", c.bottomSpace, 0.06f, 0.32f) { ctx.frameCfg.value = c.copy(bottomSpace = it) }
            EdSliderRow("TONE", c.overlayAlpha, 0f, 0.35f) { ctx.frameCfg.value = c.copy(overlayAlpha = it) }
            EdSliderRow("WEAR", c.textureWear, 0f, 0.5f) { ctx.frameCfg.value = c.copy(textureWear = it) }
            EdSliderRow("SHADOW", c.shadowDepth, 0f, 0.5f) { ctx.frameCfg.value = c.copy(shadowDepth = it) }
            EdSliderRow("MIX", ctx.frameMix.value, 0f, 1f) { ctx.frameMix.value = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EdChip("STAMP", ctx.stamp.value) { ctx.stamp.value = !ctx.stamp.value }
            }
        }
    }
}

@Composable
private fun CropPanel(ctx: Ctx) {
    ToolShell(ctx, "CROP") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val aspects = listOf("FREE" to 0f, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
            for ((label, a) in aspects) {
                EdChip(label, kotlin.math.abs(ctx.cropAspect.value - a) < 0.001f) {
                    ctx.cropAspect.value = a
                    if (a > 0f) {
                        val r = ctx.crop.value
                        val im = ctx.oriented.value
                        val iw = im?.width?.toFloat() ?: 1f
                        val ih = im?.height?.toFloat() ?: 1f
                        // Normalized w/h that yields a TRUE pixel aspect of `a`
                        // (crop rects live in normalized image space).
                        val target = a * ih / iw
                        val cw = r.width(); val ch = r.height()
                        var nw = if (cw / ch > target) ch * target else cw
                        var nh = nw / target
                        if (nh > ch) { nh = ch; nw = nh * target }
                        val nx = r.left + (cw - nw) / 2f
                        val ny = r.top + (ch - nh) / 2f
                        ctx.crop.value = RectF(nx, ny, nx + nw, ny + nh)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("RESET", false) { ctx.crop.value = RectF(0f, 0f, 1f, 1f); ctx.cropAspect.value = 0f }
        }
        Spacer(Modifier.height(12.dp))
        Text("GEOMETRY", color = Color.White.copy(0.42f), fontSize = 10.sp, letterSpacing = 1.sp)
        val gm = ctx.geom.value
        EdSliderRow("STRAIGHTEN", gm.angle, -45f, 45f, fmt = { "${it.toInt()}°" }) { ctx.geom.value = gm.copy(angle = it) }
        EdSliderRow("PERSP V", gm.perspV, -0.3f, 0.3f, fmt = { "${(it * 100).toInt()}" }) { ctx.geom.value = gm.copy(perspV = it) }
        EdSliderRow("PERSP H", gm.perspH, -0.3f, 0.3f, fmt = { "${(it * 100).toInt()}" }) { ctx.geom.value = gm.copy(perspH = it) }
    }
}

@Composable
private fun RotatePanel(ctx: Ctx) {
    ToolShell(ctx, "ROTATE / FLIP") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip("⟲ 90°", false) { ctx.rotQ.value = (ctx.rotQ.value + 3) % 4; ctx.crop.value = RectF(0f, 0f, 1f, 1f) }
            EdChip("⟳ 90°", false) { ctx.rotQ.value = (ctx.rotQ.value + 1) % 4; ctx.crop.value = RectF(0f, 0f, 1f, 1f) }
            EdChip("FLIP H", false) { ctx.flipH.value = !ctx.flipH.value; ctx.crop.value = RectF(0f, 0f, 1f, 1f) }
            EdChip("FLIP V", false) { ctx.flipV.value = !ctx.flipV.value; ctx.crop.value = RectF(0f, 0f, 1f, 1f) }
        }
        Spacer(Modifier.height(6.dp))
        Text("The photo is also kept in the original file rotation in the export.", color = Color.White.copy(0.3f), fontSize = 9.sp)
    }
}


// ── Curve canvas (drag points on a grid; live LUT push) ─────────────────────
@Composable
private fun CurvesPanel(ctx: Ctx) {
    val ch = ctx.curveChan.value
    ToolShell(ctx, "CURVES") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((chan, name) in EditorCurves.CHANNELS) {
                EdChip(name, ch == chan) { ctx.curveChan.value = chan }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, pts) in EditorCurves.PRESETS) {
                EdChip(name, false) {
                    val list = ctx.curves[ch]
                    list.clear()
                    list.addAll(pts.map { it.copyOf() })
                    pushCurvesOp(ctx)
                }
            }
            EdChip("RESET", false) {
                val list = ctx.curves[ch]
                list.clear()
                list.add(floatArrayOf(0f, 0f)); list.add(floatArrayOf(255f, 255f))
                pushCurvesOp(ctx)
            }
        }
        Spacer(Modifier.height(6.dp))
        CurveCanvas(ctx)
    }
}

@Composable
private fun CurveCanvas(ctx: Ctx) {
    val ch = ctx.curveChan.value
    val pts = ctx.curves[ch]
    var tick by remember { mutableStateOf(0) }
    var sel by remember { mutableStateOf(-1) }

    Box(
        Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(0.05f))
            .pointerInput(ch, pts) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cx = (down.position.x / size.width * 255f).coerceIn(0f, 255f)
                    val cy = ((1f - down.position.y / size.height) * 255f).coerceIn(0f, 255f)
                    var idx = -1
                    var best = Float.MAX_VALUE
                    pts.forEachIndexed { i, p ->
                        val dx = p[0] - cx
                        val dy = (p[1] - cy) * 0.4f
                        val d = dx * dx + dy * dy
                        if (d < best) { best = d; idx = i }
                    }
                    val hit = best <= 576f
                    if (!hit) {
                        if (pts.size < 12) {
                            pts.add(floatArrayOf(cx, cy))
                            pts.sortBy { it[0] }
                            pushCurvesOp(ctx)
                            idx = pts.indexOfFirst { kotlin.math.abs(it[0] - cx) < 1f }
                        } else idx = -1
                    }
                    sel = idx
                    if (idx >= 0) {
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) {
                            val p = event.changes.first().position
                            val arr = pts[idx]
                            val nx = (p.x / size.width * 255f).coerceIn(0f, 255f)
                            val ny = ((1f - p.y / size.height) * 255f).coerceIn(0f, 255f)
                            val lo = if (idx > 0) pts[idx - 1][0] + 3f else 0f
                            val hi = if (idx < pts.size - 1) pts[idx + 1][0] - 3f else 255f
                            arr[0] = nx.coerceIn(lo, hi)
                            arr[1] = ny.coerceIn(0f, 255f)
                            pushCurvesOp(ctx)
                            tick++
                            event = awaitPointerEvent()
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val tickMark = tick
            repeat(5) { i ->
                val gx = size.width * i / 4f
                drawLine(Color.White.copy(0.07f), Offset(gx, 0f), Offset(gx, size.height), 1f)
                val gy = size.height * i / 4f
                drawLine(Color.White.copy(0.07f), Offset(0f, gy), Offset(size.width, gy), 1f)
            }
            val lut = EditorCurves.sample(pts)
            var prev: Offset? = null
            for (i in 0..255) {
                val v = lut[i]
                val p = Offset(i / 255f * size.width, (1f - v) * size.height)
                prev?.let { drawLine(Color.White.copy(0.92f), it, p, 2f) }
                prev = p
            }
            pts.forEachIndexed { i, p ->
                drawCircle(
                    if (i == sel) EagleGold else Color.White,
                    6f,
                    Offset(p[0] / 255f * size.width, (1f - p[1] / 255f) * size.height)
                )
            }
        }
    }
}

@Composable
private fun SelectPanel(ctx: Ctx) {
    ToolShell(ctx, if (ctx.heal.value) "HEAL" else "SPOT EDIT") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EdChip(if (ctx.heal.value) "HEAL MODE" else "ADJUST", ctx.heal.value) {
                ctx.heal.value = !ctx.heal.value
            }
            EdChip("ADD", false) {
                ctx.spots.add(SelSpot(0.5f, 0.5f))
                ctx.selIdx.value = ctx.spots.lastIndex
            }
            EdChip("REMOVE", false) {
                val i = ctx.selIdx.value
                if (i in ctx.spots.indices) ctx.spots.removeAt(i)
                ctx.selIdx.value = -1
            }
            EdChip("CLEAR", false) {
                ctx.spots.clear(); ctx.selIdx.value = -1
            }
        }
        val si = ctx.selIdx.value
        if (si in ctx.spots.indices) {
            val s = ctx.spots[si]
            EdSliderRow("SIZE", s.r, 0.02f, 0.3f) { ctx.spots[si] = s.copy(r = it) }
            if (ctx.heal.value) {
                EdSliderRow("SOFT EDGE", s.b, 0f, 1f) { ctx.spots[si] = s.copy(b = it) }
                Text("TAP ON THE PHOTO TO PLACE HEAL SPOTS — SMUDGES, DUST AND SMALL DEFECTS ARE INPAINTED FROM THE SURROUNDING PIXELS, GPU AND CPU.",
                    color = Color.White.copy(0.32f), fontSize = 9.sp, lineHeight = 13.sp, letterSpacing = 0.5.sp)
            } else {
                EdSliderRow("FEATHER", s.b, 0f, 0.5f) { ctx.spots[si] = s.copy(b = it) }
                EdSliderRow("BRIGHT", s.s, 0f, 2f) { ctx.spots[si] = s.copy(s = it) }
                EdSliderRow("TEMP", s.t, -1f, 1f) { ctx.spots[si] = s.copy(t = it) }
            }
        }
    }
}

@Composable
private fun MaskPanel(ctx: Ctx) {
    val idx = ctx.maskIdx.value
    ToolShell(ctx, "MASK") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ctx.maskList.forEachIndexed { i, me ->
                EdChip(if (me.visible) "${me.name}" else "${me.name}  •  HIDDEN", i == idx) { ctx.maskIdx.value = i }
            }
            if (ctx.maskList.size < 4) {
                EdChip("＋ NEW", false) {
                    ctx.maskList.add(MaskEntry(name = "MASK ${ctx.maskList.size + 1}"))
                    ctx.maskIdx.value = ctx.maskList.size - 1
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val e = ctx.maskList.getOrNull(idx)
        if (e == null) {
            Text("TAP ＋ NEW TO CREATE A MASK", color = Color.White.copy(0.35f), fontSize = 11.sp, letterSpacing = 1.sp)
            return@ToolShell
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EdChip("BRUSH", e.mode == 0) { ctx.maskList[idx] = e.copy(mode = 0) }
            EdChip("GRADIENT", e.mode == 1) { ctx.maskList[idx] = e.copy(mode = 1) }
            EdChip("ERASE", e.erase) { ctx.maskList[idx] = e.copy(erase = !e.erase) }
            EdChip("INVERT", e.inverted) { ctx.maskList[idx] = e.copy(inverted = !e.inverted) }
            EdChip("SUBJECT", e.aiMode == 3) { applySegMask(ctx, 3) }
            EdChip("HAIR", e.aiMode == 4) { applySegMask(ctx, 4) }
            EdChip("SHOW", e.show) { ctx.maskList[idx] = e.copy(show = !e.show) }
            EdChip("CLEAR", false) {
                e.strokes.clear(); e.erases.clear()
                ctx.maskList[idx] = e.copy(gx0 = -1f, gy0 = -1f, gx1 = -1f, gy1 = -1f, rev = e.rev + 1)
            }
            EdChip("DELETE", false) {
                ctx.maskList.removeAt(idx)
                ctx.maskIdx.value = ctx.maskIdx.value.coerceIn(0, (ctx.maskList.size - 1).coerceAtLeast(0))
            }
        }
        EdSliderRow("SIZE", e.size, 0.02f, 0.3f) { ctx.maskList[idx] = e.copy(size = it) }
        EdSliderRow("FEATHER", e.feather, 0f, 0.6f) { ctx.maskList[idx] = e.copy(feather = it, rev = e.rev + 1) }
        EdSliderRow("FLOW", e.flow, 0.05f, 1f) { ctx.maskList[idx] = e.copy(flow = it, rev = e.rev + 1) }
        EdSliderRow("BRIGHT", e.bright, -1f, 1f) { ctx.maskList[idx] = e.copy(bright = it) }
        EdSliderRow("EXPOSURE", e.expos, -1f, 1f, fmt = { "${(it * 100).toInt()}" }) { ctx.maskList[idx] = e.copy(expos = it) }
        EdSliderRow("CONTRAST", e.contr, -1f, 1f, fmt = { "${(it * 100).toInt()}" }) { ctx.maskList[idx] = e.copy(contr = it) }
        EdSliderRow("SAT", e.sat, -1f, 1f) { ctx.maskList[idx] = e.copy(sat = it) }
        EdSliderRow("WARM", e.warm, -1f, 1f) { ctx.maskList[idx] = e.copy(warm = it) }
        EdSliderRow("STRENGTH", e.strength, 0f, 1f) { ctx.maskList[idx] = e.copy(strength = it) }
        if (segBusy) Text("MAPPING SUBJECT / HAIR…", color = EagleGold, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

// AI subject / hair selection: run the bundled SINet (subject) or BiRefNet (hair)
// segmentation model on the current photo and store the soft probability mask on the
// active MaskEntry. Mirrors the depth-loader's center-crop -> square -> inverse-map so
// the mask aligns 1:1 with the photo (no horizontal squashing of the scene).
private val _segBusy = mutableStateOf(false)
val segBusy get() = _segBusy.value

private fun applySegMask(ctx: Ctx, mode: Int) {
    val o = ctx.oriented.value ?: return
    val idx = ctx.maskIdx.value
    if (ctx.maskList.getOrNull(idx) == null) return
    _segBusy.value = true
    ctx.scope.launch(Dispatchers.Default) {
        val result = runCatching {
            val eng = if (mode == 4) BirefNetEngine(ctx.context) else HairSegEngine(ctx.context)
            try {
                val n = eng.inputSize
                val pw = o.width; val ph = o.height
                val side = kotlin.math.min(pw, ph)
                val sxc = (pw - side) / 2; val syc = (ph - side) / 2
                val inner = Bitmap.createBitmap(o, sxc, syc, side, side)
                val square = Bitmap.createScaledBitmap(inner, n, n, true)
                val rgb = ByteArray(n * n * 3)
                val px = IntArray(n * n); square.getPixels(px, 0, n, 0, 0, n, n)
                if (square !== inner) square.recycle()
                var i = 0
                while (i < px.size) {
                    val c = px[i]
                    rgb[i * 3] = (c shr 16 and 0xFF).toByte()
                    rgb[i * 3 + 1] = (c shr 8 and 0xFF).toByte()
                    rgb[i * 3 + 2] = (c and 0xFF).toByte()
                    i++
                }
                val out = eng.run(java.nio.ByteBuffer.wrap(rgb))
                val mask = ByteArray(out.remaining()); out.get(mask)
                val ow = eng.outputWidth.coerceAtLeast(1); val oh = eng.outputHeight.coerceAtLeast(1)
                val aspect = pw.toFloat() / ph.toFloat()
                val tw = (ow * kotlin.math.max(aspect, 1f)).toInt().coerceAtLeast(1)
                val th = (oh * kotlin.math.max(1f / aspect, 1f)).toInt().coerceAtLeast(1)
                val tgt = IntArray(tw * th)
                var t = 0
                for (y in 0 until th) {
                    val uy = y / th.toFloat()
                    val my = if (aspect < 1f) 0.5f + (uy - 0.5f) / aspect else uy
                    val syi = (my * oh).toInt().coerceIn(0, oh - 1)
                    for (x in 0 until tw) {
                        val ux = x / tw.toFloat()
                        val mx = if (aspect >= 1f) 0.5f + (ux - 0.5f) * aspect else ux
                        val sxi = (mx * ow).toInt().coerceIn(0, ow - 1)
                        val p = mask[syi * ow + sxi].toInt() and 0xFF
                        tgt[t++] = 0xFF000000.toInt() or (p shl 24)
                    }
                }
                val bm = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                bm.setPixels(tgt, 0, tw, 0, 0, tw, th)
                bm
            } finally { runCatching { eng.close() } }
        }
        _segBusy.value = false
        result.onSuccess { bm ->
            ctx.scope.launch(Dispatchers.Main) {
                val cur = ctx.maskList.getOrNull(idx) ?: return@launch
                ctx.maskList[idx] = cur.copy(aiMode = mode, aiBmp = bm, rev = cur.rev + 1)
            }
        }
    }
}

@Composable
private fun MaskOverlay(ctx: Ctx) {
    val img = ctx.oriented.value ?: return
    Box(
        Modifier.fillMaxSize().pointerInput(img) {
            awaitEachGesture {
                val iw = img.width.toFloat(); val ih = img.height.toFloat()
                val sc = kotlin.math.min(size.width / iw, size.height / ih)
                val ox = (size.width - iw * sc) / 2f
                val oy = (size.height - ih * sc) / 2f
                val down = awaitFirstDown(requireUnconsumed = false)
                val idx = ctx.maskIdx.value
                val ee = ctx.maskList.getOrNull(idx) ?: return@awaitEachGesture
                if (ee.mode == 1) {
                    val sx = ((down.position.x - ox) / sc / iw).coerceIn(0f, 1f)
                    val sy = ((down.position.y - oy) / sc / ih).coerceIn(0f, 1f)
                    var event = awaitPointerEvent()
                    while (event.changes.any { it.pressed }) {
                        val p = event.changes.first().position
                        ctx.maskList[idx] = ee.copy(
                            gx0 = sx, gy0 = sy,
                            gx1 = ((p.x - ox) / sc / iw).coerceIn(0f, 1f),
                            gy1 = ((p.y - oy) / sc / ih).coerceIn(0f, 1f),
                            rev = ee.rev + 1
                        )
                        event = awaitPointerEvent()
                    }
                    return@awaitEachGesture
                }
                var lx = ((down.position.x - ox) / sc / iw).coerceIn(0f, 1f)
                var ly = ((down.position.y - oy) / sc / ih).coerceIn(0f, 1f)
                var event = awaitPointerEvent()
                while (event.changes.any { it.pressed }) {
                    val p = event.changes.first().position
                    val nx = ((p.x - ox) / sc / iw).coerceIn(0f, 1f)
                    val ny = ((p.y - oy) / sc / ih).coerceIn(0f, 1f)
                    val dxm = (nx - lx) * iw
                    val dym = (ny - ly) * ih
                    if (dxm * dxm + dym * dym > (iw * 0.0015f) * (iw * 0.0015f)) {
                        val cur = ctx.maskList[ctx.maskIdx.value]
                        if (cur.erase) cur.erases.add(MaskBrush(nx, ny, cur.size, true))
                        else cur.strokes.add(MaskBrush(nx, ny, cur.size, false))
                        ctx.maskList[ctx.maskIdx.value] = cur.copy(rev = cur.rev + 1)
                        lx = nx; ly = ny
                    }
                    event = awaitPointerEvent()
                }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val iw = img.width.toFloat(); val ih = img.height.toFloat()
            val sc = kotlin.math.min(size.width / iw, size.height / ih)
            val ox = (size.width - iw * sc) / 2f
            val oy = (size.height - ih * sc) / 2f
            val m = ctx.maskList.getOrNull(ctx.maskIdx.value) ?: return@Canvas
            val mb = ctx.maskCache[ctx.maskIdx.value]?.second
            if (mb != null && (m.strokes.isNotEmpty() || m.erases.isNotEmpty() || (m.gx0 >= 0f && m.gx1 >= 0f))) {
                drawImage(
                    mb.asImageBitmap(),
                    srcOffset = IntOffset.Zero, srcSize = IntSize(mb.width, mb.height),
                    dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((iw * sc).toInt(), (ih * sc).toInt()),
                    colorFilter = ColorFilter.tint(Color.Red.copy(alpha = if (m.show) 0.45f else 0.28f))
                )
            }
            val last = m.strokes.lastOrNull() ?: m.erases.lastOrNull()
            if (last != null) {
                val cx = ox + last.x * iw * sc
                val cy = oy + last.y * ih * sc
                drawCircle(Color.White, last.r * iw * sc, Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
            }
            if (m.mode == 1 && m.gx0 >= 0f && m.gx1 >= 0f) {
                val p0 = Offset(ox + m.gx0 * iw * sc, oy + m.gy0 * ih * sc)
                val p1 = Offset(ox + m.gx1 * iw * sc, oy + m.gy1 * ih * sc)
                drawLine(Color.White.copy(alpha = 0.9f), p0, p1, 2.dp.toPx())
                drawCircle(Color.White, 6.dp.toPx(), p0)
                drawCircle(EagleGold, 4.dp.toPx(), p1)
            }
        }
    }
}

@Composable
private fun CropOverlay(ctx: Ctx) {
    val img = ctx.oriented.value ?: return
    var tick by remember { mutableStateOf(0) }
    Box(
        Modifier.fillMaxSize().pointerInput(img) {
            awaitEachGesture {
                val iw = img.width.toFloat(); val ih = img.height.toFloat()
                val sc = kotlin.math.min(size.width / iw, size.height / ih)
                val ox = (size.width - iw * sc) / 2f
                val oy = (size.height - ih * sc) / 2f
                val down = awaitFirstDown(requireUnconsumed = false)
                val downX = ((down.position.x - ox) / sc / iw).coerceIn(0f, 1f)
                val downY = ((down.position.y - oy) / sc / ih).coerceIn(0f, 1f)
                val cur0 = ctx.crop.value
                val sX0 = cur0.left * iw * sc + ox; val sY0 = cur0.top * ih * sc + oy
                val sX1 = cur0.right * iw * sc + ox; val sY1 = cur0.bottom * ih * sc + oy
                val px = down.position.x; val py = down.position.y
                val tol = 30f
                var mode = -1
                if (kotlin.math.abs(px - sX0) < tol && kotlin.math.abs(py - sY0) < tol) mode = 0
                else if (kotlin.math.abs(px - sX1) < tol && kotlin.math.abs(py - sY0) < tol) mode = 1
                else if (kotlin.math.abs(px - sX0) < tol && kotlin.math.abs(py - sY1) < tol) mode = 2
                else if (kotlin.math.abs(px - sX1) < tol && kotlin.math.abs(py - sY1) < tol) mode = 3
                else if (px in sX0..sX1 && py in sY0..sY1) mode = 4
                if (mode < 0) return@awaitEachGesture
                var event = awaitPointerEvent()
                while (event.changes.any { it.pressed }) {
                    val p = event.changes.first().position
                    val nx = ((p.x - ox) / sc / iw).coerceIn(0f, 1f)
                    val ny = ((p.y - oy) / sc / ih).coerceIn(0f, 1f)
                    val cur = ctx.crop.value
                    var v = when (mode) {
                        0 -> RectF(nx, ny, cur.right, cur.bottom)
                        1 -> RectF(cur.left, ny, nx, cur.bottom)
                        2 -> RectF(nx, cur.top, cur.right, ny)
                        3 -> RectF(cur.left, cur.top, nx, ny)
                        4 -> {
                            val dw = nx - downX
                            val dt = ny - downY
                            val w = cur.width(); val h = cur.height()
                            val l = (cur0.left + dw).coerceIn(0f, 1f - w)
                            val t = (cur0.top + dt).coerceIn(0f, 1f - h)
                            RectF(l, t, l + w, t + h)
                        }
                        else -> cur
                    }
                    if (mode in 0..3) {
                        // Corner drag: the opposite corner stays anchored. Enforce
                        // the aspect lock and MIN_CROP so the rect can never end
                        // up invalid (both were previously only applied on chip tap).
                        val (ax, ay) = when (mode) {
                            0 -> cur.right to cur.bottom
                            1 -> cur.left to cur.bottom
                            2 -> cur.right to cur.top
                            else -> cur.left to cur.top
                        }
                        val aspect = ctx.cropAspect.value
                        var w = kotlin.math.abs(ax - nx)
                        var h = kotlin.math.abs(ay - ny)
                        if (aspect > 0f) {
                            val t = aspect * ih / iw
                            if (w / h.coerceAtLeast(1e-6f) > t) h = w / t else w = h * t
                        }
                        if (aspect > 0f) {
                            if (w < MIN_CROP || h < MIN_CROP) {
                                val s = kotlin.math.max(MIN_CROP / w.coerceAtLeast(1e-6f), MIN_CROP / h.coerceAtLeast(1e-6f))
                                w *= s; h *= s
                            }
                            if (w > 1f || h > 1f) {
                                val s = kotlin.math.min(1f / w, 1f / h)
                                w *= s; h *= s
                            }
                        } else {
                            w = w.coerceIn(MIN_CROP, 1f)
                            h = h.coerceIn(MIN_CROP, 1f)
                        }
                        val left = if (mode == 0 || mode == 2) ax - w else ax
                        val top = if (mode == 0 || mode == 1) ay - h else ay
                        v = RectF(left, top, left + w, top + h)
                    }
                    ctx.crop.value = v
                    tick++
                    event = awaitPointerEvent()
                }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val tickMark = tick
            val iw = img.width.toFloat(); val ih = img.height.toFloat()
            val sc = kotlin.math.min(size.width / iw, size.height / ih)
            val ox = (size.width - iw * sc) / 2f
            val oy = (size.height - ih * sc) / 2f
            val r = ctx.crop.value
            val x0 = ox + r.left * iw * sc; val y0 = oy + r.top * ih * sc
            val x1 = ox + r.right * iw * sc; val y1 = oy + r.bottom * ih * sc
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
                moveTo(x0, y0); lineTo(x1, y0); lineTo(x1, y1); lineTo(x0, y1); close()
                fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            }
            drawPath(path, Color.Black.copy(alpha = 0.55f))
            val w = x1 - x0; val h = y1 - y0
            for (i in 1..2) {
                drawLine(Color.White.copy(0.35f), Offset(x0 + w * i / 3f, y0), Offset(x0 + w * i / 3f, y1), 1f)
                drawLine(Color.White.copy(0.35f), Offset(x0, y0 + h * i / 3f), Offset(x1, y0 + h * i / 3f), 1f)
            }
            drawRect(androidx.compose.ui.graphics.Color.White, Offset(x0, y0), Size(w, h), style = Stroke(1.5f))
            val cr = 9.dp.toPx()
            listOf(Offset(x0, y0), Offset(x1, y0), Offset(x0, y1), Offset(x1, y1)).forEach {
                drawCircle(EagleGold, cr, it)
                drawCircle(androidx.compose.ui.graphics.Color.White, cr - 3.dp.toPx(), it)
            }
        }
    }
}

@Composable
private fun SelectOverlay(ctx: Ctx) {
    val img = ctx.oriented.value ?: return
    var tick by remember { mutableStateOf(0) }
    Box(
        Modifier.fillMaxSize().pointerInput(img) {
            awaitEachGesture {
                val iw = img.width.toFloat(); val ih = img.height.toFloat()
                val sc = kotlin.math.min(size.width / iw, size.height / ih)
                val ox = (size.width - iw * sc) / 2f
                val oy = (size.height - ih * sc) / 2f
                val down = awaitFirstDown(requireUnconsumed = false)
                val nx = ((down.position.x - ox) / sc / iw).coerceIn(0f, 1f)
                val ny = ((down.position.y - oy) / sc / ih).coerceIn(0f, 1f)
                var target = -1
                ctx.spots.forEachIndexed { i, s ->
                    val d = (s.x - nx) * (s.x - nx) + (s.y - ny) * (s.y - ny)
                    if (d < s.r * s.r) target = i
                }
                if (target < 0) {
                    if (ctx.spots.size < 8) {
                        ctx.spots.add(SelSpot(nx, ny))
                        ctx.selIdx.value = ctx.spots.lastIndex
                    }
                    return@awaitEachGesture
                }
                ctx.selIdx.value = target
                var event = awaitPointerEvent()
                while (event.changes.any { it.pressed }) {
                    val p = event.changes.first().position
                    val sx = ((p.x - ox) / sc / iw).coerceIn(0f, 1f)
                    val sy = ((p.y - oy) / sc / ih).coerceIn(0f, 1f)
                    val s = ctx.spots[target]
                    ctx.spots[target] = SelSpot(sx, sy, s.r, s.b, s.s, s.t)
                    tick++
                    event = awaitPointerEvent()
                }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val tickMark = tick
            val iw = img.width.toFloat(); val ih = img.height.toFloat()
            val sc = kotlin.math.min(size.width / iw, size.height / ih)
            val ox = (size.width - iw * sc) / 2f
            val oy = (size.height - ih * sc) / 2f
            ctx.spots.forEachIndexed { i, s ->
                val cx = ox + s.x * iw * sc
                val cy = oy + s.y * ih * sc
                val rpx = s.r * iw * sc
                drawCircle(EagleGold.copy(0.16f), rpx, Offset(cx, cy))
                drawCircle(if (i == ctx.selIdx.value) EagleGold else Color.White.copy(0.9f), 5f, Offset(cx, cy))
            }
        }
    }
}