# Laughter Playback, Clip Media Association, and Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不修改原始研究音频和既有采集/回顾功能的前提下，为过小笑声提供保持相对强弱的 App 内播放增强，为每个 laughter clip 关联 `±90s` 内最近的一张照片和一个视频，并统一替换为“笑声涟漪”图标。

**Architecture:** 音频部分由纯增益策略和 PCM16 WAV 播放副本准备器组成，`EventDetailActivity` 只负责异步请求、生命周期和现有 `MediaPlayer` UI。媒体部分在采集时写入明确的 `capture_time_ms`，读取旧数据时由独立解析器恢复可信时间，再由纯匹配器完成每类最多一个的窗口选择。图标继续使用现有 Android 资源名，通过 VectorDrawable、adaptive icon 和五档 legacy PNG 同步更新。

**Tech Stack:** Java 7/Android SDK 28、minSdk 22、`MediaPlayer`、PCM 16-bit little-endian WAV、`org.json`、JUnit 4、Android VectorDrawable/adaptive icon、SVG、`sips`、Gradle。

## Global Constraints

- 音量增强只作用于 App 播放；原始 WAV 不得修改、覆盖或写回研究数据目录。
- 所有 clip 只增不减；quiet threshold 为 `-24 dBFS`，补偿比例为 `75%`，最大增益为 `+18 dB`。
- 使用 `20ms` 帧，并以响度最高 `5%` 帧的平均 RMS 测量有效响度。
- 输出超过 `-1 dBFS` 保护点的部分使用连续 `tanh` 软饱和。
- 增强副本只放入 App cache；任何解析、写入或缓存失败都回退到原 WAV。
- 每个 clip 分别选择 `±90s` 内最近的 `1` 张照片和 `1` 个视频；恰好 `90s` 包含，超过则排除。
- 同一媒体允许被多个 clip 共用；没有合格媒体时隐藏内容，不做远距离替代填充。
- 新照片保存拍摄触发时间，新视频保存录制开始时间，字段名统一为 `capture_time_ms`。
- 旧媒体时间只可来自明确字段、`event_photo_<ms>` / `event_video_<ms>` 文件名或可信文件修改时间，不可使用 event 开始时间伪造。
- 图标采用已确认的 A“笑声涟漪”，沿用暖橙和米白主题，不改变页面尺寸、点击区域和导航。
- 不新增第三方运行时依赖，不改变采集、检测、事件聚合、补充、删除、地图和通知策略。
- 所有实现先写失败测试，再写最小实现；每个任务独立提交。
- 所有提交保留在当前 `fj_ver` 分支；未经用户再次明确授权不得 push。

---

