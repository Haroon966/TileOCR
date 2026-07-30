# TileOCR Design System — Master

Source of truth for Compose UI. Attached lime OCR sheet + Jetpack Compose Material 3.

## Brand

Focused, minimal document scanner. Lime primary on soft neutrals. High contrast text. Outline icons + labels.

## Color

### Primary / Lime

| Token | Hex | Use |
|-------|-----|-----|
| Lime50 | `#E6F5B3` | Soft fills, chips |
| Lime100 | `#D7EF8A` | Hover light |
| Lime200 | `#C8E66B` | Secondary accent |
| Lime300 | `#B9D94F` | Hover / pressed mid |
| Lime400 | `#A9C63D` | Primary CTA / active |

### Neutrals

| Token | Hex | Use |
|-------|-----|-----|
| Canvas | `#FAFAF6` | App background |
| SurfaceMuted | `#F2F2ED` | Cards, inputs idle |
| Border | `#E6E7E1` | Outlines, dividers |
| Muted | `#878980` | Secondary / caption text |
| Ink | `#3C3D37` | Body, titles, on-primary |

### Semantic

| Token | Hex |
|-------|-----|
| Success | `#A8E6A1` |
| Warning | `#FFE7A3` |
| Info | `#FFD1B3` |
| Error | `#FFBDD3` |

### Camera chrome

Near-black surfaces (`#000000` / `#1A1A18`) with lime accents for shutter and focus chrome.

## Typography

**Family:** Inter (Regular 400, Medium 500, SemiBold 600)

| Style | Size / Line | Weight |
|-------|-------------|--------|
| Display Large | 40 / 48 | SemiBold |
| Heading 1 | 28 / 36 | SemiBold |
| Heading 2 | 22 / 28 | Medium |
| Heading 3 | 18 / 24 | Medium |
| Body Large | 16 / 24 | Regular |
| Body Medium | 14 / 20 | Regular |
| Caption | 12 / 16 | Regular |

## Spacing (8px base)

`4, 8, 16, 24, 32, 40, 48, 64`

## Radius

`4, 8, 12, 16, 20, 24, 32`

Buttons / cards default **12–16**. Chips more rounded (~20–24).

## Shadows

Low-opacity black only. Medium ≈ `rgba(0,0,0,0.08)`. Prefer elevation via Material 3; avoid harsh multi-layer glow.

## Components

- **Primary button:** fill Lime400, text Ink
- **Secondary:** white/canvas fill, Lime400 border, Ink text
- **Tertiary:** text-only Ink / Muted
- **Disabled:** faint border / muted fill, no strong lime
- **Inputs:** rounded rect, Border stroke; focus = Lime400 border
- **Chips:** capsule, muted fill + Ink label
- **Alerts:** full-width bar, semantic bg, icon left, dismiss right
- **Cards:** simple text card or file card (name, size, date)
- **Icons:** outline, Ink (`#3C3D37`); no emoji as icons

## Do / Don't

**Do:** generous spacing; lime for primary actions only; high contrast; icon + text for clarity.

**Don't:** rainbow of colors; low-contrast muted-on-muted; cluttered hero; harsh neon; Inter replaced without updating this file.

## Compose mapping

- Colors → `ui/theme/Color.kt` + `MaterialTheme.colorScheme`
- Type → `ui/theme/Type.kt` (Inter)
- Spacing / radius → `ui/theme/Dimens.kt`
- Shared buttons / cards → `ui/components/AppButtons.kt`, `AppCard.kt`

Page overrides live in `design-system/pages/`.
