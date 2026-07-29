# Three-Clip Resurfacing Aggregation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace one-card-per-laughter review rendering with one card per three-clip recording bucket while making automatic photo/video capture use the same bucket boundaries.

**Architecture:** Introduce a pure shared bucket policy and a pure resurfacing-window aggregator. Persist only the bucket and context-link metadata needed for deterministic ownership, enrich legacy review data at the repository boundary, then render each aggregate window with dynamic laughter/context audio rows in the existing detail Activity.

**Tech Stack:** Android Java, Android support libraries, `org.json`, JUnit 4, Gradle Android plugin 3.1.4.

## Global Constraints

- Work only on branch `fj_aggregate_ver`.
- The aggregation window is exactly three audio clips:
  `aggregation_window_sec = session_clip_duration_sec * 3`.
- Automatic capture and review aggregation must use the same session-relative bucket boundaries.
- The default 30-second audio clip therefore produces a 90-second window, but 20- and 45-second configurations produce 60- and 135-second windows.
- Do not change Speechmatics detection, raw audio recording, laughter confidence filtering, laughter WAV extraction, or the existing 2.5-second laughter padding.
- Keep one automatic bundle fixed at two photos and one video.
- A media bundle and a possible-related-speech file may each appear in at most one aggregate card.
- Short-term default order is laughter audio, photo/video, date/location, then longer audio; Social context and User summary remain in one shared collapsed section.
- Long-term default order is laughter audio then date/location; photo/video, longer audio, Social context, and User summary remain in one shared collapsed section.
- Do not add new sensitive content to research interaction logs.
- Preserve edit, deletion, notification navigation, media playback, playback gain, and append-only research-log behavior.
- Use TDD for every production behavior change and commit each independently reviewable task.

---

### Task 1: Shared three-clip bucket policy and capture threshold

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAggregationBucketPolicy.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAggregationBucketPolicyTest.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java`
- Modify: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentConfigTest.java`

**Interfaces:**
- Consumes: positive `clipDurationSec` and non-negative session-relative seconds.
- Produces:
  - `bucketDurationSec(int clipDurationSec)`
  - `bucketId(double sessionOffsetSec, int clipDurationSec)`
  - `bucketStartOffsetMs(int bucketId, int clipDurationSec)`
  - `bucketEndOffsetMs(int bucketId, int clipDurationSec)`

- [ ] **Step 1: Write failing bucket-policy tests**

```java
@Test
public void threeClipsDefineOneBucketForEveryPreset() {
    assertEquals(60,
            AtlasAggregationBucketPolicy.bucketDurationSec(20));
    assertEquals(90,
            AtlasAggregationBucketPolicy.bucketDurationSec(30));
    assertEquals(135,
            AtlasAggregationBucketPolicy.bucketDurationSec(45));
}

@Test
public void boundaryIsLeftClosedAndRightOpen() {
    assertEquals(0,
            AtlasAggregationBucketPolicy.bucketId(89.999, 30));
    assertEquals(1,
            AtlasAggregationBucketPolicy.bucketId(90.000, 30));
    assertEquals(90000L,
            AtlasAggregationBucketPolicy.bucketStartOffsetMs(1, 30));
    assertEquals(180000L,
            AtlasAggregationBucketPolicy.bucketEndOffsetMs(1, 30));
}
```

Add a configuration assertion that the old four-clip multiplier no longer
exists and `AppConfig.AGGREGATION_CLIPS_PER_BUCKET == 3`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```bash
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasAggregationBucketPolicyTest \
  --tests com.hry.camera.usbcamerademo.JoyfulMomentConfigTest \
  --console=plain
```

Expected: compilation fails because `AtlasAggregationBucketPolicy` and
`AGGREGATION_CLIPS_PER_BUCKET` do not exist.

- [ ] **Step 3: Implement the pure policy and replace the capture formula**

Implement:

