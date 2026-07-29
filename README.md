# TileOCR

Android app that captures paper via **guided multi-tile / panorama** scanning, stitches and flattens the page, then runs **English + Urdu OCR** (offline-first).

**Repo:** [github.com/Haroon966/TileOCR](https://github.com/Haroon966/TileOCR)

## Features (current)

- CameraX capture: single-shot and multi-tile modes
- OpenCV document stitch (`SCANS` / affine path) with progress and failure fallback
- Edge detect, corner drag, perspective warp, enhance presets
- Local scan library (save / open / delete)
- Compose UI: Home → Camera → Stitch → Prepare → Result

OCR model wiring (PaddleOCR ONNX) is still in progress — see the PRD phases.

## Stack

| Layer | Choice |
|-------|--------|
| UI | Kotlin, Jetpack Compose, Material 3 |
| Camera | CameraX |
| CV / stitch | OpenCV (`Stitcher::SCANS`) |
| Paper mask | U²-Net lite (`u2netp.tflite` in assets) |
| On-device OCR | PaddleOCR via ONNX Runtime (planned) |
| Optional quality | Cloud / VLM for hard Nastaliq (opt-in) |

## Requirements

- [Android Studio](https://developer.android.com/studio) Ladybug or newer (JDK 17)
- Android SDK 35
- Physical device recommended for camera work (API 26+)

## Quick start

1. **File → Open** this repository root.
2. Let Gradle sync (wrapper downloads on first run).
3. Select the `app` run configuration and a device/emulator.
4. Run.

`local.properties` is gitignored — Android Studio creates it with your SDK path.

## Project layout

```text
PRD.md
docs/
  RESEARCH.md
  FEATURE_REQUIREMENTS.md
app/
  src/main/java/com/paperpanorama/ocr/   # applicationId (legacy package; display name is TileOCR)
settings.gradle.kts
build.gradle.kts
```

## Docs

1. [PRD.md](PRD.md) — product vision and success metrics  
2. [docs/RESEARCH.md](docs/RESEARCH.md) — repos, papers, models  
3. [docs/FEATURE_REQUIREMENTS.md](docs/FEATURE_REQUIREMENTS.md) — P0/P1/P2 features  

## License

MIT — see [LICENSE](LICENSE).
