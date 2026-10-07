# Effects Engine — Ranking & Roadmap

## 10 Technique Families (ordered by priority)

| # | Family | Effects | Priority | Rationale |
|---|--------|---------|----------|-----------|
| 1 | **COLOR_GRADING** | bleach, duotone, tealorange, kodak, fuji, fade, sepia, noir, crossprocess, solidtint | P0 | Highest visual impact per cycle; film looks sell the app |
| 2 | **BLUR_TOOLKIT** | softfocus, tiltshift, zoomblur, motionblur | P0 | Portrait mode essential; tiltshift is marquee feature |
| 3 | **BLOOM_DIFFUSION** | bloom, halation, starnd, lensflare | P0 | Halation + bloom define the "analog" look |
| 4 | **GRAIN_NOISE** | grain, halftone, dither, dust, pixelate | P0 | Grain is the #1 requested texture effect |
| 5 | **VIGNETTE_LIGHTING** | vignette, spotlight | P0 | Vignette is universal; spotlight adds creative control |
| 6 | **CHROMATIC_RETRO** | chromatic, vhs, crt, scanlines | P1 | Niche but highly shareable (VHS, CRT) |
| 7 | **WARP_DISTORTION** | fisheye, twirl, bulge, kaleidoscope, heat_haze, wave | P1 | Kaleidoscope is viral potential; wave/heat_haze for video |
| 8 | **OVERLAY_COMPOSITOR** | (reserved for multi-layer blending) | P2 | Post-MVP |
| 9 | **FILM_RECIPE** | (reserved for full film stock emulations) | P2 | Post-MVP |
| 10 | **BEAUTY_SMOOTHING** | (reserved for face-aware processing) | P3 | Requires ML model integration |

## Implementation Status (MVP: 35 effects)

| Layer | Effects | Status |
|-------|---------|--------|
| **AGSL Shaders** | All 35 effects have GLSL-derived AGSL fragment programs | Done |
| **CPU Pipeline** | All 35 effects have pixel-accurate Bitmap implementations in `EffectsPipeline.applyToBitmap()` | Done |
| **Canvas Overlay** | All 35 effects render visual feedback on `EffectsOverlay` (primary preview path) | Done |
| **CameraScreen wiring** | FX button triggers effects state; AGSL chain attempted via `renderEffect` param | Done |
| **TextureView preview** | AGSL via `setRenderEffect()` blocked on PreviewView (Surface bypass); requires TextureView | Next |

## Performance Targets

- CPU pipeline: <200ms per 12MP frame with 5 concurrent effects
- AGSL path (future): <8ms per frame (GPU fragment shader)
- Canvas overlay: <4ms per frame (single DrawScope pass)
- Memory: <3 intermediate Bitmaps at capture time

## Open Issues

1. PreviewView uses SurfaceView internally — `View.setRenderEffect()` does not process camera frames. Solution: switch to TextureView-based CameraX preview or use Canvas overlay as fallback.
2. RenderScript deprecated at API 31+ — `EffectsPipeline` catches exception and falls to software blur. No functional issue but ~3x slower on API 33+.
3. AGSL uniform update runs at 20fps via LaunchedEffect — could be optimized to choreographer frame callback.
