# EaglesEye — Product & Engineering Roadmap

> Living document. Organized for *usefulness and prioritization*, not line count.
> Scope: the EaglesEye Android camera app (Kotlin + Jetpack Compose + OpenGL ES + AGSL
> on-device ML for depth/segmentation/relight). This plan captures concrete, user-facing
> improvements across capture → edit → gallery → share, plus the reliability, performance,
> accessibility and testing work needed to make them solid.
>
> Guiding skills referenced: `design-system`, `kotlin-patterns`, `kotlin-coroutines-flows`,
> `android-clean-architecture`, `frontend-a11y`, `accessibility`, `motion-patterns`,
> `make-interfaces-feel-better`, `ai-regression-testing`, `error-handling`,
> `compose-multiplatform-patterns`, `coding-standards`.

---

## 0. Status & non-negotiables

- **minSdk 33, targetSdk 35, compileSdk 36.** AGSL APIs are used; `RuntimeShader.setInputBitmap`
  does NOT exist at compileSdk 36, so all AGSL input must go through `setInputShader` +
  `BitmapShader`. Keep this invariant in every shader change.
- **MIT/permissive OSS only.** No GPL dependencies (rules out some ML runtimes / UI libs).
- **Gallery is a display passthrough** (Coil `AsyncImage` decodes the saved JPEG). Image
  corruption is *baked into pre-fix saved files*; new captures are clean. The gallery grid
  layout must NOT auto-rearrange on open.
- **Builds run without asking.** CI/assemble must stay green.

## 1. P0 — Correctness fixes (do first, everything depends on these)

### 1.1 Portrait bokeh keeps the SUBJECT sharp  ✅ partially done
- **Root cause fixed:** the segmentation mask pushes the subject toward the LARGER end of the
  depth range, but auto-focus used the global `lastDepthMin`, so focus landed on the
  background and the subject blurred. Now `currentFocusDepth()` anchors to the **median depth
  of the masked subject pixels** (`subjectFocusDepth`), making the subject sharp regardless of
  depth convention. (FusionPipelineEngine.kt)
- **Remaining (this sprint):**
  - Add a shader-side subject *guarantee*: sample the segmentation mask in `FUSE_FRAG` and
    force `blur = 0` where foreground confidence is high, so hair-thin edges and partially
    masked subjects never blur. Requires uploading the subject mask as an R8 texture aligned
    to the upsampled depth (mirror `uploadDepthTexture`/`blitDepthUpsample`).
  - **Textural consistency:** blurred regions currently lose micro-contrast. Add a
    high-frequency preserve term — blend the laplacian of the sharp image into the blurred
    result weighted by `(1 - coc)`, so bokeh reads like a real lens, not a smear.
  - **Smooth falloff:** exponential falloff already exists (`uExpK`); expose it as a user
    "Falloff" slider (1–6) and default to a natural ~2.5.

### 1.2 Relight frame correctness
- Verify relight frame is upright (the `rgbBytesToBitmap` row-flip handles GL bottom-up) and
  not stretched (full-surface `setScale` stretch is in place). Add a regression snapshot test.
- Add an exposure/intensity clamp so over-bright relight doesn't clip to white.

### 1.3 Export must never write junk
- `isJunkBitmap()` already guards GL readback. Extend it to also reject all-black, all-white,
  and single-color frames, and surface a toast "Save failed — try again" (already present).
- Ensure the editor's `renderAndSave` falls back to the CPU path on any GL failure instead of
  returning null silently.

### 1.4 Gallery decode failures are graceful
- `AsyncImage` already has `error`/`placeholder`. Add an explicit "Can't preview" state with a
  retry and a "Re-save via editor" action for pre-fix corrupt files (fixes the baked-in
  corruption for the user without re-capturing).

## 2. P1 — Capture experience

### 2.1 Mode system (clean, extensible)
- Replace ad-hoc effect toggles with a typed `CaptureMode` sealed class (Photo, Portrait,
  Night, Pro, Hyperlapse, Live-Photo, Burst, RAW/DNG) driven by a single `CaptureViewModel`.
- Each mode exposes its own parameter sheet (bottom sheet) reusing `design-system` components.

### 2.2 Pro controls
- Manual focus (already tap-to-focus; add a focus-distance slider + focus-peaking overlay),
  ISO/shutter/ EV, white-balance presets + fine tint, exposure bracketing (3/5 shots),
  lens selection (wide/tele where available).

### 2.3 Night & low-light
- Multi-frame fusion (align + average) with noise reduction; show a "processing" state with
  progress. Reuse the existing GL pipeline; keep a single-frame fallback.

### 2.4 Live Photo / motion
- Capture a short HEVC clip alongside the still; gallery "Live" tab already hosts these. Add
  playback in the lightbox (the `VideoPlayerPage` can play them) and a "Make key photo"
  picker.

### 2.5 Burst & Best-Take
- Burst capture with on-device "best shot" scoring (sharpness + face/eye-open + exposure);
  present a carousel to pick/keep.

## 3. P2 — Editing (depth-aware, the differentiator)

### 3.1 Portrait / bokeh editor
- Re-expose the live bokeh in the editor: adjustable aperture (shape + blades), focus point
  re-pick, falloff, foreground blur toggle, and a **subject/background separate adjustments**
  path (brighten subject, desaturate background, etc.) using the stored depth/seg maps.

### 3.2 Relight studio
- Multiple light types (key/fill/rim already in `STUDIO_FRAG`); add editable position (drag
  the orb), color temperature, and presets (studio, golden hour, noir). Save light setups as
  reusable recipes.