### Task 1: Centralize playback parameters and implement the monotonic gain policy

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLaughterGainPolicy.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasLaughterGainPolicyTest.java`

**Interfaces:**
- Consumes: measured effective loudness in dBFS.
- Produces: `AtlasLaughterGainPolicy.computeGainDb(double measuredDbfs): double`.
- Later tasks rely on the exact `AppConfig` constant names shown below.

- [ ] **Step 1: Write the failing policy tests**

Create `AtlasLaughterGainPolicyTest.java`:

```java
package com.hry.camera.usbcamerademo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AtlasLaughterGainPolicyTest {
    @Test
    public void normalClipIsNeverReduced() {
        assertEquals(0.0, AtlasLaughterGainPolicy.computeGainDb(-18.0), 0.0001);
        assertEquals(0.0, AtlasLaughterGainPolicy.computeGainDb(-24.0), 0.0001);
    }

    @Test
    public void quietClipReceivesPartialPositiveCompensation() {
        assertEquals(6.0, AtlasLaughterGainPolicy.computeGainDb(-32.0), 0.0001);
        assertEquals(15.0, AtlasLaughterGainPolicy.computeGainDb(-44.0), 0.0001);
    }

    @Test
    public void boostIsCappedAndInvalidMeasurementsStaySafe() {
        assertEquals(18.0, AtlasLaughterGainPolicy.computeGainDb(-80.0), 0.0001);
        assertEquals(0.0, AtlasLaughterGainPolicy.computeGainDb(Double.NaN), 0.0001);
        assertEquals(0.0, AtlasLaughterGainPolicy.computeGainDb(Double.NEGATIVE_INFINITY), 0.0001);
    }

    @Test
    public void outputLevelRemainsMonotonic() {
        double quietOutput = -44.0 + AtlasLaughterGainPolicy.computeGainDb(-44.0);
        double mediumOutput = -32.0 + AtlasLaughterGainPolicy.computeGainDb(-32.0);
        double loudOutput = -18.0 + AtlasLaughterGainPolicy.computeGainDb(-18.0);
        assertTrue(quietOutput < mediumOutput);
        assertTrue(mediumOutput < loudOutput);
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasLaughterGainPolicyTest \
  --console=plain
```

Expected: compilation fails because `AtlasLaughterGainPolicy` does not exist.

- [ ] **Step 3: Add the exact configuration constants**

Append an “App-only laughter playback enhancement” group to `AppConfig`:

```java
public static final int LAUGHTER_PLAYBACK_FRAME_MS = 20;
public static final double LAUGHTER_PLAYBACK_TOP_FRAME_RATIO = 0.05;
public static final double LAUGHTER_PLAYBACK_QUIET_THRESHOLD_DBFS = -24.0;
public static final double LAUGHTER_PLAYBACK_COMPENSATION_RATIO = 0.75;
public static final double LAUGHTER_PLAYBACK_MAX_BOOST_DB = 18.0;
public static final double LAUGHTER_PLAYBACK_PEAK_GUARD_DBFS = -1.0;
public static final int LAUGHTER_PLAYBACK_ALGORITHM_VERSION = 1;
```

- [ ] **Step 4: Implement the minimal monotonic policy**

Create `AtlasLaughterGainPolicy.java`:

```java
package com.hry.camera.usbcamerademo;

final class AtlasLaughterGainPolicy {
    private AtlasLaughterGainPolicy() {
    }

    static double computeGainDb(double measuredDbfs) {
        if (Double.isNaN(measuredDbfs) || Double.isInfinite(measuredDbfs)) {
            return 0.0;
        }
        double gap = AppConfig.LAUGHTER_PLAYBACK_QUIET_THRESHOLD_DBFS - measuredDbfs;
        if (gap <= 0.0) {
            return 0.0;
        }
        double requested = gap * AppConfig.LAUGHTER_PLAYBACK_COMPENSATION_RATIO;
        return Math.max(0.0, Math.min(
                AppConfig.LAUGHTER_PLAYBACK_MAX_BOOST_DB,
                requested));
    }
}
```

- [ ] **Step 5: Run the policy tests**

Run the command from Step 2.

Expected: all four tests pass.

- [ ] **Step 6: Commit the policy**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLaughterGainPolicy.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasLaughterGainPolicyTest.java
git commit -m "feat: add monotonic laughter playback gain policy"
```

---

### Task 2: Build deterministic PCM16 WAV analysis, enhancement, and cache preparation

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLaughterPlaybackPreparer.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasLaughterPlaybackPreparerTest.java`

**Interfaces:**
- Consumes: an original WAV `File` and an App cache directory.
- Produces: `AtlasLaughterPlaybackPreparer.prepare(File source, File cacheDir): Result`.
- `Result` exposes `playbackFile`, `gainDb`, `algorithmVersion`, `enhanced`, and `fallbackReason`.
- Task 3 must log and play from this result while retaining the original path as the research media identity.

- [ ] **Step 1: Write failing fixture-based tests**

Create tests that write real mono PCM16 WAV fixtures and assert:

```java
@Test
public void loudestFivePercentFramesIgnoreLongPaddingSilence() throws Exception {
    File source = writeWavWithSilenceAndTone(
            16000, 5, 200, amplitudeForDbfs(-32.0));
    AtlasLaughterPlaybackPreparer.Result result =
            AtlasLaughterPlaybackPreparer.prepare(source, cacheFolder.getRoot());
    assertTrue(result.enhanced);
    assertEquals(6.0, result.gainDb, 0.6);
}

@Test
public void normalClipUsesOriginalFileWithoutReducingIt() throws Exception {
    File source = writeConstantToneWav(16000, 300, amplitudeForDbfs(-18.0));
    byte[] before = readAll(source);
    AtlasLaughterPlaybackPreparer.Result result =
            AtlasLaughterPlaybackPreparer.prepare(source, cacheFolder.getRoot());
    assertFalse(result.enhanced);
    assertEquals(source.getCanonicalFile(), result.playbackFile.getCanonicalFile());
    assertArrayEquals(before, readAll(source));
}

@Test
public void quietClipCreatesReusableEnhancedCacheAndKeepsOriginalBytes() throws Exception {
    File source = writeConstantToneWav(16000, 300, amplitudeForDbfs(-44.0));
    byte[] before = readAll(source);
    AtlasLaughterPlaybackPreparer.Result first =
            AtlasLaughterPlaybackPreparer.prepare(source, cacheFolder.getRoot());
    AtlasLaughterPlaybackPreparer.Result second =
            AtlasLaughterPlaybackPreparer.prepare(source, cacheFolder.getRoot());
    assertTrue(first.enhanced);
    assertEquals(15.0, first.gainDb, 0.6);
    assertEquals(first.playbackFile.getCanonicalFile(), second.playbackFile.getCanonicalFile());
    assertArrayEquals(before, readAll(source));
}

@Test
public void peakProtectionKeepsEveryOutputSampleInsidePcm16Range() throws Exception {
    File source = writeImpulseAndQuietToneWav();
    AtlasLaughterPlaybackPreparer.Result result =
            AtlasLaughterPlaybackPreparer.prepare(source, cacheFolder.getRoot());
    for (short sample : readPcm16Samples(result.playbackFile)) {
        assertTrue(sample >= Short.MIN_VALUE);
        assertTrue(sample <= Short.MAX_VALUE);
    }
}

@Test
public void brokenWaveFallsBackToOriginalWithoutThrowing() throws Exception {
    File source = cacheFolder.newFile("broken.wav");
    writeBytes(source, new byte[] {1, 2, 3});
    AtlasLaughterPlaybackPreparer.Result result =
            AtlasLaughterPlaybackPreparer.prepare(source, cacheFolder.getRoot());
    assertFalse(result.enhanced);
    assertEquals(source.getCanonicalFile(), result.playbackFile.getCanonicalFile());
    assertEquals("unsupported_or_invalid_wav", result.fallbackReason);
}
```

The fixture writer must emit a standard RIFF/WAVE header with PCM format `1`, mono channel,
16-bit samples, correct `sampleRate`, `byteRate`, `blockAlign`, and `data` chunk length.

- [ ] **Step 2: Run the tests and verify they fail**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasLaughterPlaybackPreparerTest \
  --console=plain
```

Expected: compilation fails because the preparer and `Result` do not exist.

- [ ] **Step 3: Implement WAV parsing and effective loudness measurement**

Implement a package-private preparer with this public-to-package interface:

```java
final class AtlasLaughterPlaybackPreparer {
    static final class Result {
        final File playbackFile;
        final double gainDb;
        final int algorithmVersion;
        final boolean enhanced;
        final String fallbackReason;

        Result(File playbackFile, double gainDb, boolean enhanced, String fallbackReason) {
            this.playbackFile = playbackFile;
            this.gainDb = gainDb;
            this.algorithmVersion = AppConfig.LAUGHTER_PLAYBACK_ALGORITHM_VERSION;
            this.enhanced = enhanced;
            this.fallbackReason = fallbackReason;
        }
    }

    static Result prepare(File source, File cacheDir) {
        // Validate source, parse RIFF chunks, analyze, cache, and always return a playable fallback.
    }
}
```

The parser must:

```text
accept RIFF + WAVE
find fmt and data chunks even when another chunk appears between them
accept audioFormat=1, bitsPerSample=16, channels>=1
use blockAlign to advance complete frames
reject truncated chunks and incomplete sample frames
```

For each `20ms` frame, compute RMS across all channel samples. Sort frame RMS values ascending,
select `max(1, ceil(frameCount * 0.05))` frames from the loud end, average their RMS, then convert:

```java
double measuredDbfs = 20.0 * Math.log10(
        Math.max(1.0 / 32768.0, topFrameRms / 32768.0));
double gainDb = AtlasLaughterGainPolicy.computeGainDb(measuredDbfs);
```

- [ ] **Step 4: Implement sample gain and soft peak protection**

For `gainDb > 0`, multiply samples by:

```java
double linearGain = Math.pow(10.0, gainDb / 20.0);
double guard = Math.pow(10.0,
        AppConfig.LAUGHTER_PLAYBACK_PEAK_GUARD_DBFS / 20.0);
double amplified = sample / 32768.0 * linearGain;
double magnitude = Math.abs(amplified);
double protectedMagnitude = magnitude <= guard
        ? magnitude
        : guard + (1.0 - guard)
                * Math.tanh((magnitude - guard) / (1.0 - guard));
short output = (short) Math.round(
        Math.max(-1.0, Math.min(32767.0 / 32768.0,
                Math.copySign(protectedMagnitude, amplified))) * 32768.0);
```

Copy the source file to a temporary file in `cacheDir/laughter_playback`, transform only the data
chunk sample bytes, preserve all other RIFF bytes, flush and close, then atomically rename the
temporary file to its cache key.

The cache key must be SHA-256 over:

```text
canonical source path | source length | source lastModified | algorithm version
```

If `gainDb == 0`, return the original source without creating a cache file. A RIFF/format/chunk/read
failure returns the original source with `fallbackReason="unsupported_or_invalid_wav"`. A cache
directory, temporary-file, write, flush, close, or rename failure returns the original source with
`fallbackReason="cache_write_failed"`.

- [ ] **Step 5: Run preparer and existing waveform tests**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasLaughterPlaybackPreparerTest \
  --tests com.hry.camera.usbcamerademo.AtlasWavWaveformExtractorTest \
  --console=plain
```

Expected: both classes pass; existing waveform extraction remains unchanged.

- [ ] **Step 6: Commit the deterministic playback preparer**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLaughterPlaybackPreparer.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasLaughterPlaybackPreparerTest.java
git commit -m "feat: prepare enhanced laughter playback copies"
```

---

### Task 3: Integrate asynchronous enhanced playback without changing audio controls

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:55-110`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:290-305`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:1210-1415`

**Interfaces:**
- Consumes: `AtlasLaughterPlaybackPreparer.Result` from Task 2.
- Produces: the existing playback UI behavior plus `gain_db` and `gain_algorithm_version` on
  `media_play_started` events.
- Original `binding.path` remains the media identity; only `MediaPlayer.setDataSource` receives
  `result.playbackFile`.

- [ ] **Step 1: Add preparation state fields**

Add:

```java
private final ExecutorService audioPreparationExecutor =
        Executors.newSingleThreadExecutor();
private long audioPreparationGeneration = 0L;
private double activePlaybackGainDb = 0.0;
private int activePlaybackGainAlgorithmVersion =
        AppConfig.LAUGHTER_PLAYBACK_ALGORITHM_VERSION;
```

- [ ] **Step 2: Split request preparation from MediaPlayer preparation**

Change `startAudioPlayback` so it validates the original path, calls `stopAudioPlayback("replaced")`,
records a new request token, disables only the selected row during preparation, and submits:

```java
final long requestGeneration = ++audioPreparationGeneration;
final File originalFile = new File(path);
audioPreparationExecutor.execute(new Runnable() {
    @Override
    public void run() {
        final AtlasLaughterPlaybackPreparer.Result result =
                AtlasLaughterPlaybackPreparer.prepare(
                        originalFile,
                        new File(getCacheDir(), "laughter_playback"));
        audioUiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()
                        || requestGeneration != audioPreparationGeneration
                        || !path.equals(activePlaybackPath)) {
                    return;
                }
                prepareMediaPlayer(binding, path, mediaType, result);
            }
        });
    }
});
```

Move the current `new MediaPlayer()` through `prepareAsync()` block into:

```java
private void prepareMediaPlayer(
        final AudioRowBinding binding,
        final String originalPath,
        final String mediaType,
        final AtlasLaughterPlaybackPreparer.Result result)
