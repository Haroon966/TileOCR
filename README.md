# PaperPanorama OCR

Android app that captures paper via **guided panorama / multi-frame** scanning, stitches and flattens the page, then runs **English + Urdu OCR** (offline-first).

This repository currently contains:

- Product docs: [`PRD.md`](PRD.md), [`docs/RESEARCH.md`](docs/RESEARCH.md), [`docs/FEATURE_REQUIREMENTS.md`](docs/FEATURE_REQUIREMENTS.md)
- A **Kotlin + Jetpack Compose + CameraX** scaffold (`app/`) with Home → Camera → Review → Result navigation placeholders

## Stack

| Layer | Choice |
|-------|--------|
| UI | Kotlin, Jetpack Compose, Material 3 |
| Camera | CameraX (wired in Phase 1) |
| CV / stitch | OpenCV (`Stitcher::SCANS`) |
| On-device OCR | PaddleOCR via ONNX Runtime |
| Optional quality | Cloud / VLM for hard Nastaliq (opt-in) |

See the PRD for architecture and phases.

## Requirements

- [Android Studio](https://developer.android.com/studio) Ladybug or newer (JDK 17)
- Android SDK 35
- Physical device recommended for camera work (API 26+)

## Open in Android Studio

1. **File → Open** this repository root (`ocr/`).
2. Let Gradle sync (wrapper downloads on first run).
3. Select the `app` run configuration and a device/emulator.
4. Run.

If `local.properties` is missing, Android Studio creates it. A checked local SDK path may already exist for this machine; do not commit secrets.

## Project layout

```text
PRD.md
docs/
  RESEARCH.md
  FEATURE_REQUIREMENTS.md
app/
  src/main/java/com/paperpanorama/ocr/
    MainActivity.kt
    ui/                 # Compose shell + screens
settings.gradle.kts
build.gradle.kts
```

## Current status (scaffold)

- Compose navigation shell with placeholder Camera / Review / Result screens
- Dependencies declared: CameraX, OpenCV, ONNX Runtime
- **Not yet implemented:** live camera, stitching, OCR models, export

Next implementation work is Phase 1 in the PRD (single-shot + basic multi-frame stitch + on-device EN/UR OCR).

## Docs

1. [PRD.md](PRD.md) — product vision and success metrics  
2. [docs/RESEARCH.md](docs/RESEARCH.md) — GitHub repos, papers, models  
3. [docs/FEATURE_REQUIREMENTS.md](docs/FEATURE_REQUIREMENTS.md) — P0/P1/P2 features  
# TileOCR