```java
final class AtlasAggregationBucketPolicy {
    static int bucketDurationSec(int clipDurationSec) {
        if (clipDurationSec <= 0) {
            throw new IllegalArgumentException(
                    "clip duration must be positive");
        }
        return clipDurationSec
                * AppConfig.AGGREGATION_CLIPS_PER_BUCKET;
    }

    static int bucketId(
            double sessionOffsetSec,
            int clipDurationSec) {
        if (sessionOffsetSec < 0.0) {
            throw new IllegalArgumentException(
                    "session offset must be non-negative");
        }
        return (int) Math.floor(
                sessionOffsetSec
                        / bucketDurationSec(clipDurationSec));
    }
}
```

Add overflow-safe start/end millisecond methods. Replace
`AUTO_CAPTURE_RATE_LIMIT_CLIP_MULTIPLIER = 4` with
`AGGREGATION_CLIPS_PER_BUCKET = 3`, and change
`triggerAutomationForDetectionIfNewClip(...)` to obtain both bucket ID and
duration from the shared policy.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2.

Expected: both test classes pass.

- [ ] **Step 5: Commit**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAggregationBucketPolicy.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAggregationBucketPolicyTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentConfigTest.java
git commit -m "feat: align capture to three-clip buckets"
```

---

### Task 2: Persist capture-bucket identity end to end

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequest.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentClusterer.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadata.java`
- Modify: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequestTest.java`
- Modify: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentMediaAssetTest.java`
- Modify: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadataTest.java`

**Interfaces:**
- Consumes: bucket ID and bucket duration calculated in Task 1.
- Produces capture metadata fields:
  - `automation_bucket_id`
  - `automation_bucket_clip_count`
  - `automation_bucket_duration_sec`
  - existing bundle fields unchanged.

- [ ] **Step 1: Extend tests to require complete bucket metadata**

Update `AtlasCaptureBundleRequestTest`:

```java
AtlasCaptureBundleRequest bundle =
        AtlasCaptureBundleRequest.create(
                "event-1", 12, 90, 1000L, 5);
assertEquals(12, bundle.automationBucketId);
assertEquals(3, bundle.automationBucketClipCount);
assertEquals(90, bundle.automationBucketDurationSec);
```

Update `JoyfulMomentMediaAssetTest` to construct a bucket-aware record and
assert:

```java
assertEquals(12, json.getInt("automation_bucket_id"));
assertEquals(3,
        json.getInt("automation_bucket_clip_count"));
assertEquals(90,
        json.getInt("automation_bucket_duration_sec"));
```

Update `AtlasCaptureBundleMetadataTest` so complete bucket metadata copies
all three new fields. Existing bundle ID/trigger/index fields must still copy
for legacy data when the new bucket metadata is absent; an incomplete new
bucket group copies none of only the three new bucket fields.

- [ ] **Step 2: Run focused tests and verify RED**

```bash
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasCaptureBundleRequestTest \
  --tests com.hry.camera.usbcamerademo.JoyfulMomentMediaAssetTest \
  --tests com.hry.camera.usbcamerademo.AtlasCaptureBundleMetadataTest \
  --console=plain
```

Expected: compilation or assertions fail because the bucket fields are not
persisted.

- [ ] **Step 3: Implement request, record, log, and normalization metadata**

Change the factory signature to:

```java
static AtlasCaptureBundleRequest create(
        String eventId,
        int automationBucketId,
        int automationBucketDurationSec,
        long triggerTimeMs,
        int videoDurationSec)
```

Set `automationBucketClipCount` from the shared policy constant. Extend
`MediaAssetRecord` constructors and `toJson()`. Update Controller video/photo
save paths and `putBundleFields(...)` to use request fields. Extend
`AtlasCaptureBundleMetadata.copyIfPresent(...)` so bucket metadata is copied
only when the existing bundle metadata and all new bucket fields form one
complete valid group.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the command from Step 2.

Expected: all three test classes pass.

- [ ] **Step 5: Commit**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequest.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentClusterer.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadata.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequestTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentMediaAssetTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadataTest.java
git commit -m "feat: persist capture bucket metadata"
```

---

