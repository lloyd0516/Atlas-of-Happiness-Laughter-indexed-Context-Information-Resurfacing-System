# Research Interaction Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有采集、回顾和通知行为的前提下，将 14 天研究所需的 session 时长、App 页面访问、moment 操作、媒体使用、地图浏览、设置修改和通知响应逐事件写入一个手机本地 JSONL 文件。

**Architecture:** 新增一个独立的 `ResearchInteractionLogger` facade，统一构造带 schema、时间、participant/session/moment/notification 标识的记录，再交给同步、追加并 `fsync` 的 `ResearchJsonlWriter`。session、页面和媒体时长分别由小型状态类计算，通知通过唯一实例 ID、Intent extras 和 dismiss receiver 完成 posted/opened/dismissed 归因；所有业务入口只提交不含原文、路径、坐标的结构化属性。

**Tech Stack:** Android Java、Android Support Library 28、`org.json`、`SharedPreferences`、JSON Lines、JUnit 4.12。

## Global Constraints

- 输出文件固定为 App 私有外部存储 `joyful_moment/research_interaction_log.jsonl`；`getExternalFilesDir(null)` 不可用时回退 `getFilesDir()`。
- 日志只追加、不覆盖、不轮转，跨 App 重启和多个 capture session 共用一个文件。
- “佩戴时间”严格等于 capture session 成功开始至停止的运行时间，不使用 USB 连接时长或 App 前台时长替代。
- 不记录补充内容原文、转录、Speechmatics payload、经纬度、详细地址、媒体文件路径、媒体二进制和 API 凭据。
- moment 删除后保留已有研究日志，并追加 `moment_deleted`。
- 日志写入失败不得改变采集、保存、播放、删除、页面跳转或通知调度的结果。
- `schema_version` 从 `1` 开始；事件名和属性名一旦进入研究 APK 不做静默重命名。
- 不新增第三方 analytics、数据库或网络上传依赖。
- 本阶段不增加导出 UI；研究结束后手动从手机复制文件。
- 保持 `minSdk 22`、`targetSdk 28`、`compileSdk 28`、Java 8 和现有 Android Support Library。
- 不提交 `.superpowers/`、`artifacts/`、`.gradle/`、`atlasapp/build/`、`local.properties` 或任何 API key。
- 未获得用户明确许可前，不 push 远端。

---

## File Structure

### 新增生产代码

| 文件 | 单一职责 |
| --- | --- |
| `ResearchEventNames.java` | 集中定义 schema v1 事件名和公共 Intent extra 名 |
| `ResearchLogRecord.java` | 纯 Java 构造一条完整、可序列化的研究记录 |
| `ResearchJsonlWriter.java` | 顺序追加、落盘同步和有限内存重试 |
| `ResearchIdentifiers.java` | 生成通知实例 ID，并把媒体路径/地点 key 单向哈希为匿名 ID |
| `ResearchInteractionLogger.java` | Android facade：补齐版本、设备、参与者和时间字段，写入统一文件 |
| `ResearchSessionTiming.java` | 纯 Java 计算正常/异常 session 时长 |
| `ResearchSessionTracker.java` | 用 `SharedPreferences` 保存活动 session，并记录 start/stop/interrupted |
| `ResearchVisitTimer.java` | 纯 Java 累计页面可见区间 |
| `ResearchScreenTracker.java` | 将 Activity resumed/paused 区间写成 `screen_opened`/`screen_closed` |
| `ResearchNavigation.java` | 统一写入、读取 `entry_source` |
| `ResearchSupplementProgress.java` | 只统计补充步骤完成/跳过，不保存答案 |
| `ResearchPlaybackTracker.java` | 纯 Java 累计一次媒体播放实例的实际播放区间 |
| `ResearchNotificationData.java` | 纯 Java 生成通知属性、响应时延和幂等键 |
| `ResearchNotificationTracker.java` | 通知 Intent 打包、点击/划掉幂等记录 |
| `AtlasNotificationDismissReceiver.java` | 接收 Android notification `deleteIntent` |

### 修改的生产代码

| 文件 | 改动 |
| --- | --- |
| `AtlasApplication.java` | 初始化 logger、恢复异常 session、注册 screen tracker |
| `JoyfulMomentController.java` | 在实际 session start/stop 边界记录时长和原因 |
| `MainActivity.java` | 区分用户停止和 Activity 销毁停止原因，并标记补充流程入口 |
| `AtlasBottomNav.java` | 给底部导航目标页传递统一 entry source |
| `SupplementPickerActivity.java` | 记录补充列表结束和 moment 选择 |
| `EventSupplementActivity.java` | 记录步骤跳过/完成、保存决策和删除 |
| `EventDetailActivity.java` | 记录详情访问、展开、编辑、删除、照片/音频/视频交互 |
| `FullscreenPhotoActivity.java` | 记录照片查看开始/结束 |
| `VideoPlayerActivity.java` | 记录视频开始、暂停、完成、失败和实际播放时长 |
| `ReviewShellActivity.java` | 记录 tab、地图卡片、日历、时间线和 moment 打开 |
| `MapReviewActivity.java` | 覆盖仍可进入的旧地图页面交互 |
| `MeActivity.java` | 记录两类提醒开关的用户修改 |
| `AtlasNotificationHelper.java` | 为每次通知创建实例 ID，记录 posted/failed，增加 deleteIntent |
| `AtlasDailyReminderReceiver.java` | 记录 daily skipped 原因而不改变重试策略 |
| `AtlasLocationReminderReceiver.java` | 记录 location skipped 原因而不记录坐标 |
| `AndroidManifest.xml` | 注册 dismiss receiver |
| `README.md` | 写明研究日志位置、隐私边界和手动复制命令 |

### 新增测试

`ResearchLogRecordTest.java`、`ResearchJsonlWriterTest.java`、`ResearchIdentifiersTest.java`、`ResearchSessionTimingTest.java`、`ResearchVisitTimerTest.java`、`ResearchSupplementProgressTest.java`、`ResearchPlaybackTrackerTest.java`、`ResearchNotificationDataTest.java`。

---

### Task 1: Versioned JSONL logging core

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchEventNames.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchLogRecord.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchJsonlWriter.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchIdentifiers.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchInteractionLogger.java`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchLogRecordTest.java`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchJsonlWriterTest.java`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchIdentifiersTest.java`

