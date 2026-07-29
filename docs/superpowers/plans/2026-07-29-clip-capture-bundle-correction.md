# 2+1 Capture Bundle Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将一次自动采集统一为带稳定身份的 `2 photos + 1 video` bundle，并让每个 laughter clip 在 `±90s` 内关联和展示最近的整个 bundle。

**Architecture:** 控制器在自动采集触发时创建不可变的 bundle 请求，并把同一请求显式传递到视频与两张延迟照片的排队、保存和日志链路。新媒体持久化 `bundle_id`、bundle 触发时间和媒体序号；读取层透传这些字段，纯 Java 匹配器优先按显式 bundle 分组，对旧数据使用 15 秒时间规则推断，详情页只消费一个匹配结果。

**Tech Stack:** Java 7、Android SDK 28、minSdk 22、`org.json`、JUnit 4、Gradle、现有 Android Support Library。

## Global Constraints

- 每次通过限频策略并真正触发的自动采集固定为 2 张照片和 1 段视频。
- 照片在触发后 `1500ms`、`3500ms` 请求；视频数量固定为 1。
- clip 媒体匹配窗口保持对称 `±90000ms`，恰好 90 秒包含，超过 90 秒排除。
- 旧数据 bundle 推断窗口固定为 `15000ms`。
- 新数据必须显式保存 `bundle_id`、`bundle_trigger_time_ms`、`bundle_media_index`。
- 显式 bundle 某项采集失败时不得从其他 bundle 补齐。
- 同一个 bundle 允许被多个相邻 clip 共用。
- 旧 event JSON 和旧 `trigger_photo_count` 配置必须继续可读，不批量重写历史文件。
- 设置页面不再显示照片数量滑杆，运行时照片数量始终规范为 2。
- 不改变 laughter 检测、event 聚合、120 秒限频、视频时长、通知、地图、编辑或删除逻辑。
- 不新增第三方运行时依赖。
- 所有实现先写失败测试，再写最小实现；每个任务独立提交。
- 所有提交保留在当前 `fj_ver` 分支；未经用户再次明确授权不得 push。

---

## File Structure

### 新建文件

- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequest.java`
  不可变的采集 bundle 请求与照片子请求，只负责身份和元数据。
- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAutoCaptureQueue.java`
  保存 MainActivity 中待执行、已派发的照片与视频请求，保证异步完成时身份不丢失。
- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadata.java`
  统一定义并复制 JSON 中的 bundle 字段。
- `atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentConfigTest.java`
- `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequestTest.java`
- `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAutoCaptureQueueTest.java`
- `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadataTest.java`

### 修改文件

- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java`
  集中固定 bundle 数量、拍摄延迟、旧数据推断窗口。
- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentConfig.java`
  兼容读取旧照片数量，但运行时和输出统一为 2。
- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/SettingsActivity.java`
  移除照片数量滑杆及读写。
- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java`
  创建和传递 bundle 请求；使用请求记录采集日志与保存媒体。
- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java`
  使用 bundle-aware 队列完成视频和照片异步采集。
- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentClusterer.java`
  在结构化媒体记录中保存 bundle 字段。
- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java`
  归一化时透传 bundle 字段。
- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcher.java`
  从独立媒体选择改为显式/推断 bundle 选择。
- `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java`
  一次取得并展示同一 bundle 的 0–2 张照片和 0–1 段视频。
- `atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentMediaAssetTest.java`
- `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcherTest.java`
- `README.md`
- `docs/superpowers/specs/2026-07-29-laughter-playback-media-association-icon-design.md`
- `docs/superpowers/plans/2026-07-29-laughter-playback-media-association-icon.md`

---

### Task 1: Fix the 2+1 configuration invariant and remove the misleading setting

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java:20-75`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentConfig.java:30-110`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/SettingsActivity.java:130-295`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentConfigTest.java`

**Interfaces:**
- Produces: `AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE = 2`
- Produces: `AppConfig.AUTO_CAPTURE_VIDEOS_PER_BUNDLE = 1`
- Produces: `AppConfig.autoCapturePhotoDelayMs(int mediaIndex): long`
- Produces: `AppConfig.LEGACY_CAPTURE_BUNDLE_GROUP_WINDOW_MS = 15000L`
- Preserves: `JoyfulMomentConfig.triggerPhotoCount` as a compatibility field whose runtime value is always 2.

- [ ] **Step 1: Write failing configuration tests**

```java
package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JoyfulMomentConfigTest {
    @Test
    public void everyPresetUsesFixedTwoPhotoBundle() {
        assertEquals(2, JoyfulMomentConfig.preset(
                JoyfulMomentConfig.LEVEL_FREQUENT).triggerPhotoCount);
        assertEquals(2, JoyfulMomentConfig.preset(
                JoyfulMomentConfig.LEVEL_MEDIUM).triggerPhotoCount);
        assertEquals(2, JoyfulMomentConfig.preset(
                JoyfulMomentConfig.LEVEL_SPARSE).triggerPhotoCount);
    }

    @Test
    public void legacyPhotoCountIsAcceptedButNormalized() throws Exception {
        JoyfulMomentConfig config = JoyfulMomentConfig.fromJson(
                new JSONObject().put("trigger_photo_count", 6));
        assertEquals(AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE,
                config.triggerPhotoCount);
        assertEquals(AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE,
                config.toJson().getInt("trigger_photo_count"));
    }

    @Test
    public void fixedPhotoDelaysAreAddressedByMediaIndex() {
        assertEquals(1500L, AppConfig.autoCapturePhotoDelayMs(0));
        assertEquals(3500L, AppConfig.autoCapturePhotoDelayMs(1));
    }
}
```

- [ ] **Step 2: Run the configuration tests and verify failure**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.JoyfulMomentConfigTest \
  --console=plain
```

Expected: compilation fails because the new constants and delay accessor do not exist; after only
adding the test, frequent and sparse presets would also return 3 and 1.

