package com.eagleseye.camera

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.BasicTextField

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*
import com.eagleseye.camera.presets.EffectConfig
import com.eagleseye.camera.DateStampStyle
import com.eagleseye.camera.dateStampColor
import com.eagleseye.camera.dateStampStyleName
import com.eagleseye.camera.DateFormatType

private val GOLD: Color get() = AppAccent.color
private val GOLD_HI: Color get() = AppAccent.hi
private val BG = Color(0xFF030304)
private val SURFACE = Color(0xFF0A0A0C)
private val SURF_UP = Color(0xFF18181D)
private val BORDER = Color(0xFF1C1C22)
private val BDR_DIM = Color(0xFF161620)
private val BDR_HI = Color(0xFF2D2D3A)
private val TEXT = Color(0xFFF0EDE8)
private val TX_SUB = Color(0xFF636060)
private val MN = FontFamily.Monospace
private val CIRCLE = RoundedCornerShape(50)



private val FRAMES_JSX = listOf(
    FrameDef(null, 0.8f, null, false, null),
    FrameDef("POLAROID", 1f, "79×79", false, null),
    FrameDef("MAT", 0.8f, "102×127", false, null),
    FrameDef("35MM", 1.5f, "36×24", false, null),
    FrameDef("MATTE", 0.8f, "102×127", false, null),
    FrameDef("FLOAT", 1.5f, "36×24", false, null),
    FrameDef("POST", 1.41f, "148×105", false, null),
    FrameDef("110", 1.31f, "17×13", true, null),
    FrameDef("MUSEUM", 1.25f, "127×102", true, null),
    FrameDef("GILDED", 1f, "79×79", true, null),
    FrameDef("VELVET", 0.8f, "102×127", true, null),
    FrameDef("CONTACT", 1.5f, "36×24", true, null),
    FrameDef("ARCHIVE", 1.25f, "110×88", true, null),
)

data class FrameDef(val name: String?, val ratio: Float, val mm: String?, val rare: Boolean, val customW: Int?)

private enum class FrameCat(val polarCat: PolarFrameCat?) {
    TYPES(null),
    CLASSIC(PolarFrameCat.CLASSIC),
    VINTAGE(PolarFrameCat.VINTAGE),
    POP(PolarFrameCat.POP),
    MINIMAL(PolarFrameCat.MINIMAL)
}

@Composable
fun GrainCanvas(tint: Color, amount: Float, sizeDp: Dp, dim: Boolean = false, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.toPx() }
    Canvas(modifier.size(sizeDp)) {
        // Base tint
        drawRect(tint, Offset.Zero, Size(sizePx, sizePx))
        // Grain noise
        val n = (sizePx * sizePx * 0.05f * amount).toInt()
        for (i in 0 until n) {
            val x = (Math.random() * sizePx).toFloat()
            val y = (Math.random() * sizePx).toFloat()
            drawCircle(Color.White.copy(alpha = (Math.random() * 0.35f).toFloat()), 0.5f, Offset(x, y))
        }
        // Vignette
        if (amount > 0.1f) {
            drawRect(
                brush = Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = (amount * 0.3f).coerceAtMost(0.85f))),
                    center = Offset(sizePx / 2, sizePx / 2),
                    radius = sizePx * 0.65f
                ),
                topLeft = Offset.Zero,
                size = Size(sizePx, sizePx)
            )
        }
    }
    if (dim) {
        Box(modifier.size(sizeDp).graphicsLayer { alpha = 0.5f })
    }
}

// Reports slider drag state up to the sheet so it can go transparent while adjusting.
val LocalSliderActive = compositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
fun AnalogEngineSheet(
    visible: Boolean,
    profiles: List<FilmProfile>,
    selectedProfile: FilmProfile,
    onSelect: (FilmProfile) -> Unit,
    onDismiss: () -> Unit,
    grainValue: Float, onGrainChange: (Float) -> Unit,
    leakValue: Float, onLeakChange: (Float) -> Unit,
    leakColorMode: LightLeakColor = LightLeakColor.ORANGE,
    onLeakColorChange: (LightLeakColor) -> Unit = {},
    leakAdaptive: Boolean = true,
    onLeakAdaptiveChange: (Boolean) -> Unit = {},
    frameType: FrameType, onFrameChange: (FrameType) -> Unit,
    frameMode: FrameMode = FrameMode.OVERLAY, onFrameModeChange: (FrameMode) -> Unit = {},
    frameConfig: FrameConfig = FrameConfig(),
    onFrameConfigChange: (FrameConfig) -> Unit = {},
    savedPresets: List<FramePreset?> = listOf(null,null,null,null,null),
    onSavePreset: (Int) -> Unit = {},
    onLoadPreset: (FramePreset) -> Unit = {},
    onClearPreset: (Int) -> Unit = {},
    showTimestamp: Boolean, onTimestampToggle: () -> Unit,
    dateStyle: DateStampStyle = DateStampStyle.ORANGE_FILM, onDateStyleChange: (DateStampStyle) -> Unit = {},
    dateFormat: DateFormatType = DateFormatType.DD_MM_YY, onDateFormatChange: (DateFormatType) -> Unit = {},
    dateShowTime: Boolean = true, onDateShowTimeChange: (Boolean) -> Unit = {},
    datePos: Int = 0, onDatePosChange: (Int) -> Unit = {},
    dateCustom: String = "", onDateCustomChange: (String) -> Unit = {},
    config: EffectConfig = EffectConfig(),
    onConfigChange: (EffectConfig) -> Unit = {},
    lookStrength: Float = 1f, onLookStrength: (Float) -> Unit = {},
    // Renders a real preview of a single effect pass (GL) for the effect tiles.
    // Absent on previews/scenes without a GL view → tiles fall back to art.
    effectsThumb: ((String, (Bitmap?) -> Unit) -> Unit)? = null,
    // When provided, APPLY bakes the current look (e.g. onto a still photo) before
    // the sheet animates away. Null (live camera) keeps the old behaviour: APPLY
    // just dismisses because the look is already applied to the viewfinder.
    onApply: (() -> Unit)? = null,
) {
    if (!visible) return

    // Settings → "UI animations" toggle feeds the sheet spring + tab fades.
    val ctx = LocalContext.current
    val uiAnimOn = remember { AppSettings.uiAnim(ctx) }
    val uiSpring: AnimationSpec<Float> = if (uiAnimOn) spring(dampingRatio = 0.85f, stiffness = 650f) else tween(durationMillis = 1)

    var activeTab by remember { mutableStateOf("FILM") }
    var selectedFrameName by remember { mutableStateOf<String?>(null) }
    var orientation by remember { mutableStateOf("portrait") }
    var sheetFrac by remember { mutableFloatStateOf(0f) }
    val sheetAnim by animateFloatAsState(sheetFrac, uiSpring, label = "sheetAnim")
    var closing by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var wasCollapsed by remember { mutableStateOf(false) }
    // While any slider is being dragged the sheet goes fully transparent so the
    // live camera preview shows through and you can judge the effect in real time.
    var sliderActive by remember { mutableStateOf(false) }
    val panelAlpha by animateFloatAsState(if (sliderActive) 0f else 1f, tween(140), label = "panelAlpha")
    val scope = rememberCoroutineScope()

    val collapsedFrac = 0.42f
    val expandedFrac = 0.92f

    val tabs = listOf("FILM", "FRAME", "EFFECTS")
    val tabIdx = tabs.indexOf(activeTab)

    val dismiss = { closing = true; sheetFrac = 0f; scope.launch { delay(UiMotion.MedMs.toLong()); onDismiss() } }

    // Map FilmProfile to tint for frame preview
    val activeFrame = FRAMES_JSX.find { it.name == selectedFrameName } ?: FRAMES_JSX[0]
    val tint = selectedProfile.previewTint.copy(alpha = selectedProfile.previewTintAlpha.coerceAtLeast(0.3f))

    val extrasOn = listOf(frameType != FrameType.NONE, showTimestamp, grainValue > 0.01f, leakValue > 0.01f).count { it }
    val activeCount = extrasOn + ALL_EFFECTS.count { it.on(config) }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    Box(Modifier.fillMaxSize()) {
        // Scrim
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f * panelAlpha))
                .clickable(remember { MutableInteractionSource() }, null) { dismiss() }
        )

// Sheet — draggable: pull up to expand, pull down to collapse/dismiss
    val expandedPx = with(density) { (configuration.screenHeightDp * expandedFrac).dp.toPx() }
    LaunchedEffect(Unit) { sheetFrac = 1f } // spring in from collapsed on open
    CompositionLocalProvider(LocalSliderActive provides { sliderActive = it }) {
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(collapsedFrac + (expandedFrac - collapsedFrac) * sheetAnim)
            .align(Alignment.BottomCenter)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { wasCollapsed = sheetFrac < 0.1f },
                    onVerticalDrag = { _, dy ->
                        val norm = (dy / expandedPx).coerceIn(-1f, 1f)
                        sheetFrac = (sheetFrac - norm).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        val v = sheetFrac
                        if (wasCollapsed && v < 0.25f) dismiss()
                        else sheetFrac = if (v > 0.5f) 1f else 0f
                    },
                    onDragCancel = { sheetFrac = if (sheetFrac > 0.5f) 1f else 0f }
                )
            }
            .then(
                if (closing) Modifier.graphicsLayer(alpha = 0f)
                else Modifier
            )
            .background(Color(0x0D0D11).copy(alpha = 0.94f * panelAlpha), RoundedCornerShape(topStart = Spacing.xl, topEnd = Spacing.xl))
    ) {
            // Grab handle (drag down to collapse/dismiss)
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = Spacing.sm, bottom = Spacing.xxs)
                    .width(if (sheetAnim > 0.02f) 28.dp else 32.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (sheetAnim > 0.02f) GOLD.copy(alpha = 0.5f) else BDR_HI)
            )

            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "ANALOG ENGINE",
                    fontFamily = MN,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = GOLD
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .background(GOLD.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
                            .border(1.dp, GOLD.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (activeCount > 0) "${activeCount} ON" else "STOCK",
                            fontFamily = MN, fontSize = 7.sp, fontWeight = FontWeight.ExtraBold,
                            color = if (activeCount > 0) GOLD else TX_SUB
                        )
                    }
                    Box(
                        Modifier
                            .size(48.dp)
                            .clickable(remember { MutableInteractionSource() }, null) { dismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CIRCLE)
                                .background(SURF_UP)
                                .border(1.dp, BORDER, CIRCLE),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, "Close", tint = TX_SUB, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Tab bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .border(1.dp, BDR_DIM)
            ) {
                Row(Modifier.fillMaxWidth()) {
                    tabs.forEach { t ->
                        Box(
                            Modifier
                                .weight(1f)
                                .clickable(remember { MutableInteractionSource() }, null) { activeTab = t }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                t,
                                fontFamily = MN,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp,
                                color = if (activeTab == t) GOLD else TX_SUB
                            )
                        }
                    }
                }
                // Tab indicator
                val screenWidth = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .offset { IntOffset((tabIdx * screenWidth / 3).toInt(), 0) }
                        .width(with(LocalDensity.current) { (screenWidth / 3).toDp() })
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(GOLD)
                )
            }

            // Content
            Box(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = Spacing.xs)
            ) {
                Crossfade(activeTab, animationSpec = if (uiAnimOn) tween(UiMotion.MedMs) else tween(1), label = "tabFade") {
                    when (it) {
                        "FILM" -> FilmStripTab(profiles, selectedProfile, onSelect,
                            leakValue, onLeakChange, leakColorMode, onLeakColorChange,
                            leakAdaptive, onLeakAdaptiveChange,
                            lookStrength, onLookStrength)

                        "FRAME" -> FrameTab(
                            frameType, onFrameChange,
                            frameMode, onFrameModeChange,
                            frameConfig, onFrameConfigChange,
                            showTimestamp, onTimestampToggle,
                            dateStyle, onDateStyleChange,
                            dateFormat, onDateFormatChange,
                            dateShowTime, onDateShowTimeChange,
                            datePos, onDatePosChange,
                            dateCustom, onDateCustomChange,
                            isEditing, { isEditing = it },
                            savedPresets, onSavePreset, onLoadPreset, onClearPreset
                        )

                        else -> EffectsTab(
                            config = config, onConfigChange = onConfigChange,
                            effectsThumb = effectsThumb
                        )
                    }
                }
            }

            // Bottom buttons
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, GOLD, RoundedCornerShape(14.dp))
                        .background(GOLD.copy(alpha = 0.06f))
                        .clickable(remember { MutableInteractionSource() }, null) { isEditing = !isEditing }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isEditing) "DONE" else "EDIT FRAME",
                        fontFamily = MN,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = GOLD
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(GOLD)
                        .clickable(remember { MutableInteractionSource() }, null) { onApply?.invoke(); dismiss() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "APPLY",
                        fontFamily = MN,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = Color(0xFF1a1408)
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))
        }
    }
    }
}