**Interfaces:**
- Produces: `ResearchInteractionLogger.initialize(Context)`
- Produces: `ResearchInteractionLogger.log(Context, String, String, String, String, JSONObject): boolean`
- Produces: `ResearchInteractionLogger.properties(Object...): JSONObject`
- Produces: `ResearchInteractionLogger.getLogFile(Context): File`
- Produces: `ResearchIdentifiers.notificationInstanceId(): String`
- Produces: `ResearchIdentifiers.anonymousId(String, String): String`
- Produces: schema v1 constants in `ResearchEventNames`

- [ ] **Step 1: Write failing schema, append/retry and identifier tests**

```java
@Test
public void recordContainsRequiredEnvelopeWithoutContentFields() throws Exception {
    JSONObject record = ResearchLogRecord.build(
            "screen_opened", "row-1", 1000L,
            "2026-07-28T19:30:00.000+08:00", "Asia/Shanghai", 2000L,
            "01", "session-1", "moment-1", null,
            "2.0-main", 20, "OPPO", new JSONObject().put("screen", "review"));
    assertEquals(1, record.getInt("schema_version"));
    assertEquals("screen_opened", record.getString("event_name"));
    assertEquals("01", record.getString("participant_id"));
    assertFalse(record.toString().contains("with_whom"));
    assertFalse(record.toString().contains("latitude"));
    assertFalse(record.toString().contains("/storage/"));
}

@Test
public void failedLineIsRetriedBeforeNewLine() throws Exception {
    FailOnceSink sink = new FailOnceSink();
    ResearchJsonlWriter writer = new ResearchJsonlWriter(sink, 10);
    assertFalse(writer.append(new JSONObject().put("event_id", "first")));
    assertEquals(1, writer.pendingCount());
    assertTrue(writer.append(new JSONObject().put("event_id", "second")));
    assertEquals(Arrays.asList("first", "second"), sink.eventIds());
    assertEquals(0, writer.pendingCount());
}

@Test
public void anonymousIdsAreStableAndDoNotExposeInput() {
    String id = ResearchIdentifiers.anonymousId("media", "/storage/emulated/0/private.wav");
    assertEquals(id, ResearchIdentifiers.anonymousId(
            "media", "/storage/emulated/0/private.wav"));
    assertFalse(id.contains("private.wav"));
    assertNotEquals(id, ResearchIdentifiers.anonymousId(
            "media", "/storage/emulated/0/other.wav"));
}
```

`FailOnceSink` 在测试内实现 `ResearchJsonlWriter.LineSink`：第一次 `appendAndSync` 抛 `IOException`，后续把每行解析成 JSON 并保存 `event_id`。

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchLogRecordTest \
  --tests com.hry.camera.usbcamerademo.ResearchJsonlWriterTest \
  --tests com.hry.camera.usbcamerademo.ResearchIdentifiersTest \
  --console=plain
```

Expected: compilation fails because the four production classes do not exist.

- [ ] **Step 3: Add exact schema constants**

`ResearchEventNames` must define constants for every event used later:

```java
static final int SCHEMA_VERSION = 1;
static final String FILE_NAME = "research_interaction_log.jsonl";
static final String LOG_STARTED = "research_log_started";
static final String SESSION_STARTED = "capture_session_started";
static final String SESSION_STOPPED = "capture_session_stopped";
static final String SESSION_INTERRUPTED = "capture_session_interrupted";
static final String SCREEN_OPENED = "screen_opened";
static final String SCREEN_CLOSED = "screen_closed";
static final String MOMENT_SAVE_DECISION = "moment_save_decision";
static final String MOMENT_DETAIL_OPENED = "moment_detail_opened";
static final String MOMENT_DETAIL_CLOSED = "moment_detail_closed";
static final String MOMENT_EDIT_STARTED = "moment_edit_started";
static final String MOMENT_EDIT_COMPLETED = "moment_edit_completed";
static final String MOMENT_DELETED = "moment_deleted";
static final String SUPPLEMENT_FLOW_OPENED = "supplement_flow_opened";
static final String SUPPLEMENT_STEP_SKIPPED = "supplement_step_skipped";
static final String SUPPLEMENT_FLOW_COMPLETED = "supplement_flow_completed";
static final String DETAIL_SECTION_EXPANDED = "detail_section_expanded";
static final String DETAIL_SECTION_COLLAPSED = "detail_section_collapsed";
static final String MEDIA_OPENED = "media_opened";
static final String MEDIA_PLAY_STARTED = "media_play_started";
static final String MEDIA_PLAY_PAUSED = "media_play_paused";
static final String MEDIA_PLAY_COMPLETED = "media_play_completed";
static final String MEDIA_PLAY_FAILED = "media_play_failed";
static final String REVIEW_TAB_SELECTED = "review_tab_selected";
static final String MAP_OPENED = "map_opened";
static final String MAP_CARD_CHANGED = "map_card_changed";
static final String MAP_MOMENT_OPENED = "map_moment_opened";
static final String MAP_RECENTER_REQUESTED = "map_recenter_requested";
static final String SETTING_CHANGED = "setting_changed";
static final String NOTIFICATION_POSTED = "notification_posted";
static final String NOTIFICATION_POST_FAILED = "notification_post_failed";
static final String NOTIFICATION_OPENED = "notification_opened";
static final String NOTIFICATION_DISMISSED = "notification_dismissed";
static final String NOTIFICATION_SKIPPED = "notification_skipped";
```

- [ ] **Step 4: Implement the pure record builder and anonymized IDs**

`ResearchLogRecord.build(...)` must always write the common envelope, use `JSONObject.NULL` for applicable nullable IDs, and place optional values only inside `properties`. `ResearchIdentifiers.anonymousId(namespace, raw)` must calculate SHA-256 over `namespace + "\n" + raw`, return `namespace + "_" +` the first 16 lowercase hex bytes, and return `namespace + "_missing"` for empty input. `notificationInstanceId()` returns `"notification_" + UUID.randomUUID().toString()`.

```java
static JSONObject build(
        String eventName, String eventId, long timestampMs, String timestampLocal,
        String timezoneId, long elapsedRealtimeMs, String participantId,
        String sessionId, String momentId, String notificationInstanceId,
        String appVersionName, int appVersionCode, String deviceModel,
        JSONObject properties) throws JSONException {
    return new JSONObject()
            .put("schema_version", ResearchEventNames.SCHEMA_VERSION)
            .put("event_name", eventName)
            .put("event_id", eventId)
            .put("timestamp_ms", timestampMs)
            .put("timestamp_local", timestampLocal)
            .put("timezone_id", timezoneId)
            .put("elapsed_realtime_ms", elapsedRealtimeMs)
            .put("participant_id", nullable(participantId))
            .put("session_id", nullable(sessionId))
            .put("moment_id", nullable(momentId))
            .put("notification_instance_id", nullable(notificationInstanceId))
            .put("app_version_name", appVersionName)
            .put("app_version_code", appVersionCode)
            .put("device_model", deviceModel)
            .put("properties", properties == null ? new JSONObject() : properties);
}
```

- [ ] **Step 5: Implement ordered append, sync and bounded retry**

`ResearchJsonlWriter` has a production `FileLineSink` that creates the parent directory, opens `FileOutputStream(file, true)`, writes exactly one UTF-8 JSON line plus `\n`, calls `flush()` and `getFD().sync()`, then closes in `finally`. `append` is `synchronized`; it drains pending lines first, then writes the current line. Any failed line is placed in an `ArrayDeque<String>` capped at 100 by dropping the oldest only after emitting an error through `ErrorReporter`.

Define the testable contracts exactly as:

```java
interface LineSink {
    void appendAndSync(String line) throws IOException;
}