### 3.3 Local / mask adjustments
- Brush + auto-subject/auto-sky masks (segmentation already available) for exposure, color,
  sharpness, blur. Add a mask preview toggle and feather/opacity.

### 3.4 Healing / object removal
- `EditorEngine` already has HEAL. Add a content-aware mode (GL inpaint or patch-match on CPU
  for small spots) and a "remove tourists" batch that picks the cleanest frame from burst.

### 3.5 Film / LUT engine
- User-importable LUTs (.cube), built-in film emulations, and a tone-curve editor (the
  `curves` infra exists). Add a before/after swipe compare.

### 3.6 Frames, text, stickers
- Expand `FrameType` (already has classic/instax); add adjustable borders, watermarks,
  date-stamp styles (already has `StampLcd`), and a small set of tasteful stickers/text with
  font picker.

## 4. P3 — Gallery & library

### 4.1 Sections (shipped)
- Photos / Edits / Live / Trash / Settings pill is in. Next: a **search** that indexes capture
  metadata (date, lens, mode) and an **albums** auto-group by date/place (place via
  EXIF GPS when present).

### 4.2 Viewer
- Already has zoom/pan + video. Add: pinch-to-zoom refine, quick "edit"/"variants" (show the
  original + its edited copies side by side), info sheet (EXIF: ISO, shutter, lens, depth on/off).

### 4.3 Bulk & organization
- Multi-select already exists: add "add to album", "export originals", "auto-cache for
  offline", and a safe "free up space" (keep originals in cloud/trash) flow.

### 4.4 Sharing
- Share sheet with size/format choices (original, edited, compressed, video clip), and
  "share as story" (multiple → a quick reel/contact sheet).

## 5. Performance & reliability

### 5.1 GL pipeline hardening
- Centralize all shader compiles in one `createProgram` with cached handles; add a
  "degraded mode" that disables the heaviest pass (bokeh/relight) if frame time > budget for
  N consecutive frames (the `SLOW frame` log already exists — turn it into a governor).
- Reuse FBOs/ping-pong textures; eliminate per-frame allocations in `runLivePipeline`.

### 5.2 Memory
- Cap decoded bitmaps in the lightbox (`ZOOM_DECODE_MAX` exists); evict LRU; avoid holding two
  full-res `Bitmap`s (GL + CPU) simultaneously.

### 5.3 On-device ML
- Model warm-up on cold start; quantization check; fallback chain (BiRefNet → SINet → none)
  already partly there — make it explicit with timeouts so a slow model never stalls capture.
- Add a small benchmark (the `bench` skill ideas) to track inference ms across devices.

### 5.4 Crash resilience
- The `ee_crash.log` handler exists. Add a "last good frame" recovery so a GL context loss
  rebuilds state instead of a black screen, and a watchdog that resets the pipeline after K
  consecutive GL errors.

## 6. UX, motion & accessibility

### 6.1 Motion (from `motion-patterns`, `make-interfaces-feel-better`)
- Standardize spring presets; add meaningful motion only (sheet expand, pill morph, lightbox
  dismiss drag already present). Remove gratuitous animation; respect `reducedMotion`.
- Haptics consistency (already gated by `AppSettings.haptics`).

### 6.2 Accessibility (`frontend-a11y`, `accessibility`)
- Content descriptions on every tappable surface (capture button, mode chips, gallery cells,
  lightbox controls). Minimum touch target 48dp.
- High-contrast / large-text support; ensure `G_GOLD` text meets contrast on dark bg.
- TalkBack pass on the full capture→edit→gallery flow; add a "describe photo" using on-device
  ML labels (optional, opt-in).

### 6.3 Design system (`design-system`)
- Extract all surfaces (glass, pill, sheet, button, slider, toggle) into reusable
  `EagleSurface`/`EagleButton`/`EagleSlider` composables with tokens; kill one-off styling.
- Theming: light/dark/true-black; accent already supports `AppAccent`.

## 7. Settings & onboarding

- Group settings into sections (Capture, Portrait, Editor, Gallery, Privacy, About/Licenses).
- First-run onboarding: permission rationale, "what the AI does on-device" privacy note,
  quick tutorial for portrait/relight.
- Privacy: explicit "processing stays on device" statement; optional analytics opt-in (none
  by default).

## 8. Testing & regression (`ai-regression-testing`, `coding-standards`)

- **Visual regression:** capture fixed scenes (chart, portrait, low-light) through the GL
  pipeline and diff against baselines in CI; flag bokeh/relight regressions.
- **Unit:** depth/seg fusion math, `GlPixel` RGBA↔ARGB round-trip, `combineAndNormalize`
  invariants, `isJunkBitmap`.
- **Integration:** a headless "render N frames, assert no junk + subject sharp" check.
- **Manual matrix:** a device list (API 33/34/35) × (with/without AGSL) smoke checklist.

## 9. Phasing summary

| Phase | Theme | Exit criteria |
|-------|-------|---------------|
| P0 | Correctness | Subject always sharp in bokeh; relight upright; no junk exports; graceful decode |
| P1 | Capture | Mode system + pro/night/live/burst solid and tested |
| P2 | Editing | Depth-aware portrait/relight/local edits + LUTs + healing |
| P3 | Gallery/Library | Albums/search/bulk/share; polished viewer |
| — | Cross-cutting | Perf governor, a11y pass, design-system extraction, regression suite |

## 10. Open questions (resolve with user before building P2+)
- Do we need cloud sync/account, or stay purely on-device + local?
- Target device range — flagship-only AGSL features, or must we support a CPU fallback for
  all effects on low-end API-33 devices?
- Licensing for any film/LUT packs we may bundle (keep MIT/permissive).