```

Use:

```java
player.setDataSource(result.playbackFile.getAbsolutePath());
activePlaybackGainDb = result.gainDb;
activePlaybackGainAlgorithmVersion = result.algorithmVersion;
```

Keep `activePlaybackPath`, `audioStatus`, anonymous `media_item_id`, waveform source, and all
failure messages tied to `originalPath`, not the cache path.

- [ ] **Step 3: Make stale requests and Activity teardown harmless**

At the start of `stopAudioPlayback` and in `onDestroy`, invalidate outstanding work:

```java
audioPreparationGeneration += 1L;
```

In `onDestroy`, also call:

```java
audioPreparationExecutor.shutdownNow();
```

The UI callback must check generation, Activity state, and the current original path before creating
or starting a player. Re-enable the old binding through the existing `finishAudioPlayback` path.

- [ ] **Step 4: Add gain metadata to playback-start logs**

For initial start and resume, include:

```java
"gain_db", activePlaybackGainDb,
"gain_algorithm_version", activePlaybackGainAlgorithmVersion
```

Do not log the cache path or measured raw amplitude. Completion, pause, and failure schemas otherwise
remain unchanged.

- [ ] **Step 5: Compile and run the audio regression tests**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasLaughterGainPolicyTest \
  --tests com.hry.camera.usbcamerademo.AtlasLaughterPlaybackPreparerTest \
  --tests com.hry.camera.usbcamerademo.AtlasAudioPlaybackStateTest \
  --tests com.hry.camera.usbcamerademo.AtlasWavWaveformExtractorTest \
  --console=plain
```