interface ErrorReporter {
    void onWriteFailure(IOException error, int pendingCount);
}
```

```java
synchronized boolean append(JSONObject json) {
    String line = json.toString();
    try {
        while (!pending.isEmpty()) {
            String pendingLine = pending.peekFirst();
            sink.appendAndSync(pendingLine);
            pending.removeFirst();
        }
        sink.appendAndSync(line);
        return true;
    } catch (IOException error) {
        enqueueBounded(line);
        reporter.onWriteFailure(error, pending.size());
        return false;
    }
}
```

Because a pending line is removed only after `appendAndSync` succeeds, a failed pending line stays at the head when the new line is queued, so event order remains `first`, then `second`.

- [ ] **Step 6: Implement the Android facade**

`ResearchInteractionLogger` must:

- resolve the same `joyful_moment` root strategy as `JoyfulMomentEventStore`;
- read participant ID from `JoyfulMomentConfig.PREF_NAME` / `joyful_participant_number`;
- format local time with `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`;
- use `System.currentTimeMillis()`, `SystemClock.elapsedRealtime()`, `TimeZone.getDefault().getID()`, `BuildConfig.VERSION_NAME`, `BuildConfig.VERSION_CODE` and `Build.MODEL`;
- never throw to callers; report failures through `AtlasDevLogger`;
- emit `research_log_started` only if the file did not previously contain data;
- let `properties(Object...)` reject odd key/value counts and convert null values to `JSONObject.NULL`.

```java
public static boolean log(
        Context context, String eventName, String sessionId, String momentId,
        String notificationInstanceId, JSONObject properties) {
    try {
        initialize(context);
        JSONObject record = ResearchLogRecord.build(
                eventName, UUID.randomUUID().toString(), now, localTime(now),
                TimeZone.getDefault().getID(), SystemClock.elapsedRealtime(),
                currentParticipant(context), sessionId, momentId,
                notificationInstanceId, BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE, Build.MODEL, properties);
        return writer.append(record);
    } catch (Exception error) {
        AtlasDevLogger.e(context, "ResearchLog", "record failed: " + eventName, error);
        return false;
    }
}
```

- [ ] **Step 7: Run core tests and commit**

Run the focused command from Step 2. Expected: all tests PASS.

Then:

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchEventNames.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchLogRecord.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchJsonlWriter.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchIdentifiers.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchInteractionLogger.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchLogRecordTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchJsonlWriterTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchIdentifiersTest.java
git commit -m "feat: add durable research interaction log"
```

---

### Task 2: Capture session duration and interrupted-session recovery

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchSessionTiming.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchSessionTracker.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasApplication.java:12-41`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java:124-234`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java:374-400,1526-1581`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchSessionTimingTest.java`

**Interfaces:**
- Consumes: `ResearchInteractionLogger.log(...)`
- Produces: `ResearchSessionTracker.start(Context, String, String, long, long)`
- Produces: `ResearchSessionTracker.stop(Context, String, String, int, int, long, long)`
- Produces: `ResearchSessionTracker.recoverInterrupted(Context, long, long)`
- Produces: `JoyfulMomentController.stopSession(String stopReason)`

- [ ] **Step 1: Write failing duration tests**

```java
@Test
public void normalDurationUsesMonotonicClock() {
    assertEquals(6500L, ResearchSessionTiming.normalDuration(1000L, 7500L));
}

@Test
public void durationNeverBecomesNegative() {
    assertEquals(0L, ResearchSessionTiming.normalDuration(7500L, 1000L));
    assertEquals(0L, ResearchSessionTiming.estimatedWallDuration(9000L, 8000L));
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchSessionTimingTest \
  --console=plain
```

Expected: compilation fails because `ResearchSessionTiming` does not exist.

- [ ] **Step 3: Implement session timing and persisted active state**

`ResearchSessionTiming` exposes `normalDuration(startElapsed, stopElapsed)` and `estimatedWallDuration(startWall, stopWall)`, both `Math.max(0L, stop - start)`.

`ResearchSessionTracker` uses private preferences `atlas_research_session` with exact keys:

```java
private static final String KEY_ACTIVE = "active";
private static final String KEY_SESSION_ID = "session_id";
private static final String KEY_PARTICIPANT_ID = "participant_id";
private static final String KEY_START_WALL_MS = "start_wall_ms";
private static final String KEY_START_ELAPSED_MS = "start_elapsed_ms";
```

`start` first persists all fields with `commit()` and then logs `capture_session_started`. `stop` verifies that the stored session ID matches, calculates monotonic duration, logs `capture_session_stopped` with `duration_ms`, `stop_reason`, `detection_count`, `moment_count`, `duration_estimated:false`, and only clears active state after the log attempt. If the first log attempt fails, call it once more; regardless of the second result, retain the active snapshot for recovery rather than silently discarding the session boundary.