@Composable
private fun FilmStripTab(profiles: List<FilmProfile>, selectedProfile: FilmProfile, onSelect: (FilmProfile) -> Unit,
    leakValue: Float = 0f, onLeakChange: (Float) -> Unit = {},
    leakColorMode: LightLeakColor = LightLeakColor.ORANGE, onLeakColorChange: (LightLeakColor) -> Unit = {},
    leakAdaptive: Boolean = true, onLeakAdaptiveChange: (Boolean) -> Unit = {},
    lookStrength: Float = 1f, onLookStrength: (Float) -> Unit = {}) {
    var likedIds by remember { mutableStateOf(setOf<String>()) }

    Column {
        // Header row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "AVAILABLE NEGATIVES",
                fontFamily = MN,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = TX_SUB
            )
            Text(
                "TAP HEART TO PREFER",
                fontFamily = MN,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = GOLD.copy(alpha = 0.6f)
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // Cards horizontal scroll
        Row(Modifier.height(220.dp)) {
            // "LIKED LOOKS" vertical label
            Column(
                Modifier
                    .width(28.dp)
                    .fillMaxHeight()
                    .padding(top = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Star, "Featured", tint = GOLD, modifier = Modifier.size(12.dp))
                Spacer(Modifier.height(Spacing.xs))
                listOf("L","I","K","E","D").forEach { ch ->
                    Text(ch, fontFamily = MN, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = GOLD, letterSpacing = 0.sp)
                }
                Spacer(Modifier.height(Spacing.xxs))
                listOf("L","O","O","K","S").forEach { ch ->
                    Text(ch, fontFamily = MN, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = GOLD, letterSpacing = 0.sp)
                }
            }

            LazyRow(
                modifier = Modifier.fillMaxHeight(),
                contentPadding = PaddingValues(start = Spacing.xs, end = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(profiles) { profile ->
                    val sel = profile.id == selectedProfile.id
                    val liked = profile.id in likedIds
                    FilmCard(
                        profile = profile,
                        selected = sel,
                        liked = liked,
                        onSelect = { onSelect(profile) },
                        onLike = {
                            likedIds = if (liked) likedIds - profile.id else likedIds + profile.id
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        // Look strength — VSCO-style intensity for the whole selected film look
        ASlider("LOOK STRENGTH", lookStrength, 0.05f..1f, GOLD, onLookStrength)

        // Light Leak — always visible
        ASlider("LIGHT LEAK", leakValue, 0f..1f, Color(0xFFFF6600), onLeakChange)

        // Leak colour picker — visible when leak > 0
        AnimatedVisibility(leakValue > 0.01f,
            enter = fadeIn(tween(UiMotion.FastMs)) + expandVertically(tween(UiMotion.MedMs)),
            exit = fadeOut(tween(UiMotion.FastMs)) + shrinkVertically(tween(UiMotion.MedMs))) {
            Row(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xs).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("LEAK", color = Color.White.copy(0.38f), fontSize = 9.sp,
                    letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(40.dp))
                listOf(LightLeakColor.ORANGE, LightLeakColor.BLUE, LightLeakColor.RAINBOW, LightLeakColor.RANDOM).forEach { mode ->
                    val sel = leakColorMode == mode
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Color.White.copy(0.12f) else Color.Transparent)
                        .border(0.5.dp, if (sel) Color.White.copy(0.45f) else Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                        .clickable(remember { MutableInteractionSource() }, null) { onLeakColorChange(mode) }
                        .padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                        when (mode) {
                            LightLeakColor.RAINBOW -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { listOf(0xFFFF2200L, 0xFFFF8800L, 0xFFFFDD00L, 0xFF00CC44L, 0xFF0055FFL, 0xFF9900CCL).forEach { Box(Modifier.size(4.dp).background(Color(it), CircleShape)) } }
                            LightLeakColor.ORANGE -> Text("ORA", color = Color(0xFFFF6600), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                            LightLeakColor.BLUE -> Text("BLU", color = Color(0xFF0088FF), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                            LightLeakColor.RANDOM -> Text("RND", color = Color(0xFFCCCCCC), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                        }
                    }
                }
                // AUTO: leak seeks the brightest part of the frame and wanders
                Box(Modifier.width(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (leakAdaptive) Color(0xFFFF6600).copy(0.85f) else Color.White.copy(0.06f))
                    .border(0.5.dp, if (leakAdaptive) Color(0xFFFF6600) else Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                    .clickable(remember { MutableInteractionSource() }, null) { onLeakAdaptiveChange(!leakAdaptive) }
                    .padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                    Text("AUTO", color = if (leakAdaptive) Color.Black else Color.White.copy(0.5f),
                        fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                }
            }
        }
    }
}

@Composable
private fun FilmCard(
    profile: FilmProfile,
    selected: Boolean,
    liked: Boolean,
    onSelect: () -> Unit,
    onLike: () -> Unit
) {
    Column(
        Modifier
            .width(110.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(SURFACE)
            .border(1.dp, if (selected) GOLD else BDR_DIM, RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.shadow(6.dp, RoundedCornerShape(10.dp), false, GOLD.copy(alpha = 0.2f), Color.Transparent) else Modifier)
            .clickable(remember { MutableInteractionSource() }, null) { onSelect() }
            .padding(10.dp)
    ) {
        // Name + heart row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                profile.name,
                fontFamily = MN,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TEXT
            )
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(remember { MutableInteractionSource() }, null) { onLike() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(22.dp).clip(CIRCLE),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Like",
                        tint = if (liked) Color(0xFFFF4466) else TX_SUB,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Color swatch
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            profile.previewTint.copy(alpha = profile.previewTintAlpha.coerceAtLeast(0.6f)),
                            profile.previewTint.copy(alpha = (profile.previewTintAlpha * 0.5f).coerceAtLeast(0.3f))
                        )
                    )
                )
        )

        Spacer(Modifier.height(8.dp))

        // Description
        Text(
            profile.description.uppercase(),
            fontFamily = MN,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = TX_SUB
        )
    }
}



@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrameTab(
    frameType: FrameType, onFrameChange: (FrameType) -> Unit,
    frameMode: FrameMode, onFrameModeChange: (FrameMode) -> Unit,
    frameConfig: FrameConfig, onFrameConfigChange: (FrameConfig) -> Unit,
    showTimestamp: Boolean, onTimestampToggle: () -> Unit,
    dateStyle: DateStampStyle, onDateStyleChange: (DateStampStyle) -> Unit,
    dateFormat: DateFormatType, onDateFormatChange: (DateFormatType) -> Unit,
    dateShowTime: Boolean, onDateShowTimeChange: (Boolean) -> Unit,
    datePos: Int, onDatePosChange: (Int) -> Unit,
    dateCustom: String, onDateCustomChange: (String) -> Unit,
    isEditing: Boolean, onEditingChange: (Boolean) -> Unit,
    savedPresets: List<FramePreset?>, onSavePreset: (Int) -> Unit,
    onLoadPreset: (FramePreset) -> Unit, onClearPreset: (Int) -> Unit
) {
    var selectedCat by remember { mutableStateOf(FrameCat.TYPES) }
    val catFrames = selectedCat.polarCat?.let { polarFramesByCategory(it) } ?: emptyList()
    val selectedCode = frameConfig.polarFrameCode
    val hasSelection = selectedCode.isNotEmpty() || frameType != FrameType.NONE

    Column {
        // ── Header: title + selection status + NO FRAME ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("FRAMES", fontFamily = MN, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp, color = TX_SUB)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (hasSelection) (if (selectedCode.isNotEmpty()) "POLAROID" else frameDisplayName(frameType))
                    else "NONE",
                    fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    color = if (hasSelection) GOLD else Color.White.copy(0.25f))
                if (hasSelection) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(0.06f))
                        .clickable(remember { MutableInteractionSource() }, null) {
                            onFrameChange(FrameType.NONE)
                            onFrameConfigChange(frameConfig.copy(polarFrameCode = ""))
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                        Text("NO FRAME", fontFamily = MN, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp, color = Color.White.copy(0.5f))
                    }
                }
            }
        }

        // ── Frame mode selector ──
        if (hasSelection) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    FrameMode.OVERLAY to "OVERLAY",
                    FrameMode.EXTENDED to "EXTENDED",
                    FrameMode.EXTENDED_OVERLAY to "EXT+OVERLAY"
                ).forEach { (mode, label) ->
                    val sel = frameMode == mode
                    Box(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (sel) GOLD.copy(0.15f) else Color.White.copy(0.05f))
                        .border(0.5.dp, if (sel) GOLD else Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                        .clickable(remember { MutableInteractionSource() }, null) { onFrameModeChange(mode) }
                        .padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = if (sel) GOLD else Color.White.copy(0.5f),
                            fontSize = 8.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.4.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Category pills (TYPES capture frame styles) ──
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf(FrameCat.TYPES to "TYPES")) { (cat, label) ->
                val sel = selectedCat == cat
                Box(Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (sel) GOLD.copy(0.18f) else Color.White.copy(0.05f))
                    .border(0.5.dp, if (sel) GOLD else Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                    .clickable(remember { MutableInteractionSource() }, null) { selectedCat = cat }
                    .padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (sel) GOLD else Color.White.copy(0.5f),
                        fontSize = 9.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.5.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Tile grid: frame types (TYPES) or polaroid palette ──
        if (selectedCat == FrameCat.TYPES) {
            val typeFrames = listOf(
                FrameType.CLASSIC_POLAROID to "POLAROID",
                FrameType.INSTAX to "INSTAX",
                FrameType.FILM_35MM to "35MM",
                FrameType.MINIMAL_MAT to "MAT",
                FrameType.THICK_MATTE to "MATTE",
                FrameType.CARTRIDGE_110 to "110",
                FrameType.VELVET_CINEMA to "VELVET",
                FrameType.FILM_8MM to "8MM",
                FrameType.SLIDE_MOUNT to "SLIDE"
            )
            Column(Modifier.padding(horizontal = 16.dp)) {
                typeFrames.chunked(3).forEach { rowFrames ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowFrames.forEach { (ft, name) ->
                            FrameTypeTile(ft, name, frameConfig, selected = frameType == ft && selectedCode.isEmpty()) {
                                onFrameChange(ft)
                                onFrameConfigChange(frameConfig.copy(polarFrameCode = ""))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val chunked = catFrames.chunked(3)
                chunked.forEach { rowFrames ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowFrames.forEach { pf ->
                            Box(Modifier.weight(1f)) {
                                val sel = selectedCode == pf.code
                                PolarFrameCard(pf, sel) {
                                    onFrameChange(FrameType.CLASSIC_POLAROID)
                                    onFrameConfigChange(frameConfig.copy(
                                        polarFrameCode = pf.code,
                                        paperColor = pf.color.toLong(),
                                        borderWidth = pf.borderPct,
                                        bottomSpace = pf.bottomPct,
                                        cornerRadius = pf.corner,
                                        textureWear = pf.wear,
                                        shadowDepth = pf.shadow,
                                        overlayColor = pf.color.toLong(),
                                        overlayAlpha = 0f
                                    ))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Frame preview + edit button ──
        AnimatedVisibility(hasSelection,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) {
            Column {
                    // Mini preview
                    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(0.3f))) {
                        if (selectedCode.isNotEmpty()) {
                            val pf = polarFrameByCode(selectedCode)
                            // Preview: colored border around gray center
                            Canvas(Modifier.fillMaxSize()) {
                                val w = size.width; val h = size.height
                                val b = w * pf.borderPct; val bot = w * pf.bottomPct; val pH = h - b - bot
                                val c = Color(pf.color)
                                drawRect(c, Offset.Zero, Size(w, b))
                                drawRect(c, Offset(0f, b + pH), Size(w, bot + b))
                                drawRect(c, Offset.Zero, Size(b, h))
                                drawRect(c, Offset(w - b, 0f), Size(b, h))
                            }
                        } else {
                            FramePreviewCard(frameType, frameConfig)
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // EDIT / DONE + SAVE buttons
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (isEditing) GOLD else GOLD.copy(0.12f))
                            .border(0.5.dp, if (isEditing) GOLD else GOLD.copy(0.5f), RoundedCornerShape(10.dp))
                            .clickable(remember { MutableInteractionSource() }, null) { onEditingChange(!isEditing) }
                            .padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Text(if (isEditing) "DONE" else "CUSTOMIZE",
                                color = if (isEditing) Color.Black else GOLD,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                        Box(Modifier.width(72.dp).clip(RoundedCornerShape(10.dp))
                            .border(1.dp, GOLD.copy(0.7f), RoundedCornerShape(10.dp))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                val idx = savedPresets.indexOfFirst { it == null }.takeIf { it >= 0 } ?: 0
                                onSavePreset(idx)
                            }
                            .padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Text("SAVE", color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                    }
                }
            }

        // ── Customization sliders ──
        AnimatedVisibility(hasSelection && isEditing,
            enter = fadeIn(tween(250)) + expandVertically(tween(280)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(180))) {
            Column {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(Color.White.copy(0.08f)))
                Spacer(Modifier.height(4.dp))

                val sliders = listOf(
                    "BORDER" to frameConfig.borderWidth,
                    "BOTTOM" to frameConfig.bottomSpace,
                    "ROUNDING" to frameConfig.cornerRadius / 24f,
                    "TEXTURE" to frameConfig.textureWear,
                    "SHADOW" to frameConfig.shadowDepth,
                    "OVERLAY" to frameConfig.overlayAlpha
                )
                sliders.forEachIndexed { i, (label, value) ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 1.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Color.White.copy(0.38f), fontSize = 9.sp, letterSpacing = 0.8.sp,
                            fontWeight = FontWeight.Medium, modifier = Modifier.width(72.dp))
                        SheetSlider(value = value,
                            onValueChange = { nv -> onFrameConfigChange(when(i) {
                                0 -> frameConfig.copy(borderWidth = nv.coerceIn(0.01f, 0.15f))
                                1 -> frameConfig.copy(bottomSpace = nv.coerceIn(0.08f, 0.25f))
                                2 -> frameConfig.copy(cornerRadius = nv * 24f)
                                3 -> frameConfig.copy(textureWear = nv)
                                4 -> frameConfig.copy(shadowDepth = nv)
                                5 -> frameConfig.copy(overlayAlpha = nv)
                                else -> frameConfig
                            }) },
                            valueRange = 0f..1f, modifier = Modifier.weight(1f))
                        }
                }

                // Paper color swatch row
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("COLOR", color = Color.White.copy(0.38f), fontSize = 9.sp, letterSpacing = 0.8.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.width(72.dp))
                    Column(Modifier.fillMaxWidth()) {
                        val pastelSwatches = listOf(
                            0xFFF8D0D8L to "PNK", 0xFFD0E0F0L to "BLU", 0xFFD0F0E0L to "MNT", 0xFFE0D0F0L to "LAV", 0xFFF0D8C8L to "PCH", 0xFFF8F0C8L to "YLW",
                            0xFFF0C8D8L to "ROZ", 0xFFF8E8D0L to "SKN", 0xFFD8F0D8L to "LGM", 0xFFD8E8F8L to "ICY", 0xFFE8D8F0L to "PLM", 0xFFF8E0C8L to "APR",
                            0xFFF0D0D8L to "RSE", 0xFFD0E8F0L to "SLT", 0xFFD8F0E0L to "FNG", 0xFFE8D0F0L to "WST", 0xFFF8F0E0L to "CRM", 0xFFF0E8E0L to "DWN"
                        )
                        for (rowIdx in 0 until 3) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (col in 0 until 6) {
                                    val idx = rowIdx * 6 + col
                                    if (idx < pastelSwatches.size) {
                                        val (colVal, code) = pastelSwatches[idx]
                                        val c = Color(colVal.toInt())
                                        val isActive = frameConfig.paperColor == colVal
                                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                                            .background(c)
                                            .border(if (isActive) 2.dp else 0.5.dp, if (isActive) GOLD else Color.White.copy(0.15f), RoundedCornerShape(6.dp))
                                            .clickable(remember { MutableInteractionSource() }, null) {
                                                onFrameConfigChange(frameConfig.copy(paperColor = colVal))
                                            },
                                            contentAlignment = Alignment.Center) {
                                            if (isActive) Box(Modifier.size(10.dp).clip(CircleShape).background(GOLD))
                                            Text(code, fontSize = 6.sp, color = Color.Black.copy(0.35f), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Presets row
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MY COLLECTION", color = Color.White.copy(0.32f), fontSize = 9.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
                    savedPresets.forEachIndexed { i, preset ->
                        val occupied = preset != null
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (occupied) GOLD.copy(0.18f) else Color.White.copy(0.05f))
                            .border(0.5.dp, if (occupied) GOLD.copy(0.55f) else Color.White.copy(0.12f), RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = { if (occupied) onLoadPreset(preset) else onSavePreset(i) },
                                onLongClick = { if (occupied) onClearPreset(i) }
                            ), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("C${i + 1}", color = if (occupied) GOLD else Color.White.copy(0.25f),
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                if (!occupied) Text("+", color = Color.White.copy(0.15f), fontSize = 10.sp, lineHeight = 10.sp)
                            }
                        }
                    }
                }
                Text("Hold to clear", color = Color.White.copy(0.2f), fontSize = 8.sp,
                    modifier = Modifier.padding(start = 16.dp))
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(Color.White.copy(0.08f)))

        // Date stamp toggle
        Spacer(Modifier.height(6.dp))
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("DATE STAMP", color = Color.White.copy(0.7f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp, 24.dp)
                .background(if (showTimestamp) GOLD else Color.White.copy(0.12f), RoundedCornerShape(50))
                .clickable(remember { MutableInteractionSource() }, null) { onTimestampToggle() },
                contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().padding(3.dp),
                    contentAlignment = if (showTimestamp) Alignment.CenterEnd else Alignment.CenterStart) {
                    Box(Modifier.size(18.dp).background(if (showTimestamp) Color.Black else Color.White.copy(0.6f), CircleShape))
                }
            }
        }

        if (showTimestamp) {
            Spacer(Modifier.height(6.dp))
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                PickerHeader("STYLE", "LIVE PREVIEW")
                Spacer(Modifier.height(6.dp))
                StampStyleChips(dateStyle, onDateStyleChange, dateFormat, dateShowTime)

                Spacer(Modifier.height(14.dp))

                PickerHeader("FORMAT", "DATE ORDER")
                Spacer(Modifier.height(6.dp))
                val fmts = listOf(DateFormatType.DD_MM_YY, DateFormatType.MM_DD_YY, DateFormatType.YYYY_MM_DD, DateFormatType.MM_YY)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fmts.forEach { f ->
                        val sel = dateFormat == f
                        val ex = dateFormatExample(f)
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) GOLD.copy(0.18f) else Color.White.copy(0.05f))
                                .border(0.5.dp, if (sel) GOLD else Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                                .clickable(remember { MutableInteractionSource() }, null) { onDateFormatChange(f) }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(ex, color = if (sel) GOLD else Color.White.copy(0.6f), fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, fontFamily = MN, letterSpacing = 0.4.sp)
                            Spacer(Modifier.height(3.dp))
                            Text(dateFormatName(f), color = Color.White.copy(0.26f), fontSize = 6.5.sp, letterSpacing = 0.5.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                PickerHeader("TIME", if (dateShowTime) "STAMPED" else "HIDDEN")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(true to "14 : 07", false to "NONE").forEach { (on, lab) ->
                        val sel = dateShowTime == on
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (sel) GOLD.copy(0.25f) else Color.White.copy(0.06f))
                            .border(0.5.dp, if (sel) GOLD else Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                            .clickable(remember { MutableInteractionSource() }, null) { onDateShowTimeChange(on) }
                            .padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Text(lab, color = if (sel) GOLD else Color.White.copy(0.5f),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = MN, letterSpacing = 0.5.sp)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                PickerHeader("POSITION", "CORNER")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to CornerEnum.BR, 1 to CornerEnum.BL, 2 to CornerEnum.TR, 3 to CornerEnum.TL).forEach { (p, c) ->
                        val sel = datePos == p
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (sel) GOLD.copy(0.18f) else Color.White.copy(0.05f))
                            .border(0.5.dp, if (sel) GOLD else Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                            .clickable(remember { MutableInteractionSource() }, null) { onDatePosChange(p) }
                            .padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(20.dp)
                                    .border(0.8.dp, if (sel) GOLD else Color.White.copy(0.3f), RoundedCornerShape(2.dp))) {
                                    Box(Modifier.align(c.alignment).padding(2.5.dp).size(5.dp)
                                        .background(if (sel) GOLD else Color.White.copy(0.5f), CircleShape))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                PickerHeader("TEXT", if (dateCustom.isBlank()) "AUTO DATE" else "CUSTOM")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = dateCustom,
                        onValueChange = onDateCustomChange,
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(0.4f))
                            .border(0.5.dp, if (dateCustom.isNotBlank()) GOLD else Color.White.copy(0.15f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 9.dp)) { inner ->
                            if (dateCustom.isEmpty())
                                Text("auto date…", color = Color.White.copy(0.3f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            inner()
                        }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (dateCustom.isEmpty()) Color.White.copy(0.05f) else GOLD.copy(0.18f))
                        .border(0.5.dp, if (dateCustom.isEmpty()) Color.White.copy(0.1f) else GOLD, RoundedCornerShape(8.dp))
                        .clickable { onDateCustomChange(if (dateCustom.isEmpty()) "PROOF · 100" else "") }
                        .padding(horizontal = 10.dp, vertical = 9.dp)) {
                        Text(if (dateCustom.isEmpty()) "PROOF 100" else "CLEAR",
                            color = if (dateCustom.isNotEmpty()) GOLD else Color.White.copy(0.5f),
                            fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StampStyleChips(dateStyle: DateStampStyle, onDateStyleChange: (DateStampStyle) -> Unit,
    dateFormat: DateFormatType, dateShowTime: Boolean) {
    val density = LocalDensity.current
    val sample = remember(dateFormat, dateShowTime) {
        val pat = when (dateFormat) {
            DateFormatType.DD_MM_YY -> "dd.MM.yy"
            DateFormatType.MM_DD_YY -> "MM.dd.yy"
            DateFormatType.YYYY_MM_DD -> "yyyy.MM.dd"
            DateFormatType.MM_YY -> "MM.yy"
        } + if (dateShowTime) "  HH:mm" else ""
        java.text.SimpleDateFormat(pat, java.util.Locale.getDefault()).format(java.util.Date())
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        DateStampStyle.values().forEach { s ->
            val sel = dateStyle == s
            val preview = remember(s, sample) { stampPreviewBitmap(s, sample, with(density) { 140.dp.toPx() }.toInt(), 40) }
            Column(
                Modifier
                    .width(140.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) GOLD.copy(0.16f) else Color.White.copy(0.045f))
                    .border(0.5.dp, if (sel) GOLD else Color.White.copy(0.1f), RoundedCornerShape(10.dp))
                    .clickable(remember { MutableInteractionSource() }, null) { onDateStyleChange(s) }
            ) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = dateStampStyleName(s),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    contentScale = ContentScale.Fit
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(dateStampColor(s))))
                    Text(dateStampStyleName(s), color = if (sel) GOLD else Color.White.copy(0.55f),
                        fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun stampPreviewBitmap(style: DateStampStyle, text: String, w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val vis = dateStampVisual(style)
    val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = h * 0.52f
        typeface = vis.typeface
        color = vis.color
        if (vis.shadowColor != 0) setShadowLayer(vis.shadowRadius * h * 0.16f, vis.shadowDx * h * 0.15f, vis.shadowDy * h * 0.15f, vis.shadowColor)
        if (vis.letterSpacing > 0f) letterSpacing = vis.letterSpacing
        if (vis.lcd && StampLcd.font != null) typeface = StampLcd.font
    }
    val tw = if (vis.lcd && StampLcd.font == null) lcdTextWidth(text, tp.textSize * 0.96f, vis.letterSpacing) else tp.measureText(text)
    val x = (w - tw) / 2f
    val baseY = h * 0.76f
    if (vis.chipColor != 0) {
        val pad = h * 0.14f
        cv.drawRoundRect(x - pad, h * 0.12f, x + tw + pad, h * 0.9f, pad, pad,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = vis.chipColor })
    }
    if (vis.rotation != 0f) {
        cv.save()
        cv.rotate(vis.rotation * 60f, w / 2f, h / 2f)
        drawStampText(cv, text, x, baseY, tp, vis.stroke, vis.lcd)
        cv.restore()
    } else drawStampText(cv, text, x, baseY, tp, vis.stroke, vis.lcd)
    return bmp
}

@Composable
private fun PickerHeader(title: String, hint: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = GOLD, fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(0.5.dp).background(Color.White.copy(0.08f)))
        Spacer(Modifier.width(8.dp))
        Text(hint, color = Color.White.copy(0.28f), fontSize = 7.sp, letterSpacing = 1.sp)
    }
}

private fun dateFormatExample(f: DateFormatType): String = when (f) {
    DateFormatType.DD_MM_YY -> "26.Aug.24"
    DateFormatType.MM_DD_YY -> "Aug.26.24"
    DateFormatType.YYYY_MM_DD -> "2024.Aug.26"
    DateFormatType.MM_YY -> "Aug.24"
}

private fun dateFormatName(f: DateFormatType): String = when (f) {
    DateFormatType.DD_MM_YY -> "DAY / MONTH / YEAR"
    DateFormatType.MM_DD_YY -> "MONTH / DAY / YEAR"
    DateFormatType.YYYY_MM_DD -> "YEAR / MONTH / DAY"
    DateFormatType.MM_YY -> "MONTH / YEAR"
}

private enum class CornerEnum(val alignment: Alignment) {
    BR(Alignment.BottomEnd), BL(Alignment.BottomStart), TR(Alignment.TopEnd), TL(Alignment.TopStart)
}

@Composable
private fun RowScope.FrameTypeTile(ft: FrameType, name: String, config: FrameConfig, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
        .background(if (selected) GOLD.copy(0.15f) else Color.White.copy(0.05f))
        .border(0.5.dp, if (selected) GOLD else Color.White.copy(0.1f), RoundedCornerShape(10.dp))
        .clickable(remember { MutableInteractionSource() }, null) { onClick() }
        .padding(6.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(44.dp, 52.dp).clip(RoundedCornerShape(2.dp)).background(previewBg(ft, config)),
                contentAlignment = Alignment.Center) {
                val hp = previewHPad(ft, config) / 3f
                val vp = previewVPad(ft, config) / 3f
                val bp = previewBotPad(ft, config) / 3f
                Box(Modifier.padding(start = hp, end = hp, top = vp, bottom = bp).fillMaxSize()
                    .background(Color.Gray.copy(if (ft == FrameType.FILM_35MM) 0.35f else 0.25f), RoundedCornerShape(1.5f)))
                if (ft == FrameType.FILM_35MM) {
                    Row(Modifier.align(Alignment.TopCenter).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(4) { Box(Modifier.size(4.dp, 6.dp).background(Color(0xFF1C1610), RoundedCornerShape(1.dp))) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(name, color = if (selected) GOLD else Color.White.copy(0.6f),
                fontSize = 7.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.3.sp)
        }
    }
}

@Composable
private fun PolarFrameCard(frame: PolarFrame, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp))
        .background(if (selected) GOLD.copy(0.15f) else Color.White.copy(0.05f))
        .border(0.5.dp, if (selected) GOLD else Color.White.copy(0.1f), RoundedCornerShape(10.dp))
        .clickable(remember { MutableInteractionSource() }, null) { onClick() }
        .padding(8.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Mini frame preview
            Box(Modifier.size(44.dp, 52.dp).clip(RoundedCornerShape(2.dp))) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    val b = w * frame.borderPct; val bot = w * frame.bottomPct; val pH = h - b - bot
                    val c = Color(frame.color)
                    drawRect(c, Offset.Zero, Size(w, b))
                    drawRect(c, Offset(0f, b + pH), Size(w, bot + b))
                    drawRect(c, Offset.Zero, Size(b, h))
                    drawRect(c, Offset(w - b, 0f), Size(b, h))
                    drawRect(Color.Gray.copy(0.25f), Offset(b, b), Size(w - b * 2, pH))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(frame.code, color = if (selected) GOLD else Color.White.copy(0.6f),
                fontSize = 8.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.3.sp)
        }
    }
}

@Composable
private fun FramePreviewCard(ft: FrameType, config: FrameConfig) {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(162.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(previewBg(ft, config))) {

        Box(Modifier.padding(start = previewHPad(ft, config), end = previewHPad(ft, config), top = previewVPad(ft, config), bottom = previewBotPad(ft, config))
            .fillMaxSize().background(Color.Gray.copy(if (ft == FrameType.FILM_35MM) 0.35f else 0.22f), RoundedCornerShape(2.dp)))

        if (ft == FrameType.VELVET_CINEMA) {
            val dH = previewHPad(ft, config) - 3.dp; val dV = previewVPad(ft, config) - 3.dp
            Box(Modifier.padding(start = dH, end = dH, top = dV, bottom = dV).fillMaxSize()
                .border(1.5.dp, Color(0xFFFFE8AA).copy(0.55f), RoundedCornerShape(2.dp)))
        }
        if (ft == FrameType.FILM_35MM) {
            Row(Modifier.align(Alignment.TopCenter).padding(top = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(5) { Box(Modifier.size(7.dp, 11.dp).background(Color(0xFF1C1610), RoundedCornerShape(2.dp))) }
            }
        }
        Text(frameDisplayName(ft), color = frameNameColor(ft), fontSize = 8.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}

private fun previewBg(ft: FrameType, c: FrameConfig): Color = when (ft) {
    FrameType.CLASSIC_POLAROID -> Color(1f, (0.973f - c.p2 * 0.07f).coerceIn(0f, 1f), (0.941f - c.p2 * 0.13f).coerceIn(0f, 1f))
    FrameType.MINIMAL_MAT -> { val v = (0.953f - c.p2 * 0.28f).coerceIn(0.71f, 0.953f); Color(v, v - 0.008f, v - 0.012f) }
    FrameType.FILM_35MM -> { val r = (10 + c.p2 * 32) / 255f; val g = (8 + c.p2 * 26) / 255f; val b = (6 + c.p2 * 14) / 255f; Color(r, g, b) }
    FrameType.THICK_MATTE -> { val v = (198 - c.p2 * 80).toInt().coerceIn(118, 198); Color(v / 255f, (v - 3).coerceAtLeast(0) / 255f, (v - 7).coerceAtLeast(0) / 255f) }
    FrameType.CARTRIDGE_110 -> Color(0xFF2C2620)
    FrameType.VELVET_CINEMA -> { val v = (40 - c.p1 * 32).toInt().coerceIn(8, 40); Color(v / 255f, (v * 0.75f).toInt().coerceAtLeast(0) / 255f, (v * 0.55f).toInt().coerceAtLeast(0) / 255f) }
    else -> Color.White
}

private fun previewHPad(ft: FrameType, c: FrameConfig): Dp = when (ft) {
    FrameType.THICK_MATTE -> (24 + c.p1 * 18).dp
    FrameType.MINIMAL_MAT -> (14 + c.p1 * 14).dp
    FrameType.FILM_35MM -> 0.dp
    FrameType.CARTRIDGE_110 -> (8 + c.p1 * 12).dp
    else -> (10 + c.p1 * 6).dp
}

private fun previewVPad(ft: FrameType, c: FrameConfig): Dp = when (ft) {
    FrameType.FILM_35MM -> (20 + c.p1 * 6).dp
    FrameType.THICK_MATTE -> (24 + c.p1 * 18).dp
    else -> previewHPad(ft, c)
}

private fun previewBotPad(ft: FrameType, c: FrameConfig): Dp = when (ft) {
    FrameType.CLASSIC_POLAROID -> previewVPad(ft, c) + (26 + c.p1 * 12).dp
    FrameType.CARTRIDGE_110 -> previewVPad(ft, c) + 10.dp
    else -> previewVPad(ft, c)
}

private fun frameDisplayName(ft: FrameType): String = when (ft) {
    FrameType.CLASSIC_POLAROID -> "POLAROID"; FrameType.MINIMAL_MAT -> "MAT"
    FrameType.FILM_35MM -> "35MM"; FrameType.THICK_MATTE -> "MATTE"
    FrameType.CARTRIDGE_110 -> "110"; FrameType.VELVET_CINEMA -> "VELVET"
    FrameType.FILM_8MM -> "8MM"; FrameType.SLIDE_MOUNT -> "SLIDE"
    else -> ""
}

private fun frameNameColor(ft: FrameType): Color = when (ft) {
    FrameType.FILM_35MM, FrameType.VELVET_CINEMA, FrameType.CARTRIDGE_110 -> Color.White.copy(0.38f)
    else -> Color.Black.copy(0.22f)
}

private class ParamDef(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val get: (EffectConfig) -> Float,
    val set: (EffectConfig, Float) -> EffectConfig
)

private class EffDef(
    val id: String,
    val label: String,
    val icon: String,
    val on: (EffectConfig) -> Boolean,
    val setOn: (EffectConfig, Boolean) -> EffectConfig,
    val params: List<ParamDef> = emptyList()
)

private val CATEGORIES: List<Pair<String, List<EffDef>>> = listOf(
    "CINEMA" to listOf(
        EffDef("BLOOM", "BLOOM", "BL", { it.bloomOn }, { c, o -> c.copy(bloomOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.bloomStrength }, { c, v -> c.copy(bloomStrength = v) }))),
        EffDef("HALATION", "HALATION", "HL", { it.halationOn }, { c, o -> c.copy(halationOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.halationStrength }, { c, v -> c.copy(halationStrength = v) }))),
        EffDef("MIST", "MIST", "MS", { it.mistOn }, { c, o -> c.copy(mistOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.mistStrength }, { c, v -> c.copy(mistStrength = v) }))),
        EffDef("SHARPEN", "SHARPEN", "SP", { it.sharpenOn }, { c, o -> c.copy(sharpenOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.sharpenStrength }, { c, v -> c.copy(sharpenStrength = v) }))),
        EffDef("TILT", "TILT", "TL", { it.tiltShiftOn }, { c, o -> c.copy(tiltShiftOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.tiltShiftStrength }, { c, v -> c.copy(tiltShiftStrength = v) }))),
        EffDef("BOKEH", "BOKEH", "BK", { it.bokehOn }, { c, o -> c.copy(bokehOn = o) },
            listOf(
                ParamDef("SIZE", 4f..48f, { it.bokehSize }, { c, v -> c.copy(bokehSize = v) }),
                ParamDef("THRESHOLD", 0.1f..1f, { it.bokehThreshold }, { c, v -> c.copy(bokehThreshold = v) })
            )),
        EffDef("LEAK", "LEAK", "LK", { it.lightLeakOn }, { c, o -> c.copy(lightLeakOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.lightLeakStrength }, { c, v -> c.copy(lightLeakStrength = v) }))),
        EffDef("STREAK", "STREAK", "SK", { it.streakOn }, { c, o -> c.copy(streakOn = o) },
            listOf(
                ParamDef("INTENSITY", 0f..1f, { it.streakIntensity }, { c, v -> c.copy(streakIntensity = v) }),
                ParamDef("SPREAD", 0f..1f, { it.streakSpread }, { c, v -> c.copy(streakSpread = v) })
            )),
        EffDef("SUN", "SUN", "SU", { it.sunStreakOn }, { c, o -> c.copy(sunStreakOn = o) },
            listOf(
                ParamDef("INTENSITY", 0f..1f, { it.sunStreakIntensity }, { c, v -> c.copy(sunStreakIntensity = v) }),
                ParamDef("WARP", 0f..1f, { it.sunStreakWarp }, { c, v -> c.copy(sunStreakWarp = v) })
            ))
    ),
    "LENS" to listOf(
        EffDef("FISHEYE", "FISHEYE", "FE", { it.fisheyeOn }, { c, o -> c.copy(fisheyeOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1.2f, { it.fisheyeStrength * 1.2f }, { c, v -> c.copy(fisheyeStrength = v / 1.2f) }))),
        EffDef("VIGNETTE", "VIGNETTE", "VG", { it.vignetteOn }, { c, o -> c.copy(vignetteOn = o) },
            listOf(
                ParamDef("RADIUS", 0f..1f, { it.vignetteRadius }, { c, v -> c.copy(vignetteRadius = v) }),
                ParamDef("INTENSITY", 0f..1f, { it.vignetteIntensity }, { c, v -> c.copy(vignetteIntensity = v) })
            )),
        EffDef("CHROM AB", "CHROM AB", "CA", { it.caOn }, { c, o -> c.copy(caOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.caStrength }, { c, v -> c.copy(caStrength = v) }))),
        EffDef("SOFT", "SOFT", "SF", { it.softFocusOn }, { c, o -> c.copy(softFocusOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.softFocusStrength }, { c, v -> c.copy(softFocusStrength = v) }))),
        EffDef("WIDE", "WIDE", "WD", { it.wideAngleOn }, { c, o -> c.copy(wideAngleOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.wideAngleStrength }, { c, v -> c.copy(wideAngleStrength = v) }))),
        EffDef("FOCUS PK", "FOCUS PK", "FP", { it.focusPeakOn }, { c, o -> c.copy(focusPeakOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.focusPeakStrength }, { c, v -> c.copy(focusPeakStrength = v) }))),
        EffDef("LIGHT RAYS", "LIGHT RAYS", "LR", { it.lightRaysOn }, { c, o -> c.copy(lightRaysOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.lightRaysStrength }, { c, v -> c.copy(lightRaysStrength = v) })))
    ),
    "TEXTURE" to listOf(
        EffDef("GRAIN", "GRAIN", "GR", { it.grainOn }, { c, o -> c.copy(grainOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.grainStrength }, { c, v -> c.copy(grainStrength = v) }))),
        EffDef("ISO GRAIN", "ISO GRAIN", "IG", { it.isoGrainOn }, { c, o -> c.copy(isoGrainOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.isoGrainStrength }, { c, v -> c.copy(isoGrainStrength = v) }))),
        EffDef("DUST", "DUST", "DT", { it.dustOn }, { c, o -> c.copy(dustOn = o) }),
        EffDef("VHS", "VHS", "VH", { it.vhsOn }, { c, o -> c.copy(vhsOn = o) },
            listOf(ParamDef("TRACKING", 0f..1f, { it.vhsTracking }, { c, v -> c.copy(vhsTracking = v) }))),
        EffDef("CRT", "CRT", "CT", { it.crtOn }, { c, o -> c.copy(crtOn = o) },
            listOf(ParamDef("INTENSITY", 0f..1f, { it.crtStrength }, { c, v -> c.copy(crtStrength = v) }))),
        EffDef("TELETEXT", "TELETEXT", "TX", { it.teletextOn }, { c, o -> c.copy(teletextOn = o) }),
        EffDef("TERMINAL", "TERMINAL", "TM", { it.terminalOn }, { c, o -> c.copy(terminalOn = o) },
            listOf(ParamDef("COLOR", 0f..1f, { it.terminalColor }, { c, v -> c.copy(terminalColor = v) }))),
        EffDef("FAT PIXEL", "FAT PIXEL", "PX", { it.fatPixelOn }, { c, o -> c.copy(fatPixelOn = o) },
            listOf(
                ParamDef("SIZE", 2f..24f, { it.fatPixelSize }, { c, v -> c.copy(fatPixelSize = v) }),
                ParamDef("GLITCH", 0f..1f, { it.fatPixelGlitch }, { c, v -> c.copy(fatPixelGlitch = v) })
            )),
        EffDef("HALFTONE", "HALFTONE", "HT", { it.halftoneOn }, { c, o -> c.copy(halftoneOn = o) },
            listOf(
                ParamDef("DOT", 2f..20f, { it.halftoneSize }, { c, v -> c.copy(halftoneSize = v) }),
                ParamDef("ANGLE", 0f..3.14f, { it.halftoneAngle }, { c, v -> c.copy(halftoneAngle = v) })
            )),
        EffDef("CMYK", "CMYK", "CK", { it.cmykOn }, { c, o -> c.copy(cmykOn = o) },
            listOf(ParamDef("DOT", 2f..20f, { it.cmykSize }, { c, v -> c.copy(cmykSize = v) })))
    ),
    "COLOR" to listOf(
        EffDef("DUOTONE", "DUOTONE", "DU", { it.duotoneOn }, { c, o -> c.copy(duotoneOn = o) }),
        EffDef("B&W", "B&W", "BW", { it.bwOn }, { c, o -> c.copy(bwOn = o) }),
        EffDef("BLEACH", "BLEACH", "BC", { it.bleachOn }, { c, o -> c.copy(bleachOn = o) }),
        EffDef("CROSS", "CROSS", "CR", { it.crossOn }, { c, o -> c.copy(crossOn = o) }),
        EffDef("IN COLOR", "IN COLOR", "IC", { it.inColorOn }, { c, o -> c.copy(inColorOn = o) },
            listOf(
                ParamDef("STRENGTH", 0f..1f, { it.inColorStrength }, { c, v -> c.copy(inColorStrength = v) }),
                ParamDef("WARMTH", 0f..1f, { it.inColorWarmth }, { c, v -> c.copy(inColorWarmth = v) })
            )),
        EffDef("1-BIT", "1-BIT", "1B", { it.oneBitOn }, { c, o -> c.copy(oneBitOn = o) },
            listOf(ParamDef("SCALE", 1f..12f, { it.oneBitScale }, { c, v -> c.copy(oneBitScale = v) }))),
        EffDef("SHIFT", "SHIFT", "CS", { it.colorShiftOn }, { c, o -> c.copy(colorShiftOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.colorShiftStrength }, { c, v -> c.copy(colorShiftStrength = v) }))),
        EffDef("INSTANT", "INSTANT", "IN", { it.instantOn }, { c, o -> c.copy(instantOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.instantStrength }, { c, v -> c.copy(instantStrength = v) }))),
        EffDef("RETRO", "RETRO", "RT", { it.retroOn }, { c, o -> c.copy(retroOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.retroStrength }, { c, v -> c.copy(retroStrength = v) }))),
        EffDef("FALSE CLR", "FALSE CLR", "FC", { it.falseColorOn }, { c, o -> c.copy(falseColorOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.falseColorStrength }, { c, v -> c.copy(falseColorStrength = v) }))),
        EffDef("KALEIDO", "KALEIDO", "KL", { it.kaleidoOn }, { c, o -> c.copy(kaleidoOn = o) },
            listOf(ParamDef("SLICES", 2f..12f, { it.kaleidoSlices }, { c, v -> c.copy(kaleidoSlices = v) }))),
        EffDef("VELVIA", "VELVIA", "VL", { it.velviaOn }, { c, o -> c.copy(velviaOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.velviaStrength }, { c, v -> c.copy(velviaStrength = v) }))),
        EffDef("PORTRA 800", "PORTRA 800", "P8", { it.portraOn }, { c, o -> c.copy(portraOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.portraStrength }, { c, v -> c.copy(portraStrength = v) }))),
        EffDef("WINTER", "WINTER", "WN", { it.winterOn }, { c, o -> c.copy(winterOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.winterStrength }, { c, v -> c.copy(winterStrength = v) }))),
        EffDef("OBSIDIAN", "OBSIDIAN", "OB", { it.obsidianOn }, { c, o -> c.copy(obsidianOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.obsidianStrength }, { c, v -> c.copy(obsidianStrength = v) }))),
        EffDef("DREAMY", "DREAMY", "DR", { it.dreamyOn }, { c, o -> c.copy(dreamyOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.dreamyStrength }, { c, v -> c.copy(dreamyStrength = v) }))),
        EffDef("SPLIT TONE", "SPLIT TONE", "SP", { it.splitToneOn }, { c, o -> c.copy(splitToneOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.splitToneStrength }, { c, v -> c.copy(splitToneStrength = v) }))),
        EffDef("FADED", "FADED", "FD", { it.fadedFilmOn }, { c, o -> c.copy(fadedFilmOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.fadedFilmStrength }, { c, v -> c.copy(fadedFilmStrength = v) })))
    ),
    "MOTION" to listOf(
        EffDef("PRISM", "PRISM", "PR", { it.prismOn }, { c, o -> c.copy(prismOn = o) }),
        EffDef("WARP 180", "WARP 180", "W1", { it.warpOn }, { c, o -> c.copy(warpOn = o) }),
        EffDef("GLITCH", "GLITCH", "GL", { it.glitchOn }, { c, o -> c.copy(glitchOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.glitchStrength }, { c, v -> c.copy(glitchStrength = v) }))),
        EffDef("MOTION BLUR", "MOTION BLUR", "MB", { it.motionBlurOn }, { c, o -> c.copy(motionBlurOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.motionBlurStrength }, { c, v -> c.copy(motionBlurStrength = v) }))),
        EffDef("GYRO LEAK", "GYRO LEAK", "GY", { it.gyroLeakOn }, { c, o -> c.copy(gyroLeakOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.gyroLeakStrength }, { c, v -> c.copy(gyroLeakStrength = v) }))),
        EffDef("DBL EXP", "DBL EXP", "DX", { it.doubleExposureOn }, { c, o -> c.copy(doubleExposureOn = o) },
            listOf(ParamDef("STRENGTH", 0f..1f, { it.doubleExposureStrength }, { c, v -> c.copy(doubleExposureStrength = v) })))
    ),
    "DEPTH" to listOf(
        EffDef("D VIEW", "D VIEW", "DV", { it.depthMode == 1 }, { c, o -> c.copy(depthMode = if (o) 1 else 0) }),
        EffDef("STUDIO", "STUDIO", "ST", { it.depthMode == 2 }, { c, o -> c.copy(depthMode = if (o) 2 else 0) }),
        EffDef("BOKEH3", "BOKEH", "B3", { it.depthMode == 3 }, { c, o -> c.copy(depthMode = if (o) 3 else 0) },
            listOf(ParamDef("BLUR", 0f..1f, { it.depthBlurStrength }, { c, v -> c.copy(depthBlurStrength = v) })))
    )
)