Expected: all pass and `EventDetailActivity` compiles.

- [ ] **Step 6: Commit playback integration**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java
git commit -m "feat: enhance quiet laughter during app playback"
```

---

### Task 4: Persist exact photo and video capture times while keeping legacy fields

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentClusterer.java:81-138`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java:991-1056`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java:90-125`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java:950-980`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java:1170-1190`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java:1410-1445`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentMediaAssetTest.java`

**Interfaces:**
- Produces: `JoyfulMomentClusterer.MediaAssetRecord(path, contentUri, captureTimeMs)`.
- Produces JSON: `assets.photo_records[].capture_time_ms` and
  `assets.videos[].capture_time_ms`.
- Keeps legacy `assets.photos`, `assets.video`, `assets.video_content_uri`, and string/path lists.
- Task 5 consumes the exact `capture_time_ms` fields.

- [ ] **Step 1: Write failing serialization tests**

```java
@Test
public void eventJsonKeepsLegacyPathsAndAddsStructuredCaptureTimes() throws Exception {
    JoyfulMomentClusterer.EventRecord event =
            new JoyfulMomentClusterer.EventRecord();
    event.photoPaths.add("/session/event_photo_1000.jpg");
    event.videoPaths.add("/session/event_video_2000.mp4");
    event.videoContentUris.add("content://video/2");
    event.photoAssets.add(new JoyfulMomentClusterer.MediaAssetRecord(
            "/session/event_photo_1000.jpg", null, 1000L));
    event.videoAssets.add(new JoyfulMomentClusterer.MediaAssetRecord(
            "/session/event_video_2000.mp4", "content://video/2", 2000L));

    JSONObject assets = event.toJson().getJSONObject("assets");
    assertEquals("/session/event_photo_1000.jpg", assets.getJSONArray("photos").getString(0));
    assertEquals(1000L, assets.getJSONArray("photo_records")
            .getJSONObject(0).getLong("capture_time_ms"));
    assertEquals(2000L, assets.getJSONArray("videos")
            .getJSONObject(0).getLong("capture_time_ms"));
    assertEquals("content://video/2", assets.getJSONArray("videos")
            .getJSONObject(0).getString("content_uri"));
}
```

- [ ] **Step 2: Run the test and verify it fails**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.JoyfulMomentMediaAssetTest \
  --console=plain
```

Expected: compilation fails because `MediaAssetRecord`, `photoAssets`, and `videoAssets` do not exist.

- [ ] **Step 3: Add the structured media record and serialization**

Inside `JoyfulMomentClusterer` add:

```java
public static final class MediaAssetRecord {
    public final String path;
    public final String contentUri;
    public final long captureTimeMs;

    public MediaAssetRecord(String path, String contentUri, long captureTimeMs) {
        this.path = path;
        this.contentUri = contentUri;
        this.captureTimeMs = captureTimeMs;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("path", path);
        if (contentUri != null) {
            json.put("content_uri", contentUri);
        }
        json.put("capture_time_ms", captureTimeMs);
        return json;
    }
}
```

Add `photoAssets` and `videoAssets` to `EventRecord`. Serialize `photo_records` from
`photoAssets`. Serialize `videos` from `videoAssets` when present; if it is empty, retain the current
legacy loop over `videoPaths`. Continue serializing `photos` as the current string array.

- [ ] **Step 4: Thread video start time through MainActivity and the controller**

Add:

```java
private long mActiveJoyfulAutoVideoCaptureTimeMs = 0L;
```

Immediately before `startRecord()`:

```java
mActiveJoyfulAutoVideoCaptureTimeMs = System.currentTimeMillis();
mJoyfulController.onAutoVideoCaptureStarted(
        mActiveJoyfulAutoVideoEventId,
        mActiveJoyfulAutoVideoCaptureTimeMs);
