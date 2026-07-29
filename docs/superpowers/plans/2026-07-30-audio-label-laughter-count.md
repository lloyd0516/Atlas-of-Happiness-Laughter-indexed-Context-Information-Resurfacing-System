# Audio Labels and Laughter Counts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Label both review audio types clearly and replace stale user-facing `Period: 0`/`0段笑声` values with a shared count derived from actual laughter audio.

**Architecture:** Add a small pure count policy that derives the display count from normalized event JSON and provides a legacy `period_ids` fallback. Store that value on `EventSummary`, then make every user-facing review surface consume it and shared string resources. Add headings around the existing audio containers without changing playback or logging.

**Tech Stack:** Android Java, Android XML resources, `org.json`, JUnit 4, Gradle.

## Global Constraints

- Chinese labels are exactly `笑声音频` and `相关上下文音频`.
- Prefer `auto_captured.audio_clips[type=laughter]`; use `period_ids` only when no typed laughter audio can be counted.
- Never invent one laughter clip for an event whose derived count is zero.
- A zero count is rendered as `暂无笑声片段`, never `0段笑声`.
- Do not change recording, Speechmatics detection, aggregation, media association, notifications, playback behavior, or research-log schema.

---

### Task 1: Shared laughter-count policy

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLaughterCountPolicy.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasLaughterCountPolicyTest.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java`

**Interfaces:**
- Consumes: normalized event `JSONObject`.
- Produces: `static int count(JSONObject event)` and `EventSummary.laughterClipCount`.

- [ ] **Step 1: Write failing policy tests**

```java
@Test public void typedLaughterAudioTakesPriorityOverPeriods() {
    JSONObject event = eventWithAudioTypes("laughter", "laughter", "possible_related_speech_context")
            .put("period_ids", new JSONArray().put("p1").put("p2").put("p3"));
    assertEquals(2, AtlasLaughterCountPolicy.count(event));
}

@Test public void legacyEventFallsBackToPeriodIds() {
    JSONObject event = new JSONObject()
            .put("auto_captured", new JSONObject())
            .put("period_ids", new JSONArray().put("p1").put("p2"));
    assertEquals(2, AtlasLaughterCountPolicy.count(event));
}