- [ ] **Step 3: Centralize the fixed bundle parameters**

Replace the three preset-specific photo counts and the old per-type display maximum with:

```java
public static final int AUTO_CAPTURE_PHOTOS_PER_BUNDLE = 2;
public static final int AUTO_CAPTURE_VIDEOS_PER_BUNDLE = 1;
public static final long AUTO_CAPTURE_PHOTO_DELAY_1_MS = 1500L;
public static final long AUTO_CAPTURE_PHOTO_DELAY_2_MS = 3500L;
public static final long CLIP_MEDIA_MATCH_WINDOW_MS = 90L * 1000L;
public static final long LEGACY_CAPTURE_BUNDLE_GROUP_WINDOW_MS = 15L * 1000L;

public static long autoCapturePhotoDelayMs(int mediaIndex) {
    if (mediaIndex == 0) {
        return AUTO_CAPTURE_PHOTO_DELAY_1_MS;
    }
    if (mediaIndex == 1) {
        return AUTO_CAPTURE_PHOTO_DELAY_2_MS;
    }
    throw new IllegalArgumentException("Unsupported photo media index: " + mediaIndex);
}
```

Keep `DEFAULT_TRIGGER_PHOTO_COUNT` only as a deprecated alias if another compatibility caller still
references it:

```java
@Deprecated
public static final int DEFAULT_TRIGGER_PHOTO_COUNT =
        AUTO_CAPTURE_PHOTOS_PER_BUNDLE;
```

- [ ] **Step 4: Normalize all old and new config values to 2**

Initialize `triggerPhotoCount` from `AUTO_CAPTURE_PHOTOS_PER_BUNDLE`. Remove frequent/sparse
assignments and force the value after parsing:

```java
config.triggerPhotoCount = AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE;
```

Serialize the compatibility key using the invariant instead of mutable state:

```java
json.put("trigger_photo_count", AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE);
```

- [ ] **Step 5: Remove the Settings photo-count slider**

Delete only these three calls/assignments from `SettingsActivity`:

```java
addSlider(
        "trigger_photo_count",
        R.string.config_trigger_photo,
        R.string.config_trigger_photo_desc,
        0,
        6,
        1);
setSliderValue("trigger_photo_count", config.triggerPhotoCount);
config.triggerPhotoCount = getSliderValue("trigger_photo_count");
```

Before saving, explicitly preserve the invariant:

```java
config.triggerPhotoCount = AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE;
```

Leave the old localized string resources in place for binary/source compatibility; no visible view
will reference them.

- [ ] **Step 6: Run the focused tests**

Run the command from Step 2.

Expected: all `JoyfulMomentConfigTest` tests pass.

- [ ] **Step 7: Commit**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentConfig.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/SettingsActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentConfigTest.java
git commit -m "fix: enforce fixed 2+1 capture bundle"
```

---

### Task 2: Add immutable bundle and photo request identities

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequest.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequestTest.java`

**Interfaces:**
- Produces: `AtlasCaptureBundleRequest.create(String eventId, int automationBucketId, long triggerTimeMs, int videoDurationSec)`
- Produces immutable fields: `bundleId`, `eventId`, `automationBucketId`, `triggerTimeMs`, `videoDurationSec`
- Produces: `photoRequest(int mediaIndex): AtlasCaptureBundleRequest.PhotoRequest`
- Produces immutable photo fields: `bundle`, `mediaIndex`

- [ ] **Step 1: Write failing identity tests**

```java
package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AtlasCaptureBundleRequestTest {
    @Test
    public void createsStableIdentityAndTwoIndexedPhotoRequests() {
        AtlasCaptureBundleRequest bundle =
                AtlasCaptureBundleRequest.create("event-7", 12, 5000L, 5);

        assertEquals("event-7", bundle.eventId);
        assertEquals(12, bundle.automationBucketId);
        assertEquals(5000L, bundle.triggerTimeMs);
        assertEquals(5, bundle.videoDurationSec);
        assertTrue(bundle.bundleId.contains("event-7"));

        AtlasCaptureBundleRequest.PhotoRequest first = bundle.photoRequest(0);
        AtlasCaptureBundleRequest.PhotoRequest second = bundle.photoRequest(1);
        assertSame(bundle, first.bundle);
        assertSame(bundle, second.bundle);
        assertEquals(0, first.mediaIndex);
        assertEquals(1, second.mediaIndex);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPhotoIndexOutsideFixedBundle() {
        AtlasCaptureBundleRequest.create("event-7", 12, 5000L, 5)
                .photoRequest(2);
    }
}
```

- [ ] **Step 2: Run the request tests and verify compilation failure**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasCaptureBundleRequestTest \
  --console=plain
```

Expected: compilation fails because `AtlasCaptureBundleRequest` does not exist.

- [ ] **Step 3: Implement the immutable request**

Use package-private final classes and validate all constructor inputs:

```java
final class AtlasCaptureBundleRequest {
    final String bundleId;
    final String eventId;
    final int automationBucketId;
    final long triggerTimeMs;
    final int videoDurationSec;

    private AtlasCaptureBundleRequest(
            String bundleId,
            String eventId,
            int automationBucketId,
            long triggerTimeMs,
            int videoDurationSec) {
        if (bundleId == null || bundleId.length() == 0) {
            throw new IllegalArgumentException("bundle id missing");
        }
        if (eventId == null || eventId.length() == 0) {
            throw new IllegalArgumentException("event id missing");
        }
        if (triggerTimeMs <= 0L) {
            throw new IllegalArgumentException("trigger time invalid");
        }
        if (videoDurationSec <= 0) {
            throw new IllegalArgumentException("video duration invalid");
        }
        this.bundleId = bundleId;
        this.eventId = eventId;
        this.automationBucketId = automationBucketId;
        this.triggerTimeMs = triggerTimeMs;
        this.videoDurationSec = videoDurationSec;
    }

