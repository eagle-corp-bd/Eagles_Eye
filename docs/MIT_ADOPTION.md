# MIT-Licensed Adoption Plan (live view + editing)

> Sourced from GitHub research on 2026-08-26. Goal: replace/upgrade the custom GL
> pipeline pieces that produce the repeated gallery artifacts with proven MIT
> libraries, while keeping our on-device depth/segmentation (the differentiator).

## Vetted MIT projects

### Live view / camera preview + GL filters
- **MasayukiSuda/CameraRecorder-android** (MIT) — Camera2 + OpenGL ES live filters,
  video recording. `GlFilter` base class extended with GLSL. Primary candidate to
  harden/replace the live preview path.
- **NumericalMax/Effect-Camera** (MIT) — real-time OpenGL ES preview with working
  filters (gray/sepia/edge/overlay). Reference for a correct artifact-free loop.

### Editing (2D layer)
- **burhanrashid52/PhotoEditor** (MIT, ~4.5k★) — draw/text/emoji/sticker/filter/undo.
  Use as the non-destructive 2D editing layer composited over depth/bokeh renders.
- **wasim15185/Foto-Editor** (MIT) — Kotlin/Java/RenderScript, MVVM; crop/rotate/
  adjust/brush/filter/text/sticker/collage. Full-editor reference.
- **bevy/photo-editor-android** (MIT, ~560★) — edit/scale/rotate/draw/transform SDK.

## Licensing caveat (IMPORTANT)
The strong depth/bokeh/segmentation *model* repos are NOT MIT:
- MediaPipe selfie-seg = Apache 2.0
- anilsathyan7/*, DAVID-Hown/Portrait-Segmentation, farmaker47/photos_with_depth,
  dhruv2295/DepthBlur, RohitAwate/Bokehlicious = NO LICENSE (all-rights-reserved)
=> Keep our shipped BiRefNet/SINet models. MIT libs cover live preview + 2D edit UI only.

## Integration plan
1. Add `burhanrashid52:PhotoEditor` (JITPACK) as the 2D editing layer; render our
   depth/bokeh result to a Bitmap and feed it in, then re-composite edits on save.
2. Port the live GL preview to the `CameraRecorder-android` `GlFilter` pattern
   (or use it as the renderer) to eliminate the repeated-artifact class of bugs.
3. Keep FusionPipelineEngine depth/seg + bokeh; treat the MIT libs as the
   presentation/editing shell around them.

## Open question for user
Are the artifacts in (a) the gallery grid of OLD photos (baked-in pre-fix corruption)
or (b) fresh captures / live preview? (a) needs a "re-save via editor" recovery;
(b) needs the live-pipeline rebuild above.
