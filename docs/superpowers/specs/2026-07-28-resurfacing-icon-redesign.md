# Atlas Resurfacing Icon Redesign

## Goal

Replace the current laughing map-pin launcher icon, which incorrectly frames Atlas as a
"laughter map", with a mark that communicates the product's actual identity:
laughter-indexed positive moments resurfacing.

## Visual Concept

The launcher icon depicts one warm positive-memory light rising from two soft temporal ripples.

- The warm light represents a positive moment.
- The ripples represent a moment being retrieved and resurfaced over time.
- Laughter remains an implicit indexing mechanism rather than a literal face or microphone.
- The existing warm orange and cream Atlas 2.0 palette remains unchanged.

## Form and Constraints

- Flat, geometric, legible at 48 px.
- No text, gradients, detailed illustration, or photographic treatment.
- No laughing face, smile, map pin, microphone, map, or conventional photo-album symbol.
- Keep the core form within the Android adaptive-icon safe region.
- Produce matching adaptive vector foreground/background resources and legacy density PNGs.
- Keep the existing monochrome notification icon unchanged: this task only changes launcher
  identity.

## Verification

- Inspect the final 192 px legacy PNG visually.
- Confirm mdpi, hdpi, xhdpi, xxhdpi, and xxxhdpi launcher and round resources are present.
- Run `:atlasapp:assembleDebug` to validate all icon resources.
- Do not change notification scheduling, reminder behavior, or UI logic.
- Do not push until the user explicitly authorizes it.
