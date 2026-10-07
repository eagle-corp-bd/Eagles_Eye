# Effects Engine Architecture

## Overview

The EaglesEye effects engine has **two parallel pipelines** (GPU + CPU) and a **Canvas overlay** for realtime preview. All three paths share the same 35-effect catalog defined in `EffectsConfig.kt`.

```
CameraScreen
  ├── CameraPreview (PreviewView) ──AGSL─→ GpuEffectEngine (blocked on Surface)
  ├── EffectsOverlay (Canvas) ───────────→ 35 Canvas representations (PRIMARY PREVIEW)
  └── Capture path ──────────────────────→ EffectsPipeline.applyToBitmap() (CPU)
```

---

## Layer 1: AGSL GPU Pipeline (`GpuEffectEngine.kt`)

### Architecture

- Singleton `object GpuEffectEngine` managing a `Map<String, RuntimeShader>` and `Map<String, RenderEffect>`
- Requires API 33+ (`isAvailable` check)
- All 35 shaders defined as raw AGSL strings in companion `AGSL_SHADERS` map

### Lifecycle

```
getOrCreateShader(id)
  → RuntimeShader(source)  [compiled once, cached forever]
  → setFloatUniform defaults
  → RenderEffect.createRuntimeShaderEffect(shader, "background")
  → cached in effects[]
```

### Uniform Update

```
applyParams(effectId, params, time)
  → maps human keys ("intensity", "radius", …) -> AGSL uniforms ("iStrength", "iRadius", …)
  → sets iTime for animated effects
```

Called at ~20fps from `CameraScreen` via `LaunchedEffect` keyed on `effectsTime` + `effectsState.hashCode()`.

### Known Limitation

`View.setRenderEffect()` does not process camera frames on `PreviewView` because `PreviewView` draws to a `SurfaceView`/`TextureView` that bypasses the View `draw()` pipeline. The AGSL chain is passed as a `renderEffect` param to `CameraPreview` for future TextureView-based viewfinder support.

---

## Layer 2: CPU Pipeline (`EffectsPipeline.kt`)

### Architecture

- Singleton `object EffectsPipeline`
- Fixed 7-stage processing order: WARP → BLUR → BLOOM → GRADE → VIGNETTE → RETRO → TEXTURE
- Each stage reads from source Bitmap, writes to result Bitmap via `Canvas`
- All 35 effects have `when` branch bodies

### Fixed Stage Order

```
applyAll(source, states, time)
  → filter enabled states
  → sort by STAGE_ORDER
  → for each effect:
      Bitmap.createBitmap(w, h)
      Canvas(result)
      when(effectId) { "fisheye" -> applyFisheye(bm, result, canvas, p) … }
  → return final Bitmap
```

### Key Implementation Details

- **RenderScript** used for blur (API 17+), caught exception falls to software `Convolution` loop
- **ColorMatrix** used for all color grading effects (bleach, sepia, etc.)
- **Kotlin Random** seeded per-frame for grain/dust (not per-pixel — deterministic between frames)
- **Animation**: `time` parameter drives heat_haze, wave, lens flare, VHS tracking wobble
- **Bitmap recycling**: callers responsible; pipeline creates new Bitmaps per stage

---

## Layer 3: Canvas Overlay (`EffectsOverlay.kt`)

### Architecture

- Composable `EffectsOverlay` receiving `effectsState`, `effectsActive`, `effectsTime`
- Single `Canvas` composable drawing all active effects on a `drawIntoCanvas` block
- **Primary preview path** — works on all API levels, no AGSL dependency
- All 35 effects produce visible output (no "invisible active" state)

### Overlay Strategy by Family

| Family | Overlay Technique |
|--------|-------------------|
| COLOR_GRADING | Semi-transparent overlay rect with effect color matrix simulated via Paint |
| BLUR | Faint gradient indicator / edge markers |
| BLOOM | Radial glow dots at brightness centers |
| GRAIN | Random dot noise overlay via `drawRect` |
| VIGNETTE | Radial gradient ring |
| WARP | Grid distortion lines / indicators |
| RETRO | Scanline pattern / color fringe bars |

---

## Layer 4: Capture Path

- Triggered from CameraScreen shutter → `FilmEngine` → `EffectsPipeline.applyToBitmap()`
- Returns processed Bitmap for save/share
- CPU pipeline used exclusively (AGSL HardwareRenderer + ImageReader considered too complex for MVP)

---

## Configuration (`EffectsConfig.kt`)

- `EffectDef`: id, label, icon, category, list of `EffectParam`
- `EffectParam`: key, label, default, min, max, optional step
- 10 `TechniqueFamily` enum values with order index
- `EFFECT_CATALOG_V2`: 35 effect definitions with UI metadata

## State Management

- `effectsState: List<EffectState>` — each has `effectId`, `enabled`, `paramValues: Map<String, Float>`
- `effectsActive: Boolean` — derived from any enabled effect
- `effectsTime: Float` — monotonically increasing frame time for animations
- All held as Compose `mutableStateOf` in CameraScreen

---

## File Map

```
docs/
  01-effects-ranking-roadmap.md
  02-technique-deepdives.md
  03-engine-architecture.md
app/.../camera/effects/
  EffectsConfig.kt        — 35 effect definitions, TechniqueFamily enum
  GpuEffectEngine.kt      — AGSL RuntimeShader manager + all 35 shaders (~800 lines)
  EffectsPipeline.kt      — CPU Bitmap pipeline, all 35 effect impls (~760 lines)
app/.../camera/
  EffectsOverlay.kt       — Canvas composable, all 35 overlay representations
  EffectsPanel.kt         — Bottom sheet UI for effect selection
  EffectsEngine.kt        — Default effect state factory
  CameraScreen.kt         — Wires everything: FX button, AGSL chain, capture pipeline
```