### Task 3: Persist context links and expose original session timing

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasContextAudioRecord.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasContextAudioRecordTest.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentClusterer.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java`

**Interfaces:**
- Consumes: context `clipId`, session-relative start/end, saved path, and
  `relatedLaughterClipIds` already calculated by `ClipState`.
- Produces:
  - event JSON array `context_audio_records`;
  - normalized context fields `linked_laughter_clip_ids`, `start_sec`,
    `end_sec`, and `duration_sec`;
  - `_meta.session_start_ms`, `_meta.clip_duration_sec`, and
    `_meta.context_neighbor_clips`.

- [ ] **Step 1: Write failing context serialization and merge tests**

Create tests for:

```java
AtlasContextAudioRecord record =
        new AtlasContextAudioRecord(
                5, 150.0, 180.0, "/clips/context.wav",
                Arrays.asList(3, 4));
JSONObject json = record.toJson();
assertEquals(5, json.getInt("clip_id"));
assertEquals(30.0, json.getDouble("duration_sec"), 0.001);
assertEquals(2,
        json.getJSONArray("linked_laughter_clip_ids").length());
```

Also test `copyMatchingRecord(...)` with one path match and one clip-ID
fallback. The target JSON must receive timing and link fields, while an
unmatched target remains unchanged.

- [ ] **Step 2: Run the focused test and verify RED**

```bash
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasContextAudioRecordTest \
  --console=plain
```

Expected: compilation fails because `AtlasContextAudioRecord` does not exist.

- [ ] **Step 3: Implement context records and repository enrichment**

Implement the immutable record with:

```java
JSONObject toJson() throws JSONException

static boolean copyMatchingRecord(
        JSONArray records,
        int clipId,
        String path,
        JSONObject target) throws JSONException
```

Add `List<AtlasContextAudioRecord> contextAudioRecords` to `EventRecord`.
When `attachClipToEvent(...)` handles
`possible_related_speech_context`, upsert one record with the current
`ClipState.relatedLaughterClipIds`.

In `AtlasReviewRepository.normalizeEvent(...)` and
`backfillAudioClipsFromSavedPaths(...)`, copy matching context metadata into
the normalized audio item. Read `summary.json` once per event normalization
and add valid original session timing/config values to `_meta`. Do not
substitute the current global setting when historical session metadata is
missing; leave the field absent so the aggregator can reject time ownership
that cannot be resolved safely.

- [ ] **Step 4: Run focused tests and the existing deletion-path test**

```bash
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasContextAudioRecordTest \
  --tests com.hry.camera.usbcamerademo.AtlasEventDeletionPathsTest \
  --console=plain
```

Expected: both test classes pass; adding metadata does not change media
deletion path extraction.

- [ ] **Step 5: Commit**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasContextAudioRecord.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentClusterer.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasContextAudioRecordTest.java
git commit -m "feat: retain context audio relationships"
```

---

### Task 4: Expose complete capture bundles without nearest-clip reuse

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcher.java`
- Modify: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcherTest.java`

**Interfaces:**
- Consumes: normalized photo/video arrays and the existing legacy grouping
  window.
- Produces:

```java
static List<MatchedCaptureBundle> collectBundles(
        JSONArray photos,
        JSONArray videos,
        long legacyGroupWindowMs)
```

`MatchedCaptureBundle` additionally exposes the complete optional bucket
metadata copied in Task 2.

- [ ] **Step 1: Write failing collection tests**

Add tests proving:

```java
List<AtlasClipMediaMatcher.MatchedCaptureBundle> bundles =
        AtlasClipMediaMatcher.collectBundles(
                photos, videos, 15000L);
assertEquals(2, bundles.size());
assertEquals("bucket-0", bundles.get(0).bundleId);
assertEquals(0, bundles.get(0).automationBucketId);
assertEquals(3,
        bundles.get(0).automationBucketClipCount);
assertEquals(90,
        bundles.get(0).automationBucketDurationSec);
```

Add cases for deterministic time order, a partial explicit bundle, and
legacy 2-photo/1-video inference. Keep all existing nearest-matcher tests as
regression coverage.

- [ ] **Step 2: Run matcher tests and verify RED**

```bash
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasClipMediaMatcherTest \
  --console=plain
```

Expected: compilation fails because `collectBundles(...)` and bucket fields
do not exist.

- [ ] **Step 3: Refactor candidate collection behind the public package API**