    static AtlasCaptureBundleRequest create(
            String eventId,
            int automationBucketId,
            long triggerTimeMs,
            int videoDurationSec) {
        String id = eventId + "_capture_" + triggerTimeMs
                + "_bucket_" + automationBucketId;
        return new AtlasCaptureBundleRequest(
                id, eventId, automationBucketId, triggerTimeMs,
                videoDurationSec);
    }

    PhotoRequest photoRequest(int mediaIndex) {
        if (mediaIndex < 0
                || mediaIndex >= AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE) {
            throw new IllegalArgumentException(
                    "Unsupported photo media index: " + mediaIndex);
        }
        return new PhotoRequest(this, mediaIndex);
    }

    static final class PhotoRequest {
        final AtlasCaptureBundleRequest bundle;
        final int mediaIndex;

        private PhotoRequest(
                AtlasCaptureBundleRequest bundle,
                int mediaIndex) {
            this.bundle = bundle;
            this.mediaIndex = mediaIndex;
        }
    }
}
```

- [ ] **Step 4: Run the focused tests**

Run the command from Step 2.

Expected: both request tests pass.

- [ ] **Step 5: Commit**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequestTest.java
git commit -m "feat: add capture bundle request identity"
```

---

### Task 3: Preserve bundle identity through asynchronous camera queues

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAutoCaptureQueue.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAutoCaptureQueueTest.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java:25-35,850-1015`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java:100-120,910-1015,1165-1210,1425-1460,1750-1835`

**Interfaces:**
- Consumes: `AtlasCaptureBundleRequest` and `AtlasCaptureBundleRequest.PhotoRequest`
- Produces queue methods:
  - `enqueueVideo(AtlasCaptureBundleRequest)`
  - `activateNextVideo(): AtlasCaptureBundleRequest`
  - `completeActiveVideo(): AtlasCaptureBundleRequest`
  - `enqueuePhoto(PhotoRequest)`
  - `dispatchNextPhoto(): PhotoRequest`
  - `completeNextPhoto(): PhotoRequest`
  - `drainAllVideos()` and `drainAllPhotos()` for contextual skipped logs
- Updates callbacks:
  - `onJoyfulAutoVideoRequested(AtlasCaptureBundleRequest request)`
  - `onJoyfulAutoPhotoRequested(AtlasCaptureBundleRequest.PhotoRequest request)`

- [ ] **Step 1: Write failing queue-order and identity tests**

```java
package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class AtlasAutoCaptureQueueTest {
    @Test
    public void photoCompletionKeepsOriginalBundleAndIndex() {
        AtlasCaptureBundleRequest first =
                AtlasCaptureBundleRequest.create("event-a", 1, 1000L, 5);
        AtlasCaptureBundleRequest second =
                AtlasCaptureBundleRequest.create("event-b", 2, 2000L, 5);
        AtlasAutoCaptureQueue queue = new AtlasAutoCaptureQueue();

        queue.enqueuePhoto(first.photoRequest(0));
        queue.enqueuePhoto(first.photoRequest(1));
        queue.enqueuePhoto(second.photoRequest(0));

        assertSame(first, queue.dispatchNextPhoto().bundle);
        assertSame(first, queue.dispatchNextPhoto().bundle);
        assertSame(second, queue.dispatchNextPhoto().bundle);
        assertEquals(0, queue.completeNextPhoto().mediaIndex);
        assertEquals(1, queue.completeNextPhoto().mediaIndex);
        assertSame(second, queue.completeNextPhoto().bundle);
    }

    @Test
    public void videosRemainFifoAcrossActivationAndCompletion() {
        AtlasCaptureBundleRequest first =
                AtlasCaptureBundleRequest.create("event-a", 1, 1000L, 5);
        AtlasCaptureBundleRequest second =
                AtlasCaptureBundleRequest.create("event-b", 2, 2000L, 6);
        AtlasAutoCaptureQueue queue = new AtlasAutoCaptureQueue();
        queue.enqueueVideo(first);
        queue.enqueueVideo(second);

        assertSame(first, queue.activateNextVideo());
        assertSame(first, queue.completeActiveVideo());
        assertSame(second, queue.activateNextVideo());
    }
}
```

- [ ] **Step 2: Run the queue tests and verify compilation failure**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasAutoCaptureQueueTest \
  --console=plain
```

Expected: compilation fails because `AtlasAutoCaptureQueue` does not exist.

- [ ] **Step 3: Implement the queue as four explicit FIFO states**

Use `ArrayDeque` for pending videos, queued photos, and dispatched photos, plus one active video:

```java
final class AtlasAutoCaptureQueue {
    private final ArrayDeque<AtlasCaptureBundleRequest> pendingVideos =
            new ArrayDeque<>();
    private final ArrayDeque<AtlasCaptureBundleRequest.PhotoRequest> queuedPhotos =
            new ArrayDeque<>();
    private final ArrayDeque<AtlasCaptureBundleRequest.PhotoRequest> dispatchedPhotos =
            new ArrayDeque<>();
    private AtlasCaptureBundleRequest activeVideo;

    void enqueueVideo(AtlasCaptureBundleRequest request) {
        pendingVideos.addLast(requireBundle(request));
    }

    AtlasCaptureBundleRequest activateNextVideo() {
        if (activeVideo != null || pendingVideos.isEmpty()) {
            return null;
        }
        activeVideo = pendingVideos.removeFirst();
        return activeVideo;
    }

    AtlasCaptureBundleRequest completeActiveVideo() {
        AtlasCaptureBundleRequest completed = activeVideo;
        activeVideo = null;
        return completed;
    }