private val ALL_EFFECTS: List<EffDef> = CATEGORIES.flatMap { it.second }

// Maps the sheet tile id to the GL pipeline pass name used for live preview +
// Kapi-style tile thumbnails. Depth-mode effects have no pass name (null).
fun effectGlName(id: String): String? = when (id) {
    "BLOOM" -> "bloom"; "HALATION" -> "halation"; "MIST" -> "mist"
    "TILT" -> "tilt_shift"; "STREAK" -> "streak"; "SUN" -> "sunstreak"
    "SHARPEN" -> "sharpen"
    "BOKEH" -> "bokeh"; "LEAK" -> "light_leak"; "LIGHT RAYS" -> "light_rays"; "FISHEYE" -> "fisheye"
    "VIGNETTE" -> "vignette"; "CHROM AB" -> "ca"
    "SOFT" -> "soft_focus"; "WIDE" -> "wide_angle"; "FOCUS PK" -> "focus_peak"
    "GRAIN" -> "grain"; "ISO GRAIN" -> "iso_grain"; "DUST" -> "dust"; "VHS" -> "vhs"
    "CRT" -> "crt"; "TELETEXT" -> "teletext"; "TERMINAL" -> "terminal"
    "FAT PIXEL" -> "fat_pixel"; "HALFTONE" -> "halftone"; "CMYK" -> "cmyk_dots"
    "DUOTONE" -> "duotone"; "B&W" -> "bw_grain"; "BLEACH" -> "bleach"; "CROSS" -> "cross"
    "IN COLOR" -> "in_color"; "1-BIT" -> "1bit"; "SHIFT" -> "color_shift"; "INSTANT" -> "instant"
    "RETRO" -> "retro"; "FALSE CLR" -> "false_color"; "VELVIA" -> "velvia"
    "KALEIDO" -> "kaleido"
    "PORTRA 800" -> "portra800"; "WINTER" -> "winter"; "OBSIDIAN" -> "obsidian"
    "SPLIT TONE" -> "split_tone"; "FADED" -> "faded_film"
    "PRISM" -> "prism"; "WARP 180" -> "warp"; "GLITCH" -> "glitch"
    "MOTION BLUR" -> "motion_blur"; "GYRO LEAK" -> "gyro_leak"; "DBL EXP" -> "double_exposure"
    "DREAMY" -> "dreamy"
    else -> null
}

