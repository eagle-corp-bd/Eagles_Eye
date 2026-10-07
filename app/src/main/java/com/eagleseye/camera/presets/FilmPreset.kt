package com.eagleseye.camera.presets

import com.eagleseye.camera.DateStampStyle
import com.eagleseye.camera.DateFormatType

data class ShaderParams(
    val fisheye: Float = 0f, val vignette: Float = 0f, val vignetteRadius: Float = 0.65f, val vignetteSoftness: Float = 0.35f,
    val chromatic: Float = 0f, val grain: Float = 0f, val grainSize: Float = 1f,
    val duotone: Float = 0f, val duotoneShadow: FloatArray = floatArrayOf(0f, 0f, 0f), val duotoneHighlight: FloatArray = floatArrayOf(1f, 1f, 1f),
    val bleach: Float = 0f, val crossProcess: Float = 0f, val instant: Float = 0f, val retro: Float = 0f,
    val prism: Float = 0f, val prismTint1: FloatArray = floatArrayOf(1f, 1f, 1f), val prismTint2: FloatArray = floatArrayOf(1f, 1f, 1f),
    val prismOffset: Float = 0.02f, val prismAngle: Float = 0.5f,
    val dust: Float = 0f, val dustSeed: Float = 0f, val vhs: Float = 0f, val glitch: Float = 0f, val glitchSpeed: Float = 3f,
    val lightLeak: Float = 0f, val lightLeakColor: FloatArray = floatArrayOf(1f, 0.6f, 0.3f), val lightLeakEntry: FloatArray = floatArrayOf(0.1f, 0.1f),
    val lensFlare: Float = 0f, val lensFlarePos: FloatArray = floatArrayOf(0.7f, 0.3f), val lensFlareIntensity: Float = 0.5f,
    val bw: Float = 0f, val colorShift: Float = 0f, val softFocus: Float = 0f,
    val cinematicBars: Float = 0f, val frameBorder: Float = 0f, val frameColor: FloatArray = floatArrayOf(1f, 1f, 1f),
    val dateOpacity: Float = 0f, val dateColor: FloatArray = floatArrayOf(1f, 1f, 1f),
    val bloom: Float = 0f, val halation: Float = 0f, val halationTint: FloatArray = floatArrayOf(1f, 0.35f, 0.15f), val mist: Float = 0f, val doubleExposure: Float = 0f,
    val splitTone: Float = 0f, val lightRays: Float = 0f, val faded: Float = 0f
)