    void enqueuePhoto(AtlasCaptureBundleRequest.PhotoRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("photo request missing");
        }
        queuedPhotos.addLast(request);
    }

    AtlasCaptureBundleRequest.PhotoRequest dispatchNextPhoto() {
        if (queuedPhotos.isEmpty()) {
            return null;
        }
        AtlasCaptureBundleRequest.PhotoRequest request =
                queuedPhotos.removeFirst();
        dispatchedPhotos.addLast(request);
        return request;
    }

    AtlasCaptureBundleRequest.PhotoRequest completeNextPhoto() {
        return dispatchedPhotos.isEmpty()
                ? null
                : dispatchedPhotos.removeFirst();
    }

    int queuedPhotoCount() {
        return queuedPhotos.size();
    }

    int dispatchedPhotoCount() {
        return dispatchedPhotos.size();
    }

    boolean hasPendingVideo() {
        return !pendingVideos.isEmpty();
    }

    boolean hasWork() {
        return activeVideo != null
                || !pendingVideos.isEmpty()
                || !queuedPhotos.isEmpty()
                || !dispatchedPhotos.isEmpty();
    }

    List<AtlasCaptureBundleRequest> drainAllVideos() {
        ArrayList<AtlasCaptureBundleRequest> drained = new ArrayList<>();
        if (activeVideo != null) {
            drained.add(activeVideo);
            activeVideo = null;
        }
        while (!pendingVideos.isEmpty()) {
            drained.add(pendingVideos.removeFirst());
        }
        return drained;
    }

    List<AtlasCaptureBundleRequest.PhotoRequest> drainAllPhotos() {
        ArrayList<AtlasCaptureBundleRequest.PhotoRequest> drained =
                new ArrayList<>();
        while (!dispatchedPhotos.isEmpty()) {
            drained.add(dispatchedPhotos.removeFirst());
        }
        while (!queuedPhotos.isEmpty()) {
            drained.add(queuedPhotos.removeFirst());
        }
        return drained;
    }

    private AtlasCaptureBundleRequest requireBundle(
            AtlasCaptureBundleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("bundle request missing");
        }
        return request;
    }
}
```

Import `ArrayDeque`, `ArrayList`, and `List`. Do not silently replace an existing request.

- [ ] **Step 4: Update controller dispatch to create one request**

Change `HostCallbacks` to the two request-based signatures. In
`triggerAutomationForDetection(...)`, create exactly one request:

```java
final long triggerTimeMs = System.currentTimeMillis();
final AtlasCaptureBundleRequest request =
        AtlasCaptureBundleRequest.create(
                eventId,
                automationBucketId,
                triggerTimeMs,
                config.triggerVideoDurationSec);
```

Add `bundle_id` and `bundle_trigger_time_ms` to
`automation.triggered_by_detection`, then post the request:

```java
mainHandler.post(new Runnable() {
    @Override
    public void run() {
        hostCallbacks.onJoyfulAutoVideoRequested(request);
    }
});
scheduleAutoPhotos(request);
```

Replace the two hand-written photo callbacks with an indexed loop:

```java
private void scheduleAutoPhotos(
        final AtlasCaptureBundleRequest request) {
    for (int i = 0;
            i < AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE;
            i++) {
        final AtlasCaptureBundleRequest.PhotoRequest photo =
                request.photoRequest(i);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hostCallbacks.onJoyfulAutoPhotoRequested(photo);
            }
        }, AppConfig.autoCapturePhotoDelayMs(i));
    }
}
```

- [ ] **Step 5: Replace MainActivity event-id queues with the bundle queue**

Replace:

```java
mActiveJoyfulAutoVideoEventId
mPendingJoyfulAutoVideoEventId
mPendingJoyfulAutoPhotoEventIds
mQueuedJoyfulAutoPhotoEventIds
```

with:

```java
private final AtlasAutoCaptureQueue mJoyfulAutoCaptureQueue =
        new AtlasAutoCaptureQueue();
private long mActiveJoyfulAutoVideoCaptureTimeMs = 0L;
```

The callback implementations enqueue the exact incoming request. Camera drain calls
`activateNextVideo()` and `dispatchNextPhoto()`. Video save calls `completeActiveVideo()`;
photo save calls `completeNextPhoto()`. Clear/failure paths iterate the drained request lists and
report each skipped item with its own bundle context.

Never call `getLastTriggeredEventId()` from an asynchronous photo/video callback or completion path.

- [ ] **Step 6: Run queue tests and compile the App**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew \
  :atlasapp:testDebugUnitTest \
  :atlasapp:assembleDebug \
  --console=plain
```

Expected: queue tests pass and debug APK compilation succeeds with the new callback signatures.

- [ ] **Step 7: Commit**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAutoCaptureQueue.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequest.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAutoCaptureQueueTest.java
git commit -m "fix: preserve bundle identity through camera queues"
```

---

### Task 4: Persist bundle metadata and include it in capture logs

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentClusterer.java:80-180`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java:990-1120,1160-1195`
- Modify: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentMediaAssetTest.java`

**Interfaces:**
- Extends: `MediaAssetRecord(path, contentUri, captureTimeMs, bundleId, bundleTriggerTimeMs, bundleMediaIndex)`
- Preserves: existing three-argument `MediaAssetRecord` constructor for old callers/tests.
- Updates controller completion methods to consume `AtlasCaptureBundleRequest` and photo media index.

- [ ] **Step 1: Extend the media serialization test so it fails**

Add a photo and video with the same bundle identity:

```java
JoyfulMomentClusterer.MediaAssetRecord photo =
        new JoyfulMomentClusterer.MediaAssetRecord(
                "/session/event_photo_1000.jpg",
                null,
                1500L,
                "bundle-1",
                1000L,
                0);
JoyfulMomentClusterer.MediaAssetRecord video =
        new JoyfulMomentClusterer.MediaAssetRecord(
                "/session/event_video_2000.mp4",
                "content://video/2",
                1100L,
                "bundle-1",
                1000L,
                0);
```

Assert both JSON objects contain:

