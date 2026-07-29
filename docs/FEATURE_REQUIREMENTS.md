# Feature Requirements — TileOCR

Requirements for a **complete camera-to-text** Android product: guided capture, panorama stitch, document CV, English + Urdu OCR, edit, and export.

**Priority legend**

| Priority | Meaning |
|----------|---------|
| **P0** | Must ship for MVP; product is incomplete without it |
| **P1** | Required for “works perfectly” v1.0 |
| **P2** | Differentiator / polish; after P0–P1 stable |

**Status column:** use `Todo` / `In progress` / `Done` during implementation.

---

## 1. Permissions & app shell

| ID | Priority | Feature | Acceptance criteria |
|----|----------|---------|---------------------|
| APP-01 | P0 | App launches to Home with “New scan” and empty/local library | Cold start &lt; 3s on mid-range device |
| APP-02 | P0 | Request `CAMERA` with rationale UI | Denied state shows retry; no crash |
| APP-03 | P0 | Jetpack Compose navigation: Home → Camera → Review → Result | Back stack correct; process death restores or fails gracefully |
| APP-04 | P1 | Dark / light theme follows system | Text readable in both |
| APP-05 | P1 | App language UI: English (+ Urdu UI strings optional) | OCR language independent of UI language |
| APP-06 | P2 | Onboarding (3 screens): panorama tip, lighting tip, EN/UR tip | Skippable; shown once |

---

## 2. Camera capture (100% from camera)

| ID | Priority | Feature | Acceptance criteria |
|----|----------|---------|---------------------|
| CAM-01 | P0 | Live CameraX preview | Full-bleed preview; correct rotation |
| CAM-02 | P0 | High-resolution still capture | Prefer max still size supported; EXIF orientation handled |
| CAM-03 | P0 | Capture shutter (tap) | Haptic/audio optional; image written to app cache |
| CAM-04 | P0 | Flash: Off / On / Auto | Works on devices with flash |
| CAM-05 | P0 | Tap-to-focus / continuous AF | Focus box feedback |
| CAM-06 | P0 | Document grid / alignment overlay | Helps square the page |
| CAM-07 | P0 | **Single-shot mode** | One frame → Review |
| CAM-08 | P0 | **Multi-frame / panorama mode** | User captures ≥2 overlapping tiles of one page |
| CAM-09 | P1 | Guided overlap indicator (arrow / progress / % overlap) | Target ~30–40% overlap between frames |
| CAM-10 | P1 | Stability hint (hold still) using motion sensors | Warns when shake likely |
| CAM-11 | P1 | Torch for dim light | Toggle independent of flash-for-capture where possible |
| CAM-12 | P1 | Switch rear camera; lock to rear for documents | Front camera not required |
| CAM-13 | P2 | Auto-capture when frame is sharp + page detected | User can disable |
| CAM-14 | P2 | Import from gallery (secondary) | Same Review → OCR path as camera |
| CAM-15 | P2 | Multi-page session (several pages → one document) | Page thumbnails; reorder |

---

## 3. Capture quality gates

| ID | Priority | Feature | Acceptance criteria |
|----|----------|---------|---------------------|
| QLT-01 | P1 | Blur detection score after capture | Soft warning + Retake / Keep |
| QLT-02 | P1 | Glare / overexposure heuristic | Soft warning |
| QLT-03 | P1 | Underexposure / shadow heuristic | Soft warning |
| QLT-04 | P2 | Skew angle estimate before accept | Suggest straighten |
| QLT-05 | P2 | Minimum effective DPI / resolution check | Block OCR with clear message if too small |

---

## 4. Stitching (panorama → one page image)

| ID | Priority | Feature | Acceptance criteria |
|----|----------|---------|---------------------|
| STH-01 | P0 | Stitch 2+ frames with OpenCV **SCANS / affine** path | Single mosaic bitmap for Review |
| STH-02 | P0 | Progress UI during stitch | Cancel returns to Camera with frames kept or discarded (defined) |
| STH-03 | P0 | Stitch failure fallback | Offer best single frame or retake |
| STH-04 | P1 | Preview mosaic before crop | Zoom/pan mosaic |
| STH-05 | P1 | Sequential / memory-safe stitch for large pages | No OOM on 4GB devices for typical A4 3–5 tiles |
| STH-06 | P1 | Reorder / delete tile before stitch | User control |
| STH-07 | P2 | Sensor-assisted capture sequencing (Sense-Panorama style) | Auto next frame when moved enough |
| STH-08 | P2 | Seam visualization / blend quality toggle | Advanced setting |

---

## 5. Document CV: edges, warp, enhance

| ID | Priority | Feature | Acceptance criteria |
|----|----------|---------|---------------------|
| DOC-01 | P0 | Auto document quadrilateral detection | Corners overlaid on Review |
| DOC-02 | P0 | Manual corner drag (4 handles) | Snap + undo |
| DOC-03 | P0 | Perspective warp to flat rectangle | Output upright page image |
| DOC-04 | P0 | Rotate 90° / 180° | Before OCR |
| DOC-05 | P1 | Auto deskew fine angle | After warp |
| DOC-06 | P1 | Enhance presets: Original, Auto, Contrast, B&amp;W | Preview live |
| DOC-07 | P1 | CLAHE / adaptive contrast for text | Default Auto uses it |
| DOC-08 | P2 | Shadow removal / background whitening | Optional preset |
| DOC-09 | P2 | Dewarp curved book pages | Best-effort |