```

On save, call:

```java
mJoyfulController.onAutoVideoSaved(
        mActiveJoyfulAutoVideoEventId,
        mMediaVideoPath,
        uri != null ? uri.toString() : null,
        mActiveJoyfulAutoVideoCaptureTimeMs);
```

Reset the active timestamp whenever auto recording succeeds, fails, or is cleared. In the controller,
add the four-argument save overload and append both the existing legacy lists and one
`MediaAssetRecord`.

- [ ] **Step 5: Thread photo trigger time through the existing callback**

The camera callback already supplies `dateTaken`. Change the save call to:

```java
mJoyfulController.onAutoPhotoSaved(
        joyfulAutoPhotoEventId,
        path,
        dateTaken);
```

In the controller, add the three-argument overload and append both `photoPaths` and a
`MediaAssetRecord(stablePath, null, captureTimeMs)`. Keep existing overloads delegating with
`System.currentTimeMillis()` so no older internal call site breaks.

- [ ] **Step 6: Run serialization tests and compile**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.JoyfulMomentMediaAssetTest \
  --console=plain
```

Expected: pass.

- [ ] **Step 7: Commit exact media capture timestamps**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentClusterer.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentMediaAssetTest.java
git commit -m "feat: persist media capture timestamps"
```

---

### Task 5: Recover trustworthy media times for old and normalized events

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasMediaCaptureTimeResolver.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java:930-1105`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java:1125-1185`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasMediaCaptureTimeResolverTest.java`

**Interfaces:**
- Produces: `AtlasMediaCaptureTimeResolver.resolve(JSONObject item, String pathKey, long eventStartMs, long eventEndMs): long`.
- Returns a positive epoch time or `-1L`; never substitutes `eventStartMs`.
- Normalized media items expose `capture_time_ms` only when resolution succeeds.

- [ ] **Step 1: Write failing resolver tests**

```java
@Test
public void explicitCaptureTimeWins() throws Exception {
    File file = temporaryFolder.newFile("event_photo_1111.jpg");
    JSONObject item = new JSONObject()
            .put("photo_path", file.getAbsolutePath())
            .put("capture_time_ms", 5000L);
    assertEquals(5000L, AtlasMediaCaptureTimeResolver.resolve(
            item, "photo_path", 1000L, 9000L));
}

@Test
public void legacyStableFilenameRecoversMilliseconds() throws Exception {
    File file = temporaryFolder.newFile("event_video_1720000000123.mp4");
    JSONObject item = new JSONObject().put("video_path", file.getAbsolutePath());
    assertEquals(1720000000123L, AtlasMediaCaptureTimeResolver.resolve(
            item, "video_path", 1719999999000L, 1720000001000L));
}

@Test
public void plausibleFileTimeIsLastFallback() throws Exception {
    File file = temporaryFolder.newFile("legacy.jpg");
    assertTrue(file.setLastModified(5000L));
    JSONObject item = new JSONObject().put("photo_path", file.getAbsolutePath());
    assertEquals(5000L, AtlasMediaCaptureTimeResolver.resolve(
            item, "photo_path", 4000L, 6000L));
}

@Test
public void syntheticTimestampAndImplausibleFileTimeDoNotBecomeCaptureTime()
        throws Exception {
    File file = temporaryFolder.newFile("legacy.jpg");
    assertTrue(file.setLastModified(50000L));
    JSONObject item = new JSONObject()
            .put("photo_path", file.getAbsolutePath())
            .put("timestamp", "1970-01-01T00:00:04.000+0000");
    assertEquals(-1L, AtlasMediaCaptureTimeResolver.resolve(
            item, "photo_path", 4000L, 6000L));
}
```

- [ ] **Step 2: Run the resolver tests and verify they fail**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasMediaCaptureTimeResolverTest \
  --console=plain
```

Expected: compilation fails because the resolver does not exist.

- [ ] **Step 3: Add media constants and implement priority resolution**

Add:

```java
public static final long CLIP_MEDIA_MATCH_WINDOW_MS = 90L * 1000L;
public static final int CLIP_MEDIA_MAX_PER_TYPE = 1;
```

Implement:

```java
static long resolve(
        JSONObject item,
        String pathKey,
        long eventStartMs,
        long eventEndMs) {
    long explicit = item.optLong("capture_time_ms", -1L);
    if (explicit > 0L) {
        return explicit;
    }
    String path = item.optString(pathKey, "");
    long filenameTime = parseStableFilenameTime(new File(path).getName());
    if (filenameTime > 0L) {
        return filenameTime;
    }
    File file = new File(path);
    long modified = file.isFile() ? file.lastModified() : -1L;
    long lower = eventStartMs - AppConfig.CLIP_MEDIA_MATCH_WINDOW_MS;
    long upper = eventEndMs + AppConfig.CLIP_MEDIA_MATCH_WINDOW_MS;
    return modified > 0L && modified >= lower && modified <= upper
            ? modified : -1L;
}
```

`parseStableFilenameTime` must accept only:

```regex
^event_(?:photo|video)_(\d{10,17})(?:\.[^.]+)?$
```

Do not inspect or parse the legacy `timestamp` field because current normalization may have filled it
with event start time.

- [ ] **Step 4: Normalize new and old media into reliable capture times**

Update raw event normalization:

```text
assets.photo_records objects first
assets.photos legacy strings second, deduplicated by path
assets.videos objects with capture_time_ms when present
assets.video singular legacy path last, deduplicated
```

For every normalized object, use keys:

```json
{"photo_path":"...","capture_time_ms":1720000000123}
{"video_path":"...","content_uri":"...","capture_time_ms":1720000000456}
```

Only write `capture_time_ms` and formatted `timestamp` when the resolver returns a positive value.
When `raw.has("auto_captured")`, extend `ensureNormalizedCollections` with a
`backfillMediaCaptureTimes(event)` pass that examines existing normalized arrays and adds only
trustworthy times. Never use event start time as the fallback for media items.

- [ ] **Step 5: Run time recovery and deletion regression tests**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasMediaCaptureTimeResolverTest \
  --tests com.hry.camera.usbcamerademo.AtlasEventDeletionPathsTest \
  --console=plain
```

