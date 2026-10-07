# 04 — Dazz Cam Feature Checklist (Complete List)

Compiled from web research (Dazz app store listing, gist of real-camera names, Paralaxis/Dazz reviews, 2025-2026).
Goal: copy every Dazz Cam feature into EaglesEye. Status: ✅ has · 🟡 partial · 🔶 planned · ⬜ missing

---

## A. Cameras (each = color profile + frame + quirks)

Sources: gist.github/hrchu (real camera names), app store, parallaxaview.com

### Vintage 135
| Dazz name | Real camera | Eagles status |
|---|---|---|
| 135 NE | 35mm slide film | ⬜ (slide frame exists, no E6 profile) |
| 135 SR | Lomo Sprocket Rocket (panoramic + sprockets) | ⬜ sprocket frame + wide crop |
| FQS / FQS R | (portra-family kit) | ✅ `fqsr` profile "PORTRA SOFT 400" |
| Classic U | SLR (Leica R3) | 🟡 SLR-ish combo |
| CPM35 | Canon Sure Shot 85 | ✅ `cpm35` profile |
| CT2F | Contax T2 (Zeiss) | 🔶 premium compact profile |
| D Classic | Canon P / Leica M rangefinder | ✅ `dclassic` |
| D Exp | Fujica 35SE (experimental/cross-process) | ✅ `xproc` (added) — rename to D EXP |
| D Fun/DFunS | Kodak Fun Saver disposable | ✅ `dispo` (added) |
| DOS | Fuji Simple Ace disposable | 🟡 same family as dispo |
| D Half | Konica Recorder (half-frame) | 🔶 half-frame crop layout |
| D3D | Nishika N8000 (4-lens stereo 3D) | ⬜ 3D wiggle/parallax + frame |
| Golf | Fujifilm Byu-N16 (16-lens motion) | ✅ GOLF combo |
| GRF | Ricoh GR street camera | 🟡 exists as `grf`? grep — profile added |
| IR | generic point & shoot | 🟡 neutral compact |
| NT16 | Yashica T4 | ✅ `nt16` (added) |

### Inst Collection
| Inst C | Instax Mini 8 | 🔶 polaroid border small version |
| Inst S | Instax SQ (square) | 🔶 square polaroid + border |
| Inst SQC | Instax Mini 90 Neo | 🟡 |

### Vintage 120
| KV88 | Kiev 88 (Soviet 6x6) | 🟡 `s67`-adjacent |
| S 67 | Pentax 67 | ✅ `s67` profile |
| S Classic | TLR (Yashica Mat) | 🔶 |
| HOGA | Pashley | ✅ `hoga` |

### Modern digital / CCD
| Original | classic default | ✅ |
| CCD | Y2K CCD color | 🔶 |
| FXN / FXN2 | FX-N film neg | ✅ `fxnr` |
| FX3-3 | CCD whites (new) | 🔶 |
| Dazz Yellow | warm golden preset | 🟡 golden-like combo exists |
| 07FF | color profile | 🔶 |

### Video cameras (11 total, up to 720p HD, 9:16)
| VHS | 90s camcorder | ✅ VHS shader + passion |
| DCR | 能 camera | 🟡 |
| Original V / PAF R / FQS R / OFM R | video openers | 🔶 |
| 8 mm / 16 mm film | retro film look | ✅ |
| Movie camera / slide projector | ! | 🔶 |
| OFM (film adjustments presets) | save preset groups | 🔶 |

## B. Effects & film treatments
- ✅ Light leaks (random, amount slider) — `light_leak`
- ✅ Film grain / ISO grain — `grain`, `iso_grain`
- ✅ Vignette, dust/scratches, halation — `vignette`, `dust`, `halation`
- ✅ Film burn marks — `film_burn` pass
- ✅ Fisheye — `fisheye`
- ✅ Starburst (star filter) — `starburst`
- ✅ Prism — `prism`
- ✅ ND filter — `nd` (check) 🟡
- ✅ Chromatic aberration — `ca`
- ✅ Bokeh, tilt-shift, anamorphic lens flare, light rays — present
- ✅ Duotone, cross-process (bleach/cross) — `duotone`, `cross`
- ✅ VHS tracking/scanline noise/glitch — `vhs`, `glitch`
- ✅ Dreamy/soft focus, mist, retro fade — `dreamy`, `mist`, `retro`
- 🟡 Color flash filters (red/blue gels) — `flashColor`
- 🔶 Double exposure rebuild (layered photo) — form exists
- ⬜ 3D dual-frame wiggle + frame — NL-3D
- ⬜ Half-frame double-photo crop — 🔶
- ⬜ Sprivy frame (sprocket) — 🔶

## C. Capture tooling (per-camera)
- ✅ Timer 3s/10s
- ✅ Flash auto/on/fill
- ✅ Front/back camera
- ✅ Zoom/multi-lens/ultra-wide
- ✅ Manual ISO + shutter (Pro dial)
- ✅ EV exposure compensation
- ✅ White balance temp/tint
- ✅ Focal presets & crop ratios 1:1, 3:4, 9:16, 16:9, 3:2
- ✅ Stabilization (EIS gyro)
- ✅ Grids (3×3, 5×5, 4×4, golden, center)
- 🟡 Import-based editing flow (gallery→editing)
- 🔶 Graded-eye negative save/reprocess (bleach-skip)

## D. Stamps & frames
- ✅ Date stamps (13 styles incl glow/timer)
- ✅ Data-stamp, film frame overlay
- ✅ Film strip, sprocker frames, polaroid margins
- 🔶 photo frame variants (Inst crop)
- ✅ Timer stamp / auto date on by default (like Dazz default)

## E. UX / Pipeline (non-image)
- ✅ Real-time full preview (GL pipeline)
- ✅ One-tap quick looks (GOLD/GOLF combos)
- ✅ Effet thumbnails (chart render)
- 🟡 Save film adjustments as presets (OFM-style)
- 🟡 Masks, deformations, resolution, FPS, bg color, light-leak randomness per camera
- 🔶 Collage / batch editing / multiple photos import for collage
- 🔶 HSL adjustments panel

## F. Copy mnemonic for engineers
Lookup composites: "Dazz X" ⇒ (base filmProfileId, extraEffectToggle, stampStyle, frame, crop). Keeps parity tracking simple over time in docs/01 roadmap.

## Implementation order (agreed next)
1. ✅ cross-process film profiles (XPROC/DFS/DOM/NT16; fix naming to D EXP / DISPOSABLE / DOM / NT16 MLT)
2. 🔶 D3D stereo pair wiggle-smoothing frame
3. 🔶 Half-frame & sprocket (D HALF, 135 SR)
4. 🔶 Instax borders (Inst C/SQ/SQC) — crop + tone
5. 🔶 Camera import→edit pipeline