data class FilmPreset(
    val id: String, val name: String,
    val lutName: String? = null,
    val duotoneShadow: FloatArray = floatArrayOf(0.05f, 0.05f, 0.1f),
    val duotoneHighlight: FloatArray = floatArrayOf(1.0f, 0.8f, 0.6f),
    val duotoneIntensity: Float = 0f, val bleachIntensity: Float = 0f, val crossProcessIntensity: Float = 0f,
    val instantIntensity: Float = 0f, val retroIntensity: Float = 0f, val bwIntensity: Float = 0f, val colorShift: Float = 0f,
    val fisheye: Float = 0f, val vignetteIntensity: Float = 0f, val vignetteRadius: Float = 0.65f, val vignetteSoftness: Float = 0.35f,
    val chromaticIntensity: Float = 0f,     val prismIntensity: Float = 0f, val prismOffset: Float = 0.02f, val prismAngle: Float = 0.5f,
    val prismTint1: FloatArray = floatArrayOf(0.9f, 0.95f, 1.0f), val prismTint2: FloatArray = floatArrayOf(1.0f, 0.9f, 0.85f),
    val bloomIntensity: Float = 0f, val halationIntensity: Float = 0f,
    val halationTint: FloatArray = floatArrayOf(1.0f, 0.35f, 0.15f),
    val mistIntensity: Float = 0f, val mistTint: FloatArray = floatArrayOf(0.85f, 0.87f, 0.9f),
    val lightLeakIntensity: Float = 0f, val lightLeakColor: FloatArray = floatArrayOf(1.0f, 0.6f, 0.3f),
    val randomLightLeak: Boolean = false, val lensFlareIntensity: Float = 0f,
    val grainIntensity: Float = 0f, val grainSize: Float = 1.0f, val dustIntensity: Float = 0f, val randomDust: Boolean = false,
    val crtIntensity: Float = 0f,
    val vhsIntensity: Float = 0f, val vhsTracking: Float = 0.5f, val glitchIntensity: Float = 0f, val randomGlitch: Boolean = false, val softFocus: Float = 0f,
    val cinematicBars: Float = 0f, val frameBorder: Float = 0f, val frameColor: FloatArray = floatArrayOf(1f, 1f, 1f),
    val dateStamp: Boolean = false, val dateColor: FloatArray = floatArrayOf(1f, 1f, 1f),
    val warpIntensity: Float = 0f,
    val tiltShiftIntensity: Float = 0f,
    val anamorphicIntensity: Float = 0f,
    val streakIntensity: Float = 0f, val streakSpread: Float = 0f,
    val sunStreakIntensity: Float = 0f, val sunStreakWarp: Float = 0f,
    val sharpenIntensity: Float = 0f,
    val motionBlurIntensity: Float = 0f,
    val isoGrainIntensity: Float = 0f,
    val gyroLeakIntensity: Float = 0f,
    val wideAngleIntensity: Float = 0f,
    val doubleExposureIntensity: Float = 0f,
    val starburstIntensity: Float = 0f,
    val focusPeakIntensity: Float = 0f,
    val falseColorIntensity: Float = 0f,
    val kaleidoIntensity: Float = 0f, val kaleidoSlices: Float = 6f,
    val inColorIntensity: Float = 0f, val inColorWarmth: Float = 0.3f,
    val oneBitIntensity: Float = 0f, val oneBitScale: Float = 4f,
    val fatPixelIntensity: Float = 0f, val fatPixelSize: Float = 8f, val fatPixelGlitch: Float = 0f,
    val halftoneIntensity: Float = 0f, val halftoneSize: Float = 6f, val halftoneAngle: Float = 0.3f,
    val cmykIntensity: Float = 0f, val cmykSize: Float = 8f,
    val teletextIntensity: Float = 0f, val sixteenBitSteps: Float = 8f,
    val terminalIntensity: Float = 0f, val terminalColor: Float = 0.33f,
    val bokehIntensity: Float = 0f, val bokehSize: Float = 14f, val bokehThreshold: Float = 0.35f,
    val velviaIntensity: Float = 0f, val portraIntensity: Float = 0f,
    val winterIntensity: Float = 0f, val obsidianIntensity: Float = 0f,
    val dreamyIntensity: Float = 0f,
    val splitToneIntensity: Float = 0f, val lightRaysIntensity: Float = 0f, val fadedFilmIntensity: Float = 0f
) {
    fun hasBlur(): Boolean = bloomIntensity > 0 || halationIntensity > 0 || mistIntensity > 0

    fun toParams(seed: Float): ShaderParams = ShaderParams(
        fisheye = fisheye, vignette = vignetteIntensity, vignetteRadius = vignetteRadius, vignetteSoftness = vignetteSoftness,
        chromatic = chromaticIntensity, grain = grainIntensity, grainSize = grainSize,
        duotone = duotoneIntensity, duotoneShadow = duotoneShadow, duotoneHighlight = duotoneHighlight,
        bleach = bleachIntensity, crossProcess = crossProcessIntensity, instant = instantIntensity, retro = retroIntensity,
        prism = prismIntensity, prismTint1 = prismTint1, prismTint2 = prismTint2, prismOffset = prismOffset, prismAngle = prismAngle,
        dust = if (randomDust) rng(seed * 3.7f) * dustIntensity else dustIntensity, dustSeed = seed,
        vhs = vhsIntensity, glitch = if (randomGlitch) rng(seed * 7.3f) * glitchIntensity else glitchIntensity, glitchSpeed = 3f,
        lightLeak = if (randomLightLeak) rng(seed * 1.1f) * lightLeakIntensity else lightLeakIntensity,
        lightLeakColor = lightLeakColor, lightLeakEntry = floatArrayOf(rng(seed * 2f), rng(seed * 3f)),
        bw = bwIntensity, colorShift = colorShift, softFocus = softFocus,
        cinematicBars = cinematicBars, frameBorder = frameBorder, frameColor = frameColor,
        dateOpacity = if (dateStamp) 0.9f else 0f, dateColor = dateColor,
        bloom = bloomIntensity, halation = halationIntensity, halationTint = halationTint, mist = mistIntensity,
        doubleExposure = doubleExposureIntensity,
        splitTone = splitToneIntensity, lightRays = lightRaysIntensity, faded = fadedFilmIntensity
    )

    private fun rng(n: Float): Float { val v = kotlin.math.sin(n.toDouble()) * 43758.5453123; return (v % 1.0).toFloat().let { if (it < 0) it + 1 else it } }
}