`recoverInterrupted` reads any active snapshot at process start, logs `capture_session_interrupted` with wall-clock `duration_ms`, `duration_estimated:true`, `stop_reason:"process_interrupted"`, then clears the snapshot only after a successful write.

- [ ] **Step 4: Place hooks at the real controller boundaries**

In `JoyfulMomentController.startSession(String)`, after `realtimeEngine.start()` returns without throwing:

```java
ResearchSessionTracker.start(
        context, participantNumber, sessionId,
        sessionStartMs, SystemClock.elapsedRealtime());
```

Add a monotonic field `sessionStartElapsedMs`. Set it next to `sessionStartMs`.

Replace the existing stop method with:

```java
public synchronized void stopSession() {
    stopSession("unspecified");
}

public synchronized void stopSession(String stopReason) {
    if (!sessionRunning) {
        return;
    }
    sessionRunning = false;
    // Keep the existing engine finalization and summary writes unchanged.
    ResearchSessionTracker.stop(
            context, sessionId, stopReason,
            detectionRecords.size(), eventRecords.size(),
            sessionStartElapsedMs, SystemClock.elapsedRealtime());
}
```

The research stop call must execute exactly once after event finalization and `writeSessionSummary("stopped")`.

In `MainActivity`:

- manual record button uses `stopSession("user")`;
- `onDestroy` uses `stopSession("activity_destroyed")`;
- starting remains routed through `JoyfulMomentController.startSession(participantNumber)`;
- opening the picker remains unchanged until Task 3 adds the common navigation-source contract.

In `AtlasApplication.onCreate`, call `ResearchInteractionLogger.initialize(this)` and then `ResearchSessionTracker.recoverInterrupted(this, System.currentTimeMillis(), SystemClock.elapsedRealtime())` before resurfacing initialization.

- [ ] **Step 5: Run session and existing selector tests**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchSessionTimingTest \
  --tests com.hry.camera.usbcamerademo.AtlasResurfacingSelectorTest \
  --console=plain
```

Expected: PASS. Also verify with `rg` that only the two intended `MainActivity` call sites stop the controller and both supply an explicit reason.

- [ ] **Step 6: Commit**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchSessionTiming.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchSessionTracker.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasApplication.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/JoyfulMomentController.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchSessionTimingTest.java
git commit -m "feat: log capture session runtime"
```

---

### Task 3: Page visits and navigation sources

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchVisitTimer.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchScreenTracker.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchNavigation.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasApplication.java:24-40`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasBottomNav.java:37-56`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java:1536-1542`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchVisitTimerTest.java`

**Interfaces:**
- Consumes: `ResearchInteractionLogger.log(...)`
- Produces: `ResearchVisitTimer.start(long)`, `pause(long): long`, `totalVisibleMs(): long`
- Produces: `ResearchScreenTracker.onResumed(Activity)`, `onPaused(Activity)`
- Produces: `ResearchNavigation.withSource(Intent, String): Intent`
- Produces: `ResearchNavigation.source(Intent, String): String`

- [ ] **Step 1: Write failing visit-timer tests**

```java
@Test
public void accumulatesOnlyVisibleIntervals() {
    ResearchVisitTimer timer = new ResearchVisitTimer();
    timer.start(100L);
    assertEquals(200L, timer.pause(300L));
    timer.start(500L);
    assertEquals(250L, timer.pause(550L));
    assertEquals(250L, timer.totalVisibleMs());
}

@Test
public void duplicateStartAndPauseDoNotDoubleCount() {
    ResearchVisitTimer timer = new ResearchVisitTimer();
    timer.start(100L);
    timer.start(120L);
    assertEquals(100L, timer.pause(200L));
    assertEquals(100L, timer.pause(300L));
}
```

- [ ] **Step 2: Run the test and verify RED**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchVisitTimerTest \
  --console=plain
```

Expected: compilation fails because `ResearchVisitTimer` does not exist.

- [ ] **Step 3: Implement visit timer and screen lifecycle tracker**

`ResearchScreenTracker` stores one state per live Activity in a synchronized `WeakHashMap<Activity, ScreenState>`. On every first `onActivityResumed` after a pause:

- create a `visit_id`;
- log `screen_opened` with normalized `screen_name`, `entry_source`, and `visit_id`;
- include `session_id`/`moment_id` from intent extras when present;
- start `ResearchVisitTimer` using `SystemClock.elapsedRealtime()`.

On `onActivityPaused`, log `screen_closed` with the same `visit_id` and `visible_duration_ms`, then remove that visit state. This makes a return from a child Activity a new visible visit instead of incorrectly counting background time.

Normalize exact page names:

```java
MainActivity -> "record"
ReviewShellActivity -> "review"
MeActivity -> "me"
SupplementPickerActivity -> "supplement_picker"
EventSupplementActivity -> "event_supplement"
EventDetailActivity -> "moment_detail"
FullscreenPhotoActivity -> "photo_viewer"
VideoPlayerActivity -> "video_player"
MapReviewActivity -> "legacy_map"
SettingsActivity -> "developer_settings"
LogViewerActivity -> "developer_logs"
```

Unknown Activity classes use their simple class name and do not include arbitrary Intent extras.

- [ ] **Step 4: Register screen tracking and propagate bottom-navigation source**

In `AtlasApplication.ActivityLifecycleCallbacks`, keep existing developer logging and additionally call:

```java
@Override public void onActivityResumed(Activity activity) {
    log(activity, "resumed");
    ResearchScreenTracker.onResumed(activity);
}
@Override public void onActivityPaused(Activity activity) {
    ResearchScreenTracker.onPaused(activity);
    log(activity, "paused");
}
```

`ResearchNavigation` defines `EXTRA_ENTRY_SOURCE = "research_entry_source"`. In `AtlasBottomNav`, build target intents with:

```java
ResearchNavigation.withSource(intent, "bottom_navigation");
```

In `MainActivity`, wrap the post-session picker Intent with:

```java
ResearchNavigation.withSource(intent, "session_stop");
```

Do not serialize whole `Intent`, Bundle, URI or file path into the research log.

- [ ] **Step 5: Run focused and full unit tests, then commit**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchVisitTimerTest \
  --console=plain
sh gradlew :atlasapp:testDebugUnitTest --console=plain
```

