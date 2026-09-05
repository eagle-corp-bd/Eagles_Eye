# Eagles Eye 
<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/license-Proprietary-red" />
  <img src="https://img.shields.io/badge/status-Coming%20Soon-yellow" />
  <img src="https://img.shields.io/badge/shaders-AGSL-orange" />
  <img src="https://img.shields.io/badge/AI-on--device-blueviolet" />
  <img src="https://img.shields.io/badge/API-26%2B-brightgreen" />
</p>


**A pro camera and film-emulation studio, built entirely on-device.**

EaglesEye is an Android camera and photo-editing app that brings together manual pro camera controls, real-time film emulation, GPU shader effects, and on-device AI depth/bokeh into a single all-in-one workflow — from capture to final edit, without leaving the app.

> **Status:** Coming soon — not yet published.

---

## What it does

EaglesEye works as a full capture-to-edit pipeline:

- **Shoot** with manual pro controls (ISO, shutter speed, EV, white balance, focus) alongside photo, video, hyperlapse, slow-mo, and panorama modes.
- **Emulate film** in real time with analog presets — grain, light leaks, vignette, tint — layered live in the viewfinder.
- **Apply GPU shader effects** — VHS, glitch, dust, bloom, duotone, and more — rendered with custom AGSL fragments.
- **Get real depth** with an on-device AI bokeh pipeline that blends multiple depth/segmentation models for natural, lens-like portrait blur on any subject.
- **Edit afterward** in a built-in gallery editor with curves, HSL, split toning, healing, structure, and relighting tools.

All processing — shader effects, depth estimation, film rendering — runs entirely on the device. No cloud processing, no external servers.

## Key features

- Photo, video, hyperlapse, slow-mo (60fps), panorama capture
- Manual pro controls: ISO, shutter speed, EV, white balance, focus diopters
- Film stock emulation with grain, light leaks, vignette, and tint
- 60+ AGSL shader effects: VHS, glitch, dust, bloom, color grading, duotone
- Real-time AI depth/bokeh pipeline, blending multiple depth and segmentation models
- HDR bracketing and night mode stacking
- Live relight preview with adjustable light position and color
- Full gallery: editing, favorites, trash, search, sort
- Shake-to-undo and configurable feature flags

## Tech stack

| | |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Camera | AndroidX CameraX, Camera2 API |
| Rendering | AGSL (Android Graphics Shading Language) |
| On-device AI | Multiple depth/segmentation models for portrait bokeh |
| Image loading | Coil |

## Design

EaglesEye uses a dark, studio-inspired visual language with a user-selectable accent palette (Gold, Rose, Teal, Violet, Green), glass-panel UI elements, and pill-shaped controls — designed to feel like a professional imaging tool rather than a typical camera app.

## Third-party technology & attribution

EaglesEye's on-device depth and portrait segmentation pipeline is powered by several openly licensed models, run fully on-device:

- **BiRefNet** – high-accuracy depth/segmentation model — MIT License
- **MiDaS** (small, 256, fp16) – lightweight real-time depth model — MIT License
- **SiNet** – secondary segmentation model — MIT License
- **ZipDepth** (384×384) – depth estimation model — MIT License

The app UI also uses the **DSEG7 Classic** font (`DSEG7Classic-Bold.ttf`) for seven-segment style displays, licensed under the **SIL Open Font License 1.1** (see `DSEG-LICENSE.txt`).

Each project above is used in compliance with its own license. Source repositories and full license terms can be found by searching the project name.

## License

This repository is made public for **viewing purposes only**. The source code is proprietary and closed-source — no use, copying, modification, or redistribution is permitted. See [LICENSE](./LICENSE) for full terms.

The compiled app, once published, may be downloaded and used as-is under the terms described in the license.

---

© 2026 Izan Afrid Zim, Eagle Corp BD. All rights reserved.