Expected: pass; owned media deletion still recognizes normalized paths.

- [ ] **Step 6: Commit backward-compatible media time normalization**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasMediaCaptureTimeResolver.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasMediaCaptureTimeResolverTest.java
git commit -m "feat: recover trustworthy legacy media times"
```

---

### Task 6: Match and display only the nearest photo and video within ±90 seconds

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcher.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:516-585`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:828-865`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcherTest.java`

**Interfaces:**
- Consumes normalized arrays with `photo_path` / `video_path` and `capture_time_ms`.
- Produces `findNearestPath(JSONArray items, String pathKey, long clipTimeMs, long maxDeltaMs): String`.
- Returns `null` when no accessible candidate qualifies.

- [ ] **Step 1: Write failing nearest-media tests**

Use real temporary files so accessibility behavior is tested:

```java
@Test
public void choosesNearestIndependentCandidateInsideInclusiveWindow() throws Exception {
    File early = temporaryFolder.newFile("event_photo_1000.jpg");
    File near = temporaryFolder.newFile("event_photo_2000.jpg");
    JSONArray items = new JSONArray()
            .put(item(early, 1000L))
            .put(item(near, 2000L));
    assertEquals(near.getAbsolutePath(), AtlasClipMediaMatcher.findNearestPath(
            items, "photo_path", 2500L, 1500L));
}

@Test
public void exactBoundaryIsIncludedAndOutsideBoundaryIsRejected() throws Exception {
    File boundary = temporaryFolder.newFile("event_video_1000.mp4");
    JSONArray items = new JSONArray().put(item(boundary, 1000L));
    assertEquals(boundary.getAbsolutePath(), AtlasClipMediaMatcher.findNearestPath(
            items, "video_path", 91000L, 90000L));
    assertNull(AtlasClipMediaMatcher.findNearestPath(
            items, "video_path", 91001L, 90000L));
}

@Test
public void equalDeltaPrefersEarlierThenLexicographicPath() throws Exception {
    File earlier = temporaryFolder.newFile("a.jpg");
    File later = temporaryFolder.newFile("b.jpg");
    JSONArray items = new JSONArray()
            .put(item(later, 11000L))
            .put(item(earlier, 9000L));
    assertEquals(earlier.getAbsolutePath(), AtlasClipMediaMatcher.findNearestPath(
            items, "photo_path", 10000L, 90000L));
}

@Test
public void missingUnknownAndTooDistantItemsReturnNull() throws Exception {
    JSONArray items = new JSONArray()
            .put(new JSONObject().put("photo_path", "/missing.jpg")
                    .put("capture_time_ms", 10000L))
            .put(new JSONObject().put("photo_path", "/unknown.jpg"));
    assertNull(AtlasClipMediaMatcher.findNearestPath(
            items, "photo_path", 200000L, 90000L));
}

@Test
public void sameMediaCanBeSelectedForNeighboringClips() throws Exception {
    File shared = temporaryFolder.newFile("shared.jpg");
    JSONArray items = new JSONArray().put(item(shared, 10000L));
    assertEquals(shared.getAbsolutePath(), AtlasClipMediaMatcher.findNearestPath(
            items, "photo_path", 9000L, 90000L));
    assertEquals(shared.getAbsolutePath(), AtlasClipMediaMatcher.findNearestPath(
            items, "photo_path", 11000L, 90000L));
}
```

- [ ] **Step 2: Run the matcher tests and verify they fail**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasClipMediaMatcherTest \
  --console=plain
```

Expected: compilation fails because the matcher does not exist.

- [ ] **Step 3: Implement deterministic nearest selection**

Implement a single pass that skips null items, non-positive timestamps, empty paths, unavailable
files, and candidates with `abs(captureTimeMs - clipTimeMs) > maxDeltaMs`. Replace the current best
when:

```java
delta < bestDelta
        || (delta == bestDelta && captureTimeMs < bestTime)
        || (delta == bestDelta && captureTimeMs == bestTime
                && path.compareTo(bestPath) < 0)
```

Return `null` for invalid `clipTimeMs`, invalid window, or no match. Do not keep global “used media”
state; reuse across neighboring calls is required.

- [ ] **Step 4: Replace EventDetailActivity's all-media collection**

Replace `collectNearbyPhotoPaths` and `collectNearbyVideoPaths` with:

```java
private List<String> collectNearbyPhotoPaths(JSONArray photos, long clipTimeMs) {
    return singletonPath(AtlasClipMediaMatcher.findNearestPath(
            photos,
            "photo_path",
            clipTimeMs,
            AppConfig.CLIP_MEDIA_MATCH_WINDOW_MS));
}

private List<String> collectNearbyVideoPaths(JSONArray videos, long clipTimeMs) {
    return singletonPath(AtlasClipMediaMatcher.findNearestPath(
            videos,
            "video_path",
            clipTimeMs,
            AppConfig.CLIP_MEDIA_MATCH_WINDOW_MS));
}