Expected: PASS.

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchVisitTimer.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchScreenTracker.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchNavigation.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasApplication.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasBottomNav.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchVisitTimerTest.java
git commit -m "feat: log app screen visits"
```

---

### Task 4: Moment decisions, supplement flow, edits and deletion

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchSupplementProgress.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/SupplementPickerActivity.java:28-73`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventSupplementActivity.java:42-206`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:125-258,481-506,1247-1492`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchSupplementProgressTest.java`

**Interfaces:**
- Consumes: logger, visit timer and navigation source from Tasks 1 and 3
- Produces: `ResearchSupplementProgress.record(int step, boolean answered)`
- Produces: `answeredCount()`, `skippedCount()`, `properties(): JSONObject`

- [ ] **Step 1: Write failing supplement-counter tests**

```java
@Test
public void countsAnswersAndSkipsWithoutKeepingText() throws Exception {
    ResearchSupplementProgress progress = new ResearchSupplementProgress(3);
    progress.record(0, true);
    progress.record(1, false);
    progress.record(2, true);
    JSONObject properties = progress.properties();
    assertEquals(2, properties.getInt("answered_step_count"));
    assertEquals(1, properties.getInt("skipped_step_count"));
    assertFalse(properties.has("answers"));
    assertFalse(properties.has("with_whom"));
    assertFalse(properties.has("doing_what"));
    assertFalse(properties.has("mood"));
}
```

The assertions inspect keys rather than rejecting the allowed aggregate key `answered_step_count`.

- [ ] **Step 2: Run the focused test and verify RED**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchSupplementProgressTest \
  --console=plain
```

Expected: compilation fails because `ResearchSupplementProgress` does not exist.

- [ ] **Step 3: Implement aggregate-only supplement progress**

Back `ResearchSupplementProgress` with two boolean arrays so repeated callbacks for the same step do not double count. `properties()` returns only:

```json
{
  "step_count": 3,
  "answered_step_count": 2,
  "skipped_step_count": 1
}
```

It must never receive or retain the answer String.

- [ ] **Step 4: Instrument picker and initial supplement decisions**

In `SupplementPickerActivity`:

- log picker Done as `supplement_flow_completed` with `completion_reason:"picker_done"` and no moment ID;
- before opening an event, add entry source `"supplement_picker"`; let `EventSupplementActivity` log `supplement_flow_opened`.

In `EventSupplementActivity`:

- create `ResearchSupplementProgress(3)` after event load;
- on Skip, log `supplement_step_skipped` with `step_name` from `with_whom`, `doing_what`, `mood`, then call `progress.record(currentStep, false)`;
- on Next, call `progress.record(currentStep, !TextUtils.isEmpty(answer))` without logging or storing the answer in the research layer;
- after the third step is saved to the existing event JSON, log `supplement_flow_completed` with `progress.properties()`;
- for `save_push` and `save_no_push`, log `moment_save_decision` only after `repository.saveDecision` returns true, with `action` and `push_allowed`;
- for the delete choice, log `moment_save_decision` with `action:"delete"` when confirmed, and `moment_deleted` only if `deleteEventPermanently` succeeds;
- when “edit now” is selected, log `moment_edit_started` with `entry_source:"post_session_decision"` and propagate the same entry source to `EventDetailActivity`.

No research property may contain `withWhom`, `doingWhat`, `mood` or the answer String.

- [ ] **Step 5: Instrument detail access, expand/collapse and CRUD success**

In `EventDetailActivity`:

- after a valid event loads, log `moment_detail_opened` with `entry_source` and reconstruction mode (`short`/`long`);
- use a `ResearchVisitTimer` started in `onStart`; in `onStop`, log `moment_detail_closed` with accumulated visible duration before stopping playback;
- for each clip-card toggle, use a per-card anonymous ID derived from the laughter clip path; log `detail_section_expanded` when showing details and `detail_section_collapsed` with `expanded_duration_ms` when hiding it;
- after successful event deletion, log `moment_deleted` with `delete_source:"moment_detail"`;
- after successful text note add/edit/delete, log `moment_edit_completed` with `field_category:"text_note"` and `operation:"add"|"edit"|"delete"`;
- after successful audio note save/delete, use `field_category:"audio_note"`;
- after successful photo note save/delete, use `field_category:"photo_note"`;
- after successful context refresh, use `field_category:"derived_context"` without lat/lng/location/weather values.

The log property for media edits may contain `item_id` only if it is the existing opaque `item_id`; otherwise use `ResearchIdentifiers.anonymousId("media", path)`. Never write `path`.

- [ ] **Step 6: Run tests and static privacy checks**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchSupplementProgressTest \
  --tests com.hry.camera.usbcamerademo.AtlasEventDeletionPathsTest \
  --console=plain
rg -n 'ResearchInteractionLogger.*(withWhom|doingWhat|mood|path|lat|lng)' \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventSupplementActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java
```

Expected: tests PASS; `rg` returns no research-log call that passes sensitive variables.

- [ ] **Step 7: Commit**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchSupplementProgress.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/SupplementPickerActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventSupplementActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchSupplementProgressTest.java
git commit -m "feat: log moment and supplement interactions"
```

---

### Task 5: Photo, laughter audio and video usage

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchPlaybackTracker.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:509-659,680-1165`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/FullscreenPhotoActivity.java:14-42`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/VideoPlayerActivity.java:21-165`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java:527-549`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchPlaybackTrackerTest.java`

**Interfaces:**
- Consumes: `ResearchIdentifiers.anonymousId(...)` and logger
- Produces: `ResearchPlaybackTracker.start(long)`, `pause(long): long`, `finish(long): long`, `playedDurationMs(): long`
- Produces: media events with `media_type`, anonymous `media_item_id`, playback position and actual played duration

- [ ] **Step 1: Write failing playback-segment tests**

Create `ResearchPlaybackTrackerTest`:

```java
@Test
public void excludesPausedGapFromActualPlayback() {
    ResearchPlaybackTracker tracker = new ResearchPlaybackTracker();
    tracker.start(1000L);
    assertEquals(600L, tracker.pause(1600L));
    tracker.start(5000L);
    assertEquals(1000L, tracker.finish(5400L));
    assertEquals(1000L, tracker.playedDurationMs());
}

