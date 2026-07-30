# TileOCR

Android app that captures paper via **guided multi-tile / panorama** scanning, stitches and flattens the page, and keeps scans in a private local library.

**Repo:** [github.com/Haroon966/TileOCR](https://github.com/Haroon966/TileOCR)

## Problem

- A single phone photo of an A4 / legal / notebook page is often too low-res, skewed, or glare-lit.
- Large or dense pages need close-up shots; stitching those tiles into one page is missing from most scanner apps.

## Solution

**TileOCR** is a camera-first Android pipeline:

1. Capture overlapping **tiles** of one page (or a single shot / ML Kit document scan).
2. **Stitch** them into one high-res mosaic (OpenCV document scans mode).
3. Detect edges, warp flat, and enhance for readability.
4. Save pages in a private local library (crop / rotate / rescan).

## Features (current)

- CameraX capture: single-shot and multi-tile modes
- OpenCV document stitch (`SCANS` / affine path) with progress and failure fallback
- Edge detect, corner drag, perspective warp, enhance presets
- Local scan library (save / open / delete)
- Compose UI: Home → Camera → Stitch → Prepare → Page view

## Stack

| Layer | Choice |
|-------|--------|
| UI | Kotlin, Jetpack Compose, Material 3 |
| Camera | CameraX / ML Kit Document Scanner |
| CV / stitch | OpenCV (`Stitcher::SCANS`) |
| Paper mask | U²-Net lite (`u2netp.tflite` in assets) |

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

## License

MIT — see [LICENSE](LICENSE).
