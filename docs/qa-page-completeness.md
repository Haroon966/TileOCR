# Device QA — whole-page 8×12 guided scan

| # | Case | Expect |
|---|------|--------|
| 1 | Empty desk | No shutter; “Can't see paper” |
| 2 | Whole page + margins | Phase A — 8×12 grid locks; no capture yet |
| 3 | Zoom top-left | Overlay stays stable (does not re-split AABB); red cells guide |
| 4 | Align on Empty/Soft | Auto-capture when centered, close enough, IoU-stable, features OK |
| 5 | Duplicate still (>30% locked overlap) | Reject; coach “already scanned”; no forced pan for Soft recapture |
| 6 | Soft strip | Red/soft cell; sharper close-up accepts and upgrades to Locked |
| 7 | Middle of page only | Never Done — empty cells remain |
| 8 | All 96 Locked | **Done** enabled only — no auto-stitch |
| 9 | Tap Done | Chain stitch (neighbor match) + polish progress → mosaic |
| 10 | Delete a still | Coverage rebuild from remaining URIs; seed overlay stays |
| 11 | Rotate phone | Overlay stays aligned (analysis rotation) |
| 12 | Reopen camera | Bind once — no flicker storm |

Permanent rules: fail-closed gates, shared `PageSpaceTracker` seed, accept/reject MOVE signaling, gyro shaky clears, ≤30% locked-overlap gate.