private class ComboPreset(val id: String, val label: String, val icon: String, val build: (EffectConfig) -> EffectConfig)

private val EFFECT_COMBOS: List<ComboPreset> = listOf(
    ComboPreset("NOIR", "PULP NOIR", "N◼", {
        it.copy(bwOn = true, grainOn = true, grainStrength = 0.55f,
            vignetteOn = true, vignetteRadius = 0.45f, vignetteIntensity = 0.55f)
    }),
    ComboPreset("GOLD", "GOLDEN HOUR", "◉", {
        it.copy(bloomOn = true, bloomStrength = 0.45f,
            lightLeakOn = true, lightLeakStrength = 0.3f,
            retroOn = true, retroStrength = 0.35f)
    }),
    // Golf look: faded, muted, hazy, dreamy motion aesthetic.
    ComboPreset("GOLF", "GOLF 16", "◷", {
        it.copy(fadedFilmOn = true, fadedFilmStrength = 0.45f,
            dreamyOn = true, dreamyStrength = 0.4f,
            softFocusOn = true, softFocusStrength = 0.4f,
            motionBlurOn = true, motionBlurStrength = 0.22f)
    }),
    ComboPreset("MINT", "MINT SHOP", "❋", {
        it.copy(mistOn = true, mistStrength = 0.5f,
            halationOn = true, halationStrength = 0.4f,
            instantOn = true, instantStrength = 0.5f)
    }),
    ComboPreset("VHS", "VHS MEMORY", "▤", {
        it.copy(vhsOn = true, vhsTracking = 0.35f,
            crtOn = true, crtStrength = 0.3f,
            grainOn = true, grainStrength = 0.25f)
    }),
    ComboPreset("PIX", "PIXEL LO-FI", "◫", {
        it.copy(fatPixelOn = true, fatPixelSize = 8f,
            grainOn = true, grainStrength = 0.3f,
            vignetteOn = true, vignetteIntensity = 0.4f)
    }),
    ComboPreset("DREAM", "DREAMY", "◍", {
        it.copy(dreamyOn = true, dreamyStrength = 0.55f,
            bloomOn = true, bloomStrength = 0.35f,
            softFocusOn = true, softFocusStrength = 0.45f)
    })
)