private List<String> singletonPath(String path) {
    ArrayList<String> result = new ArrayList<>();
    if (!TextUtils.isEmpty(path)) {
        result.add(path);
    }
    return result;
}
```

Keep the current visibility expressions unchanged: long-term hides both
`labelPhotoVideoLong` and `photoStripLong` when both lists are empty; short-term hides
`photoStripShort` under the same combined-empty condition.

- [ ] **Step 5: Run matcher and existing detail-state tests**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasClipMediaMatcherTest \
  --tests com.hry.camera.usbcamerademo.AtlasAudioPlaybackStateTest \
  --tests com.hry.camera.usbcamerademo.AtlasEventDeletionPathsTest \
  --console=plain
```

Expected: pass.

- [ ] **Step 6: Commit clip-scoped media display**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcher.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcherTest.java
git commit -m "feat: associate clips with nearby media"
```

---

### Task 7: Replace smile, launcher, and notification artwork with “laughter ripples”

**Files:**
- Create: `docs/assets/laughter-ripples-launcher.svg`
- Create: `docs/assets/laughter-ripples-launcher-round.svg`
- Modify: `atlasapp/src/main/res/drawable/ic_atlas_laughing_face.xml`
- Modify: `atlasapp/src/main/res/drawable/ic_atlas_laughter.xml`
- Modify: `atlasapp/src/main/res/drawable/ic_atlas_notification.xml`
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
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasForegroundService.java:118-140`

**Interfaces:**
- Preserves resource names already referenced by layouts, menus, manifest, README, and notifications.
- Produces adaptive icon foreground, five density-specific square and round legacy icons, two in-App
  smile vectors, and one Android-compliant monochrome notification icon.

- [ ] **Step 1: Replace the 48dp in-App laughing face**

Use the approved symmetric geometry in `ic_atlas_laughing_face.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp" android:height="48dp"
    android:viewportWidth="48" android:viewportHeight="48">
    <path android:fillColor="#FFF38A32"
        android:pathData="M24,4A20,20 0,1 0,24 44A20,20 0,0 0,24 4"/>
    <path android:fillColor="#00000000" android:strokeColor="#FFFFFFFF"
        android:strokeWidth="3.2" android:strokeLineCap="round"
        android:pathData="M13,21Q17,16 21,21M27,21Q31,16 35,21"/>
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M13,27Q24,39 35,27Q32,39 24,40Q16,39 13,27Z"/>
</vector>
```

Create the 24dp navigation version from the same face proportions, with dark gray fill and transparent
background so selected-state tinting continues to work. Keep the eyes and mouth centered; do not
reuse the current side-heavy mouth path.

- [ ] **Step 2: Replace the launcher adaptive foreground**

Use a `108×108` viewport with:

```text
orange face circle centered at (54,39), radius 20
white symmetric closed-eye strokes around y=36
white centered open laugh from y=44..58
near orange ripple from (36,64) through (54,76) to (72,64)
far dark-orange ripple from (26,74) through (54,87) to (82,74)
```

Keep all essential geometry inside x=`20..88`, y=`19..87` so Android adaptive masks do not remove a
ripple or facial feature. Set `ic_launcher_background.xml` to the existing cream `#FFF5E9`.

- [ ] **Step 3: Create deterministic square and round SVG masters**

Use this shape language in the tracked `512×512` source:

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
  <rect width="512" height="512" rx="112" fill="#FFF5E9"/>
  <circle cx="256" cy="190" r="100" fill="#F38A32"/>
  <path d="M190 181Q212 153 234 181M278 181Q300 153 322 181"
        fill="none" stroke="#FFF" stroke-width="22" stroke-linecap="round"/>
  <path d="M190 220Q256 303 322 220Q307 303 256 311Q205 303 190 220Z" fill="#FFF"/>
  <path d="M170 340Q256 400 342 340" fill="none"
        stroke="#F38A32" stroke-width="25" stroke-linecap="round"/>
  <path d="M110 390Q256 475 402 390" fill="none"
        stroke="#E67821" stroke-width="22" stroke-linecap="round" opacity=".72"/>
</svg>
```

For the round master, replace the rounded rectangle with `<circle cx="256" cy="256" r="256">` and
keep the mark unchanged.

- [ ] **Step 4: Rasterize all legacy launcher resources**

```sh
mkdir -p /tmp/atlas-laughter-icon
sips -s format png docs/assets/laughter-ripples-launcher.svg \
  --out /tmp/atlas-laughter-icon/square.png
sips -s format png docs/assets/laughter-ripples-launcher-round.svg \
  --out /tmp/atlas-laughter-icon/round.png

sips -z 48 48 /tmp/atlas-laughter-icon/square.png \
  --out atlasapp/src/main/res/mipmap-mdpi/ic_launcher.png
sips -z 48 48 /tmp/atlas-laughter-icon/round.png \
  --out atlasapp/src/main/res/mipmap-mdpi/ic_launcher_round.png
sips -z 72 72 /tmp/atlas-laughter-icon/square.png \
  --out atlasapp/src/main/res/mipmap-hdpi/ic_launcher.png
sips -z 72 72 /tmp/atlas-laughter-icon/round.png \
  --out atlasapp/src/main/res/mipmap-hdpi/ic_launcher_round.png
sips -z 96 96 /tmp/atlas-laughter-icon/square.png \
  --out atlasapp/src/main/res/mipmap-xhdpi/ic_launcher.png