```java
assertEquals("bundle-1", json.getString("bundle_id"));
assertEquals(1000L, json.getLong("bundle_trigger_time_ms"));
assertEquals(0, json.getInt("bundle_media_index"));
```

Also keep one three-argument legacy record and assert it does not contain `bundle_id`.

- [ ] **Step 2: Run the media-asset test and verify compilation failure**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.JoyfulMomentMediaAssetTest \
  --console=plain
```

Expected: compilation fails because the six-argument constructor does not exist.

- [ ] **Step 3: Extend MediaAssetRecord with optional bundle fields**

Add:

```java
public final String bundleId;
public final long bundleTriggerTimeMs;
public final int bundleMediaIndex;
```

The old constructor delegates using `null`, `-1L`, `-1`. The new constructor assigns all fields.
Serialize bundle fields only when `bundleId` is non-empty:

```java
if (bundleId != null && bundleId.length() > 0) {
    json.put("bundle_id", bundleId);
    json.put("bundle_trigger_time_ms", bundleTriggerTimeMs);
    json.put("bundle_media_index", bundleMediaIndex);
}
```

- [ ] **Step 4: Make controller start/save/skip APIs bundle-aware**

Use these exact signatures from MainActivity:

```java
void onAutoVideoCaptureStarted(
        AtlasCaptureBundleRequest request,
        long captureTimeMs)
void onAutoVideoSaved(
        AtlasCaptureBundleRequest request,
        String path,
        String contentUri,
        long captureTimeMs)
void onAutoPhotoSaved(
        AtlasCaptureBundleRequest.PhotoRequest request,
        String path,
        long captureTimeMs)
void onAutoVideoCaptureSkipped(
        AtlasCaptureBundleRequest request,
        String reason)
void onAutoPhotoCaptureSkipped(
        AtlasCaptureBundleRequest.PhotoRequest request,
        String reason)
```

Construct structured assets from request data:

```java
new JoyfulMomentClusterer.MediaAssetRecord(
        stablePath,
        contentUri,
        captureTimeMs,
        request.bundleId,
        request.triggerTimeMs,
        0)
```

and:

```java
new JoyfulMomentClusterer.MediaAssetRecord(
        stablePath,
        null,
        captureTimeMs,
        request.bundle.bundleId,
        request.bundle.triggerTimeMs,
        request.mediaIndex)
```

All started/saved/skipped/save_failed records must include `event_id`, `bundle_id`, and
`bundle_trigger_time_ms`; photo records also include `bundle_media_index`.

- [ ] **Step 5: Run focused tests and compile**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew \
  :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.JoyfulMomentMediaAssetTest \
  :atlasapp:assembleDebug \
  --console=plain
```

Expected: serialization tests pass and App compilation succeeds.

- [ ] **Step 6: Commit**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentClusterer.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentMediaAssetTest.java
git commit -m "feat: persist capture bundle metadata"
```

---

### Task 5: Preserve bundle metadata during repository normalization

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadata.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadataTest.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java:950-1040,1160-1235`

**Interfaces:**
- Produces: `AtlasCaptureBundleMetadata.copyIfPresent(JSONObject source, JSONObject target)`
- Copies only valid `bundle_id`, `bundle_trigger_time_ms`, and `bundle_media_index`.
- Repository calls the helper for both normalized photos and normalized videos.

- [ ] **Step 1: Write failing metadata-copy tests**

```java
package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class AtlasCaptureBundleMetadataTest {
    @Test
    public void copiesCompleteBundleMetadata() throws Exception {
        JSONObject source = new JSONObject()
                .put("bundle_id", "bundle-2")
                .put("bundle_trigger_time_ms", 9000L)
                .put("bundle_media_index", 1);
        JSONObject target = new JSONObject();

        AtlasCaptureBundleMetadata.copyIfPresent(source, target);

        assertEquals("bundle-2", target.getString("bundle_id"));
        assertEquals(9000L, target.getLong("bundle_trigger_time_ms"));
        assertEquals(1, target.getInt("bundle_media_index"));
    }

    @Test
    public void oldRecordStaysWithoutInventedBundleIdentity() throws Exception {
        JSONObject target = new JSONObject();
        AtlasCaptureBundleMetadata.copyIfPresent(
                new JSONObject().put("capture_time_ms", 9000L),
                target);
        assertFalse(target.has("bundle_id"));
        assertFalse(target.has("bundle_trigger_time_ms"));
        assertFalse(target.has("bundle_media_index"));
    }
}
```

- [ ] **Step 2: Run the metadata tests and verify compilation failure**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasCaptureBundleMetadataTest \
  --console=plain
```

Expected: compilation fails because `AtlasCaptureBundleMetadata` does not exist.

- [ ] **Step 3: Implement strict optional-field copying**

```java
final class AtlasCaptureBundleMetadata {
    private AtlasCaptureBundleMetadata() {
    }

    static void copyIfPresent(
            JSONObject source,
            JSONObject target) throws JSONException {
        if (source == null || target == null) {
            return;
        }
        String bundleId = source.optString("bundle_id", "");
        long triggerTimeMs =
                source.optLong("bundle_trigger_time_ms", -1L);
        int mediaIndex = source.optInt("bundle_media_index", -1);
        if (bundleId.length() == 0
                || triggerTimeMs <= 0L
                || mediaIndex < 0) {
            return;
        }
        target.put("bundle_id", bundleId);
        target.put("bundle_trigger_time_ms", triggerTimeMs);
        target.put("bundle_media_index", mediaIndex);
    }
}
```

- [ ] **Step 4: Call the helper from both repository paths**

In `appendNormalizedPhoto(...)` and `appendNormalizedVideo(...)`, call:

```java
AtlasCaptureBundleMetadata.copyIfPresent(timeSource, photo);
```

or:

```java
AtlasCaptureBundleMetadata.copyIfPresent(timeSource, video);
```

Call it after resolved time handling and before appending to the output array. Do not call it for a
legacy synthetic `timeSource` that has no bundle fields; the helper intentionally leaves output
unchanged.

- [ ] **Step 5: Run focused tests**

Run the command from Step 2, then:

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasMediaCaptureTimeResolverTest \
  --tests com.hry.camera.usbcamerademo.AtlasCaptureBundleMetadataTest \
  --console=plain
```

