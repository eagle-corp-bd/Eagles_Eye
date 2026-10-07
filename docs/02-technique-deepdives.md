# Technique Deep Dives — Implementation Details

## 1. Color Grading Engine

**Approach:** 3×3 color matrix + per-channel curve approximation.

- 10 effects: bleach, duotone, tealorange, kodak, fuji, fade, sepia, noir, crossprocess, solidtint
- All implemented as `ColorMatrix` multiplications for CPU path
- AGSL path uses direct RGB manipulation in fragment shader

### Key formulas:

```
// Bleach Bypass: desaturate + contrast
luma = dot(rgb, vec3(0.299, 0.587, 0.114))
mixed = mix(rgb, vec3(luma), strength)
result = (mixed - 0.5) * 1.5 + 0.5

// Duotone:
shadowColor = hslToRgb(shadowHue, 0.6, 0.3)
highlightColor = hslToRgb(highlightHue, 0.8, 0.7)
t = smoothstep(0.0, 1.0, luma)
result = mix(shadowColor, highlightColor, t)

// Teal & Orange: orange skin, teal shadows
result.rgb *= vec3(1.1, 0.9, 0.8) + strength * vec3(-0.3, 0.1, 0.4)
```

**AGSL uniform count:** 3–5 per effect. All use `iStrength` as primary param.

---

## 2. Blur Toolkit

**Approach:** Separable Gaussian convolution (CPU), 1D+1D pass (AGSL).

- 4 effects: softfocus, tiltshift, zoomblur, motionblur
- CPU: RenderScript ScriptIntrinsicBlur API 17+, falls to software `java.awt` convolution
- Soft focus: blur + recombine with original at `iStrength`
- Tilt-shift: linear gradient alpha mask, blurred outside band
- Zoom blur: radial sampling from center point
- Motion blur: directional 1D kernel at `iAngle`

---

## 3. Bloom & Diffusion / Halation

**Approach:** Threshold → blur → add.

- 4 effects: bloom, halation, starnd, lensflare
- Bloom: extract brights above `iThreshold`, blur, add back
- Halation: red-channel-only bloom (emulates film halation)
- Star ND: reduced contrast on highlights only
- Lens flare: procedural circles + streaks at `iPosition`

**AGSL note:** Halation AGSL shader was fixed to clamp red channel only.

---

## 4. Grain / Noise Compositor

**Approach:** Per-pixel random noise (CPU uses seeded Random, AGSL uses hash functions).

- 5 effects: grain, halftone, dither, dust, pixelate
- Grain: multi-octave value noise at `iSize` scale
- Halftone: circular dot screen at `iDotSize` (AGSL coord math was fixed)
- Dither: Bayer matrix 4×4 ordered dither (AGSL was fixed to use correct bayer array)
- Dust: random line segments with age animation
- Pixelate: nearest-neighbor block at `iBlockSize`

---

## 5. Vignette & Gradient Lighting

- 2 effects: vignette, spotlight
- Vignette: radial gradient from center, `iRadius` controls falloff
- Spotlight: radial gradient at center point, hard/soft blend controlled by `iRadius`

---

## 6. Chromatic Aberration / Retro-Digital

- 4 effects: chromatic, vhs, crt, scanlines
- Chromatic AB: split RGB channels, offset R and B oppositely
- VHS: color bleed + horizontal jitter + `iTracking` noise
- CRT: RGB sub-pixel pattern overlay
- Scanlines: alternating horizontal line darkening

---

## 7. Warp / Distortion Engine

**Approach:** Reverse-mapping with bilinear interpolation.

- 6 effects: fisheye, twirl, bulge, kaleidoscope, heat_haze, wave
- Fisheye: polar distortion with `iIntensity` curvature
- Twirl: angular rotation exponential falloff from center
- Bulge: radial magnification at center
- Kaleidoscope: mirror wedge at `iSegments` (blend fix: was red-only)
- Heat Haze: sinusoidal offset on y-axis
- Wave: traveling sine wave at `iSpeed`

---

## 8–10. Reserved Families

Not yet implemented. Placeholder slots for:
- **Overlay Compositor:** Multi-layer blend modes (screen, multiply, overlay)
- **Film Recipe:** Full ICC profile emulations with film D50 gamut mapping
- **Beauty Smoothing:** Face detection → selective blur + skin tone adjustment
