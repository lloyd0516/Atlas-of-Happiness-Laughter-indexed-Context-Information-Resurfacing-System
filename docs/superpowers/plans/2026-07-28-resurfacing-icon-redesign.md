# Atlas Resurfacing Icon Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the laughing map-pin launcher icon with a positive-memory light resurfacing from temporal ripples.

**Architecture:** Keep the launcher artwork deterministic and repo-native: one adaptive vector foreground, one cream vector background, and rasterized legacy launcher resources for five Android density buckets. The existing monochrome notification icon and all notification code remain unchanged.

**Tech Stack:** Android VectorDrawable XML, adaptive icons, SVG source used only for deterministic PNG rasterization, `qlmanage`, `sips`, Gradle.

## Global Constraints

- Use the Atlas 2.0 warm orange `#FF982F`, dark orange `#E07E1A`, and cream `#FFF1E4`.
- Show one positive-memory light rising from two soft temporal ripples.
- Do not use a laughing face, smile, map pin, microphone, map, photo album, text, or gradient.
- Keep the essential shape inside the Android adaptive-icon safe region and legible at 48 px.
- Change launcher identity only; do not modify `ic_atlas_notification.xml` or reminder behavior.
- Do not push until the user explicitly authorizes it.

---

### Task 1: Replace and verify launcher artwork

**Files:**
- Modify: `atlasapp/src/main/res/drawable-v24/ic_launcher_foreground.xml`
- Modify: `atlasapp/src/main/res/drawable/ic_launcher_background.xml`
- Modify: `atlasapp/src/main/res/mipmap-mdpi/ic_launcher.png`
- Modify: `atlasapp/src/main/res/mipmap-mdpi/ic_launcher_round.png`
- Modify: `atlasapp/src/main/res/mipmap-hdpi/ic_launcher.png`
- Modify: `atlasapp/src/main/res/mipmap-hdpi/ic_launcher_round.png`
- Modify: `atlasapp/src/main/res/mipmap-xhdpi/ic_launcher.png`
- Modify: `atlasapp/src/main/res/mipmap-xhdpi/ic_launcher_round.png`
- Modify: `atlasapp/src/main/res/mipmap-xxhdpi/ic_launcher.png`
- Modify: `atlasapp/src/main/res/mipmap-xxhdpi/ic_launcher_round.png`
- Modify: `atlasapp/src/main/res/mipmap-xxxhdpi/ic_launcher.png`
- Modify: `atlasapp/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png`

**Interfaces:**
- Consumes: existing adaptive-icon references in `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`.
- Produces: unchanged Android resource names `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`.

- [ ] **Step 1: Replace the vector artwork**

Use a cream full-canvas background. Build the foreground from:

```text
upper light: small cream/orange circular positive moment at y≈35
resurfacing path: short vertical glow/stem connecting the light to the ripples
near ripple: upward-facing orange arc centered around y≈59
far ripple: wider dark-orange/soft-orange arc centered around y≈72
```

All foreground geometry must stay between approximately x=20..88 and y=20..88 in the
108×108 viewport. The mark must read as “rising/resurfacing,” not broadcasting radio waves.

- [ ] **Step 2: Create one deterministic 512×512 SVG master**

Create a temporary SVG with the same geometry and palette as the Android vector:

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <rect width="512" height="512" rx="112" fill="#FFF1E4"/>
  <circle cx="256" cy="166" r="54" fill="#FF982F"/>
  <circle cx="256" cy="166" r="20" fill="#FFF8F1"/>
  <path d="M256 220V258" stroke="#FF982F" stroke-width="24" stroke-linecap="round"/>
  <path d="M174 274Q256 338 338 274" fill="none" stroke="#FF982F" stroke-width="28" stroke-linecap="round"/>
  <path d="M112 327Q256 438 400 327" fill="none" stroke="#E07E1A" stroke-width="30" stroke-linecap="round"/>
</svg>
```

The inner cream dot makes the memory light read as luminous without using a gradient.

- [ ] **Step 3: Rasterize all legacy density resources**

Render the 512 px master once, then resize it to:

```text
mdpi: 48×48
hdpi: 72×72
xhdpi: 96×96
xxhdpi: 144×144
xxxhdpi: 192×192
```

Write the same raster to each bucket's `ic_launcher.png` and `ic_launcher_round.png`; the
rounded cream master is safe under both legacy masks.

- [ ] **Step 4: Inspect the final icon**

Open `atlasapp/src/main/res/mipmap-xxxhdpi/ic_launcher.png` and verify:

```text
no face or pin silhouette
one clearly dominant positive-memory light
two temporal ripples
no clipped geometry
recognizable when visually reduced to 48 px
```

- [ ] **Step 5: Build from a clean source state**

Run:

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:assembleDebug --console=plain --quiet
```

Expected: exit code 0. Restore only tracked Gradle/build cache files and the temporary local SDK
path after verification.

- [ ] **Step 6: Confirm scope and commit locally**

Run `git diff --check` and confirm `ic_atlas_notification.xml` has no diff. Commit only the
launcher resources:

```sh
git add atlasapp/src/main/res/drawable-v24/ic_launcher_foreground.xml \
  atlasapp/src/main/res/drawable/ic_launcher_background.xml \
  atlasapp/src/main/res/mipmap-*/ic_launcher.png \
  atlasapp/src/main/res/mipmap-*/ic_launcher_round.png
git commit -m "style: clarify resurfacing launcher icon"
```

Do not push.