@Composable
private fun EffectsTab(
    config: EffectConfig,
    onConfigChange: (EffectConfig) -> Unit,
    effectsThumb: ((String, (Bitmap?) -> Unit) -> Unit)? = null,
    ) {
    var category by remember { mutableStateOf("ALL") }
    var focusedId by remember { mutableStateOf<String?>(null) }
    val allDefs = remember { ALL_EFFECTS }
    val activeDefs = remember(config) { allDefs.filter { it.on(config) } }
    val focused = allDefs.find { it.id == focusedId }?.takeIf { it.on(config) }
    val cats = remember { CATEGORIES.map { it.first } }

    // Real GL previews per effect pass, generated once and cached. Depth-mode
    // effects (and effects without a pass name) keep the hand-drawn art tile.
    val thumbs = remember { mutableStateMapOf<String, Bitmap>() }
    val thumbGetter by rememberUpdatedState(effectsThumb)
    val wantTiles = effectsThumb != null
    val thumbDone = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(wantTiles) {
        if (!wantTiles) return@LaunchedEffect
        val names = ALL_EFFECTS.mapNotNull { def ->
            val pn = effectGlName(def.id) ?: return@mapNotNull null
            def.id to pn
        }
        while (isActive) {
            var pending = false
            names.forEach { (id, pn) ->
                if (thumbs.containsKey(id) || thumbDone[id] == true) return@forEach
                pending = true
                thumbGetter?.invoke(pn) { bmp ->
                    if (bmp != null) thumbs[id] = bmp
                    thumbDone[id] = true
                }
                delay(8)
            }
            if (!pending) break
            // GL view ready but a tile failed to render — mark it done after one
            // more retry so the loop terminates instead of hammering the GL thread.
            delay(400)
        }
    }

    // Adjust panel slides over the grid while an effect is focused; it closes the
    // moment the effect is switched off (focused == null), so there is never a dead end.
    AnimatedContent(
        targetState = focused,
        transitionSpec = {
            (slideInVertically { it / 3 } + fadeIn(tween(220)))
                .togetherWith(slideOutVertically { -it / 3 } + fadeOut(tween(120)))
        },
        label = "effFocus"
    ) { f ->
        if (f == null) EffectGrid(
            config = config, onConfigChange = onConfigChange,
            category = category, onCategory = { category = it },
            cats = cats, allDefs = allDefs, activeDefs = activeDefs,
            thumbs = thumbs,
            onAdjust = { focusedId = it.id }
        )
        else EffectAdjust(
            def = f, config = config, onConfigChange = onConfigChange,
            onClose = { focusedId = null }
        )
    }
}