Expected: old time-resolution behavior and new metadata propagation tests pass.

- [ ] **Step 6: Commit**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadata.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadataTest.java
git commit -m "feat: normalize capture bundle metadata"
```

---

### Task 6: Match an entire explicit or inferred bundle

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcher.java`
- Modify: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcherTest.java`

**Interfaces:**
- Replaces: `findNearestPath(...)`
- Produces: `findNearestBundle(JSONArray photos, JSONArray videos, long clipTimeMs, long maxDeltaMs, long legacyGroupWindowMs): MatchedCaptureBundle`
- Produces immutable match fields: `bundleId`, `bundleTimeMs`, `photoPaths`, `videoPaths`
- Returns `null` when no accessible candidate bundle qualifies.

- [ ] **Step 1: Replace independent-media tests with failing explicit-bundle tests**

Use `TemporaryFolder` files. Add a helper:

```java
private JSONObject item(
        File file,
        String pathKey,
        long captureTimeMs,
        String bundleId,
        long bundleTimeMs,
        int mediaIndex) throws Exception {
    return new JSONObject()
            .put(pathKey, file.getAbsolutePath())
            .put("capture_time_ms", captureTimeMs)
            .put("bundle_id", bundleId)
            .put("bundle_trigger_time_ms", bundleTimeMs)
            .put("bundle_media_index", mediaIndex);
}
```

Test the correct 2+1 result:

```java
@Test
public void explicitBundleReturnsTwoPhotosAndOneVideo() throws Exception {
    File p0 = temporaryFolder.newFile("p0.jpg");
    File p1 = temporaryFolder.newFile("p1.jpg");
    File video = temporaryFolder.newFile("v.mp4");
    JSONArray photos = new JSONArray()
            .put(item(p0, "photo_path", 11500L, "b1", 10000L, 0))
            .put(item(p1, "photo_path", 13500L, "b1", 10000L, 1));
    JSONArray videos = new JSONArray()
            .put(item(video, "video_path", 10100L, "b1", 10000L, 0));

    AtlasClipMediaMatcher.MatchedCaptureBundle match =
            AtlasClipMediaMatcher.findNearestBundle(
                    photos, videos, 9000L, 90000L, 15000L);

    assertEquals(2, match.photoPaths.size());
    assertEquals(p0.getAbsolutePath(), match.photoPaths.get(0));
    assertEquals(p1.getAbsolutePath(), match.photoPaths.get(1));
    assertEquals(1, match.videoPaths.size());
    assertEquals(video.getAbsolutePath(), match.videoPaths.get(0));
}
```

Test that adjacent bundles never mix:

```java
@Test
public void explicitPartialBundleDoesNotBorrowFromNeighbor() throws Exception {
    File nearPhoto = temporaryFolder.newFile("near.jpg");
    File otherPhoto = temporaryFolder.newFile("other.jpg");
    File otherVideo = temporaryFolder.newFile("other.mp4");
    JSONArray photos = new JSONArray()
            .put(item(nearPhoto, "photo_path", 10000L, "near", 10000L, 0))
            .put(item(otherPhoto, "photo_path", 20000L, "other", 20000L, 0));
    JSONArray videos = new JSONArray()
            .put(item(otherVideo, "video_path", 20000L, "other", 20000L, 0));

    AtlasClipMediaMatcher.MatchedCaptureBundle match =
            AtlasClipMediaMatcher.findNearestBundle(
                    photos, videos, 10000L, 90000L, 15000L);

    assertEquals("near", match.bundleId);
    assertEquals(1, match.photoPaths.size());
    assertEquals(0, match.videoPaths.size());
}
```

Also add separate tests for:

- exact 90-second inclusion and 90001ms rejection;
- deterministic earlier-time then bundle-id tie break;
- same bundle reused by two clip calls;
- inaccessible closest file omitted while other files in the same bundle remain;
- output photo order by `bundle_media_index`;
- maximum 2 photos and 1 video even if malformed input contains extras.

- [ ] **Step 2: Add failing legacy inference tests**

Create legacy items with only path and `capture_time_ms`:

```java
@Test
public void legacyMediaWithinFifteenSecondsFormsTwoPlusOneBundle()
        throws Exception {
    File video = temporaryFolder.newFile("legacy.mp4");
    File p0 = temporaryFolder.newFile("legacy0.jpg");
    File p1 = temporaryFolder.newFile("legacy1.jpg");
    JSONArray photos = new JSONArray()
            .put(legacyItem(p0, "photo_path", 11500L))
            .put(legacyItem(p1, "photo_path", 13500L));
    JSONArray videos = new JSONArray()
            .put(legacyItem(video, "video_path", 10000L));

    AtlasClipMediaMatcher.MatchedCaptureBundle match =
            AtlasClipMediaMatcher.findNearestBundle(
                    photos, videos, 9000L, 90000L, 15000L);

    assertEquals(2, match.photoPaths.size());
    assertEquals(1, match.videoPaths.size());
}