sips -z 96 96 /tmp/atlas-laughter-icon/round.png \
  --out atlasapp/src/main/res/mipmap-xhdpi/ic_launcher_round.png
sips -z 144 144 /tmp/atlas-laughter-icon/square.png \
  --out atlasapp/src/main/res/mipmap-xxhdpi/ic_launcher.png
sips -z 144 144 /tmp/atlas-laughter-icon/round.png \
  --out atlasapp/src/main/res/mipmap-xxhdpi/ic_launcher_round.png
sips -z 192 192 /tmp/atlas-laughter-icon/square.png \
  --out atlasapp/src/main/res/mipmap-xxxhdpi/ic_launcher.png
sips -z 192 192 /tmp/atlas-laughter-icon/round.png \
  --out atlasapp/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png
```

- [ ] **Step 5: Unify notification small icons**

Replace `ic_atlas_notification.xml` with a white-only simplified symmetric laughing face that has no
background color or semi-transparent pixels. Keep `AtlasNotificationHelper` on
`R.drawable.ic_atlas_notification` and change:

```java
builder.setSmallIcon(R.mipmap.ic_launcher)
```

in `AtlasForegroundService` to:

```java
builder.setSmallIcon(R.drawable.ic_atlas_notification)
```

- [ ] **Step 6: Inspect and build the artwork**

Open and inspect the `xxxhdpi` square and round PNG. Verify the mouth is centered, two ripple layers
remain legible at `48×48`, no essential feature is clipped, and no launcher/pin/map silhouette
remains. Then run:

```sh
sh gradlew :atlasapp:assembleDebug --console=plain
```

Expected: Android resource linking and APK build succeed.

- [ ] **Step 7: Commit the icon system**

```sh
git add docs/assets/laughter-ripples-launcher.svg \
  docs/assets/laughter-ripples-launcher-round.svg \
  atlasapp/src/main/res/drawable/ic_atlas_laughing_face.xml \
  atlasapp/src/main/res/drawable/ic_atlas_laughter.xml \
  atlasapp/src/main/res/drawable/ic_atlas_notification.xml \
  atlasapp/src/main/res/drawable-v24/ic_launcher_foreground.xml \
  atlasapp/src/main/res/drawable/ic_launcher_background.xml \
  atlasapp/src/main/res/mipmap-*/ic_launcher.png \
  atlasapp/src/main/res/mipmap-*/ic_launcher_round.png \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasForegroundService.java
git commit -m "style: adopt laughter ripples icon system"
```

---

### Task 8: Run full regression verification and document the resulting APK

**Files:**
- Verify only: all files changed in Tasks 1–7.
- Build output: `atlasapp/build/outputs/apk/debug/atlasapp-debug.apk` (do not commit unless the user separately requests an artifact commit).

**Interfaces:**
- Consumes all prior tasks.
- Produces a tested local branch and a debug APK ready for optional phone installation.

- [ ] **Step 1: Run all focused tests together**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasLaughterGainPolicyTest \
  --tests com.hry.camera.usbcamerademo.AtlasLaughterPlaybackPreparerTest \
  --tests com.hry.camera.usbcamerademo.JoyfulMomentMediaAssetTest \
  --tests com.hry.camera.usbcamerademo.AtlasMediaCaptureTimeResolverTest \
  --tests com.hry.camera.usbcamerademo.AtlasClipMediaMatcherTest \
  --tests com.hry.camera.usbcamerademo.AtlasAudioPlaybackStateTest \
  --tests com.hry.camera.usbcamerademo.AtlasWavWaveformExtractorTest \
  --tests com.hry.camera.usbcamerademo.AtlasEventDeletionPathsTest \
  --console=plain
```

Expected: all pass.

- [ ] **Step 2: Run the complete debug unit-test suite**

```sh
sh gradlew :atlasapp:testDebugUnitTest --console=plain
```

Expected: exit code `0`, no previously passing test regresses.

- [ ] **Step 3: Build the debug APK**

```sh
sh gradlew :atlasapp:assembleDebug --console=plain
test -f atlasapp/build/outputs/apk/debug/atlasapp-debug.apk
shasum -a 256 atlasapp/build/outputs/apk/debug/atlasapp-debug.apk
```

Expected: APK exists and a SHA-256 value is printed.

- [ ] **Step 4: Inspect scope and data-safety invariants**

```sh
git diff --check
git status --short
git diff HEAD~7 -- \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReminderSchedule.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLocationReminderPolicy.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasResurfacingSelector.java
```

Expected: no whitespace errors; reminder policy files have no diff; `.superpowers/` and pre-existing
`artifacts/` remain untracked and are not staged.

- [ ] **Step 5: Perform the no-ADB manual checklist if a phone is available**

```text
open a normal-volume laughter clip → playback is not reduced
open a very quiet laughter clip → playback is audibly raised
pause/resume → icon, waveform, and elapsed time remain synchronized
rapidly switch clips → only the last selected clip starts
open adjacent clips → each shows at most one nearest photo and one nearest video
open a clip with no media inside ±90s → photo/video section is absent
check launcher, home, review, map, bottom nav, and notification icons
clear App cache → the original WAV still plays and enhancement can regenerate
```

This checklist does not authorize ADB installation. Install only if the user asks.

- [ ] **Step 6: Report completion without pushing**

Report:

```text
focused test result
full unit-test result
APK build result and absolute APK path
APK SHA-256
commits created
remaining manual-only checks, if any
```

Do not push. Wait for explicit user authorization.