Extract the existing explicit distribution and legacy inference pipeline
from `findNearestBundle(...)` into `collectBundles(...)`. Sort resulting
bundles by bundle time, then bundle ID. Keep caps at two photos and one
video. Make `findNearestBundle(...)` call the shared collector so its
existing behavior remains unchanged.

- [ ] **Step 4: Run matcher tests and verify GREEN**

Run the command from Step 2.

Expected: all existing and new matcher tests pass.

- [ ] **Step 5: Commit**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcher.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcherTest.java
git commit -m "feat: collect capture bundles for aggregation"
```

---

### Task 5: Build deterministic resurfacing windows

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowAggregator.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowAggregatorTest.java`

**Interfaces:**
- Consumes:

```java
static List<Window> aggregate(
        JSONArray audioClips,
        JSONArray photos,
        JSONArray videos,
        long sessionStartMs,
        int sessionClipDurationSec,
        int contextNeighborClips,
        long legacyBundleGroupWindowMs)
```

- Produces immutable `Window` fields:

```java
int bucketId;
long startTimeMs;
long endTimeMs;
double totalLaughterDurationSec;
List<JSONObject> laughterClips;
List<JSONObject> contextClips;
List<String> photoPaths;
List<String> videoPaths;
```

- [ ] **Step 1: Write RED tests for bucket membership and ordering**

Create helpers that build temporary audio/media JSON with real temporary
files. Add:

```java
@Test
public void multipleLaughterItemsShareOneThreeClipWindow() {
    List<Window> windows = aggregate(
            laughterAt(5.0, 7.0),
            laughterAt(40.0, 41.5),
            laughterAt(89.0, 90.0));
    assertEquals(1, windows.size());
    assertEquals(3, windows.get(0).laughterClips.size());
    assertEquals(4.5,
            windows.get(0).totalLaughterDurationSec,
            0.001);
}

@Test
public void exactBoundaryStartsNextWindow() {
    List<Window> windows = aggregate(
            laughterAt(89.999, 90.0),
            laughterAt(90.0, 91.0));
    assertEquals(2, windows.size());
    assertEquals(0, windows.get(0).bucketId);
    assertEquals(1, windows.get(1).bucketId);
}
```

- [ ] **Step 2: Run aggregator tests and verify RED**

```bash
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasResurfacingWindowAggregatorTest \
  --console=plain
```

Expected: compilation fails because the aggregator does not exist.

- [ ] **Step 3: Implement laughter windows and union duration**

Resolve audio offsets in order: `start_sec`,
`device_time_ms - sessionStartMs`, then
`clip_id * sessionClipDurationSec`. Reject unresolved or negative offsets.
Create windows only from laughter items. Merge overlapping `[start_sec,
end_sec)` intervals before summing; when an old item lacks a full interval,
add its non-negative `duration_sec` once.

- [ ] **Step 4: Run the first aggregator tests and verify GREEN**

Run the command from Step 2.

Expected: bucket, ordering, and duration cases pass.

- [ ] **Step 5: Add RED tests for unique media ownership**

Test:

- one explicit three-clip bundle enters its exact bucket once;
- an old four-clip bucket suffix is ignored and trigger time determines the
  new bucket;
- partial bundles remain partial;
- a bundle in a context-only bucket does not create a card;
- no bundle is copied to an adjacent laughter window.

- [ ] **Step 6: Implement media assignment and verify GREEN**

Call `AtlasClipMediaMatcher.collectBundles(...)`. Trust explicit bucket IDs
only when `automationBucketClipCount == 3` and duration matches the current
session. Otherwise compute the bucket from bundle trigger/capture time minus
session start. Assign each collected bundle at most once.

Run the focused aggregator tests and verify all media cases pass.

- [ ] **Step 7: Add RED tests for unique context ownership**

Cover:

- explicit `linked_laughter_clip_ids`;
- one context linked to laughter in two buckets;
- closest laughter wins;
- equal distance chooses the earlier bucket;
- legacy clip-distance fallback;
- one context is never present in two windows;
- context-only input creates no window.

- [ ] **Step 8: Implement context assignment and verify GREEN**

Index laughter by `clip_id`, resolve candidate distances by session-relative
start time, apply the explicit-link-first rules, and mark each context item
consumed after one assignment. Sort assigned context rows by time then path.