@Test
public void legacyPhotoOutsideGroupingWindowIsNotBorrowed()
        throws Exception {
    File video = temporaryFolder.newFile("legacy.mp4");
    File near = temporaryFolder.newFile("near.jpg");
    File far = temporaryFolder.newFile("far.jpg");
    JSONArray photos = new JSONArray()
            .put(legacyItem(near, "photo_path", 11000L))
            .put(legacyItem(far, "photo_path", 30001L));
    JSONArray videos = new JSONArray()
            .put(legacyItem(video, "video_path", 10000L));

    AtlasClipMediaMatcher.MatchedCaptureBundle match =
            AtlasClipMediaMatcher.findNearestBundle(
                    photos, videos, 10000L, 90000L, 15000L);

    assertEquals(1, match.photoPaths.size());
    assertEquals(1, match.videoPaths.size());
}
```

Add a photo-only legacy test where two photos within 15 seconds form a bundle and no video is
invented.

- [ ] **Step 3: Run matcher tests and verify failure**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasClipMediaMatcherTest \
  --console=plain
```

Expected: compilation fails because `findNearestBundle` and `MatchedCaptureBundle` do not exist.

- [ ] **Step 4: Implement candidate parsing and explicit grouping**

Inside `AtlasClipMediaMatcher`, define private `MediaCandidate` and `BundleCandidate` records and:

```java
static final class MatchedCaptureBundle {
    final String bundleId;
    final long bundleTimeMs;
    final List<String> photoPaths;
    final List<String> videoPaths;
}
```

Parse only objects with a positive `capture_time_ms`, non-empty path, and `new File(path).isFile()`.
Group records with non-empty `bundle_id` in a `HashMap<String, BundleCandidate>`. Set group time to
the earliest positive `bundle_trigger_time_ms`; when absent, use the earliest capture time.

Sort photos by media index, capture time, then path and keep indices 0–1. Sort videos by media
index, capture time, then path and keep the first item.

- [ ] **Step 5: Implement deterministic legacy grouping**

For untagged records:

1. Sort videos and photos by capture time then path.
2. Build every video/photo possible pair whose absolute delta is at most
   `legacyGroupWindowMs`.
3. Sort pairs by delta, video time, photo time, video path, then photo path.
4. Greedily assign a photo only if it is unassigned and its video bundle has fewer than 2 photos.
5. Create one inferred bundle per video, including videos that receive no photos.
6. Sort remaining photos by time/path; pair each first photo with the next remaining photo only when
   their delta is at most `legacyGroupWindowMs`, otherwise create a one-photo bundle.
7. Use video time as inferred bundle time, or first-photo time for photo-only bundles.
8. Give inferred bundles an in-memory ID such as
   `"legacy@" + bundleTimeMs + ":" + anchorPath`; never persist it.

- [ ] **Step 6: Select one whole bundle**

Reject invalid clip times/windows. Keep bundles with:

```java
Math.abs(bundle.bundleTimeMs - clipTimeMs) <= maxDeltaMs
```

Choose the smallest absolute delta; on ties choose the earlier `bundleTimeMs`, then lexicographically
smaller `bundleId`. Convert only the winning candidate to an immutable
`MatchedCaptureBundle`. Do not maintain cross-call used state.

- [ ] **Step 7: Run matcher tests**

Run the command from Step 3.

Expected: all explicit grouping, partial failure, reuse, boundary, legacy grouping and deterministic
ordering tests pass.

- [ ] **Step 8: Commit**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcher.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcherTest.java
git commit -m "fix: match laughter clips to 2+1 bundles"
```

---

### Task 7: Render the matched bundle in both detail modes

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:540-590,850-915`

**Interfaces:**
- Consumes: `AtlasClipMediaMatcher.findNearestBundle(...)`
- Uses: `MatchedCaptureBundle.photoPaths` and `videoPaths`
- Preserves current photo viewer, video player, visibility rules, and short/long layouts.

- [ ] **Step 1: Replace the two independent collection calls**

At clip binding time, call the matcher once:

```java
AtlasClipMediaMatcher.MatchedCaptureBundle mediaBundle =
        AtlasClipMediaMatcher.findNearestBundle(
                photos,
                videos,
                deviceTimeMs,
                AppConfig.CLIP_MEDIA_MATCH_WINDOW_MS,
                AppConfig.LEGACY_CAPTURE_BUNDLE_GROUP_WINDOW_MS);
List<String> clipPhotoPaths = mediaBundle == null
        ? Collections.<String>emptyList()
        : mediaBundle.photoPaths;
List<String> clipVideoPaths = mediaBundle == null
        ? Collections.<String>emptyList()
        : mediaBundle.videoPaths;
```

Delete `collectNearbyPhotoPaths`, `collectNearbyVideoPaths`, and `singletonMediaPath`.

- [ ] **Step 2: Render stable media order without placeholders**

In `populatePhotoStrip(...)`, iterate photos first and video second so the visible order is:

```text
photo index 0 → photo index 1 → video index 0
```

Keep the current click handlers. Keep both short- and long-term visibility expressions based on
both lists being empty; if the selected bundle has no accessible media, hide the section.

- [ ] **Step 3: Run matcher regression and compile**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew \
  :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasClipMediaMatcherTest \
  :atlasapp:assembleDebug \
  --console=plain
```

Expected: matcher tests pass and both detail layouts compile against the bundle result.

- [ ] **Step 4: Commit**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java
git commit -m "fix: render full capture bundle per clip"
```

---

### Task 8: Correct developer documentation and historical design references

**Files:**
- Modify: `README.md:90-150,190-220,270-285,470-490`
- Modify: `docs/superpowers/specs/2026-07-29-laughter-playback-media-association-icon-design.md:110-170`
- Modify: `docs/superpowers/plans/2026-07-29-laughter-playback-media-association-icon.md:1-30`
- Reference: `docs/superpowers/specs/2026-07-29-clip-capture-bundle-correction-design.md`

**Interfaces:**
- Documents event JSON bundle fields and `2 photos + 1 video` behavior.
- Marks the old one-photo/one-video plan as superseded for media association only.

- [ ] **Step 1: Update README architecture and data examples**

Change the automatic-media row to state that every accepted throttled trigger creates one fixed
2+1 bundle. Add these fields to both photo and video JSON examples:

```json
{
  "bundle_id": "event-7_capture_1785295800000_bucket_12",
  "bundle_trigger_time_ms": 1785295800000,
  "bundle_media_index": 0
}
```

Replace the old independent matching bullets with:

```markdown
- 每个 clip 在 ±90 秒内选择最近的一个采集 bundle；
- 一个 bundle 固定对应 2 张照片和 1 段视频；
- 同一 bundle 可被多个相邻 clip 共用；
- bundle 部分采集失败时只展示成功项，不从其他 bundle 补齐；
- 旧数据没有 bundle_id 时，以 15 秒窗口按拍摄时间恢复。
```

Update the core-file and test tables to describe bundle grouping rather than nearest single media.

- [ ] **Step 2: Correct the previous design document**

Replace its “照片和视频独立匹配、每类最多 1 个” section with a correction note linking to:

```markdown
[`2026-07-29-clip-capture-bundle-correction-design.md`](2026-07-29-clip-capture-bundle-correction-design.md)
```

Summarize the current rule as one nearest `2 photos + 1 video` bundle and keep its existing audio
gain/icon sections unchanged.

- [ ] **Step 3: Mark the historical implementation plan as superseded**

Add immediately below its title:

```markdown
> **媒体关联勘误（2026-07-29）：** 本计划中“每类最多 1 个”的媒体关联方案已被
> `2026-07-29-clip-capture-bundle-correction.md` 取代。当前规则为每个 clip
> 匹配最近的一个固定 `2 photos + 1 video` bundle。本计划的音频增益和图标任务仍有效。
```

Do not rewrite its historical task commands; the prominent erratum prevents reuse while retaining
implementation history.

- [ ] **Step 4: Scan for stale product claims**

```sh
rg -n \
  "每个 clip 最多显示一张|照片和视频独立匹配|每类最多选择.?1|CLIP_MEDIA_MAX_PER_TYPE" \
  README.md \
  docs/superpowers/specs \
  atlasapp/src/main/java \
  atlasapp/src/test/java
```

Expected: no active README/spec/source/test claim remains. A historical implementation-plan match
is acceptable only underneath the prominent erratum.

- [ ] **Step 5: Check Markdown and Git whitespace**

```sh
git diff --check
```

Expected: no output.

- [ ] **Step 6: Commit**

```sh
git add \
  README.md \
  docs/superpowers/specs/2026-07-29-laughter-playback-media-association-icon-design.md \
  docs/superpowers/plans/2026-07-29-laughter-playback-media-association-icon.md
git commit -m "docs: correct clip capture bundle behavior"
```

---

### Task 9: Run full regression, inspect artifacts, and record completion

**Files:**
- Modify only if verification reveals a defect in an in-scope file.
- Inspect: `atlasapp/build/reports/tests/testDebugUnitTest/index.html`
- Inspect: `atlasapp/build/outputs/apk/debug/atlasapp-debug.apk`

**Interfaces:**
- Verifies all tasks as one integrated Android application.
- Does not install with ADB unless the user asks again.
- Does not push.

- [ ] **Step 1: Run all JVM unit tests**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL` and zero failing tests.

- [ ] **Step 2: Build the debug APK**

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL` and
`atlasapp/build/outputs/apk/debug/atlasapp-debug.apk` exists.

- [ ] **Step 3: Verify the APK and test counts**

```sh
ls -lh atlasapp/build/outputs/apk/debug/atlasapp-debug.apk
rg -n \
  'tests=\"[0-9]+\"|failures=\"[1-9]|errors=\"[1-9]' \
  atlasapp/build/test-results/testDebugUnitTest
```

Expected: APK has non-zero size; XML summaries report zero failures and zero errors.

- [ ] **Step 4: Inspect the final diff and forbidden stale code**

```sh
git diff HEAD~8 --check
rg -n \
  "getLastTriggeredEventId\\(\\).*Auto|CLIP_MEDIA_MAX_PER_TYPE|FREQUENT_TRIGGER_PHOTO_COUNT|SPARSE_TRIGGER_PHOTO_COUNT" \
  atlasapp/src/main/java \
  atlasapp/src/test/java
git status --short --branch
```

Expected: no whitespace errors; no asynchronous auto-capture path resolves its identity from
`getLastTriggeredEventId()`; no active variable-photo or single-media constants remain; only known
untracked `.superpowers/` and `artifacts/` may remain.

- [ ] **Step 5: Review acceptance criteria against evidence**

Confirm from tests and source:

1. one trigger creates one bundle request;
2. request schedules exactly two indexed photos and one video;
3. all saved media share `bundle_id`;
4. repository preserves bundle metadata;
5. explicit matches never cross-fill;
6. legacy 2+1 grouping works inside 15 seconds;
7. clip window stays inclusive at 90 seconds;
8. both detail modes consume the same match;
9. settings no longer expose photo count;
10. full unit suite and APK build pass.

- [ ] **Step 6: Commit any verification-only correction**

If Steps 1–5 required an in-scope correction, rerun the failing verification first, then commit only
that correction:

```sh
git add \
  README.md \
  docs/superpowers/specs/2026-07-29-laughter-playback-media-association-icon-design.md \
  docs/superpowers/plans/2026-07-29-laughter-playback-media-association-icon.md \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentConfig.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/SettingsActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequest.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasAutoCaptureQueue.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentClusterer.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadata.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcher.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentConfigTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleRequestTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasAutoCaptureQueueTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/JoyfulMomentMediaAssetTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCaptureBundleMetadataTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasClipMediaMatcherTest.java
git commit -m "fix: complete capture bundle regression"
```

If no correction was needed, do not create an empty commit.

- [ ] **Step 7: Report without pushing**

Report:

- commit list created by Tasks 1–8;
- test count and result;
- APK absolute path and size;
- any remaining limitations of old-data inference;
- confirmation that `fj_ver` is ahead of `origin/fj_ver`;
- explicit statement that no push was performed.