@Composable
private fun EffectGrid(
    config: EffectConfig,
    onConfigChange: (EffectConfig) -> Unit,
    category: String,
    onCategory: (String) -> Unit,
    cats: List<String>,
    allDefs: List<EffDef>,
    activeDefs: List<EffDef>,
    thumbs: Map<String, Bitmap>,
    onAdjust: (EffDef) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        var listMode by remember { mutableStateOf(false) }
        // Header: title + count + reset-all (preserves date-stamp style settings)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("EFFECTS", fontFamily = MN, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp, color = TX_SUB)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (activeDefs.isNotEmpty()) "${activeDefs.size} ON" else "ALL OFF",
                    fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                    color = if (activeDefs.isNotEmpty()) GOLD else Color.White.copy(0.25f))
                Box(Modifier.clip(RoundedCornerShape(6.dp))
                    .background(if (listMode) GOLD.copy(0.18f) else Color.White.copy(0.06f))
                    .border(0.5.dp, if (listMode) GOLD else Color.White.copy(0.1f), RoundedCornerShape(6.dp))
                    .clickable(remember { MutableInteractionSource() }, null) { listMode = !listMode }
                    .padding(horizontal = 8.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                    Text(if (listMode) "GRID" else "LIST", fontFamily = MN, fontSize = 7.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                        color = if (listMode) GOLD else Color.White.copy(0.5f))
                }
                if (activeDefs.isNotEmpty()) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(0.06f))
                        .clickable(remember { MutableInteractionSource() }, null) {
                            onConfigChange(EffectConfig(
                                dateStyle = config.dateStyle,
                                dateFormat = config.dateFormat,
                                dateShowTime = config.dateShowTime
                            ))
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
                        Text("RESET", fontFamily = MN, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp, color = Color.White.copy(0.5f))
                    }
                }
            }
        }

        // One-tap looks
        Text("ONE-TAP LOOKS", fontFamily = MN, fontSize = 7.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp, color = Color.White.copy(0.3f),
            modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp))
        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(EFFECT_COMBOS) { combo ->
                val active = config == combo.build(config)
                Box(Modifier.clip(RoundedCornerShape(9.dp))
                    .background(if (active) GOLD.copy(0.2f) else Color.White.copy(0.05f))
                    .border(0.5.dp, if (active) GOLD else Color.White.copy(0.1f), RoundedCornerShape(9.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {
                        onConfigChange(combo.build(config))
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(combo.icon, fontFamily = MN, fontSize = 8.sp,
                            color = if (active) GOLD else Color.White.copy(0.45f))
                        Text(combo.label, fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp, color = if (active) GOLD else Color.White.copy(0.6f))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Category pills with per-category active counts
        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                CatPill("ALL", activeDefs.size, category == "ALL") { onCategory("ALL") }
            }
            items(cats) { cat ->
                val n = CATEGORIES.first { it.first == cat }.second.count { it.on(config) }
                CatPill(cat, n, category == cat) { onCategory(cat) }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Effect grid (or full list view)
        if (listMode) {
            val shownCats = if (category == "ALL") CATEGORIES else CATEGORIES.filter { it.first == category }
            shownCats.forEach { (catName, defs) ->
                Text(catName, fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp, color = GOLD.copy(0.85f),
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 3.dp))
                defs.forEach { def ->
                    val on = def.on(config)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(remember { MutableInteractionSource() }, null) {
                                onConfigChange(def.setOn(config, !on))
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape)
                            .background(if (on) GOLD else Color.White.copy(0.12f)))
                        Text(def.label, fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp, color = Color.White.copy(0.75f), maxLines = 1)
                        if (def.params.isNotEmpty()) Text("◈", fontFamily = MN, fontSize = 7.sp,
                            color = Color.White.copy(0.3f))
                        Spacer(Modifier.weight(1f))
                        Text(if (on) "ON" else "OFF", fontFamily = MN, fontSize = 7.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
                            color = if (on) GOLD else Color.White.copy(0.22f))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("TAP = ON/OFF   ·   ◈ = FINE-TUNE", fontFamily = MN, fontSize = 7.sp,
                letterSpacing = 0.6.sp, color = Color.White.copy(0.25f),
                modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
        } else {
        val defs = if (category == "ALL") allDefs
            else CATEGORIES.first { it.first == category }.second
        defs.chunked(3).forEach { rowDefs ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowDefs.forEach { def ->
                    EffTile(def = def, on = def.on(config), config = config,
                        thumb = thumbs[def.id],
                        onToggle = { onConfigChange(def.setOn(config, !def.on(config))) },
                        onAdjust = { if (def.params.isNotEmpty()) onAdjust(def) })
                }
                for (i in 0 until (3 - rowDefs.size)) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("TAP = ON/OFF   ·   LONG-PRESS OR ◈ = FINE-TUNE", fontFamily = MN, fontSize = 7.sp,
            letterSpacing = 0.6.sp, color = Color.White.copy(0.25f),
            modifier = Modifier.padding(start = 16.dp, bottom = 2.dp))
        }
    }
}

@Composable
private fun CatPill(name: String, active: Int, sel: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp))
        .background(if (sel) GOLD.copy(0.18f) else Color.White.copy(0.05f))
        .border(0.5.dp, if (sel) GOLD else Color.White.copy(0.1f), RoundedCornerShape(8.dp))
        .clickable(remember { MutableInteractionSource() }, null) { onClick() }
        .padding(horizontal = 11.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, fontFamily = MN, fontSize = 8.sp,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                letterSpacing = 0.8.sp, color = if (sel) GOLD else Color.White.copy(0.5f))
            if (active > 0) Text("·$active", fontFamily = MN, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                color = if (sel) GOLD_HI else GOLD.copy(0.6f))
        }
    }
}

@Composable
private fun EffectAdjust(
    def: EffDef,
    config: EffectConfig,
    onConfigChange: (EffectConfig) -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(0.06f))
                .clickable(remember { MutableInteractionSource() }, null) { onClose() }
                .padding(horizontal = 10.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                Text("← BACK", fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp, color = Color.White.copy(0.6f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(def.icon, fontFamily = MN, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = GOLD)
                Text("${def.label} · ADJUST", fontFamily = MN, fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = GOLD)
            }
            Box(Modifier.size(30.dp))
        }

        // Master power switch
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("EFFECT POWER", fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, color = Color.White.copy(0.38f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(true to "ON", false to "OFF").forEach { (on, lbl) ->
                    Box(Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (def.on(config) == on) GOLD.copy(0.2f) else Color.White.copy(0.05f))
                        .border(0.5.dp, if (def.on(config) == on) GOLD else Color.White.copy(0.12f), RoundedCornerShape(6.dp))
                        .clickable(remember { MutableInteractionSource() }, null) { onConfigChange(def.setOn(config, on)) }
                        .padding(horizontal = 14.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                        Text(lbl, fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = if (def.on(config) == on) GOLD else Color.White.copy(0.45f))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Sliders for this effect
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp))
            .background(SURF_UP)
            .border(0.5.dp, GOLD.copy(0.4f), RoundedCornerShape(12.dp))) {
            Column(Modifier.padding(vertical = 8.dp)) {
                if (def.params.isEmpty()) {
                    Text("NO FINE-TUNE · THIS EFFECT IS A SINGLE TAP", fontFamily = MN, fontSize = 7.sp,
                        letterSpacing = 0.8.sp, color = Color.White.copy(0.28f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                } else {
                    def.params.forEach { p -> ParamSliderRow(p, config, onConfigChange) }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                .background(Color(0x33FF4455)).border(0.5.dp, Color(0x88FF5566), RoundedCornerShape(8.dp))
                .clickable(remember { MutableInteractionSource() }, null) { onConfigChange(def.setOn(config, false)) }
                .padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text("REMOVE EFFECT", fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp, color = Color(0xFFFF7A88))
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(GOLD)
                .clickable(remember { MutableInteractionSource() }, null) { onClose() }
                .padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text("DONE", fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, color = Color.Black)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ParamSliderRow(p: ParamDef, config: EffectConfig, onConfigChange: (EffectConfig) -> Unit) {
    // Same contract as ASlider: thumb binds to the parent value directly,
    // emissions throttled to ~15/s with an exact commit on release.
    var lastEmit by remember { mutableLongStateOf(0L) }
    var lastNv by remember(config) { mutableFloatStateOf(p.get(config)) }
    val v = p.get(config)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, fontFamily = MN, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, color = Color.White.copy(0.5f))
            Text("${((v / p.range.endInclusive) * 100).toInt()}%", fontFamily = MN, fontSize = 8.sp,
                fontWeight = FontWeight.Bold, color = GOLD)
        }
        SheetSlider(
            value = v, valueRange = p.range, track = GOLD,
            onValueChange = { nv ->
                lastNv = nv
                val now = System.nanoTime()
                if (now - lastEmit > 66_000_000L) { lastEmit = now; onConfigChange(p.set(config, nv)) }
            },
            onValueChangeFinished = { onConfigChange(p.set(config, lastNv)) },
            modifier = Modifier.height(26.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.EffTile(def: EffDef, on: Boolean, config: EffectConfig, thumb: Bitmap?, onToggle: () -> Unit, onAdjust: () -> Unit) {
    val adjustable = def.params.isNotEmpty()
    Box(Modifier.weight(1f).height(78.dp).clip(RoundedCornerShape(14.dp))
        .background(if (on) GOLD.copy(0.14f) else Color.White.copy(0.045f))
        .border(if (on) 1.dp else 0.5.dp,
            if (on) GOLD.copy(0.85f) else Color.White.copy(0.09f),
            RoundedCornerShape(14.dp))
        .combinedClickable(
            onClick = onToggle,
            onLongClick = { if (adjustable) onAdjust() }
        )) {
        // Real shader preview when available; hand-drawn art as a fallback.
        if (thumb != null) {
            Image(thumb.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            EffArt(def, on)
        }
        // label + adjust tag
        Column(Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp)) {
            Text(def.label, fontFamily = MN, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp, color = if (on) GOLD_HI else Color.White.copy(0.9f),
                style = TextStyle(shadow = Shadow(Color.Black.copy(0.85f), Offset(0f, 1f), 1.2f)))
            if (adjustable) {
                Text(if (on) "◈ ADJ" else "ADJ", fontFamily = MN, fontSize = 6.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp, color = if (on) GOLD_HI.copy(0.9f) else Color.White.copy(0.55f),
                    style = TextStyle(shadow = Shadow(Color.Black.copy(0.85f), Offset(0f, 1f), 1.2f)))
            }
        }
        if (on) {
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(14.dp).clip(CircleShape)
                .background(GOLD), contentAlignment = Alignment.Center) {
                Text("✓", fontFamily = MN, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

// ── Creative effect art tiles ──────────────────────────────────────────────
// Each tile is a tiny "test chart": a neutral gray base with a simple subject
// silhouette, then the effect's signature drawn on top so the look is readable
// at a glance (grain = speckles, fisheye = warped lens circle, cmyk = 4 dots...).
@Composable
private fun EffArt(def: EffDef, on: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        drawEffectArt(def, on)
    }
}

private fun DrawScope.artSpeckle(n: Int, seed: Int, c: Color, rMin: Float, rMax: Float) {
    val rnd = java.util.Random(seed.toLong())
    repeat(n) {
        drawCircle(c, radius = rMin + rnd.nextFloat() * (rMax - rMin),
            center = Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height))
    }
}

private fun DrawScope.artHScan(y0: Float, step: Float, c: Color, th: Float) {
    var y = y0
    while (y < size.height) {
        drawLine(c, Offset(0f, y), Offset(size.width, y), strokeWidth = th)
        y += step
    }
}

private fun DrawScope.drawEffectArt(def: EffDef, on: Boolean) {
    val w = size.width; val h = size.height
    val cx = w * 0.5f; val cy = h * 0.42f
    val sub = Color(0xFFB8B8C0)
    val dm = Color(0xFFE4A94B)

    // ── gray test-chart base + subject ──
    drawRect(Brush.linearGradient(listOf(Color(0xFF414148), Color(0xFF6D6D76), Color(0xFF2A2A2F)),
        start = Offset(w * 0.22f, 0f), end = Offset(w * 0.8f, h)), size = size)
    drawLine(sub.copy(alpha = 0.30f), Offset(0f, h * 0.66f), Offset(w, h * 0.66f), strokeWidth = h * 0.012f)
    drawCircle(sub, radius = h * 0.15f, center = Offset(cx, cy))
    drawRoundRect(sub, topLeft = Offset(cx - w * 0.20f, h * 0.60f), size = Size(w * 0.40f, h * 0.20f),
        cornerRadius = CornerRadius(h * 0.06f))

    when (def.id) {
        "BLOOM" -> {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFF2D9A0).copy(0.9f), Color(0xFFF2D9A0).copy(0f)),
                center = Offset(w * 0.5f, h * 0.3f), radius = w * 0.55f), radius = w * 0.55f, center = Offset(w * 0.5f, h * 0.3f))
            drawCircle(Color(0xFFFFF3CE), radius = h * 0.07f, center = Offset(w * 0.5f, h * 0.3f))
            drawCircle(sub, radius = h * 0.13f, center = Offset(cx, cy))
        }
        "HALATION" -> {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB08A).copy(0.75f), Color(0xFFFF6A3D).copy(0.25f), Color(0xFFFF6A3D).copy(0f)),
                center = Offset(w * 0.5f, h * 0.3f), radius = w * 0.6f), radius = w * 0.6f, center = Offset(w * 0.5f, h * 0.3f))
            drawCircle(Color(0xFFFFC9A8), radius = h * 0.06f, center = Offset(w * 0.5f, h * 0.3f))
        }
        "MIST" -> {
            drawRect(Brush.linearGradient(listOf(Color.White.copy(0f), Color.White.copy(0.32f), Color.White.copy(0f)),
                start = Offset(0f, h * 0.55f), end = Offset(0f, h * 0.8f)), topLeft = Offset(0f, h * 0.55f), size = Size(w, h * 0.25f))
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(0.28f), Color.White.copy(0f)),
                center = Offset(w * 0.5f, h * 0.66f), radius = w * 0.5f), radius = w * 0.5f, center = Offset(w * 0.5f, h * 0.66f))
        }
        "DREAMY" -> {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE9C9).copy(0.5f), Color(0xFFFFE9C9).copy(0f)),
                center = Offset(w * 0.5f, h * 0.4f), radius = w * 0.6f), radius = w * 0.6f, center = Offset(w * 0.5f, h * 0.4f))
            listOf(Pair(w * 0.24f, h * 0.2f), Pair(w * 0.7f, h * 0.28f), Pair(w * 0.42f, h * 0.62f),
                Pair(w * 0.8f, h * 0.68f), Pair(w * 0.15f, h * 0.55f)).forEach { (bx, by) ->
                drawCircle(Color.White.copy(0.3f), radius = h * (0.04f + 0.03f * (bx / w)), center = Offset(bx, by))
            }
        }