@Test
public void duplicateCallbacksDoNotDoubleCount() {
    ResearchPlaybackTracker tracker = new ResearchPlaybackTracker();
    tracker.start(1000L);
    tracker.start(1200L);
    tracker.pause(1500L);
    tracker.pause(1800L);
    assertEquals(500L, tracker.playedDurationMs());
}
```

- [ ] **Step 2: Run the test and verify RED**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchPlaybackTrackerTest \
  --console=plain
```

Expected: compilation fails because `ResearchPlaybackTracker` does not exist.

- [ ] **Step 3: Add media identity and actual-audio-duration tracking**

Implement `ResearchPlaybackTracker` with `runningSinceMs = -1L` and `playedDurationMs = 0L`. Duplicate `start` calls keep the first start; duplicate `pause`/`finish` calls return the existing total. All intervals clamp negative elapsed time to zero.

Change `wireAudioControl(...)` to accept `mediaType`:

```java
private void wireAudioControl(
        View row, ImageView playIcon, AtlasWaveformView waveform, TextView time,
        String path, String mediaType)
```

Use exact types:

- laughter clip: `laughter_audio`;
- related context clip: `context_audio`;
- user recording: `user_audio`;
- legacy audio button without subtype: `audio`.

At MediaPlayer `onPrepared`/start, create a playback instance ID, start a `ResearchPlaybackTracker`, and log `media_play_started` with anonymous `media_item_id`, `media_type`, `position_ms`, `duration_ms`, and `resumed:false`. On resume, start a new interval and log the same event with `resumed:true`.

On pause, stop the interval and log `media_play_paused` with `position_ms`, `played_duration_ms` and `reason:"user"`. On completion, stop the interval and log `media_play_completed` with `played_duration_ms` and media duration. On screen stop or replacement, log `media_play_paused` with `reason:"screen_hidden"` or `"replaced"` only when playback had actually started. On error, log `media_play_failed` with a normalized `failure_type` such as `missing_file`, `prepare_error` or `player_error`; do not log the exception message if it contains a path.

- [ ] **Step 4: Instrument photo and video opens without paths**

Before opening a photo viewer or video player in `EventDetailActivity`, log `media_opened` and pass only these research extras to the child Activity:

```java
intent.putExtra("research_moment_id", eventId);
intent.putExtra("research_session_id", sessionId);
intent.putExtra("research_media_item_id",
        ResearchIdentifiers.anonymousId("media", path));
intent.putExtra("research_media_type", "photo"); // or "video"
```

`FullscreenPhotoActivity` logs `media_opened` once if it was entered from any path that did not already mark the open, and uses the global screen tracker for viewing duration. Avoid duplicate media-open logging with `research_media_open_logged:true`.

`VideoPlayerActivity`:

- logs `media_play_started` when `VideoView` actually starts in `onPrepared`;
- installs `setOnCompletionListener` for `media_play_completed`;
- uses `ResearchPlaybackTracker` and `VideoView.getCurrentPosition()` in `onStop` for `media_play_paused`;
- logs `media_play_failed` from `setOnErrorListener`;
- logs `media_opened` when the user chooses the external-player fallback, with `open_target:"external"`;
- never logs `playbackUri` or `playbackPath`.

When `ReviewShellActivity` changes a cover photo, log `moment_edit_completed` with `field_category:"cover_photo"` and the anonymous media ID after `setCoverPhoto` succeeds.

- [ ] **Step 5: Run audio state, timer and build checks**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchPlaybackTrackerTest \
  --tests com.hry.camera.usbcamerademo.AtlasAudioPlaybackStateTest \
  --console=plain
sh gradlew :atlasapp:assembleDebug --console=plain
```

Expected: PASS and `atlasapp-debug.apk` is produced.

- [ ] **Step 6: Commit**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchPlaybackTracker.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/FullscreenPhotoActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/VideoPlayerActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchPlaybackTrackerTest.java
git commit -m "feat: log research media interactions"
```

---

### Task 6: Review, map and reminder-setting interactions

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java:75-312,318-585`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MapReviewActivity.java:28-151`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MeActivity.java:55-127`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCardCarouselStateTest.java`

**Interfaces:**
- Consumes: logger, anonymous IDs and `ResearchNavigation`
- Produces: tab, carousel, calendar, timeline, map and reminder-setting events

- [ ] **Step 1: Add regression coverage for deterministic carousel position changes**

Extend `AtlasCardCarouselStateTest` with a case that asserts previous/current/next indexes before and after one move in each direction. The expected sequence for three cards is:

```java
AtlasCardCarouselState state = new AtlasCardCarouselState(3);
assertEquals(0, state.currentIndex());
assertTrue(state.moveNext());
assertEquals(1, state.currentIndex());
assertTrue(state.movePrevious());
assertEquals(0, state.currentIndex());
```

- [ ] **Step 2: Run carousel tests**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasCardCarouselStateTest \
  --console=plain
```

Expected: PASS; this locks the indexes that are written into research properties.

- [ ] **Step 3: Instrument ReviewShell without logging location content**

Add fields `lastReviewTab`, `lastMapCardPosition`, and `pendingMapNavigationMethod`.

- Tab selection logs `review_tab_selected` only when the position changes, with `tab:"map"|"calendar"|"timeline"` and `selection_source:"user"|"navigation_intent"`.
- When map first becomes visible in a visit, log `map_opened` with `entry_source`; if a location notification supplied focus coordinates, record only `focused_from_notification:true`.
- Previous/next buttons set `pendingMapNavigationMethod` to `"previous_button"`/`"next_button"` before moving.
- Position callback logs `map_card_changed` only when the index changes, with `from_index`, `to_index`, `total`, and method; default method is `"swipe"`. Do not log card title, location or coordinates.
- Card click logs `map_moment_opened` with moment ID and current index, then opens detail with source `"map_card"`.
- Calendar month buttons log `review_calendar_month_changed` with direction only.
- Calendar day click logs `review_calendar_day_selected` with `days_from_today`, not an exact date.
- Calendar and timeline moment opens propagate source `"calendar_card"` or `"timeline_card"`.
- Cover changes retain the Task 5 edit event.

Add the two new review event names to `ResearchEventNames`.

- [ ] **Step 4: Instrument legacy map and settings**

`MapReviewActivity`:

- log `map_opened` with `legacy:true`;
- Refresh button logs `map_recenter_requested` with `method:"refresh_button"`;
- card click logs `map_moment_opened` and propagates `"legacy_map_card"`;
- long press logs `map_recenter_requested` with `method:"card_long_press"`;
- never write `formatMapCoordinates`, lat/lng or location name to research properties.

`MeActivity`:

```java
ResearchInteractionLogger.log(
        MeActivity.this, ResearchEventNames.SETTING_CHANGED,
        null, null, null,
        ResearchInteractionLogger.properties(
                "setting_name", "daily_reminder",
                "enabled", isChecked,
                "change_source", "user"));
