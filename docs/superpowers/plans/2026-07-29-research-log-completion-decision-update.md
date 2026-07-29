# Research Log Completion and Decision Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为音频和视频完成日志增加可直接计算播放比例的兼容字段，并让首次及后续 A/B/C 保存决策通过追加式 `is_update` 日志形成完整历史。

**Architecture:** 新增一个纯 Java、无 Android UI 依赖的 `ResearchLogProperties`，集中构造播放完成和保存决策 properties。Activity 继续负责持久化和写日志：只有保存或物理删除成功后才调用 logger；Repository、UI 和 notification policy 不承担日志职责。

**Tech Stack:** Java 7、Android SDK 28、`org.json`、JUnit 4、现有 `ResearchInteractionLogger`、`ResearchPlaybackTracker`、Gradle 4.10.1。

## Global Constraints

- 只补日志，不新增详情页 B/C 切换 UI。
- `duration_played`、`total_duration` 的单位均为毫秒。
- 保留 `played_duration_ms`、`duration_ms` 和 `position_ms`。
- `duration_played == played_duration_ms`，`total_duration == duration_ms`。
- 研究日志继续 append-only；不修改或迁移已有 JSONL。
- 首次 A/B/C 为 `is_update=false`，已有 action 成功改为另一 action 为 `is_update=true`。
- 相同 action 是 no-op，不追加 `moment_save_decision`。
- 取消、保存失败或删除失败不追加决策日志。
- 详情页永久删除成功后同时保留原有 `moment_deleted`。
- 不改变 event JSON、播放 tracker、保存、删除或 resurfacing policy。
- 不新增第三方依赖，不记录用户回答、媒体内容或其他敏感信息。
- 每个生产行为先有失败测试并确认正确失败。
- 所有提交保留在本地 `fj_ver`；未经用户再次明确授权不得 push。

---

### Task 1: Add centralized media completion properties

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchLogProperties.java`
- Create: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchLogPropertiesTest.java`

**Interfaces:**
- Consumes: completion position, total media duration and actual played duration in milliseconds.
- Produces: `ResearchLogProperties.mediaPlayCompleted(long positionMs, long totalDurationMs, long durationPlayedMs): JSONObject`.
- Later tasks must use this method for both audio and video completion.

- [ ] **Step 1: Write the failing media properties tests**

Create `ResearchLogPropertiesTest.java`:

```java
package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ResearchLogPropertiesTest {
    @Test
    public void mediaCompletionContainsNewAndCompatibleDurations() {
        JSONObject properties =
                ResearchLogProperties.mediaPlayCompleted(
                        4200L,
                        5000L,
                        3200L);

        assertEquals(4200L, properties.optLong("position_ms", -1L));
        assertEquals(5000L, properties.optLong("total_duration", -1L));
        assertEquals(3200L, properties.optLong("duration_played", -1L));
        assertEquals(5000L, properties.optLong("duration_ms", -1L));
        assertEquals(3200L, properties.optLong("played_duration_ms", -1L));
        assertEquals(5, properties.length());
    }

    @Test
    public void mediaCompletionPreservesNonPositivePlayerDurationForAnalysisFiltering() {
        JSONObject properties =
                ResearchLogProperties.mediaPlayCompleted(
                        0L,
                        -1L,
                        0L);

        assertEquals(-1L, properties.optLong("total_duration", 99L));
        assertEquals(-1L, properties.optLong("duration_ms", 99L));
        assertEquals(0L, properties.optLong("duration_played", -1L));
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchLogPropertiesTest \
  --console=plain
```

Expected: compilation fails because `ResearchLogProperties` does not exist.

- [ ] **Step 3: Implement the minimal media properties builder**

Create `ResearchLogProperties.java`:

```java
package com.hry.camera.usbcamerademo;

import org.json.JSONException;
import org.json.JSONObject;

/** Pure builders and transition rules for stable research log properties. */
final class ResearchLogProperties {
    private ResearchLogProperties() {
    }

    static JSONObject mediaPlayCompleted(
            long positionMs,
            long totalDurationMs,
            long durationPlayedMs
    ) {
        JSONObject properties = new JSONObject();
        try {
            properties.put("position_ms", positionMs);
            properties.put("duration_ms", totalDurationMs);
            properties.put("played_duration_ms", durationPlayedMs);
            properties.put("duration_played", durationPlayedMs);
            properties.put("total_duration", totalDurationMs);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return properties;
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: both media properties tests pass.

- [ ] **Step 5: Commit the media builder**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchLogProperties.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchLogPropertiesTest.java
git commit -m "feat: add media completion log properties"
```