"LENS FLARE" -> {
            drawLine(Brush.linearGradient(listOf(Color(0xFF9FFF9F).copy(0f), Color(0xFFB8FFB8).copy(0.7f), Color(0xFF9FFF9F).copy(0f)),
                start = Offset(0f, 0f), end = Offset(w, h)), Offset(0f, 0f), Offset(w, h), strokeWidth = h * 0.025f)
            drawCircle(Color(0xFFCCFFCC).copy(0.9f), radius = h * 0.05f, center = Offset(w * 0.3f, h * 0.3f))
            drawCircle(Color(0xFFCCFFCC).copy(0.5f), radius = h * 0.03f, center = Offset(w * 0.55f, h * 0.55f))
            drawCircle(Color(0xFFCCFFCC).copy(0.35f), radius = h * 0.02f, center = Offset(w * 0.75f, h * 0.75f))
        }
        "LIGHT RAYS" -> {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE9B0).copy(0.95f), Color(0xFFFFE9B0).copy(0f)),
                center = Offset(w * 0.42f, h * 0.3f), radius = w * 0.1f), radius = w * 0.1f, center = Offset(w * 0.42f, h * 0.3f))
            drawCircle(Color(0xFFFFF6D8), radius = h * 0.045f, center = Offset(w * 0.42f, h * 0.3f))
            val rayC = Color(0xFFFFE9A8).copy(0.55f)
            for (i in -3..3) {
                val ang = i * 0.26f
                val dx = kotlin.math.sin(ang) * w * 0.6f
                val dy = kotlin.math.cos(ang) * w * 0.42f
                drawLine(rayC, Offset(w * 0.42f, h * 0.3f), Offset(w * 0.42f + dx, h * 0.3f + dy), strokeWidth = h * 0.014f)
            }
        }
        "SPLIT TONE" -> {
            val sh = Color(0xFF2258CC); val hi = Color(0xFFFFB34D)
            drawRect(Brush.linearGradient(listOf(sh.copy(0f), sh.copy(0.55f)),
                start = Offset(0f, h), end = Offset(0f, h * 0.55f)), topLeft = Offset(0f, h * 0.55f), size = Size(w, h * 0.45f))
            drawRect(Brush.linearGradient(listOf(hi.copy(0f), hi.copy(0.5f)),
                start = Offset(0f, 0f), end = Offset(0f, h * 0.45f)), topLeft = Offset(0f, 0f), size = Size(w, h * 0.45f))
            drawLine(Color.White.copy(0.5f), Offset(w * 0.18f, h * 0.5f), Offset(w * 0.82f, h * 0.5f), strokeWidth = h * 0.012f)
        }
        "FADED" -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFFF3EFE4).copy(0f), Color(0xFFE8E0CE).copy(0.55f), Color(0xFFF3EEDD).copy(0f)),
                start = Offset(0f, 0f), end = Offset(w, h)), size = size)
            drawRect(Color.Black.copy(0.10f), topLeft = Offset(0f, h * 0.42f), size = Size(w, h * 0.16f))
            drawRect(Color.White.copy(0.35f), topLeft = Offset(w * 0.5f, h * 0.36f), size = Size(w * 0.14f, h * 0.08f))
        }
        "ANAMORPHIC" -> {
            drawRect(Color.Black.copy(0.85f), topLeft = Offset(0f, 0f), size = Size(w, h * 0.12f))
            drawRect(Color.Black.copy(0.85f), topLeft = Offset(0f, h * 0.88f), size = Size(w, h * 0.12f))
            drawRect(Brush.linearGradient(listOf(Color(0xFF88D0FF).copy(0f), Color(0xFF88D0FF).copy(0.8f), Color(0xFF88D0FF).copy(0f)),
                start = Offset(0f, h * 0.5f), end = Offset(w, h * 0.5f)), topLeft = Offset(0f, h * 0.48f), size = Size(w, h * 0.04f))
        }
        "TILT" -> {
            drawRect(Color.Black.copy(0.4f), topLeft = Offset(0f, 0f), size = Size(w, h * 0.2f))
            drawRect(Color.Black.copy(0.4f), topLeft = Offset(0f, h * 0.8f), size = Size(w, h * 0.2f))
            drawLine(Color.White.copy(0.8f), Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = h * 0.015f)
        }
        "BOKEH" -> {
            listOf(Pair(w * 0.28f, h * 0.28f), Pair(w * 0.68f, h * 0.24f), Pair(w * 0.45f, h * 0.62f),
                Pair(w * 0.78f, h * 0.55f), Pair(w * 0.18f, h * 0.7f)).forEach { (bx, by) ->
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE9C0).copy(0.7f), Color(0xFFFFE9C0).copy(0f)),
                    center = Offset(bx, by), radius = h * 0.11f), radius = h * 0.11f, center = Offset(bx, by))
            }
        }
        "LEAK" -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFFFF9A3D).copy(0f), Color(0xFFFF9A3D).copy(0.6f), Color(0xFFFF9A3D).copy(0f)),
                start = Offset(0f, 0f), end = Offset(w * 0.6f, h)), topLeft = Offset(0f, 0f), size = Size(w * 0.35f, h))
            drawRect(Brush.linearGradient(listOf(Color(0xFF2E8C8C).copy(0f), Color(0xFF2E8C8C).copy(0.55f), Color(0xFF2E8C8C).copy(0f)),
                start = Offset(w, 0f), end = Offset(w * 0.5f, h)), topLeft = Offset(w * 0.62f, 0f), size = Size(w * 0.4f, h))
        }
        "FISHEYE" -> {
            drawCircle(Color.White.copy(0.06f), radius = w * 0.46f, center = Offset(cx, h * 0.45f))
            drawCircle(sub.copy(alpha = 0.15f), radius = w * 0.46f, center = Offset(cx, h * 0.45f), style = Stroke(h * 0.012f))
            for (i in -1..1) {
                val px = cx + i * w * 0.16f
                drawArc(Color.White.copy(0.35f), -40f, 80f, false,
                    Offset(px - w * 0.3f, h * 0.15f), Size(w * 0.6f, h * 0.62f), style = Stroke(h * 0.008f))
            }
            for (i in 0..2) {
                val py = h * (0.2f + i * 0.2f)
                drawArc(Color.White.copy(0.35f), -20f, 40f, false,
                    Offset(-w * 0.35f, py - h * 0.06f), Size(w * 1.7f, h * 0.5f), style = Stroke(h * 0.008f))
            }
            drawCircle(Color.White.copy(0.5f), radius = h * 0.018f, center = Offset(cx, h * 0.45f))
        }
        "VIGNETTE" -> {
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(0.72f)),
                center = Offset(cx, cy), radius = w * 0.62f), size = size)
        }
        "CHROM AB" -> {
            drawCircle(Color(0xFFFF5050).copy(0.7f), radius = h * 0.15f, center = Offset(cx - w * 0.04f, cy))
            drawCircle(Color(0xFF50C8FF).copy(0.7f), radius = h * 0.15f, center = Offset(cx + w * 0.04f, cy))
            drawCircle(sub, radius = h * 0.13f, center = Offset(cx, cy))
        }
        "STARBURST" -> {
            val sc = Offset(w * 0.5f, h * 0.42f)
            for (a in 0 until 12) {
                val rad = a * Math.PI / 6
                drawLine(Color(0xFFFFF3CE).copy(0.8f),
                    Offset(sc.x + Math.cos(rad).toFloat() * h * 0.08f, sc.y + Math.sin(rad).toFloat() * h * 0.08f),
                    Offset(sc.x + Math.cos(rad).toFloat() * h * 0.36f, sc.y + Math.sin(rad).toFloat() * h * 0.36f),
                    strokeWidth = h * 0.012f)
            }
            drawCircle(Color(0xFFFFF3CE), radius = h * 0.04f, center = sc)
        }
        "SOFT" -> {
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(0.5f), Color.White.copy(0f)),
                center = Offset(cx, cy), radius = w * 0.42f), radius = w * 0.42f, center = Offset(cx, cy))
            drawCircle(sub, radius = h * 0.13f, center = Offset(cx, cy))
            drawCircle(Color.White.copy(0.35f), radius = h * 0.17f, center = Offset(cx, cy))
        }
        "WIDE" -> {
            for (i in 0..2) {
                val px = w * (0.25f + i * 0.25f)
                drawArc(Color.White.copy(0.28f), -30f, 30f, false,
                    Offset(px - w * 0.28f, h * 0.1f), Size(w * 0.56f, h * 0.8f), style = Stroke(h * 0.007f))
            }
            drawArc(Color.White.copy(0.28f), -15f, 30f, false,
                Offset(-w * 0.2f, h * 0.45f), Size(w * 1.4f, h * 0.5f), style = Stroke(h * 0.007f))
        }
        "FOCUS PK" -> {
            drawCircle(Color(0xFF2A2A2F), radius = h * 0.15f, center = Offset(cx, cy))
            drawCircle(Color(0xFFFF3030).copy(0.95f), radius = h * 0.17f, center = Offset(cx, cy), style = Stroke(h * 0.02f))
            drawRect(Color(0xFFFFF0B0).copy(0.5f), topLeft = Offset(cx - w * 0.12f, h * 0.34f), size = Size(w * 0.24f, h * 0.05f))
        }
        "GRAIN" -> {
            artSpeckle(80, 11, Color.White.copy(0.4f), 0.4f, 1.4f)
            artSpeckle(50, 7, Color.Black.copy(0.3f), 0.4f, 1.3f)
        }
        "ISO GRAIN" -> {
            artSpeckle(150, 23, Color.White.copy(0.35f), 0.3f, 0.9f)
            artSpeckle(90, 29, Color.Black.copy(0.35f), 0.3f, 0.8f)
        }
        "DUST" -> {
            artSpeckle(14, 31, Color.White.copy(0.5f), 1.2f, 2.6f)
            artSpeckle(10, 37, Color.Black.copy(0.4f), 1.0f, 2.2f)
            drawLine(Color.White.copy(0.3f), Offset(w * 0.2f, h * 0.2f), Offset(w * 0.4f, h * 0.16f), strokeWidth = h * 0.008f)
            drawLine(Color.White.copy(0.22f), Offset(w * 0.7f, h * 0.8f), Offset(w * 0.95f, h * 0.83f), strokeWidth = h * 0.006f)
        }
        "VHS" -> {
            artHScan(0f, h * 0.045f, Color.Black.copy(0.3f), h * 0.008f)
            drawRect(Brush.linearGradient(listOf(Color(0xFF2ECC71).copy(0f), Color(0xFF2ECC71).copy(0.5f), Color(0xFF2ECC71).copy(0f)),
                start = Offset(0f, h * 0.4f), end = Offset(w, h * 0.4f)), topLeft = Offset(0f, h * 0.40f), size = Size(w, h * 0.06f))
            drawRect(Color(0xFF0A0A0A).copy(0.85f), topLeft = Offset(w * 0.3f, h * 0.06f), size = Size(w * 0.5f, h * 0.055f))
            drawRect(Color(0xFFFFE9A0), topLeft = Offset(w * 0.33f, h * 0.072f), size = Size(w * 0.44f, h * 0.028f))
        }
        "CRT" -> {
            drawRect(Color.Black.copy(0.1f), topLeft = Offset(0f, h * 0.14f), size = Size(w, h * 0.72f))
            artHScan(0f, h * 0.035f, Color.Black.copy(0.25f), h * 0.007f)
            drawLine(Color(0xFFFF4040).copy(0.6f), Offset(0f, h * 0.14f), Offset(w, h * 0.14f), strokeWidth = h * 0.008f)
            drawLine(Color(0xFF40A0FF).copy(0.6f), Offset(0f, h * 0.86f), Offset(w, h * 0.86f), strokeWidth = h * 0.008f)
        }
        "TELETEXT" -> {
            repeat(4) { r ->
                val y = h * (0.18f + r * 0.16f)
                drawRect(Color(0xFF0D0D10), topLeft = Offset(w * 0.1f, y), size = Size(w * 0.8f, h * 0.08f))
                drawRect(Color(0xFFF2E9D0).copy(0.9f), topLeft = Offset(w * 0.14f, y + h * 0.018f), size = Size(w * 0.3f, h * 0.044f))
                drawRect(Color(0xFFFF6A3D).copy(0.9f), topLeft = Offset(w * 0.5f, y + h * 0.018f), size = Size(w * 0.24f, h * 0.044f))
            }
        }
        "TERMINAL" -> {
            drawRect(Color(0xFF0A0F0A), size = size)
            repeat(5) { r ->
                val y = h * (0.16f + r * 0.13f)
                drawRect(Color(0xFF33FF66).copy(0.8f), topLeft = Offset(w * 0.1f, y), size = Size(w * (0.28f + 0.48f * ((r * 37) % 10) / 10f), h * 0.035f))
            }
            drawRect(Color(0xFF33FF66).copy(0.95f), topLeft = Offset(w * 0.1f, h * 0.14f), size = Size(w * 0.14f, h * 0.04f))
        }
        "FAT PIXEL" -> {
            val cellW = w / 6f; val cellH = h / 8f
            for (r in 0 until 8) for (c in 0 until 6) {
                if (((r * 31 + c * 17) % 5) == 0) continue
                val v = 0x40 + ((r * 53 + c * 29) % 4) * 0x20
                drawRect(Color(0xFF000000L or ((v shl 16) or (v shl 8) or v).toLong()),
                    topLeft = Offset(c * cellW, r * cellH), size = Size(cellW, cellH))
            }
        }
        "HALFTONE" -> {
            val step = w / 8f
            var dy = h * 0.12f
            while (dy < h) {
                var dx = step / 2f
                while (dx < w) {
                    drawCircle(Color.White.copy(0.75f), radius = h * (0.028f + 0.018f * Math.sin(dx * 0.5).toFloat()), center = Offset(dx, dy))
                    dx += step
                }
                dy += h * 0.10f
            }
        }
        "CMYK" -> {
            val base = Offset(w * 0.5f, h * 0.5f); val off = w * 0.07f
            drawCircle(Color(0xFF00C0E0).copy(0.75f), radius = h * 0.11f, center = Offset(base.x - off, base.y - off * 0.5f))
            drawCircle(Color(0xFFE02090).copy(0.75f), radius = h * 0.11f, center = Offset(base.x + off, base.y - off * 0.5f))
            drawCircle(Color(0xFFF0D000).copy(0.75f), radius = h * 0.11f, center = Offset(base.x - off, base.y + off * 0.5f))
            drawCircle(Color(0xFF202020).copy(0.85f), radius = h * 0.11f, center = Offset(base.x + off, base.y + off * 0.5f))
        }
        "DUOTONE" -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF60A5FA)), start = Offset(0f, 0f), end = Offset(w, h)), size = size)
            drawRect(Color(0xFFF59E0B), topLeft = Offset(0f, h * 0.5f), size = Size(w, h * 0.5f))
            drawCircle(Color(0xFFF5E6C8), radius = h * 0.14f, center = Offset(cx, cy))
        }
        "B&W" -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFF3A3A3A), Color(0xFF9E9E9E)), start = Offset(0f, 0f), end = Offset(w, h)), size = size)
            drawCircle(Color(0xFFD8D8D8), radius = h * 0.15f, center = Offset(cx, cy))
            drawRoundRect(Color(0xFF7A7A7A), topLeft = Offset(cx - w * 0.2f, h * 0.6f), size = Size(w * 0.4f, h * 0.2f), cornerRadius = CornerRadius(h * 0.06f))
            artSpeckle(60, 17, Color.White.copy(0.3f), 0.4f, 1.2f)
        }
        "BLEACH" -> {
            drawRect(Color(0xFFF2EBD8).copy(0.35f), size = size)
            drawRect(Color.White.copy(0.15f), size = size)
        }
        "CROSS" -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFFE04090).copy(0.5f), Color(0xFF40E080).copy(0.5f)), start = Offset(0f, 0f), end = Offset(w, h)), size = size)
            drawCircle(sub.copy(alpha = 0.6f), radius = h * 0.14f, center = Offset(cx, cy))
        }
        "IN COLOR" -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFFFFC88A).copy(0.45f), Color(0xFF8AC8FF).copy(0.45f)), start = Offset(0f, 0f), end = Offset(0f, h)), size = size)
            drawCircle(Color(0xFFB0B0B8), radius = h * 0.14f, center = Offset(cx, cy))
        }
        "1-BIT" -> {
            val cell = h * 0.125f
            var y = 0f
            while (y < h) {
                var x = 0f
                while (x < w) {
                    val isWhite = (((y / cell).toInt() * 57 + (x / cell).toInt() * 13) % 3) == 0
                    drawRect(if (isWhite) Color.White else Color.Black, topLeft = Offset(x, y), size = Size(cell, cell))
                    x += cell
                }
                y += cell
            }
            drawCircle(Color.Black, radius = h * 0.13f, center = Offset(cx, cy))
            drawCircle(Color.White.copy(0.9f), radius = h * 0.09f, center = Offset(cx - w * 0.03f, cy - h * 0.02f))
        }
        "SHIFT" -> {
            listOf(Pair(0f, Color(0xFFFF6A3D)), Pair(0.33f, Color(0xFF8AC8FF)), Pair(0.66f, Color(0xFF7AFF8A))).forEach { (fx, col) ->
                drawRect(col.copy(0.5f), topLeft = Offset(w * fx, 0f), size = Size(w * 0.34f, h))
            }
            drawCircle(Color(0xFFB0B0B8), radius = h * 0.14f, center = Offset(cx, cy))
            drawLine(Color.White.copy(0.5f), Offset(w * 0.33f, 0f), Offset(w * 0.33f, h), strokeWidth = h * 0.008f)
            drawLine(Color.White.copy(0.5f), Offset(w * 0.66f, 0f), Offset(w * 0.66f, h), strokeWidth = h * 0.008f)
        }
        "INSTANT" -> {
            drawRect(Color(0xFFE8E2D0), topLeft = Offset(w * 0.08f, h * 0.08f), size = Size(w * 0.84f, h * 0.84f))
            drawRect(Color.White, topLeft = Offset(w * 0.12f, h * 0.12f), size = Size(w * 0.76f, h * 0.5f))
            drawRect(Color(0xFFF2EBD8), topLeft = Offset(w * 0.12f, h * 0.66f), size = Size(w * 0.76f, h * 0.2f))
        }
        "RETRO" -> {
            val bw = w / 4f
            listOf(Color(0xFFE04050), Color(0xFF40C0A0), Color(0xFF4060E0), Color(0xFFF0D020)).forEachIndexed { i, col ->
                drawRect(col, topLeft = Offset(i * bw, 0f), size = Size(bw + 1f, h * 0.22f))
            }
            artSpeckle(50, 41, Color.White.copy(0.35f), 0.4f, 1.2f)
            artSpeckle(30, 43, Color.Black.copy(0.3f), 0.4f, 1.1f)
        }
        "FALSE CLR" -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFF20203A), Color(0xFF2E5EFF), Color(0xFF40E0E0), Color(0xFFE0F040), Color(0xFFFF5040)),
                start = Offset(0f, 0f), end = Offset(0f, h)), size = size)
            drawCircle(Color(0xFFFFE040), radius = h * 0.14f, center = Offset(cx, cy))
        }
        "VELVIA" -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFFC020A0).copy(0.4f), Color(0xFF20B040).copy(0.4f)), start = Offset(0f, 0f), end = Offset(w, h)), size = size)
            drawCircle(Color(0xFFE02080).copy(0.6f), radius = h * 0.15f, center = Offset(cx, cy))
            drawRoundRect(Color(0xFF20C040).copy(0.4f), topLeft = Offset(cx - w * 0.2f, h * 0.6f), size = Size(w * 0.4f, h * 0.2f), cornerRadius = CornerRadius(h * 0.06f))
        }
        "PORTRA 800" -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFFF0D8B0).copy(0.4f), Color(0xFFE0A878).copy(0.35f)), start = Offset(0f, 0f), end = Offset(0f, h)), size = size)
            drawCircle(Color(0xFFF5E6C8), radius = h * 0.15f, center = Offset(cx, cy))
            artSpeckle(30, 47, Color.White.copy(0.25f), 0.4f, 1.0f)
        }
        "WINTER" -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFF9FC8E8).copy(0.55f), Color(0xFFD8EFF8).copy(0.3f)), start = Offset(0f, 0f), end = Offset(0f, h)), size = size)
            drawCircle(Color(0xFFD8EFF8), radius = h * 0.15f, center = Offset(cx, cy))
            artSpeckle(40, 53, Color.White.copy(0.7f), 0.3f, 0.9f)
        }
        "OBSIDIAN" -> {
            drawRect(Color(0xFF0A0A0C), size = size)
            drawRect(Color(0xFFF2F2EE), topLeft = Offset(0f, 0f), size = Size(w, h * 0.5f))
            drawCircle(Color.Black, radius = h * 0.15f, center = Offset(cx, cy + h * 0.05f))
            drawRoundRect(Color.White, topLeft = Offset(cx - w * 0.2f, h * 0.60f), size = Size(w * 0.4f, h * 0.2f), cornerRadius = CornerRadius(h * 0.06f))
        }
        "PRISM" -> {
            drawRect(Color.White.copy(0.1f), size = size)
            val tri = Path()
            tri.moveTo(w * 0.3f, h * 0.18f); tri.lineTo(w * 0.72f, h * 0.18f); tri.lineTo(w * 0.3f, h * 0.8f); tri.close()
            drawPath(tri, Brush.linearGradient(listOf(Color(0xFFFF4040), Color(0xFFFFD020), Color(0xFF40FF80), Color(0xFF40A0FF), Color(0xFFC040FF)),
                start = Offset(w * 0.3f, h * 0.18f), end = Offset(w * 0.3f, h * 0.8f)))
            drawLine(Color.White.copy(0.9f), Offset(w * 0.3f, h * 0.18f), Offset(w * 0.72f, h * 0.18f), strokeWidth = h * 0.012f)
        }
        "WARP 180" -> {
            var y = h * 0.1f
            while (y < h) {
                val p = Path()
                var x = 0f
                while (x <= w) {
                    val yy = y + Math.sin(x * 0.15).toFloat() * h * 0.09f
                    if (x == 0f) p.moveTo(x, yy) else p.lineTo(x, yy)
                    x += w / 24f
                }
                drawPath(p, Color.White.copy(0.6f), style = Stroke(h * 0.008f))
                y += h * 0.11f
            }
        }
        "GLITCH" -> {
            drawRect(Color(0xFF20202A), size = size)
            drawCircle(Color(0xFFC0C0C8), radius = h * 0.15f, center = Offset(cx - w * 0.04f, cy))
            drawCircle(Color(0xFFFF4050).copy(0.8f), radius = h * 0.15f, center = Offset(cx + w * 0.04f, cy))
            drawCircle(Color(0xFF40D0FF).copy(0.8f), radius = h * 0.15f, center = Offset(cx + w * 0.08f, cy))
            listOf(h * 0.3f, h * 0.52f, h * 0.74f).forEach { yy ->
                drawRect(Color(0xFF101018), topLeft = Offset(0f, yy), size = Size(w, h * 0.05f))
            }
        }
        "MOTION BLUR" -> {
            drawCircle(sub, radius = h * 0.14f, center = Offset(cx, cy))
            var y = h * 0.1f
            while (y < h) {
                drawLine(Color(0xFFE8E8EC).copy(0.35f), Offset(w * 0.08f, y),
                    Offset(w * 0.92f, y + Math.sin(y * 0.2).toFloat() * h * 0.03f), strokeWidth = h * 0.012f)
                y += h * 0.07f
            }
        }
        "GYRO LEAK" -> {
            for (i in 0 until 3) {
                val yy = h * (0.3f + i * 0.18f)
                drawLine(Brush.linearGradient(listOf(Color(0xFFFFB84D).copy(0f), Color(0xFFFFB84D).copy(0.8f), Color(0xFFFFB84D).copy(0f)),
                    start = Offset(0f, yy), end = Offset(w, yy)), Offset(0f, yy), Offset(w, yy), strokeWidth = h * (0.02f - i * 0.004f))
            }
            drawLine(Color.White.copy(0.8f), Offset(cx, h * 0.1f), Offset(cx, h * 0.9f), strokeWidth = h * 0.008f)
            drawLine(Color.White.copy(0.8f), Offset(w * 0.1f, cy), Offset(w * 0.9f, cy), strokeWidth = h * 0.008f)
            drawCircle(Color.White.copy(0.6f), radius = h * 0.09f, center = Offset(cx, cy), style = Stroke(h * 0.008f))
        }
        "DBL EXP" -> {
            drawCircle(sub.copy(alpha = 0.5f), radius = h * 0.17f, center = Offset(w * 0.32f, h * 0.35f))
            drawCircle(Color(0xFF8A8A92).copy(0.5f), radius = h * 0.17f, center = Offset(w * 0.62f, h * 0.48f))
            drawRoundRect(sub.copy(alpha = 0.4f), topLeft = Offset(w * 0.14f, h * 0.55f), size = Size(w * 0.4f, h * 0.25f), cornerRadius = CornerRadius(h * 0.06f))
            drawRoundRect(Color(0xFF8A8A92).copy(0.4f), topLeft = Offset(w * 0.45f, h * 0.66f), size = Size(w * 0.4f, h * 0.25f), cornerRadius = CornerRadius(h * 0.06f))
        }
        "D VIEW", "STUDIO", "BOKEH3", "BG BLUR" -> {
            drawCircle(sub.copy(alpha = 0.85f), radius = h * 0.15f, center = Offset(cx, cy))
            drawCircle(sub.copy(alpha = 0.25f), radius = h * 0.3f, center = Offset(cx, cy), style = Stroke(h * 0.012f))
            drawCircle(sub.copy(alpha = 0.12f), radius = h * 0.42f, center = Offset(cx, cy), style = Stroke(h * 0.01f))
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(0.5f), Color.Transparent, Color.Black.copy(0.5f))),
                topLeft = Offset(0f, h * 0.7f), size = Size(w, h * 0.3f))
        }
    }

    if (on) drawLine(dm, Offset(0f, h * 0.03f), Offset(w, h * 0.03f), strokeWidth = h * 0.02f)

    // bottom scrim so the label always reads
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.6f))),
        topLeft = Offset(0f, h * 0.8f), size = Size(w, h * 0.2f))
}