---

## 6. OCR engine (English + Urdu)

| ID | Priority | Feature | Acceptance criteria |
|----|----------|---------|---------------------|
| OCR-01 | P0 | On-device OCR via PaddleOCR (ONNX Runtime) | No network required for EN |
| OCR-02 | P0 | Language: **English** | Printed Latin text; copyable UTF-8 |
| OCR-03 | P0 | Language: **Urdu** | RTL display; Arabic-script output |
| OCR-04 | P0 | Language picker: EN / UR / Auto | Persists last choice |
| OCR-05 | P0 | Show OCR progress + cancel | Partial results discarded on cancel |
| OCR-06 | P0 | Text blocks / lines with bounding boxes overlay | Toggle boxes on/off |
| OCR-07 | P1 | Per-line or per-block confidence | Low-confidence highlight |
| OCR-08 | P1 | Mixed bilingual page handling | Detect script regions; run appropriate rec |
| OCR-09 | P1 | Downloadable Urdu model pack | First UR use prompts download; size shown |
| OCR-10 | P1 | Optional **cloud / VLM OCR** (opt-in) | Clear consent; works when on-device CER poor |
| OCR-11 | P1 | Compare / replace result when cloud returns | User accepts cloud text |
| OCR-12 | P2 | Handwriting best-effort | No hard accuracy guarantee; labeled as experimental |
| OCR-13 | P2 | Table structure preservation | Markdown / plain aligned columns |
| OCR-14 | P2 | On-device UTRNet or fine-tuned Urdu rec | Beats Arabic baseline on Nastaliq print |

---

## 7. Post-OCR: edit, export, share

| ID | Priority | Feature | Acceptance criteria |
|----|----------|---------|---------------------|
| OUT-01 | P0 | Editable result text field | Full Unicode; Urdu RTL |
| OUT-02 | P0 | Copy all to clipboard | System toast / snackbar |
| OUT-03 | P0 | Share plain text via Android share sheet | |
| OUT-04 | P0 | Export `.txt` | SAF or app share |
| OUT-05 | P1 | Export PDF (page image + text layer or image+caption) | Opens in system PDF viewer |
| OUT-06 | P1 | Re-run OCR with different language / enhance | Keeps same warp image |
| OUT-07 | P1 | Find-in-text | Highlight matches |
| OUT-08 | P2 | Side-by-side image | text synced scroll | |
| OUT-09 | P2 | Export DOCX | Optional |

---

## 8. Library & persistence

| ID | Priority | Feature | Acceptance criteria |
|----|----------|---------|---------------------|
| LIB-01 | P0 | Save scan: image + text + metadata locally | Survives app restart |
| LIB-02 | P0 | List scans on Home (recents first) | Thumbnail + title + date |
| LIB-03 | P0 | Open, rename, delete scan | Confirm on delete |
| LIB-04 | P1 | Search library by text content | |
| LIB-05 | P1 | Storage usage screen + clear cache | |
| LIB-06 | P2 | Folders / tags | |
| LIB-07 | P2 | Backup/export zip of library | User-initiated |

---

## 9. Settings & models

| ID | Priority | Feature | Acceptance criteria |
|----|----------|---------|---------------------|
| SET-01 | P0 | Default OCR language | |
| SET-02 | P0 | Toggle optional cloud OCR (off by default) | |
| SET-03 | P1 | Manage downloaded models (size, delete) | |
| SET-04 | P1 | Image quality / max stitch resolution | Low / Med / High |
| SET-05 | P1 | Privacy statement in-app | No silent uploads |
| SET-06 | P2 | API key / endpoint for self-hosted VLM | Power users |

---

## 10. Reliability, performance, accessibility

| ID | Priority | Feature | Acceptance criteria |
|----|----------|---------|---------------------|
| REL-01 | P0 | Crash-safe: no ANR on stitch/OCR | Work on background threads / coroutines |
| REL-02 | P0 | Low-memory: downscale intermediate mats | Documented limits |
| REL-03 | P1 | Retry failed OCR without recapturing | |
| REL-04 | P1 | Content descriptions for primary buttons | TalkBack usable for core flow |
| REL-05 | P1 | Large text / font scale support | Result editor scales |
| REL-06 | P2 | Benchmark screen (timings det/rec/stitch) | Debug builds |

---

## 11. “100% camera pipeline” definition of done

The product is considered **pipeline-complete** when a user can, **without leaving the app and without gallery import**:

1. Grant camera permission  
2. Capture a large paper page using **guided multi-frame / panorama**  
3. Automatically stitch to one image (or clear fallback)  
4. Auto-detect edges, adjust corners, warp flat  
5. Enhance for readability  
6. Run **English** and **Urdu** OCR (offline path)  
7. Edit, copy, share, and export TXT (PDF at P1)  
8. Find the scan again in the local library  

All **P0** rows above must be **Done**. **P1** rows must be **Done** for the “works perfectly” v1.0 bar in the PRD.

---

## 12. Traceability

| Doc | Role |
|-----|------|
| [PRD.md](../PRD.md) | Why / success metrics / architecture |
| [RESEARCH.md](RESEARCH.md) | Repos, papers, models to implement against |
| This file | What to build and in what order |