@Test public void emptyEventStaysZero() {
    assertEquals(0, AtlasLaughterCountPolicy.count(new JSONObject()));
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasLaughterCountPolicyTest \
  --console=plain
```

Expected: compilation fails because `AtlasLaughterCountPolicy` does not exist.

- [ ] **Step 3: Implement the minimal policy**

```java
final class AtlasLaughterCountPolicy {
    static int count(JSONObject event) {
        JSONObject auto = event != null
                ? event.optJSONObject("auto_captured")
                : null;
        JSONArray clips = auto != null
                ? auto.optJSONArray("audio_clips")
                : null;
        int laughterCount = 0;
        if (clips != null) {
            for (int i = 0; i < clips.length(); i++) {
                JSONObject clip = clips.optJSONObject(i);
                if (clip != null
                        && "laughter".equals(
                        clip.optString("type", ""))) {
                    laughterCount += 1;
                }
            }
        }
        if (laughterCount > 0) {
            return laughterCount;
        }
        JSONArray periods = event != null
                ? event.optJSONArray("period_ids")
                : null;
        return periods != null ? periods.length() : 0;
    }
}
```

Set `summary.laughterClipCount = AtlasLaughterCountPolicy.count(normalized)` while preserving `periodCount` for internal compatibility.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: all `AtlasLaughterCountPolicyTest` tests pass.

- [ ] **Step 5: Commit Task 1**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLaughterCountPolicy.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasLaughterCountPolicyTest.java
git commit -m "fix: derive visible laughter counts"
```

### Task 2: Review audio headings

**Files:**
- Modify: `atlasapp/src/main/res/layout/item_laughter_clip_card.xml`
- Modify: `atlasapp/src/main/res/values/strings.xml`
- Modify: `atlasapp/src/main/res/values-zh/strings.xml`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java`
- Modify: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowPresentationTest.java`

**Interfaces:**
- Consumes: existing `windowLaughterAudioContainer`, `windowContextAudioContainerShort`, and `windowContextAudioContainerLong`.
- Produces: `clip_laughter_audio` and `clip_related_context_audio` headings whose visibility follows their audio container.

- [ ] **Step 1: Add a failing layout contract test**

Extend `cardLayoutUsesDynamicAudioContainers()` with:

```java
assertTrue(xml.contains("labelWindowLaughterAudio"));
assertTrue(xml.contains("@string/clip_laughter_audio"));
assertTrue(xml.contains("labelWindowContextAudioShort"));
assertTrue(xml.contains("@string/clip_related_context_audio"));
assertTrue(xml.indexOf("labelWindowLaughterAudio")
        < xml.indexOf("windowLaughterAudioContainer"));
assertTrue(xml.indexOf("labelWindowContextAudioShort")
        < xml.indexOf("windowContextAudioContainerShort"));
```

Add a resource-source assertion that Chinese strings contain:

```xml
<string name="clip_laughter_audio">笑声音频</string>
<string name="clip_related_context_audio">相关上下文音频</string>
```

- [ ] **Step 2: Run the presentation test and verify RED**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentationTest \
  --console=plain
```

Expected: assertions fail because the laughter and short-context heading views do not exist.

- [ ] **Step 3: Add headings and visibility wiring**

Add `TextView` headings immediately before the laughter and short-context containers. Reuse the existing long-context heading. In `bindWindowCard`, show the short-context heading exactly when `shortShowsContext` is true and the long-context heading exactly when `longShowsContext` is true. Laughter headings remain visible because aggregate cards are built only from windows containing laughter.

- [ ] **Step 4: Run the presentation test and verify GREEN**

Run the command from Step 2.

Expected: all `AtlasResurfacingWindowPresentationTest` tests pass.

- [ ] **Step 5: Commit Task 2**

```bash
git add \
  atlasapp/src/main/res/layout/item_laughter_clip_card.xml \
  atlasapp/src/main/res/values/strings.xml \
  atlasapp/src/main/res/values-zh/strings.xml \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowPresentationTest.java
git commit -m "fix: label resurfacing audio types"
```

### Task 3: Replace user-facing period counts

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasMapHtmlBuilder.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/SupplementPickerActivity.java`
- Modify: `atlasapp/src/main/res/values/strings.xml`
- Modify: `atlasapp/src/main/res/values-zh/strings.xml`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasReviewLaughterCountPresentationTest.java`

**Interfaces:**
- Consumes: `EventSummary.laughterClipCount`.
- Produces: shared `event_laughter_count` and `event_laughter_count_empty` strings; map HTML with the same semantics.

- [ ] **Step 1: Write a failing source/UI contract test**

The test reads the four Activity/builder source files and asserts:

```java
assertFalse(combinedSource.contains("event.periodCount"));
assertFalse(combinedSource.contains("Math.max(1, item.periodCount)"));
assertTrue(combinedSource.contains("event.laughterClipCount"));
assertTrue(combinedSource.contains("item.laughterClipCount"));
```

It also reads Chinese resources and asserts:

```java
assertTrue(strings.contains(
        "<string name=\"event_laughter_count\">%1$d段笑声</string>"));
assertTrue(strings.contains(
        "<string name=\"event_laughter_count_empty\">暂无笑声片段</string>"));
```

- [ ] **Step 2: Run the contract test and verify RED**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasReviewLaughterCountPresentationTest \
  --console=plain
```

Expected: assertions fail because the surfaces still use `periodCount`.

- [ ] **Step 3: Implement shared visible formatting**

Add:

```xml
<string name="event_laughter_count">%1$d段笑声</string>
<string name="event_laughter_count_empty">暂无笑声片段</string>
```

and matching English resources. Add a small Activity helper returning the empty string for zero and formatted count otherwise. Replace `Period: N` in recent records, supplement picker, calendar cards, timeline rows, generic review cards, and map-stack cards. Sum `laughterClipCount` for calendar-day captions.

In `AtlasMapHtmlBuilder`, replace:

```java
group.laughterCount += Math.max(1, item.periodCount);
```

with:

```java
group.laughterCount += Math.max(0, item.laughterClipCount);
```

and render either `N段笑声` or `暂无笑声片段` in marker content.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasLaughterCountPolicyTest \
  --tests com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentationTest \
  --tests com.hry.camera.usbcamerademo.AtlasReviewLaughterCountPresentationTest \
  --console=plain
```

Expected: all focused tests pass.

- [ ] **Step 5: Commit Task 3**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasMapHtmlBuilder.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/SupplementPickerActivity.java \
  atlasapp/src/main/res/values/strings.xml \
  atlasapp/src/main/res/values-zh/strings.xml \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasReviewLaughterCountPresentationTest.java
git commit -m "fix: show real laughter counts"
```

### Task 4: Regression verification and APK

**Files:**
- Modify: `README.md`
- Update locally without staging: `artifacts/Atlas-of-Happiness-2.0-three-clip-aggregate-fj_aggregate_ver-debug.apk`

**Interfaces:**
- Consumes: completed Tasks 1–3.
- Produces: verified documentation and installable debug APK.

- [ ] **Step 1: Update README display semantics**

Document the two audio labels and state that user-facing counts come from laughter audio with a legacy period fallback; internal log fields remain unchanged.

- [ ] **Step 2: Run all JVM tests**

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Build the debug APK**

```bash
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL` and `atlasapp/build/outputs/apk/debug/atlasapp-debug.apk`.

- [ ] **Step 4: Copy and checksum the local APK**

Copy the built APK to:

```text
artifacts/Atlas-of-Happiness-2.0-three-clip-aggregate-fj_aggregate_ver-debug.apk
```

Record its SHA-256 in `README.md`. Keep `artifacts/` untracked.

- [ ] **Step 5: Commit documentation**

```bash
git add README.md
git commit -m "docs: explain visible laughter counts"
```

- [ ] **Step 6: Final clean-state check**

Run:

```bash
git status -sb
git diff --check
```

Expected: only the pre-existing local `.superpowers/` and `artifacts/` directories remain untracked.