object PresetLibrary {
    val CLASSIC_K = FilmPreset("classic_k", "Classic K", lutName = "kodak_gold",
        vignetteIntensity = 0.4f, vignetteRadius = 0.65f, grainIntensity = 0.35f, grainSize = 1.2f,
        dustIntensity = 0.15f, randomDust = true, lightLeakIntensity = 0.25f, randomLightLeak = true,
        dateStamp = true, dateColor = floatArrayOf(0.9f, 0.9f, 0.85f))
    val INSTANT = FilmPreset("instant", "Instant", instantIntensity = 0.85f, softFocus = 0.3f,
        vignetteIntensity = 0.3f, grainIntensity = 0.2f, frameBorder = 0.08f, frameColor = floatArrayOf(0.95f, 0.95f, 0.9f), dateStamp = true)
    val VHS_90S = FilmPreset("vhs_90s", "VHS", vhsIntensity = 0.7f, chromaticIntensity = 0.4f, glitchIntensity = 0.3f,
        randomGlitch = true, grainIntensity = 0.4f, dateStamp = true, retroIntensity = 0.3f, colorShift = 0.15f)
    val CINEMATIC = FilmPreset("cinematic", "Cinematic", lutName = "teal_orange",
        bloomIntensity = 0.3f, halationIntensity = 0.2f, vignetteIntensity = 0.5f, grainIntensity = 0.15f,
        cinematicBars = 1f, colorShift = 0.1f, bwIntensity = 0.1f)
    val PORTRAIT_400 = FilmPreset("portrait_400", "Portrait", lutName = "fuji_pro_400h",
        softFocus = 0.25f, mistIntensity = 0.15f, grainIntensity = 0.3f, grainSize = 1.5f, vignetteIntensity = 0.2f, bloomIntensity = 0.1f)
    val NOIR = FilmPreset("noir", "Noir", bwIntensity = 1f, grainIntensity = 0.5f, grainSize = 1.8f,
        vignetteIntensity = 0.6f, vignetteRadius = 0.5f, dustIntensity = 0.2f, randomDust = true)
    val DREAM = FilmPreset("dream", "Dream", mistIntensity = 0.4f, softFocus = 0.5f, bloomIntensity = 0.4f, halationIntensity = 0.15f,
        grainIntensity = 0.1f, prismIntensity = 0.3f, prismTint1 = floatArrayOf(1.0f, 0.9f, 0.95f),
        lightLeakIntensity = 0.3f, lightLeakColor = floatArrayOf(1.0f, 0.7f, 0.5f))
    val GLITCH_ART = FilmPreset("glitch_art", "Glitch", glitchIntensity = 0.6f, randomGlitch = true,
        chromaticIntensity = 0.6f, vhsIntensity = 0.5f, colorShift = 0.3f, grainIntensity = 0.3f, dustIntensity = 0.1f)
    val RETRO_70S = FilmPreset("retro_70s", "70s", retroIntensity = 0.7f, instantIntensity = 0.3f, grainIntensity = 0.4f, grainSize = 1.5f,
        vignetteIntensity = 0.4f, lightLeakIntensity = 0.3f, lightLeakColor = floatArrayOf(0.9f, 0.6f, 0.3f), dateStamp = true, dateColor = floatArrayOf(1f, 0.9f, 0.7f))

    val ALL = listOf(CLASSIC_K, INSTANT, VHS_90S, CINEMATIC, PORTRAIT_400, NOIR, DREAM, GLITCH_ART, RETRO_70S)
}