---

### Task 2: Add save-decision transition properties

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchLogProperties.java`
- Modify: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchLogPropertiesTest.java`

**Interfaces:**
- Consumes: previous event action, and next A/B/C action.
- Produces: `ResearchLogProperties.momentSaveDecision(String previousAction, String nextAction): JSONObject`.
- Returns `null` for invalid next actions or same-action no-op.
- Later Activity integrations log the returned object only after persistence succeeds.

- [ ] **Step 1: Append failing initial/update/no-op tests**

Append to `ResearchLogPropertiesTest`:

```java
    @Test
    public void firstDecisionIsNotAnUpdate() {
        JSONObject properties =
                ResearchLogProperties.momentSaveDecision(
                        null,
                        "save_push");

        assertEquals("save_push", properties.optString("action"));
        assertEquals(true, properties.optBoolean("push_allowed"));
        assertEquals(false, properties.optBoolean("is_update"));
    }

    @Test
    public void changingExistingDecisionIsAnUpdate() {
        JSONObject disablePush =
                ResearchLogProperties.momentSaveDecision(
                        "save_push",
                        "save_no_push");
        JSONObject enablePush =
                ResearchLogProperties.momentSaveDecision(
                        "save_no_push",
                        "save_push");
        JSONObject delete =
                ResearchLogProperties.momentSaveDecision(
                        "save_push",
                        "delete");

        assertEquals(true, disablePush.optBoolean("is_update"));
        assertEquals(false, disablePush.optBoolean("push_allowed"));
        assertEquals(true, enablePush.optBoolean("is_update"));
        assertEquals(true, enablePush.optBoolean("push_allowed"));
        assertEquals(true, delete.optBoolean("is_update"));
        assertEquals(false, delete.optBoolean("push_allowed"));
    }

    @Test
    public void directDeleteWithoutPreviousDecisionIsInitial() {
        JSONObject properties =
                ResearchLogProperties.momentSaveDecision(
                        null,
                        "delete");

        assertEquals("delete", properties.optString("action"));
        assertEquals(false, properties.optBoolean("push_allowed"));
        assertEquals(false, properties.optBoolean("is_update"));
    }

    @Test
    public void sameAndInvalidActionsDoNotProduceDecisionProperties() {
        assertNull(ResearchLogProperties.momentSaveDecision(
                "save_push", "save_push"));
        assertNull(ResearchLogProperties.momentSaveDecision(
                null, null));
        assertNull(ResearchLogProperties.momentSaveDecision(
                null, ""));
        assertNull(ResearchLogProperties.momentSaveDecision(
                null, "unsupported"));
    }
```

Add:

```java
import static org.junit.Assert.assertNull;
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchLogPropertiesTest \
  --console=plain
```

Expected: compilation fails because `momentSaveDecision` does not exist.

- [ ] **Step 3: Implement the decision transition builder**

Append to `ResearchLogProperties`:

```java
    static JSONObject momentSaveDecision(
            String previousAction,
            String nextAction
    ) {
        if (!isDecisionAction(nextAction)
                || nextAction.equals(previousAction)) {
            return null;
        }
        JSONObject properties = new JSONObject();
        try {
            properties.put("action", nextAction);
            properties.put(
                    "push_allowed",
                    "save_push".equals(nextAction));
            properties.put(
                    "is_update",
                    previousAction != null
                            && previousAction.length() > 0);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return properties;
    }

    private static boolean isDecisionAction(String action) {
        return "delete".equals(action)
                || "save_push".equals(action)
                || "save_no_push".equals(action);
    }
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: all decision and media properties tests pass.

- [ ] **Step 5: Commit transition semantics**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ResearchLogProperties.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchLogPropertiesTest.java
git commit -m "feat: classify save decision log updates"
```

---

