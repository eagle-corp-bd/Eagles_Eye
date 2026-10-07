# MOTION_AUDIT — EaglesEye (final: sketch 1:1 + total fluid-motion overhaul)

Generated during the total fluid-motion overhaul. Counts = animation-related call
sites (`tween(`, `spring(`, `AnimatedVisibility`, `animate*AsState`, `Animatable`,
`rememberInfiniteTransition`) per file. Many `tween(`/`spring(` hits are token
call sites — `tween(EagleMotion.Fast, easing = EagleMotion.Decelerate)` — i.e.
they consume the Motion.kt token system; raw magic numbers are called out below.

## Inventory

| File | Sites | Status |
|---|---:|---|
| GalleryScreen.kt | 61 | **Converted** — header/pill entrances, bento `AnimatedItem` stagger + press/check springs, select-mode press scale + border color anim, favorite-badge `springVisibility`, `launchWithFade` activity transitions, fly-to-gallery offset spring (documented) |
| Motion.kt | 34 | **Token home** — EagleMotion durations/easings/springs + em* transition factories (`emMorph`, `emPopIn/Out`, `emSheetIn/Out`, `emFadeSlideIn/Out`, `emExpandRow/CollapseRow`), `springVisibility`, `emStaggerIn`, `rememberReduceMotion` |
| AnalogSheet.kt | 24 | **Converted** — sheet tokens (`EagleMotion.Panel`), panel alpha → `tween(EagleMotion.Fast)`, visibility blocks retuned to em* specs |
| CameraScreen.kt | 17+ | **Converted** — focus-frame, letterbox morph, teach-label, star menu, PRO sheet, scrim, timer countdown + burst counter `AnimatedContent(emMorph)`, grid fade/crossfade, lens-switch freeze overlay, cold-start/black-return fade, fly-to-corner capture motion, zoom/video-pill/histogram/share-chip entrances (`emFadeSlideIn/Out`) |
| GlassControls.kt | — | **Converted** — `GlassCircle` active disc fades (`animateFloatAsState` + layer alpha); `pressScale` on all glass buttons |
| ZoomPill.kt | ~6 | **Converted** — expand/collapse fraction + press scale (`Panel`/`Press`), width/height `lerp` morph; pill entrance now animated at call site |
| ProPanel.kt | 7 | Retuned at call site (`emSheetIn/Out`); internal dials keep drag-driven springs |
| ProDial.kt | 7 | Deferred — drag-release settle (candidate: `EagleMotion.FlingSettle`) |
| Shutter.kt | 5 | **Converted** — `EagleMotion.Press`; spin/pulse remain linear-infinite by design |
| TickWheel.kt | 3 | Deferred — tick inertia (candidate: `EagleMotion.FlingSettle`) |
| TopControls.kt | 0 (uses em*) | **Converted** — row expand/collapse, staggered icon entrances |
| ModePill.kt | 0 (uses em*) | **Converted** — `AnimatedContent` morph + per-segment selection anim (`Snap`/`Press`/`Standard` tokens) |
| StarMenuPanel.kt | 0 | **Converted** — pop in/out from call site |

Converted surfaces draw all timing from `EagleMotion` (Motion.kt): durations
Instant90/Fast140/Base240/Slow380/Sheet460/Hero600, easings
Overshoot/Decelerate/Accelerate/Standard, springs Press0.70/Snap0.62/Panel0.80/
Soft0.85/FlingSettle0.90, stagger 30 ms, `rememberReduceMotion()` honored by
`springVisibility`/`emStaggerIn`.

## Conflicts resolved (documented decisions)

- "Bottom bar fixed at 4 buttons" (old motion prompt) superseded by the newer
  sketch instruction → **3 controls** (gallery · shutter · menu); PRO moved to
  the long-pressed PHOTO/VIDEO mode row.
- Resting view is stripped to the sketch: feature dots, HDR chip, relight pill
  removed (state surfaces in the star-menu badge + teach labels); depth readout
  gated behind `AppSettings.debugPipeline`.
- Zoom pill hides at exactly 1× and retires 4 s after the last pinch.
- Letterbox bands are Compose-side (blurred live scene under the fit box); GL
  capture/processing path untouched (no pipeline-order, exposure or binding
  changes — see capture paths in CameraScreen.onShutter).
- Grid overlay uses `animateFloatAsState` + conditional mount rather than
  `AnimatedVisibility` (scope-extension ambiguity inside the fit Box).
- Favorite badge uses `Modifier.springVisibility` rather than `AnimatedVisibility`
  (same ambiguity inside the media-cell Box).
- Camera ↔ gallery stays an in-Compose swap: gallery browsing must pause the
  camera (privacy/battery), and re-entry replays the cold-start black dissolve
  so the return is a fade, not a black-to-image cut.
- M3 `AlertDialog` (delete confirm) keeps Material's built-in scale+fade dialog
  animation.
- Pre-existing 30 ms white capture blink retained (functional spec).

## Performance notes

- Scene backdrop poll: one `glReadPixels` every 900 ms, downscale to 720 px
  long-side on Dispatchers.Default; timeout-guarded while the GL surface is
  paused (gallery/editor open).
- Letterbox/aspect morph animates one small subtree (`BoxWithConstraints` fit
  box); overlay reads use `graphicsLayer {}` (deferred state) — no measure-phase
  invalidation from press springs.
- Backdrop blur is a single `RenderEffect` on a static-ish Image layer; no
  full-screen blur over the sharp viewfinder.
- Visibility animations prefer modifier-based `springVisibility` or layer-phase
  alpha over composition-removal where ambiguity/measure cost mattered.

## Deferred (documented, not blocking)

- Aspect-glyph morph internals (ratio switch already crossfades via glass row).
- ProDial/TickWheel inertia settle; PRO slider internals.
- GL-side HUD/zebra/exposure intensity ramps (functional, not UI chrome).
- Editor finish-side transition (start-side: `overrideActivityTransition` fade).

## Build

- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (APK 352 MB,
  `app/build/outputs/apk/debug/app-debug.apk`).
- compileSdk 37 (Prismal minCompileSdk) + forced `androidx.core 1.15.0`
  (Prismal's core-ktx 1.19.0 hard-pin requires AGP 9.1).
