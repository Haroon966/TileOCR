# Product Requirements Document — TileOCR

**Product name:** TileOCR  

**Platform:** Android (phone / tablet)  
**Primary languages:** English, Urdu (RTL; Naskh and Nastaliq where possible)  
**Version:** 1.0 (PRD)  
**Status:** Approved for scaffolding / implementation  

---

## 1. Vision

Build an Android app that turns a phone camera into a **high-quality paper digitizer**: users capture a document via **guided multi-frame / panorama scanning**, the app stitches and flattens the page to maximum readable resolution, then runs **OCR for English and Urdu**, and lets users edit and export the text.

A single distant photo is not enough for large or dense pages. The product’s core differentiator is **capture quality from the camera pipeline**—overlap guidance, stitching, warp, and enhancement—so OCR receives the best possible image, on-device first, with an optional cloud vision path for hard Nastaliq pages.

---

## 2. Problem

- Phone photos of A4 / legal / notebook pages are often blurry, skewed, glare-lit, or too low in effective DPI for reliable OCR.
- Urdu (especially Nastaliq) is poorly handled by generic Latin OCR engines; bilingual English + Urdu pages are common in Pakistan and diaspora workflows.
- Existing scanners often skip true multi-shot stitching or force cloud-only OCR without offline fallback.

---

## 3. Goals

| ID | Goal |
|----|------|
| G1 | Capture high-resolution document images via single-shot **and** guided panorama / multi-frame stitch |
| G2 | Detect document bounds, correct perspective, deskew, and enhance for OCR |
| G3 | Recognize **English** and **Urdu** text (including mixed pages) with editable output |
| G4 | Work **offline-first**; optional cloud/VLM only when the user opts in |
| G5 | Export and share results (TXT, PDF with image + text, system share sheet) |
| G6 | Keep all local scans private on device by default |

### Non-goals (v1)

- iOS / cross-platform Flutter rewrite
- Full DMS / cloud sync / accounts
- Handwriting OCR as a primary guarantee (may degrade gracefully)
- Real-time AR translation overlay
- Play Store listing / paid billing

---

## 4. Personas

1. **Student** — photographs textbooks / notes (EN or UR) for searchable text and study notes.  
2. **Office / admin worker** — digitizes forms, letters, notices; needs clean PDF + copyable text.  
3. **Researcher / journalist** — scans Urdu newspapers or bilingual documents; needs best possible CER on dense print.

---

## 5. Success metrics

| Metric | Target (MVP) | Target (v1.0) |
|--------|--------------|---------------|
| Guided stitch success (clear overlap, steady hand) | ≥ 85% | ≥ 95% |
| English printed CER (clean page, good light) | ≤ 5% | ≤ 2% |
| Urdu printed CER (Naskh / clear print, on-device) | ≤ 15% | ≤ 8% |
| Urdu hard Nastaliq (with optional VLM) | usable draft | CER competitive with best available model |
| End-to-end time (capture → OCR, mid-range phone) | ≤ 45s typical | ≤ 25s typical |
| Offline path available without network | Yes | Yes |
| Crash-free sessions | ≥ 99% | ≥ 99.5% |

---

## 6. User journey (happy path)

1. User opens app → **Home** (library of past scans + “New scan”).  
2. Grants **camera** permission if needed.  
3. Chooses **Single shot** or **Panorama / multi-frame**.  
4. **Camera:** frames the page; app shows grid, focus, flash, stability / blur / glare hints.  
5. In panorama mode: guided overlap indicator; auto or tap capture of successive tiles.  
6. App **stitches** frames → shows mosaic preview.  
7. **Review:** auto edge detection; user can drag corners → confirm crop / warp.  
8. Optional **enhance** filter (auto / contrast / B&W).  
9. User selects language: **English / Urdu / Auto**.  
10. **OCR** runs on-device (PaddleOCR ONNX); optional “Enhance with cloud OCR” if enabled.  
11. **Result:** editable text (RTL for Urdu), confidence highlights, copy / share / export TXT or PDF.  
12. Scan saved to local library.

```text
CameraX → overlap frames → OpenCV SCANS stitch → edge + warp → enhance
    → PaddleOCR (on-device) → [optional VLM] → edit → export
```

---

## 7. Functional scope (summary)

See [docs/FEATURE_REQUIREMENTS.md](docs/FEATURE_REQUIREMENTS.md) for P0/P1/P2 detail.

**Must work end-to-end from camera (P0):** permissions, capture modes, stitch, warp, enhance, EN+UR OCR offline, edit, export, local library.

---

## 8. Technical architecture

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Language / UI | Kotlin + Jetpack Compose | Native Android, modern UI |
| Camera | CameraX | Lifecycle-safe capture, high-res stills |
| CV / stitch / warp | OpenCV (Android / NDK) | `Stitcher::SCANS`, perspective transform |
| On-device OCR | PaddleOCR via ONNX Runtime | Multilingual path; Arabic-script baseline for Urdu |
| Optional cloud | Vision LLM / Urdu VLM (e.g. Qaari-class) | Max quality on hard Nastaliq when online |
| Storage | App-private files + Room (metadata) | Privacy; no account required |
| Min / target SDK | minSdk 26, targetSdk 35 | Broad devices; modern permissions |

### Privacy & permissions

- `CAMERA` required for capture.  
- Media / storage only as needed to save/export (prefer MediaStore / SAF share).  
- No analytics by default in MVP.  
- Cloud OCR: explicit opt-in; images sent only for that request; offline remains default.

---

## 9. Release phases

| Phase | Deliverable |
|-------|-------------|
| **Phase 0 (this repo)** | PRD, research, feature reqs, Android Compose scaffold |
| **Phase 1 — MVP** | Single-shot + basic multi-frame stitch, warp, on-device EN OCR, UR baseline, TXT export |
| **Phase 2** | Full guided panorama UX, quality gates (blur/glare), PDF export, library polish, Auto language |
| **Phase 3** | Urdu model fine-tune / specialized rec, optional cloud VLM, mixed-layout improvements |

---

## 10. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| OpenCV stitch fails on texture-poor paper | Overlap UI, sequential stitch, fallback to best single frame |
| Urdu Nastaliq CER poor on generic Arabic models | Dedicated Urdu models (UTRNet / fine-tuned Paddle); optional VLM |
| Large panoramas OOM on low-RAM devices | Downscale for match, full-res warp per tile, sequential stitch |
| Model APK size | Download language packs on demand |
| Glare / shadows | Capture hints + CLAHE / adaptive enhance |

---

## 11. Open decisions (implementation, not product blockers)

- Exact Urdu on-device model artifact (Paddle Arabic fine-tune vs UTRNet mobile export).  
- Cloud provider for optional VLM (self-hosted vs third-party API).  
- Package / applicationId rename from `com.paperpanorama.ocr` → `com.tileocr.app` (optional).

---

## 12. References

- Research catalog: [docs/RESEARCH.md](docs/RESEARCH.md)  
- Feature requirements: [docs/FEATURE_REQUIREMENTS.md](docs/FEATURE_REQUIREMENTS.md)  
- Android app module: [`app/`](app/)