Run the focused aggregator test class and confirm every case passes.

- [ ] **Step 9: Commit**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowAggregator.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowAggregatorTest.java
git commit -m "feat: aggregate resurfacing into three-clip windows"
```

---

### Task 6: Define testable Short/Long presentation order

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowPresentation.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowPresentationTest.java`

**Interfaces:**
- Consumes: `longTerm`, `expanded`, `hasMedia`, and `hasContextAudio`.
- Produces ordered visible `Section` values:
  `LAUGHTER`, `MEDIA`, `LOCATION_DATE`, `CONTEXT_AUDIO`,
  `SOCIAL_AND_SUMMARY`.

- [ ] **Step 1: Write failing order tests**

```java
@Test
public void shortTermDefaultKeepsRequiredOrder() {
    assertEquals(
            Arrays.asList(
                    LAUGHTER, MEDIA, LOCATION_DATE,
                    CONTEXT_AUDIO),
            visibleSections(false, false, true, true));
}

@Test
public void shortTermExpandedAddsOneSocialSummarySection() {
    assertEquals(
            Arrays.asList(
                    LAUGHTER, MEDIA, LOCATION_DATE,
                    CONTEXT_AUDIO, SOCIAL_AND_SUMMARY),
            visibleSections(false, true, true, true));
}

@Test
public void longTermHidesOptionalContentUntilExpanded() {
    assertEquals(
            Arrays.asList(LAUGHTER, LOCATION_DATE),
            visibleSections(true, false, true, true));
    assertEquals(
            Arrays.asList(
                    LAUGHTER, LOCATION_DATE, MEDIA,
                    CONTEXT_AUDIO, SOCIAL_AND_SUMMARY),
            visibleSections(true, true, true, true));
}
```

- [ ] **Step 2: Run presentation tests and verify RED**

```bash
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentationTest \
  --console=plain
```

Expected: compilation fails because the presentation model does not exist.

- [ ] **Step 3: Implement the minimal pure presentation model**

Return immutable ordered lists, omit `MEDIA` or `CONTEXT_AUDIO` when their
data is absent, and keep Social/User Summary as one combined section.

- [ ] **Step 4: Run presentation tests and verify GREEN**

Run the command from Step 2.

Expected: all order and empty-section cases pass.

- [ ] **Step 5: Commit**

```bash
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowPresentation.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowPresentationTest.java
git commit -m "feat: model aggregate review presentation"
```

---

### Task 7: Render one card per aggregate window

**Files:**
- Create: `atlasapp/src/main/res/layout/item_resurfacing_audio_row.xml`
- Modify: `atlasapp/src/main/res/layout/item_laughter_clip_card.xml`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java`
- Modify: `atlasapp/src/main/res/values/strings.xml`
- Modify: `atlasapp/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: `List<AtlasResurfacingWindowAggregator.Window>` from Task 5 and
  section order from Task 6.
- Produces: one Android card per aggregate bucket with dynamic laughter and
  context audio rows.

- [ ] **Step 1: Add a failing source-contract regression test**

Create the test in
`AtlasResurfacingWindowPresentationTest` that loads the layout XML as text
and asserts the card contains:

```java
assertTrue(layout.contains("windowLaughterAudioContainer"));
assertTrue(layout.contains("clipPhotoStripShort"));
assertTrue(layout.contains("txtClipLocationDate"));
assertTrue(layout.contains("windowContextAudioContainerShort"));
```

Also assert the old single-row IDs
`clipLaughterAudioRow` and `clipContextAudioRowShort` are absent. This test
must fail before the layout refactor and protects the dynamic-row contract
without adding a new Android UI-test dependency.

- [ ] **Step 2: Run the source-contract test and verify RED**

Run the Task 6 test command.

Expected: assertions fail because the dynamic containers do not yet exist.

- [ ] **Step 3: Refactor the card layout**

Create `item_resurfacing_audio_row.xml` containing:

- a 34dp play/pause `ImageView`;
- an `AtlasWaveformView`;
- a progress/duration `TextView`.

