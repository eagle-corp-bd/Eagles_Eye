package com.eagleseye.camera

import android.content.Context
import androidx.activity.compose.BackHandler
import com.eagleseye.camera.DateStampStyle
import com.eagleseye.camera.dateStampStyleName
import com.eagleseye.camera.DateFormatType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AppSettings {
    private const val PREFS = "eagleseye_settings"

    fun defaultMode(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("default_mode", "photo") ?: "photo"

    fun setDefaultMode(ctx: Context, mode: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("default_mode", mode).apply()

    fun timestampDefault(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("timestamp_default", true)

    fun setTimestampDefault(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("timestamp_default", on).apply()

    fun dateStampStyle(ctx: Context): DateStampStyle {
        val i = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("date_style", 0)
        return DateStampStyle.values()[i.coerceIn(0, DateStampStyle.values().lastIndex)]
    }

    fun setDateStampStyle(ctx: Context, s: DateStampStyle) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("date_style", s.ordinal).apply()

    fun dateStampFormat(ctx: Context): DateFormatType {
        val i = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("date_format", 0)
        return DateFormatType.values()[i.coerceIn(0, DateFormatType.values().lastIndex)]
    }

    fun setDateStampFormat(ctx: Context, f: DateFormatType) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("date_format", f.ordinal).apply()

    fun dateStampShowTime(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("date_show_time", true)

    fun setDateStampShowTime(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("date_show_time", on).apply()

    fun dateStampPos(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("date_pos", 0)

    fun setDateStampPos(ctx: Context, p: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("date_pos", p).apply()

    fun defaultGrid(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("default_grid", "off") ?: "off"

    fun setDefaultGrid(ctx: Context, g: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("default_grid", g).apply()

    fun defaultAspect(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("default_aspect", "3:4") ?: "3:4"

    fun setDefaultAspect(ctx: Context, a: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("default_aspect", a).apply()

    fun accentIndex(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt("accent_index", 0)

    fun setAccentIndex(ctx: Context, i: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("accent_index", i).apply()

    fun keepAwake(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("keep_awake", true)

    fun setKeepAwake(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("keep_awake", on).apply()

    fun haptics(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("haptics", true)

    fun setHaptics(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("haptics", on).apply()

    fun grainAnim(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("grain_anim", false)

    fun setGrainAnim(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("grain_anim", on).apply()

    fun uiAnim(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("ui_anim", true)

    fun setUiAnim(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ui_anim", on).apply()

    fun hiFps(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("hi_fps", false)

    fun setHiFps(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("hi_fps", on).apply()

    fun hiBitrate(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("hi_bitrate", false)

    fun setHiBitrate(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("hi_bitrate", on).apply()

    fun shakeUndo(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("shake_undo", false)

    fun setShakeUndo(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("shake_undo", on).apply()

    // ── Depth bokeh ──────────────────────────────────────────────────────────
    // Live depth mode (0=off, 1=DEPTH_VIEW, 2=STUDIO, 3=BOKEH) + plain-bokeh tile
    // flag, persisted so a freshly created engine (surface re-creation) can
    // restore the user's selection without needing the Compose effect to re-run.
    fun depthMode(ctx: Context): Int = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt("depth_mode", 0)

    fun setDepthMode(ctx: Context, v: Int) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("depth_mode", v).apply()

    fun depthBlur(ctx: Context): Float = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getFloat("depth_blur", 0.6f)

    fun setDepthBlur(ctx: Context, v: Float) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("depth_blur", v).apply()

    fun bokehOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("bokeh_on", false)

    fun setBokehOn(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("bokeh_on", on).apply()

    fun bokehStrength(ctx: Context): Float = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getFloat("bokeh_strength", 0.6f)

    fun setBokehStrength(ctx: Context, v: Float) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("bokeh_strength", v).apply()

    fun bokehRange(ctx: Context): Float = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getFloat("bokeh_range", 0.35f)

    fun setBokehRange(ctx: Context, v: Float) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("bokeh_range", v).apply()

    fun bakeBokeh(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("bake_bokeh", true)

    fun setBakeBokeh(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("bake_bokeh", on).apply()

    fun useMiDasDepth(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("depth_midas", true)

    fun setUseMiDasDepth(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("depth_midas", on).apply()

    fun debugPipeline(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("debug_pipeline", false)

    fun setDebugPipeline(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("debug_pipeline", on).apply()

    // ── AGSL light orb (relight) ─────────────────────────────────────────────
    fun relightOn(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("relight_on", false)

    fun setRelightOn(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("relight_on", on).apply()
}

private val SET_MODES = listOf(
    "photo" to "PHOTO",
    "video" to "VIDEO",
    "hyperlapse" to "HYPERLAPSE",
    "slomo" to "SLO-MO",
    "pano" to "PANO"
)

/** Label above, chips below in a wrapping FlowRow — keeps option text readable on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, color = Color.White.copy(0.75f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) { content() }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) AppAccent.color else Color.White.copy(0.07f))
            .clickable(src, null) { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Color.Black else Color.White.copy(0.6f),
            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsScreen(onClose: () -> Unit, onOpenLicenses: () -> Unit = {}) {
    BackHandler { onClose() }
    val context = LocalContext.current
    var defaultMode by remember { mutableStateOf(AppSettings.defaultMode(context)) }
    var timestampOn by remember { mutableStateOf(AppSettings.timestampDefault(context)) }
    var accentIdx by remember { mutableStateOf(AppSettings.accentIndex(context)) }
    var keepAwake by remember { mutableStateOf(AppSettings.keepAwake(context)) }
    var hapticsOn by remember { mutableStateOf(AppSettings.haptics(context)) }
    var grainAnim by remember { mutableStateOf(AppSettings.grainAnim(context)) }
    var uiAnim by remember { mutableStateOf(AppSettings.uiAnim(context)) }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "1.0"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(UiBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header — glass bar
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color(0xF00B0B0E))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            val closeSrc = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(40.dp)
                    .uiPressScale(closeSrc)
                    .clip(CircleShape)
                    .background(Color.White.copy(0.12f))
                    .clickable(closeSrc, null) { onClose() }
                    .align(Alignment.CenterStart),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, "Close settings", tint = Color.White, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "SETTINGS",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            UiSectionHeader("DEFAULT MODE")
            UiSettingsCard {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SET_MODES.forEach { (id, label) ->
                        val active = id == defaultMode
                        val src = remember { MutableInteractionSource() }
                        Box(
                            Modifier
                                .uiPressScale(src)
                                .clip(RoundedCornerShape(50))
                                .then(
                                    if (active) Modifier.background(UiGoldGradient())
                                    else Modifier.background(Color.White.copy(0.07f))
                                )
                                .clickable(src, null) {
                                    defaultMode = id
                                    AppSettings.setDefaultMode(context, id)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (active) UiBg else Color.White.copy(0.7f),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            UiSectionHeader("APPEARANCE")
            UiSettingsCard {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ACCENT_PALETTES.forEachIndexed { i, p ->
                        val sel = accentIdx == i
                        val src = remember { MutableInteractionSource() }
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .uiPressScale(src)
                                .clip(CircleShape)
                                .then(if (sel) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                                .background(p.color)
                                .clickable(src, null) {
                                    accentIdx = i
                                    AppAccent.apply(i)
                                    AppSettings.setAccentIndex(context, i)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (sel) Icon(Icons.Default.Check, null, tint = UiBg, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                UiCardDivider()
                UiCardRow {
                    Text("Signature accent", color = Color.White.copy(0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(ACCENT_PALETTES[accentIdx].name, color = AppAccent.hi, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            UiSectionHeader("CAMERA")
            UiSettingsCard {
                UiCardRow(chevron = false) {
                    Column(Modifier.weight(1f)) {
                        Text("Date stamp by default", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text("Overlay the date/time on every capture", color = Color.White.copy(0.45f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    UiSwitch(checked = timestampOn) {
                        timestampOn = it
                        AppSettings.setTimestampDefault(context, it)
                    }
                }
                UiCardDivider()
                var stampPos by remember { mutableStateOf(AppSettings.dateStampPos(context)) }
                ChipRow("Stamp position") {
                    listOf("B·R" to 0, "B·L" to 1, "T·R" to 2, "T·L" to 3).forEach { (l, v) ->
                        Chip(l, stampPos == v) { stampPos = v; AppSettings.setDateStampPos(context, v) }
                    }
                }
                UiCardDivider()
                var stampStyle by remember { mutableStateOf(AppSettings.dateStampStyle(context)) }
                ChipRow("Stamp style") {
                    DateStampStyle.values().forEachIndexed { i, s ->
                        Chip(dateStampStyleName(s), stampStyle.ordinal == i) {
                            stampStyle = DateStampStyle.values()[i]
                            AppSettings.setDateStampStyle(context, stampStyle)
                        }
                    }
                }
                UiCardDivider()
                UiCardRow(chevron = false) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep screen awake", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text("Stay on while the camera is open", color = Color.White.copy(0.45f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    UiSwitch(checked = keepAwake) {
                        keepAwake = it
                        AppSettings.setKeepAwake(context, it)
                    }
                }
                UiCardDivider()
                var hiFps by remember { mutableStateOf(AppSettings.hiFps(context)) }
                UiCardRow(chevron = false) {
                    Column(Modifier.weight(1f)) {
                        Text("60 FPS video", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text("Smooth high frame-rate recording", color = Color.White.copy(0.45f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    UiSwitch(checked = hiFps) {
                        hiFps = it
                        AppSettings.setHiFps(context, it)
                    }
                }
                UiCardDivider()
                var hiBit by remember { mutableStateOf(AppSettings.hiBitrate(context)) }
                UiCardRow(chevron = false) {
                    Column(Modifier.weight(1f)) {
                        Text("High bitrate video", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text("≈1.25× data rate · bigger files", color = Color.White.copy(0.45f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    UiSwitch(checked = hiBit) {
                        hiBit = it
                        AppSettings.setHiBitrate(context, it)
                    }
                }
            }

            UiSectionHeader("BEHAVIOR")
            UiSettingsCard {
                UiCardRow(chevron = false) {
                    Column(Modifier.weight(1f)) {
                        Text("Haptic feedback", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text("Vibration on taps, sliders and shutter", color = Color.White.copy(0.45f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    UiSwitch(checked = hapticsOn) {
                        hapticsOn = it
                        AppSettings.setHaptics(context, it)
                    }
                }
                UiCardDivider()
                UiCardRow(chevron = false) {
                    Column(Modifier.weight(1f)) {
                        Text("Film grain animation", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text("Static grain is cheaper and smoother", color = Color.White.copy(0.45f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    UiSwitch(checked = grainAnim) {
                        grainAnim = it
                        AppSettings.setGrainAnim(context, it)
                    }
                }
                UiCardDivider()
                UiCardRow(chevron = false) {
                    Column(Modifier.weight(1f)) {
                        Text("UI animations", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text("Sheet, tabs, appears and transitions", color = Color.White.copy(0.45f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    UiSwitch(checked = uiAnim) {
                        uiAnim = it
                        AppSettings.setUiAnim(context, it)
                    }
                }
                UiCardDivider()
                var shakeUndo by remember { mutableStateOf(AppSettings.shakeUndo(context)) }
                UiCardRow(chevron = false) {
                    Column(Modifier.weight(1f)) {
                        Text("Shake to undo", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text("A shake deletes the last capture", color = Color.White.copy(0.45f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    UiSwitch(checked = shakeUndo) {
                        shakeUndo = it
                        AppSettings.setShakeUndo(context, it)
                    }
                }
            }

            UiSectionHeader("STARTUP DEFAULTS")
            UiSettingsCard {
                var defaultGrid by remember { mutableStateOf(AppSettings.defaultGrid(context)) }
                var defaultAspect by remember { mutableStateOf(AppSettings.defaultAspect(context)) }
                ChipRow("Grid overlay") {
                    listOf(
                        "off" to "OFF", "3x3" to "3×3", "4x4" to "4×4", "5x5" to "5×5",
                        "golden" to "GOLD", "center" to "CTR", "diagonal" to "DIAG"
                    ).forEach { (v, l) ->
                        Chip(l, defaultGrid == v) { defaultGrid = v; AppSettings.setDefaultGrid(context, v) }
                    }
                }
                UiCardDivider()
                ChipRow("Aspect ratio") {
                    listOf("3:4" to "3:4", "1:1" to "1:1", "16:9" to "16:9", "9:16" to "9:16", "FULL" to "FULL").forEach { (v, l) ->
                        Chip(l, defaultAspect == v) { defaultAspect = v; AppSettings.setDefaultAspect(context, v) }
                    }
                }
            }

            UiSectionHeader("DEPTH & BOKEH")
            UiSettingsCard {
                var bokehStrength by remember { mutableStateOf(AppSettings.bokehStrength(context)) }
                UiSliderRow(
                    "PORTRAIT BLUR", bokehStrength, 0.1f, 1.0f,
                    fmt = { "${(it * 100).toInt()}%" },
                    onChange = {
                        bokehStrength = it
                        AppSettings.setBokehStrength(context, it)
                    }
                )
                UiCardDivider()
                var bokehRange by remember { mutableStateOf(AppSettings.bokehRange(context)) }
                UiSliderRow(
                    "SHARP RANGE", bokehRange, 0.05f, 0.8f,
                    fmt = { "${(it * 100).toInt()}%" },
                    onChange = {
                        bokehRange = it
                        AppSettings.setBokehRange(context, it)
                    }
                )
                UiCardDivider()
                var bakeBokeh by remember { mutableStateOf(AppSettings.bakeBokeh(context)) }
                UiCardRow(chevron = false) {
                    Column(Modifier.weight(1f)) {
                        Text("Bake blur into photos", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text("Off saves a clean photo and keeps the live bokeh preview", color = Color.White.copy(0.45f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    UiSwitch(checked = bakeBokeh) {
                        bakeBokeh = it
                        AppSettings.setBakeBokeh(context, it)
                    }
                }
                UiCardDivider()
                var midasModel by remember { mutableStateOf(AppSettings.useMiDasDepth(context)) }
                ChipRow("Depth engine") {
                    listOf("MIDAS 256" to true, "ZIP 384" to false).forEach { (l, v) ->
                        Chip(l, midasModel == v) { midasModel = v; AppSettings.setUseMiDasDepth(context, v) }
                    }
                }
            }

            UiSectionHeader("DEBUG")
            UiSettingsCard {
                var dbgPipe by remember { mutableStateOf(AppSettings.debugPipeline(context)) }
                UiCardRow(chevron = false) {
                    Column(Modifier.weight(1f)) {
                        Text("Pipeline debug view", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Spacing.xxs))
                        Text("Show raw · depth · mask stages live", color = Color.White.copy(0.45f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    UiSwitch(checked = dbgPipe) {
                        dbgPipe = it
                        AppSettings.setDebugPipeline(context, it)
                    }
                }
            }

            UiSectionHeader("STORAGE")
            UiSettingsCard {
                UiCardRow {
                    Text("Photos & videos", color = Color.White.copy(0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("DCIM/EaglesEye", color = Color.White.copy(0.4f), fontSize = 12.sp)
                }
            }

            UiSectionHeader("AI MODELS · ON-DEVICE")
            UiSettingsCard {
                val midasActive = AppSettings.useMiDasDepth(context)
                UiCardRow {
                    Text("Portrait depth · MiDaS", color = Color.White.copy(0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if (midasActive) "ACTIVE · 33.5 MB" else "33.5 MB", color = if (midasActive) AppAccent.hi else Color.White.copy(0.4f), fontSize = 12.sp)
                }
                UiCardDivider()
                UiCardRow {
                    Text("Depth maps · ZipDepth", color = Color.White.copy(0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if (!midasActive) "ACTIVE · 24.6 MB" else "24.6 MB", color = if (!midasActive) AppAccent.hi else Color.White.copy(0.4f), fontSize = 12.sp)
                }
            }

            UiSectionHeader("ABOUT")
            UiSettingsCard {
                UiCardRow(chevron = true, onClick = onOpenLicenses) {
                    Text("Open Source Licenses", color = Color.White.copy(0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                UiCardDivider()
                UiCardRow {
                    Text("EaglesEye", color = Color.White.copy(0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("Alpha build", color = Color.White.copy(0.4f), fontSize = 12.sp)
                }
                UiCardDivider()
                UiCardRow {
                    Text("Version", color = Color.White.copy(0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(versionName, color = Color.White.copy(0.4f), fontSize = 12.sp)
                }
                UiCardDivider()
                UiCardRow {
                    Text("Engine", color = Color.White.copy(0.85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("CameraX · TFLite · GLES 3.0", color = Color.White.copy(0.4f), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}
