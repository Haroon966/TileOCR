# Device QA — 4-tile AR guided scan

| # | Case | Expect |
|---|------|--------|
| 1 | Frame whole page | Live paper outline + 2×2 tile grid |
| 2 | Highlight TL | Active tile pulses; chips show TL/TR/BL/BR |
| 3 | Move into tile | Coach “move closer”; auto-capture when aligned + still |
| 4 | Blurry shot | Tile marked red; stay on that tile; recapture |
| 5 | All 4 good | **No auto stitch** — Done button appears |
| 6 | Tap Done | Panorama stitch starts |
| 7 | Delete tile | Slot clears; that tile pending again |

Never auto-advance to stitch.