// Canonical slider used everywhere inside the sheet. Reports its drag state to
// LocalSliderActive so the sheet can fade to transparent while you adjust.
@Composable
private fun SheetSlider(
    value: Float, valueRange: ClosedFloatingPointRange<Float>, track: Color = GOLD,
    onValueChange: (Float) -> Unit, onValueChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dragging by interactionSource.collectIsDraggedAsState()
    val report = LocalSliderActive.current
    LaunchedEffect(dragging) { report(dragging) }
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange, modifier = modifier, interactionSource = interactionSource,
        colors = SliderDefaults.colors(thumbColor = track, activeTrackColor = track, inactiveTrackColor = Color.White.copy(0.1f))
    )
}

@Composable
private fun ASlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, track: Color, onChange: (Float) -> Unit) {
    // The thumb binds straight to the parent value (never desyncs), while
    // emissions are throttled (~15/s) plus an exact final commit on release —
    // the per-frame recomposition storm during drags is gone without the
    // local-echo state that used to freeze the thumb mid-track.
    var lastEmit by remember { mutableLongStateOf(0L) }
    var lastNv by remember { mutableFloatStateOf(value) }
    Row(
        Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xxs).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, color = Color.White.copy(0.38f), fontSize = 9.sp, letterSpacing = 1.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.width(80.dp)
        )
        SheetSlider(
            value = value, valueRange = range, track = track,
            onValueChange = { nv ->
                lastNv = nv
                val now = System.nanoTime()
                if (now - lastEmit > 66_000_000L) { lastEmit = now; onChange(nv) }
            },
            onValueChangeFinished = { onChange(lastNv) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SheetDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .height(0.5.dp)
            .background(Color.White.copy(0.08f))
    )
}

private fun DashedBrush(color: Color) = Brush.horizontalGradient(
    generateSequence { color }.take(30).toList(),
    startX = 0f,
    endX = Float.POSITIVE_INFINITY
)
