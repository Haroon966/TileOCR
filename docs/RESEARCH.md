# Research — TileOCR

Curated resources for an Android paper scanner with **panorama / multi-frame stitching** and **English + Urdu OCR**.

**Order in this document:** GitHub repositories first, then Hugging Face / models, then papers, then datasets, then technical docs & articles.

---

## 1. GitHub repositories

### 1.1 Android document scanners & OCR apps

| Repo | Why it matters |
|------|----------------|
| [https://github.com/pynicolas/FairScan](https://github.com/pynicolas/FairScan) | Mature privacy-first Android scanner: CameraX, OpenCV warp/enhance, LiteRT segmentation, Tesseract OCR, Compose UI. Strong reference for capture → PDF flow. |
| [https://github.com/egdels/makeacopy](https://github.com/egdels/makeacopy) | Offline Android scanner with **PaddleOCR** (ONNX) and legacy Tesseract flavors; corner detection model; PDF export. Closest stack match for on-device Paddle. |
| [https://github.com/Azyrn/Scanly](https://github.com/Azyrn/Scanly) | Offline-first Compose app; **PP-OCRv6** via ONNX; downloadable language packs including **Arabic**; optional AI providers. Useful for multilingual pack UX. |
| [https://github.com/androidok/Document-Scanner](https://github.com/androidok/Document-Scanner) | Kotlin + CameraX + OpenCV edge detect + ML Kit OCR + PDF. Clean module layout for crop/editor/OCR. |
| [https://github.com/ujjawal200/Document-Scanner](https://github.com/ujjawal200/Document-Scanner) | Same family of CameraX / OpenCV / ML Kit document scanner patterns. |
| [https://github.com/st235/HSE.CVforMobileDevices.DocumentsScanner](https://github.com/st235/HSE.CVforMobileDevices.DocumentsScanner) | **Document multi-image stitching** with SIFT + FlannBasedMatcher (C++/OpenCV), then corners + filters. Primary open reference for stitch-before-OCR. |
| [https://github.com/MatiwosKebede/openscanvision](https://github.com/MatiwosKebede/openscanvision) | Offline Android CV library: perspective / template alignment with CameraX + OpenCV; modular core without UI. |

### 1.2 OCR engines & mobile deployment

| Repo | Why it matters |
|------|----------------|
| [https://github.com/PaddlePaddle/PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) | Primary on-device OCR engine family (det + rec + orientation). Multilingual models; Arabic alphabet rec as Urdu starting point. |
| [https://github.com/Ansarimajid/PaddleOCR-AndroidDemo](https://github.com/Ansarimajid/PaddleOCR-AndroidDemo) | On-device PaddleLite Android demo: camera + gallery + det/cls/rec pipeline. |
| [https://github.com/tesseract-ocr/tesseract](https://github.com/tesseract-ocr/tesseract) | Classic OCR; Urdu pack exists but Nastaliq is weak—fallback / comparison only. |
| [https://github.com/adaptech-cz/Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) | Android packaging of Tesseract (used by MakeACopy standard flavor). |
| [https://github.com/microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | ONNX Runtime — preferred mobile inference for exported PaddleOCR models. |
| [https://github.com/opencv/opencv](https://github.com/opencv/opencv) | Stitcher (`PANORAMA` / `SCANS`), warp, CLAHE, deskew building blocks. |

### 1.3 Urdu / Arabic-script OCR

| Repo | Why it matters |
|------|----------------|
| [https://github.com/abdur75648/UTRNet-High-Resolution-Urdu-Text-Recognition](https://github.com/abdur75648/UTRNet-High-Resolution-Urdu-Text-Recognition) | **UTRNet** (ICDAR 2023) — SOTA-oriented printed Urdu recognition; datasets + models + demo tooling. |
| [https://github.com/abdur75648/urdu-text-detection](https://github.com/abdur75648/urdu-text-detection) | ContourNet-based Urdu text detection companion to UTRNet. |
| [https://github.com/paper-seven/UrduOCR](https://github.com/paper-seven/UrduOCR) | End-to-end Urdu newspaper pipeline: layout, super-resolution, LLM recognition; released models/datasets. |
| [https://github.com/PaddlePaddle/PaddleOCR/discussions/10581](https://github.com/PaddlePaddle/PaddleOCR/discussions/10581) | Community notes on **fine-tuning Arabic PaddleOCR for Urdu** (dictionary / RTL caveats). |

### 1.4 Camera / Compose references (Android platform samples)

| Repo | Why it matters |
|------|----------------|
| [https://github.com/android/camera-samples](https://github.com/android/camera-samples) | Official CameraX samples (high-res capture, preview). |
| [https://github.com/android/compose-samples](https://github.com/android/compose-samples) | Compose navigation / Material patterns for app shell. |

---

## 2. Hugging Face & model hubs

| Resource | Why it matters |
|----------|----------------|
| [https://huggingface.co/oddadmix/Qaari-0.1-Urdu-OCR-VL-2B-Instruct](https://huggingface.co/oddadmix/Qaari-0.1-Urdu-OCR-VL-2B-Instruct) | Fine-tuned Qwen2-VL 2B for Urdu OCR; strong WER vs Tesseract—candidate for **optional cloud / on-server VLM**. |
| [https://huggingface.co/PaddlePaddle/arabic_PP-OCRv3_mobile_rec](https://huggingface.co/PaddlePaddle/arabic_PP-OCRv3_mobile_rec) | Arabic-alphabet mobile recognition model; baseline for Urdu before fine-tune. |
| [https://huggingface.co/PaddlePaddle](https://huggingface.co/PaddlePaddle) | PP-OCR det/rec ONNX exports for mobile (check latest PP-OCRv5/v6 mobile assets). |
| PaddleOCR Android deployment docs | [https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/cross_platform/android_deployment.html](https://www.paddleocr.ai/latest/en/version3.x/inference_deployment/cross_platform/android_deployment.html) — ONNX Runtime Android SDK pattern. |

---

## 3. Papers

| Paper | Link | Relevance |
|-------|------|-----------|
| **UTRNet: High-Resolution Urdu Text Recognition In Printed Documents** (ICDAR 2023) | [arXiv:2306.15782](https://arxiv.org/abs/2306.15782) · [HTML](https://ar5iv.labs.arxiv.org/html/2306.15782) | Core Urdu printed-line recognition architecture; UTRSet datasets; end-to-end tool. |
| **From Press to Pixels: Evolving Urdu Text Recognition** | [arXiv:2505.13943](https://arxiv.org/abs/2505.13943) · [HTML](https://arxiv.org/html/2505.13943v2) | Newspaper Nastaliq pipeline; compares Tesseract/EasyOCR/Kraken/UTRNet vs LLMs; SR + layout. |
| **Adapting Tesseract for Complex Scripts** (Urdu Nastaliq) | [IEEE DAS / ACM](https://dl.acm.org/doi/10.1145/2505370.2505371) (related DAS work) | Shows classical engine limits on Nastaliq—justifies DL / VLM path. |
| **Automatic Sequential Stitching of High-Resolution Panorama for Android** | [Sensors 2023, 23(2), 879](https://www.mdpi.com/1424-8220/23/2/879) | Mobile sequential stitch, orientation sensor, precapture feature detection—memory-friendly panorama. |
| OpenCV stitching tutorial / Stitcher modes | [OpenCV Stitcher tutorial](https://docs.opencv.org/4.x/d8/d19/tutorial_stitcher.html) | Use **`SCANS` / affine** mode for document pages, not photo `PANORAMA`. |

---

## 4. Datasets (Urdu / document)

| Dataset / source | Notes |
|------------------|-------|
| **UTRSet-Real / UTRSet-Synth** (via UTRNet project) | Large printed Urdu line sets; project page: [abdur75648.github.io/UTRNet](https://abdur75648.github.io/UTRNet/) |
| **UrduDoc** | Urdu text-line detection in scanned docs (UTRNet companion). |
| Newspaper / Nastaliq sets from *From Press to Pixels* | Via [paper-seven/UrduOCR](https://github.com/paper-seven/UrduOCR) releases. |
| IIITH Urdu OCR datasets | Historical benchmarks; UTRNet paper notes GT corrections. |
| Synthetic Urdu corpora on HF (e.g. large Urdu OCR image–text sets) | Useful for fine-tuning mobile rec models when real pages are scarce. |

---

## 5. Technical docs & articles

| Resource | Link | Use |
|----------|------|-----|
| OpenCV high-level stitching API | [docs.opencv.org — Stitcher](https://docs.opencv.org/4.x/d8/d19/tutorial_stitcher.html) | Configure `SCANS` for flat documents. |
| Document Image Stitching for Mobile Developers | [Medium — Mykola Fiantsev](https://bigmotor.medium.com/document-image-stitching-for-mobile-developers-b169868d023a) | Practical tips: masks, features on low-texture paper, mobile constraints. |
| Android CameraX docs | [developer.android.com/media/camera/camerax](https://developer.android.com/media/camera/camerax) | Capture resolution, ImageCapture, Preview. |
| ML Kit Text Recognition | [developers.google.com/ml-kit/vision/text-recognition](https://developers.google.com/ml-kit/vision/text-recognition) | Fast Latin on-device; **not** sufficient alone for Urdu—comparison / EN-only shortcut. |
| Jetpack Compose | [developer.android.com/compose](https://developer.android.com/compose) | App UI shell. |
| ONNX Runtime Mobile | [onnxruntime.ai](https://onnxruntime.ai/) | Integrate Paddle exports on Android. |

---

## 6. Recommended stack mapping (from research → product)

| Need | Prefer |
|------|--------|
| Camera + UX | CameraX + Compose (FairScan / Document-Scanner patterns) |
| Multi-frame document stitch | OpenCV `Stitcher::SCANS` + ideas from DocLens / Sense-Panorama |
| Edge + warp + enhance | OpenCV (FairScan / MakeACopy) |
| On-device EN + Arabic-script | PaddleOCR ONNX (MakeACopy / Scanly / official Android deploy) |
| Best Urdu printed lines | UTRNet / fine-tuned Paddle Urdu; evaluate export size for mobile |
| Hard Nastaliq / newspapers | Optional VLM (Qaari / LLM pipeline from Press-to-Pixels) |
| Avoid as sole Urdu engine | Stock Tesseract / ML Kit Latin-only |

---

## 7. Gaps to watch during implementation

1. **Arabic ≠ Urdu dictionary** — expect fine-tuning or Urdu-specific charset for characters and ligatures.  
2. **Nastaliq diagonality / stacking** — line detection must be Urdu-aware; generic detectors may fail.  
3. **APK / download size** — ship tiny EN pack; download UR / VLM assets separately.  
4. **Stitch on blank margins** — feature matching fails; guide user to include text/texture overlap.  
5. **Mixed EN+UR layout** — script ID per region then route to Latin vs Arabic/Urdu rec.

---

## 8. How to use this file

- Prototype stitcher → start from OpenCV SCANS + [st235 DocumentsScanner](https://github.com/st235/HSE.CVforMobileDevices.DocumentsScanner).  
- Prototype on-device OCR → [makeacopy](https://github.com/egdels/makeacopy) / Paddle Android ONNX docs.  
- Urdu accuracy roadmap → UTRNet paper + Qaari HF card + Paddle fine-tune discussion #10581.