### Task 3: Use completion properties in audio and video

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:1343-1362`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/VideoPlayerActivity.java:132-153`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchLogPropertiesTest.java`

**Interfaces:**
- Consumes: `ResearchLogProperties.mediaPlayCompleted(...)` from Task 1.
- Produces: audio and video `media_play_completed` with new and compatible duration fields.
- Existing media identity, playback instance, gain fields and tracker lifecycle remain unchanged.

- [ ] **Step 1: Run the tested properties contract before integration**

Run:

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchLogPropertiesTest \
  --tests com.hry.camera.usbcamerademo.ResearchPlaybackTrackerTest \
  --console=plain
```

Expected: PASS; the builder contract and pause-exclusion tracker are green before integration.
The required properties behavior was observed RED before the Task 1 implementation.

- [ ] **Step 2: Replace inline audio completion properties**

In `EventDetailActivity` completion callback, preserve the tracker finish call, then replace:

```java
ResearchInteractionLogger.properties(
        "position_ms", completedPlayer.getDuration(),
        "duration_ms", completedPlayer.getDuration(),
        "played_duration_ms", playedDurationMs)
```

with:

```java
ResearchLogProperties.mediaPlayCompleted(
        completedPlayer.getDuration(),
        completedPlayer.getDuration(),
        playedDurationMs)
```

Do not change `logMediaPlaybackEvent`; it must continue appending media identity,
`playback_instance_id`, media type, and laughter gain fields.

- [ ] **Step 3: Replace inline video completion properties**

In `VideoPlayerActivity` completion callback, replace the inline three duration properties with:

```java
ResearchLogProperties.mediaPlayCompleted(
        videoView.getDuration(),
        videoView.getDuration(),
        playedDurationMs)
```

Do not change paused, failed, prepared or external-player behavior.

- [ ] **Step 4: Compile and rerun focused media tests**

Run the command from Step 2.

Expected: compile succeeds and all focused tests pass.

- [ ] **Step 5: Commit the playback integrations**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/VideoPlayerActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchLogPropertiesTest.java
git commit -m "feat: log playable and total media durations"
```

---

### Task 4: Log initial and updated A/B/C decisions after success

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventSupplementActivity.java:200-260`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java:318-358`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchLogPropertiesTest.java`

**Interfaces:**
- Consumes: `ResearchLogProperties.momentSaveDecision(previousAction, nextAction)`.
- Produces: uniform append-only `moment_save_decision` events only after successful persistence or deletion.
- Returns `null` for same-action or invalid transitions; callers do not log null.

- [ ] **Step 1: Run the tested decision contract before integration**

Run:

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchLogPropertiesTest \
  --console=plain
```

Expected: PASS. Initial/update/no-op/invalid behavior was observed RED before the Task 2
implementation. This task only wires that tested contract into successful Activity operations.

- [ ] **Step 2: Add a local logging helper in `EventSupplementActivity`**

Add:

```java
private void logSaveDecisionIfChanged(
        String previousAction,
        String nextAction
) {
    JSONObject properties =
            ResearchLogProperties.momentSaveDecision(
                    previousAction,
                    nextAction);
    if (properties == null) {
        return;
    }
    ResearchInteractionLogger.log(
            this,
            ResearchEventNames.MOMENT_SAVE_DECISION,
            sessionId,
            eventId,
            null,
            properties);
}
```

This helper only writes; callers remain responsible for invoking it after success.

- [ ] **Step 3: Move initial/delete decision logging after successful deletion**

At the start of the positive delete callback:

```java
String previousAction =
        repository.getSaveDecisionAction(eventJson);
```

Remove the current unconditional `MOMENT_SAVE_DECISION` call before deletion. Inside
`if (deleted)`, before `MOMENT_DELETED`, call:

```java
logSaveDecisionIfChanged(previousAction, "delete");
```

Canceled or failed deletion must produce neither decision update nor `moment_deleted`.

- [ ] **Step 4: Mark saved B/C decisions as initial or update**

In `applyDecisionAndOfferEdit`, read before mutation:

```java
String previousAction =
        repository.getSaveDecisionAction(eventJson);
```

After `repository.saveDecision(eventJson, action)` returns true, replace the inline
`MOMENT_SAVE_DECISION` properties with:

```java
logSaveDecisionIfChanged(previousAction, action);
```

Calling the method with the same current action produces no new row.

- [ ] **Step 5: Add decision update logging to detail deletion**

In `EventDetailActivity.confirmDeleteEvent`, capture before deletion:

```java
String previousAction =
        repository.getSaveDecisionAction(eventJson);