data class EffectConfig(
    val fisheyeOn: Boolean = false, val fisheyeStrength: Float = 0f,
    val vignetteOn: Boolean = false, val vignetteRadius: Float = 0.5f,
    val vignetteSoftness: Float = 0.3f, val vignetteIntensity: Float = 0.5f,
    val caOn: Boolean = false, val caStrength: Float = 0.5f,
    val grainOn: Boolean = false, val grainStrength: Float = 0.5f,
    val duotoneOn: Boolean = false, val bwOn: Boolean = false,
    val bleachOn: Boolean = false, val crossOn: Boolean = false,
    val prismOn: Boolean = false, val prismAngle: Float = 0f,
    val prismOffset: Float = 0.05f, val prismOpacity: Float = 0.3f,
    val warpOn: Boolean = false,
    val dustOn: Boolean = false, val vhsOn: Boolean = false,
    val vhsTracking: Float = 0.5f,
    val crtOn: Boolean = false, val crtStrength: Float = 0.6f,
    val starburstOn: Boolean = false, val starburstStrength: Float = 0.5f,
    val softFocusOn: Boolean = false, val softFocusStrength: Float = 0.5f,
    val colorShiftOn: Boolean = false, val colorShiftStrength: Float = 0.4f,
    val instantOn: Boolean = false, val instantStrength: Float = 0.7f,
    val retroOn: Boolean = false, val retroStrength: Float = 0.6f,
    val motionBlurOn: Boolean = false, val motionBlurStrength: Float = 0.3f,
    val isoGrainOn: Boolean = false, val isoGrainStrength: Float = 0.4f,
    val gyroLeakOn: Boolean = false, val gyroLeakStrength: Float = 0.5f,
    val bloomOn: Boolean = false, val bloomStrength: Float = 0.4f,
    val halationOn: Boolean = false, val halationStrength: Float = 0.3f,
    val mistOn: Boolean = false, val mistStrength: Float = 0.4f,
    val tiltShiftOn: Boolean = false, val tiltShiftStrength: Float = 0.4f,
    val anamorphicOn: Boolean = false, val anamorphicStrength: Float = 0.3f,
    val streakOn: Boolean = false, val streakIntensity: Float = 0.65f, val streakSpread: Float = 0.5f,
    val sunStreakOn: Boolean = false, val sunStreakIntensity: Float = 0.6f, val sunStreakWarp: Float = 0.4f,
    val sharpenOn: Boolean = false, val sharpenStrength: Float = 0.4f,
    val glitchOn: Boolean = false, val glitchStrength: Float = 0.4f,
    val lightLeakOn: Boolean = false, val lightLeakStrength: Float = 0.5f,
    val lensFlareOn: Boolean = false, val lensFlareStrength: Float = 0.4f,
    val wideAngleOn: Boolean = false, val wideAngleStrength: Float = 0.5f,
    val doubleExposureOn: Boolean = false, val doubleExposureStrength: Float = 0.5f,
    val focusPeakOn: Boolean = false, val focusPeakStrength: Float = 0.5f,
    val falseColorOn: Boolean = false, val falseColorStrength: Float = 0.5f,
    val kaleidoOn: Boolean = false, val kaleidoSlices: Float = 6f,
    val dateStyle: DateStampStyle = DateStampStyle.ORANGE_FILM,
    val dateFormat: DateFormatType = DateFormatType.DD_MM_YY,
    val dateShowTime: Boolean = true,
    val inColorOn: Boolean = false, val inColorStrength: Float = 0.5f, val inColorWarmth: Float = 0.3f,
    val oneBitOn: Boolean = false, val oneBitScale: Float = 4f,
    val fatPixelOn: Boolean = false, val fatPixelSize: Float = 8f, val fatPixelGlitch: Float = 0f,
    val halftoneOn: Boolean = false, val halftoneSize: Float = 6f, val halftoneAngle: Float = 0.3f,
    val cmykOn: Boolean = false, val cmykSize: Float = 8f,
    val teletextOn: Boolean = false,
    val terminalOn: Boolean = false, val terminalColor: Float = 0.33f,
    val bokehOn: Boolean = false, val bokehSize: Float = 14f, val bokehThreshold: Float = 0.35f,
    val velviaOn: Boolean = false, val velviaStrength: Float = 0.7f,
    val portraOn: Boolean = false, val portraStrength: Float = 0.7f,
    val winterOn: Boolean = false, val winterStrength: Float = 0.7f,
    val obsidianOn: Boolean = false, val obsidianStrength: Float = 0.7f,
    val depthMode: Int = 0, // 0=OFF, 1=DEPTH_VIEW(debug), 2=STUDIO_LIGHT, 3=BOKEH (ZipDepth engine)
    val depthBlurStrength: Float = 0.6f, // 0..1 — blur radius for BOKEH (CoC) and BG BLUR (mask blur)
    val dreamyOn: Boolean = false, val dreamyStrength: Float = 0.5f,
    val splitToneOn: Boolean = false, val splitToneStrength: Float = 0.5f,
    val lightRaysOn: Boolean = false, val lightRaysStrength: Float = 0.5f,
    val fadedFilmOn: Boolean = false, val fadedFilmStrength: Float = 0.6f,
)