```

Repeat with `setting_name:"location_reminder"` only inside the listeners after the `bindingReminderSwitches` guard. Do not log initial switch binding as user changes.

- [ ] **Step 5: Run tests and privacy scan**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasCardCarouselStateTest \
  --console=plain
rg -n 'ResearchInteractionLogger.*(focusedMapLat|focusedMapLng|locationName|\\.lat|\\.lng)' \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MapReviewActivity.java
```

Expected: tests PASS and no sensitive value is passed to the logger.

- [ ] **Step 6: Commit**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchEventNames.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MapReviewActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MeActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasCardCarouselStateTest.java
git commit -m "feat: log review and map interactions"
```

---

### Task 7: Instance-level notification delivery, open and dismiss logging

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchNotificationData.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchNotificationTracker.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasNotificationDismissReceiver.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasNotificationHelper.java:55-168`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasDailyReminderReceiver.java:41-92`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLocationReminderReceiver.java:39-104`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:125-195`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java:75-200`
- Modify: `atlasapp/src/main/AndroidManifest.xml:44-55`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchNotificationDataTest.java`

**Interfaces:**
- Produces: `ResearchNotificationTracker.attach(Intent, Metadata): Intent`
- Produces: `ResearchNotificationTracker.logOpened(Context, Intent): boolean`
- Produces: `ResearchNotificationTracker.logDismissed(Context, Intent): boolean`
- Produces: `ResearchNotificationTracker.dismissIntent(Context, Metadata): PendingIntent`
- Produces: `ResearchNotificationData.responseDelay(long, long): long`
- Produces: `ResearchNotificationData.actionKey(String, String): String`

- [ ] **Step 1: Write failing notification metadata tests**

```java
@Test
public void responseDelayIsNonNegative() {
    assertEquals(5000L, ResearchNotificationData.responseDelay(1000L, 6000L));
    assertEquals(0L, ResearchNotificationData.responseDelay(6000L, 1000L));
}

@Test
public void openedAndDismissedHaveDifferentIdempotencyKeys() {
    assertEquals("n1:opened",
            ResearchNotificationData.actionKey("n1", "opened"));
    assertNotEquals(
            ResearchNotificationData.actionKey("n1", "opened"),
            ResearchNotificationData.actionKey("n1", "dismissed"));
}

@Test
public void locationPropertiesUseAnonymousClusterOnly() throws Exception {
    JSONObject properties = ResearchNotificationData.properties(
            "location", 2301, 1000L, "map", null,
            ResearchIdentifiers.anonymousId("location", "39.9,116.4"));
    assertFalse(properties.toString().contains("39.9"));
    assertFalse(properties.toString().contains("116.4"));
}
```

- [ ] **Step 2: Run the focused test and verify RED**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchNotificationDataTest \
  --console=plain
```

Expected: compilation fails because `ResearchNotificationData` does not exist.

- [ ] **Step 3: Implement metadata extras and action idempotency**

`ResearchNotificationTracker.Metadata` contains only:

```java
String instanceId;
String type;              // short, long, location
int androidNotificationId;
long postedAtMs;
String destination;       // short_detail, long_detail, map
String sessionId;         // nullable
String momentId;          // nullable for location
String anonymousClusterId;// nullable for daily
```

When serialized into `properties`, use the approved schema names
`notification_type`, `android_notification_id`, `posted_timestamp_ms`,
`destination` and `anonymous_cluster_id`. Click/dismiss rows additionally use
`response_delay_ms`.

Use exact extras prefixed `research_notification_`. `attach` copies these values into the destination Intent. `logOpened` and `logDismissed`:

1. return false if no instance ID exists;
2. use private preferences `atlas_research_notification_actions`;
3. reserve a key from `ResearchNotificationData.actionKey(instanceId, action)` using synchronized `commit()`;
4. write one event with `response_delay_ms`;
5. remove the reserved key if writing fails so a later callback can retry.

This prevents Activity recreation or repeated `onNewIntent` from double-counting a click.

- [ ] **Step 4: Add deleteIntent and log actual post outcome**

For each call to `postDaily` or `postLocation`:

- create one Metadata object before building PendingIntents;
- attach it to the content Intent;
- create a unique request code from `instanceId.hashCode() & 0x7fffffff`;
- create `deleteIntent` targeting `AtlasNotificationDismissReceiver`;
- extend `build(...)` to receive it and call `.setDeleteIntent(deleteIntent)`;
- call `manager.notify`;
- only after `notify` returns, log `notification_posted`;
- on permission absence, manager absence or runtime exception, log `notification_post_failed` with normalized `failure_reason`, then preserve the existing false/exception retry behavior.

Daily posted properties include moment ID through the envelope. Location posted properties contain only anonymous cluster ID; do not pass lat/lng to the logger.

- [ ] **Step 5: Log opens in both destination paths and dismiss broadcasts**

Register:

```xml
<receiver
    android:name=".AtlasNotificationDismissReceiver"
    android:exported="false" />
```

`AtlasNotificationDismissReceiver.onReceive` calls `ResearchNotificationTracker.logDismissed`.

`EventDetailActivity` calls `logOpened(this, getIntent())` after validating the event, and adds `onNewIntent` that updates the Intent and invokes the same idempotent method.

`ReviewShellActivity.applyNavigationIntent` calls `logOpened(this, intent)` before reading map focus extras; `onNewIntent` remains supported. A location notification therefore logs one notification open and one map open, linked by the same notification instance ID.

- [ ] **Step 6: Record scheduler outcomes without changing policy**

In `AtlasDailyReminderReceiver` log `notification_skipped` for:

- `setting_disabled`;
- `already_sent_today`;
- `no_eligible_moment`.

In `AtlasLocationReminderReceiver` log normalized skip reasons for:

- `setting_disabled`;
- `invalid_cluster_payload`;
- `current_fix_outside_radius`;
- `no_old_eligible_moment`;
- `already_sent_place_today`;
- `cooldown_active`.

Skip events contain notification type and reason only. They must not create a notification instance ID, reveal coordinates or mark a reminder as sent. Existing retry and de-duplication logic remains unchanged.

- [ ] **Step 7: Run notification and policy tests**

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchNotificationDataTest \
  --tests com.hry.camera.usbcamerademo.AtlasReminderScheduleTest \
  --tests com.hry.camera.usbcamerademo.AtlasLocationReminderPolicyTest \
  --tests com.hry.camera.usbcamerademo.AtlasResurfacingSelectorTest \
  --console=plain
```

Expected: PASS.

- [ ] **Step 8: Commit**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchNotificationData.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchNotificationTracker.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasNotificationDismissReceiver.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasNotificationHelper.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasDailyReminderReceiver.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLocationReminderReceiver.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java \
  atlasapp/src/main/AndroidManifest.xml \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchNotificationDataTest.java
git commit -m "feat: log notification delivery and response"
```

---

### Task 8: Research documentation, privacy audit and release verification

**Files:**
- Modify: `README.md:64-106,213-230,416-437`
- Create: `docs/research-log-schema-v1.md`

**Interfaces:**
- Consumes: all schema v1 events implemented by Tasks 1-7
- Produces: collaborator-facing extraction and analysis reference

- [ ] **Step 1: Document the exact file and retrieval process**

Add `research_interaction_log.jsonl` to the README storage tree at the `joyful_moment/` root. Add a “研究交互日志” section stating:

- the file is local only and is not uploaded;
- every line is one independent JSON object;
- interaction log excludes answer text, transcript, GPS coordinates and media content;
- uninstalling the App or clearing App data can remove it;
- export before uninstall/reset.

Document the exact ADB copy command:

```sh
adb pull \
  /sdcard/Android/data/com.hry.camera.atlasofhappiness/files/joyful_moment/research_interaction_log.jsonl \
  ./research_interaction_log.jsonl
```

Also document a basic validity check:

```sh
python3 -c 'import json,sys; [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]; print("JSONL valid")' research_interaction_log.jsonl
```

- [ ] **Step 2: Write the schema v1 analysis reference**

`docs/research-log-schema-v1.md` must list:

- every common envelope field and nullability;
- every exact event name from `ResearchEventNames`;
- properties per event;
- the normal session duration rule versus `duration_estimated:true`;
- notification instance correlation and `response_delay_ms`;
- how to compute counts, daily session duration and media listened duration;
- the privacy exclusions;
- the fact that deleted moments remain as opaque IDs in prior log rows.

Include analysis examples that use event names and numeric properties only; do not include real participant content or coordinates.

- [ ] **Step 3: Run schema/source consistency checks**

```sh
python3 - <<'PY'
import re
from pathlib import Path
source = Path("atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchEventNames.java").read_text()
doc = Path("docs/research-log-schema-v1.md").read_text()
events = re.findall(r'=\s*"([a-z][a-z0-9_]+)"\s*;', source)
missing = [event for event in events if event not in doc]
if missing:
    raise SystemExit("missing documented events: " + ", ".join(missing))
print("documented events:", len(events))
PY

rg -n 'ResearchInteractionLogger.*(SPEECHMATICS|API_KEY|payload|transcript)' \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo
```

Expected: every event is documented; the privacy scan produces no research logger calls with forbidden payloads.

- [ ] **Step 4: Run the full unit test suite and debug build**

Use JDK 8 and the local Android SDK:

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest :atlasapp:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`; all JVM tests PASS; APK exists at `atlasapp/build/outputs/apk/debug/atlasapp-debug.apk`.

If `local.properties` must be changed to use this workstation SDK, treat it as a temporary local-only change and restore it before commit.

- [ ] **Step 5: Perform a generated-log acceptance check**

Using a debug-only local exercise or a connected test device when the user permits device testing, generate at least:

- one `capture_session_started` and matching `capture_session_stopped`;
- one screen open/close pair;
- one supplement skip or completion;
- one detail expansion;
- one media playback event;
- one notification posted/opened or dismissed path.

Copy the JSONL and verify:

```sh
python3 - <<'PY'
import json
from pathlib import Path
p = Path("research_interaction_log.jsonl")
rows = [json.loads(line) for line in p.read_text(encoding="utf-8").splitlines() if line.strip()]
required = {"schema_version", "event_name", "event_id", "timestamp_ms",
            "timestamp_local", "timezone_id", "elapsed_realtime_ms",
            "participant_id", "session_id", "moment_id",
            "notification_instance_id", "app_version_name",
            "app_version_code", "device_model", "properties"}
assert rows and all(required <= row.keys() for row in rows)
blob = "\n".join(json.dumps(row, ensure_ascii=False) for row in rows)
for forbidden in ("with_whom", "doing_what", '"mood"', "speechmatics", "transcript",
                  "focus_lat", "focus_lng", "/storage/emulated/"):
    assert forbidden.lower() not in blob.lower(), forbidden
print("rows:", len(rows), "events:", sorted({row["event_name"] for row in rows}))
PY
```

If device use is not authorized, do not invoke ADB; validate the writer with JVM tests and report that end-to-end notification callbacks remain a manual-device check.

- [ ] **Step 6: Verify the working tree and commit docs**

```sh
git diff --check
git status --short
```

Expected: only intended tracked source, test and documentation changes; `.superpowers/` and `artifacts/` remain untracked and unstaged; no build directory or local SDK path is staged.

```sh
git add README.md docs/research-log-schema-v1.md
git commit -m "docs: document research interaction log"
```

- [ ] **Step 7: Final verification before completion**

Run:

```sh
ANDROID_HOME=/Users/fangjun/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/fangjun/Library/Android/sdk \
JAVA_HOME=/Users/fangjun/Library/Java/JavaVirtualMachines/corretto-1.8.0_492/Contents/Home \
sh gradlew :atlasapp:testDebugUnitTest :atlasapp:assembleDebug --console=plain
git diff --check
git status --short
git log --oneline -10
```

Expected: tests and build succeed, no accidental tracked changes remain, and all research-log commits are local on `fj_ver`. Do not push until the user explicitly authorizes it.