```

Inside `if (deleted)`, before the existing `MOMENT_DELETED` call:

```java
JSONObject decisionProperties =
        ResearchLogProperties.momentSaveDecision(
                previousAction,
                "delete");
if (decisionProperties != null) {
    ResearchInteractionLogger.log(
            EventDetailActivity.this,
            ResearchEventNames.MOMENT_SAVE_DECISION,
            sessionId,
            eventId,
            null,
            decisionProperties);
}
```

Keep `MOMENT_DELETED`, location refresh and `finish()` unchanged.

- [ ] **Step 6: Run decision tests and compile integrations**

Run:

```sh
sh gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.ResearchLogPropertiesTest \
  --tests com.hry.camera.usbcamerademo.ResearchLogRecordTest \
  --console=plain
```

Expected: all tests pass and both Activities compile.

- [ ] **Step 7: Commit decision logging**

```sh
git add \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventSupplementActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/ResearchLogPropertiesTest.java
git commit -m "feat: log save decision updates"
```

---

### Task 5: Update schema documentation and complete verification

**Files:**
- Modify: `docs/research-log-schema-v1.md:45-82`
- Modify: `README.md` research log and test sections

**Interfaces:**
- Documents the exact additive schema produced by Tasks 1-4.
- Does not change runtime behavior.

- [ ] **Step 1: Update `moment_save_decision` schema**

Change the table entry to:

```markdown
| `moment_save_decision` | `action`, `push_allowed`, `is_update` |
```

Add:

```markdown
`moment_save_decision` 采用追加式历史：首次 A/B/C 为 `is_update=false`；已有决策成功
变为另一项时为 `is_update=true`。重复选择同一 action、取消或持久化失败不写新行。
```

- [ ] **Step 2: Update completion schema and analysis formula**

Change the completed entry to:

```markdown
| `media_play_completed` | `media_item_id`, `media_type`, `playback_instance_id`, `position_ms`, `duration_ms`, `played_duration_ms`, `duration_played`, `total_duration` |
```

Document:

```markdown
`duration_played` 与 `total_duration` 均为毫秒，分别等于兼容字段
`played_duration_ms` 与 `duration_ms`。当 `total_duration > 0` 时，可计算
`duration_played / total_duration`；非正总时长应排除。
```

- [ ] **Step 3: Update README collaborator guidance**

In the research interaction log section, add:

```markdown
- 完成播放同时记录 `duration_played` 和 `total_duration`（毫秒），可计算实际播放比例；
- 保存决策首次为 `is_update=false`，后续实际变化追加 `is_update=true`，旧行不覆盖。
```

Add `ResearchLogPropertiesTest` to the focused test list.

- [ ] **Step 4: Validate documentation**

Run:

```sh
git diff --check
rg -n "duration_played|total_duration|is_update" \
  README.md docs/research-log-schema-v1.md
```

Expected: no whitespace errors; both documents contain all three field names and millisecond
semantics.

- [ ] **Step 5: Run the complete debug unit-test suite**

Run:

```sh
sh gradlew :atlasapp:testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL`, zero failed tests.

- [ ] **Step 6: Build the debug APK**

Run:

```sh
sh gradlew :atlasapp:assembleDebug --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL`; output:

```text
atlasapp/build/outputs/apk/debug/atlasapp-debug.apk
```

- [ ] **Step 7: Inspect the final diff and workspace**

Run:

```sh
git diff --check
git status --short
git diff --stat HEAD
```

Confirm only intended source, tests and docs are staged or modified. Do not stage
`local.properties`, Gradle caches, build intermediates, `.superpowers/`, `artifacts/`, API keys or
participant data.

- [ ] **Step 8: Commit documentation**

```sh
git add README.md docs/research-log-schema-v1.md
git commit -m "docs: document decision and completion log fields"
```

- [ ] **Step 9: Final verification after all commits**

Run:

```sh
git status -sb
git log --oneline -6
```

Expected: `fj_ver` contains the design, plan, implementation and docs commits; only pre-existing
untracked local directories remain. Do not push without explicit user authorization.