Replace the one laughter row with
`windowLaughterAudioContainer`. Move the Short-term media strip after that
container. Keep date/location after media. Put
`windowContextAudioContainerShort` after date/location. In the existing
single expanded section, keep Long-term media first, then
`windowContextAudioContainerLong`, then Social context and User summary.
Remove the default long-term social tag pill.

- [ ] **Step 4: Refactor Activity rendering around aggregate windows**

Replace `renderClipCards()` with `renderWindowCards()`:

```java
List<AtlasResurfacingWindowAggregator.Window> windows =
        AtlasResurfacingWindowAggregator.aggregate(
                audioClips,
                photos,
                videos,
                sessionStartMs,
                sessionClipDurationSec,
                contextNeighborClips,
                AppConfig.LEGACY_CAPTURE_BUNDLE_GROUP_WINDOW_MS);
```

For each window:

- set the badge from `totalLaughterDurationSec`;
- show the bucket wall-clock start/end in the header;
- inflate one audio row for every laughter item;
- populate the window's one media bundle;
- show the event Date/Location;
- inflate one audio row for every assigned context item;
- preserve one shared details toggle;
- use `ResearchIdentifiers.anonymousId("window",
  sessionId + ":" + bucketId)` for expand/collapse logs.

Extract:

```java
private void populateAudioRows(
        LinearLayout container,
        List<JSONObject> clips,
        String mediaType)
```

Each row must call the existing `wireAudioControl(...)`; do not copy or
replace playback state, waveform, gain, progress, or completion logging.

- [ ] **Step 5: Run focused model tests and compile resources**

```bash
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasResurfacingWindowAggregatorTest \
  --tests com.hry.camera.usbcamerademo.AtlasResurfacingWindowPresentationTest \
  --console=plain

sh gradlew :atlasapp:assembleDebug --console=plain
```

Expected: both unit-test classes pass and Android resource/Java compilation
completes successfully.

- [ ] **Step 6: Commit**

```bash
git add \
  atlasapp/src/main/res/layout/item_resurfacing_audio_row.xml \
  atlasapp/src/main/res/layout/item_laughter_clip_card.xml \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/main/res/values/strings.xml \
  atlasapp/src/main/res/values-zh/strings.xml \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasResurfacingWindowPresentationTest.java
git commit -m "feat: render aggregate resurfacing cards"
```

---

### Task 8: Documentation and full regression verification

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-07-29-three-clip-resurfacing-aggregation-design.md` only if implementation names differ from the approved abstract names

**Interfaces:**
- Consumes: completed Tasks 1–7.
- Produces: developer documentation, full test evidence, and a debug APK.

- [ ] **Step 1: Update README behavior documentation**

Document:

- automatic capture now uses `clip duration × 3`;
- default 30-second clip means a 90-second capture/review bucket;
- one aggregate card may contain multiple laughter audio rows;
- media and possible-related-speech files are not reused across cards;
- Short/Long default and collapsed content.

- [ ] **Step 2: Run the complete JVM test suite**

```bash
sh gradlew :atlasapp:testDebugUnitTest --console=plain
```

Expected: all tests pass with zero failures and zero errors. Record the exact
test count from the generated XML reports.

- [ ] **Step 3: Build a fresh debug APK**

```bash
sh gradlew :atlasapp:assembleDebug --console=plain
```

Expected: exit code 0 and a fresh
`atlasapp/build/outputs/apk/debug/atlasapp-debug.apk`.

- [ ] **Step 4: Inspect the final diff and generated artifacts**

```bash
git diff --check
git status --short
git diff --stat fj_ver...HEAD
```

Confirm:

- only intended source, resource, test, and documentation changes are
  tracked;
- `.superpowers/`, generated Gradle files, build outputs, and local APK
  artifacts are not staged;
- the current branch is `fj_aggregate_ver`;
- no Speechmatics, laughter extraction, notification, deletion, or research
  content-logging rules changed.

- [ ] **Step 5: Commit documentation**

```bash
git add README.md
git commit -m "docs: explain three-clip resurfacing windows"
```

- [ ] **Step 6: Push only after final verification**

Fetch the remote, confirm the update is fast-forward, then:

```bash
git push -u origin fj_aggregate_ver
```

Never force-push. Report the final commit, test count, APK path, and remote
branch.
