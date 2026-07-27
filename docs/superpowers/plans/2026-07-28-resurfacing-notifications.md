# Atlas 2.0 Resurfacing Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add robust Basic daily and Special same-place local notifications for `save_push` moments, independent reminder settings, event deletion, focused map navigation, and refreshed Atlas iconography.

**Architecture:** Pure Java policy classes calculate calendar windows, event ranking, location clusters, age, cooldown, and deduplication. Android-specific scheduler, receiver, registrar, and notification classes are thin adapters over AlarmManager, LocationManager, SharedPreferences, NotificationManager, and the existing file repository. UI changes are limited to Me settings, Event Detail deletion, forced resurfacing mode, focused Map navigation, permission recovery, and icon resources.

**Tech Stack:** Java 8, Android SDK 22–28 APIs, Android support libraries 28.0.0, JUnit 4, `org.json`, AlarmManager, LocationManager proximity alerts, NotificationManager, SharedPreferences, AMap WebView.

## Global Constraints

- Work only on local branch `user-study-prototype`; never push until the user explicitly authorizes it.
- Preserve all existing passive laughter capture, USB camera, Speechmatics, context resolution, review, supplement, and media behavior.
- Only events with `save_decision.action == "save_push"` are notification candidates.
- Daily and same-place reminders default to enabled and remain independently controllable.
- Daily schedule is 19:30 local time; Short queries yesterday and Long queries seven calendar days ago.
- Special radius is 50 m, backing-event minimum age is 6 h, global Special cooldown is 2 h, and same-place delivery is once per local day.
- Put tunable timing and distance values in `AppConfig`.
- Do not add Google Play Services, WorkManager, AndroidX, Room, or a remote backend.
- Follow red-green-refactor for policy behavior and run a full unit-test/build verification at the end.

---

### Task 1: Configuration, schedule math, and persistent reminder state

**Files:**
- Modify: `atlasapp/build.gradle`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReminderSchedule.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReminderPreferences.java`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasReminderScheduleTest.java`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasLocationReminderPolicyTest.java`

**Interfaces:**
- Produces: `AtlasReminderSchedule.nextDailyTrigger(long, TimeZone)`, `dayWindow(long, int, TimeZone)`, `isOldEnough(long, long)`, `cooldownElapsed(long, long)`.
- Produces: `AtlasReminderPreferences` switch, daily category, Special daily-Set, cooldown, and registered-request-code accessors.
- Consumes: existing `JoyfulMomentConfig.PREF_NAME`.

- [ ] **Step 1: Add failing schedule and policy tests**

```java
private static long localMillis(
        TimeZone zone, int year, int month, int day, int hour, int minute) {
    Calendar calendar = Calendar.getInstance(zone);
    calendar.clear();
    calendar.set(year, month, day, hour, minute, 0);
    return calendar.getTimeInMillis();
}

@Test public void nextTriggerBefore1930IsToday() {
    TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
    long now = localMillis(zone, 2026, Calendar.JULY, 28, 19, 29);
    assertEquals(
            localMillis(zone, 2026, Calendar.JULY, 28, 19, 30),
            AtlasReminderSchedule.nextDailyTrigger(now, zone));
}

@Test public void nextTriggerAt1930IsTomorrow() {
    TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
    long now = localMillis(zone, 2026, Calendar.JULY, 28, 19, 30);
    assertEquals(
            localMillis(zone, 2026, Calendar.JULY, 29, 19, 30),
            AtlasReminderSchedule.nextDailyTrigger(now, zone));
}

@Test public void dayWindowUsesCalendarDayAcrossDst() {
    TimeZone zone = TimeZone.getTimeZone("America/New_York");
    long now = localMillis(zone, 2026, Calendar.MARCH, 9, 12, 0);
    long[] window = AtlasReminderSchedule.dayWindow(now, 1, zone);
    assertEquals(localMillis(zone, 2026, Calendar.MARCH, 8, 0, 0), window[0]);
    assertEquals(localMillis(zone, 2026, Calendar.MARCH, 9, 0, 0), window[1]);
    assertEquals(23L * 60L * 60L * 1000L, window[1] - window[0]);
}

@Test public void sixHourOldEventIsEligible() {
    long now = 10L * 60L * 60L * 1000L;
    assertTrue(AtlasReminderSchedule.isOldEnough(
            now - AppConfig.SPECIAL_MIN_EVENT_AGE_MS, now));
}

@Test public void youngerEventIsRejected() {
    long now = 10L * 60L * 60L * 1000L;
    assertFalse(AtlasReminderSchedule.isOldEnough(
            now - AppConfig.SPECIAL_MIN_EVENT_AGE_MS + 1L, now));
}

@Test public void twoHourCooldownBoundaryIsEligible() {
    long lastSent = 1_000L;
    assertTrue(AtlasReminderSchedule.cooldownElapsed(
            lastSent, lastSent + AppConfig.SPECIAL_NOTIFICATION_COOLDOWN_MS));
    assertFalse(AtlasReminderSchedule.cooldownElapsed(
            lastSent, lastSent + AppConfig.SPECIAL_NOTIFICATION_COOLDOWN_MS - 1L));
}
```

- [ ] **Step 2: Run the focused tests and confirm RED**

```sh
./gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasReminderScheduleTest \
  --tests com.hry.camera.usbcamerademo.AtlasLocationReminderPolicyTest \
  --console=plain
```

Expected failure: missing `AtlasReminderSchedule` and AppConfig reminder constants.

- [ ] **Step 3: Add AppConfig constants and the pure schedule policy**

Implement calendar-based day boundaries with cloned `Calendar` objects and explicit local
hour/minute/second/millisecond fields. Do not subtract fixed 24-hour durations for day selection.

- [ ] **Step 4: Implement `AtlasReminderPreferences`**

Required API:

```java
boolean isDailyEnabled();
void setDailyEnabled(boolean enabled);
boolean isLocationEnabled();
void setLocationEnabled(boolean enabled);
boolean wasDailyCategorySent(String category, String localDate);
void markDailyCategorySent(String category, String localDate);
boolean wasLocationSentToday(String clusterKey, String localDate);
void markLocationSent(String clusterKey, String localDate, long sentAtMs);
long getLastLocationSentAtMs();
Set<Integer> getRegisteredProximityRequestCodes();
void setRegisteredProximityRequestCodes(Set<Integer> codes);
```

Always copy returned SharedPreferences `StringSet` values before pruning or mutation.

- [ ] **Step 5: Run focused tests and confirm GREEN**

Use the command from Step 2 and confirm zero failures.

- [ ] **Step 6: Commit the task**

```sh
git add atlasapp/build.gradle atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReminderSchedule.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReminderPreferences.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasReminderScheduleTest.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasLocationReminderPolicyTest.java
git commit -m "feat: add resurfacing reminder policy"
```

### Task 2: Event eligibility and two-stage Basic selection

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasResurfacingSelector.java`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasResurfacingSelectorTest.java`

**Interfaces:**
- Consumes: `AtlasReviewRepository.EventSummary.eventJson`, event timestamps, and event ID.
- Produces: `isPushEligible`, `hasUserSupplement`, `countMedia`, and `selectForCalendarDay`.

- [ ] **Step 1: Write failing selector tests**

Create JSON-backed EventSummary fixtures and test:

```java
private AtlasReviewRepository.EventSummary event(
        String id, long startMs, String action, boolean supplemented, int mediaCount)
        throws Exception {
    AtlasReviewRepository.EventSummary summary = new AtlasReviewRepository.EventSummary();
    summary.eventId = id;
    summary.startTimeMs = startMs;
    JSONObject event = new JSONObject();
    if (action != null) {
        event.put("save_decision", new JSONObject().put("action", action));
    }
    JSONObject user = new JSONObject();
    JSONArray notes = new JSONArray();
    if (supplemented) {
        notes.put(new JSONObject().put("text", "remember this"));
    }
    user.put("notes", notes);
    user.put("audio_notes", new JSONArray());
    user.put("photos", new JSONArray());
    user.put("social_context", new JSONObject());
    event.put("user_generated", user);
    JSONObject auto = new JSONObject();
    JSONArray clips = new JSONArray();
    for (int i = 0; i < mediaCount; i++) {
        clips.put(new JSONObject().put("path", "clip-" + i + ".wav"));
    }
    auto.put("audio_clips", clips);
    auto.put("photos", new JSONArray());
    auto.put("videos", new JSONArray());
    event.put("auto_captured", auto);
    summary.eventJson = event;
    return summary;
}

@Test public void rejectsSaveNoPushAndMissingDecision() throws Exception {
    AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
    assertFalse(selector.isPushEligible(event("a", 1_000L, "save_no_push", true, 2)));
    assertFalse(selector.isPushEligible(event("b", 1_000L, null, true, 2)));
}

@Test public void supplementedEventWinsBeforeMediaCount() throws Exception {
    AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
    AtlasReviewRepository.EventSummary supplemented =
            event("supplemented", 2_000L, "save_push", true, 1);
    AtlasReviewRepository.EventSummary mediaHeavy =
            event("media-heavy", 3_000L, "save_push", false, 8);
    AtlasReviewRepository.EventSummary selected = selector.selectForCalendarDay(
            Arrays.asList(mediaHeavy, supplemented), 0L, 10_000L, 5_000L);
    assertEquals("supplemented", selected.eventId);
}

@Test public void mediaCountBreaksTieWithinSupplementTier() throws Exception {
    AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
    AtlasReviewRepository.EventSummary low = event("low", 2_000L, "save_push", true, 1);
    AtlasReviewRepository.EventSummary high = event("high", 3_000L, "save_push", true, 3);
    assertEquals("high", selector.selectForCalendarDay(
            Arrays.asList(low, high), 0L, 10_000L, 5_000L).eventId);
}

@Test public void preferredTimeBreaksFullTie() throws Exception {
    AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
    AtlasReviewRepository.EventSummary far = event("far", 1_000L, "save_push", true, 2);
    AtlasReviewRepository.EventSummary near = event("near", 4_500L, "save_push", true, 2);
    assertEquals("near", selector.selectForCalendarDay(
            Arrays.asList(far, near), 0L, 10_000L, 5_000L).eventId);
}

@Test public void eventIdMakesSelectionDeterministic() throws Exception {
    AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
    AtlasReviewRepository.EventSummary b = event("b", 4_000L, "save_push", true, 2);
    AtlasReviewRepository.EventSummary a = event("a", 4_000L, "save_push", true, 2);
    assertEquals("a", selector.selectForCalendarDay(
            Arrays.asList(b, a), 0L, 10_000L, 5_000L).eventId);
}

@Test public void noEligibleEventReturnsNull() throws Exception {
    AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
    assertNull(selector.selectForCalendarDay(
            Collections.singletonList(event("x", 4_000L, "save_no_push", true, 2)),
            0L, 10_000L, 5_000L));
}
```

- [ ] **Step 2: Run selector tests and confirm RED**

```sh
./gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasResurfacingSelectorTest \
  --console=plain
```

Expected failure: selector class is missing.

- [ ] **Step 3: Implement the minimal selector**

Ranking order must be exactly:

```text
save_push eligibility
calendar-day containment
hasUserSupplement descending
countMedia descending
absolute distance from preferred 19:30 ascending
eventId ascending
```

Media count excludes text and social fields after those fields establish supplementation.

- [ ] **Step 4: Run selector tests and confirm GREEN**

Use the Step 2 command.

- [ ] **Step 5: Commit the task**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasResurfacingSelector.java \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasResurfacingSelectorTest.java
git commit -m "feat: select moments for daily resurfacing"
```

### Task 3: Basic AlarmManager scheduling and notifications

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasNotificationHelper.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasDailyReminderScheduler.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasDailyReminderReceiver.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasResurfacingSystemReceiver.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasResurfacingManager.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasApplication.java`
- Modify: `atlasapp/src/main/AndroidManifest.xml`
- Modify: `atlasapp/src/main/res/values/strings.xml`
- Modify: `atlasapp/src/main/res/values-zh/strings.xml`
- Create: `atlasapp/src/main/res/drawable/ic_atlas_notification.xml`

**Interfaces:**
- Consumes: Task 1 schedule/preferences and Task 2 selector.
- Produces: notification channels, Short/Long notification posting, one-shot exact scheduling, retry scheduling, and system-event recovery.

- [ ] **Step 1: Add notification resources and manifest declarations**

Declare boot/background-location/post-notification permissions and explicit receivers. Add
localized channel, Short, Long, fallback, and permission-status strings.

- [ ] **Step 2: Implement `AtlasNotificationHelper`**

Use distinct fixed IDs:

```java
static final int NOTIFICATION_ID_DAILY_SHORT = 2101;
static final int NOTIFICATION_ID_DAILY_LONG = 2102;
```

Build explicit EventDetail PendingIntents with event/session IDs and forced resurfacing mode. Use
the orange accent, `CATEGORY_REMINDER`, auto-cancel, BigTextStyle, and
`ic_atlas_notification`.

- [ ] **Step 3: Implement exact one-shot scheduling**

Use `setExactAndAllowWhileIdle` on API 23+ and `setExact` on API 22. Use separate request codes
for the regular alarm and retry alarm.

- [ ] **Step 4: Implement the daily receiver**

Use `goAsync`, schedule the next regular alarm first, load events once, independently select and
post categories, mark after notify, and retry only unexpected exceptions. Always call
`PendingResult.finish`.

- [ ] **Step 5: Wire application and system recovery**

Initialize channels/scheduling from AtlasApplication. Reconcile on boot, time, timezone, and
package replacement.

- [ ] **Step 6: Compile and run all current unit tests**

```sh
./gradlew :atlasapp:testDebugUnitTest :atlasapp:assembleDebug --console=plain
```

- [ ] **Step 7: Commit the task**

```sh
git add atlasapp/src/main atlasapp/src/test atlasapp/build.gradle
git commit -m "feat: schedule daily resurfacing notifications"
```

### Task 4: Special location clustering, registration, and notification

**Files:**
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLocationClusterer.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLocationReminderRegistrar.java`
- Create: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasLocationReminderReceiver.java`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasLocationClustererTest.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasNotificationHelper.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasResurfacingManager.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventSupplementActivity.java`
- Modify: `atlasapp/src/main/AndroidManifest.xml`
- Modify: `atlasapp/src/main/res/values/strings.xml`
- Modify: `atlasapp/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: Task 1 preferences/policy and Task 2 `isPushEligible`.
- Produces: clustered `save_push` GPS locations, proximity registration lifecycle, trigger
  revalidation, daily-place suppression, global cooldown, and generic Special notification.

- [ ] **Step 1: Write failing cluster tests**

Cover distance inside/outside 50 m, stable grouping, ignored missing GPS/non-save-push events,
stable keys, and collision-free request codes.

- [ ] **Step 2: Run cluster tests and confirm RED**

```sh
./gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasLocationClustererTest \
  --console=plain
```

- [ ] **Step 3: Implement Haversine clustering and request-code allocation**

Use `AppConfig.SPECIAL_LOCATION_RADIUS_METERS`; do not inline 50.

- [ ] **Step 4: Implement registrar synchronization**

Remove persisted old PendingIntents first, then register one native
`LocationManager.addProximityAlert` per current cluster. Use a mutable PendingIntent flag on API
31+ so the system can attach the entering extra.

- [ ] **Step 5: Implement receiver validation**

Recheck enabled state, current repository eligibility, distance, six-hour age, same-date cluster
Set, and two-hour global cooldown. Post generic copy and persist state only after notify.

- [ ] **Step 6: Refresh registrations after save_push and lifecycle reconciliation**

Call registrar sync after EventSupplement saves `save_push` and from the manager/system receiver.

- [ ] **Step 7: Run focused and full tests**

```sh
./gradlew :atlasapp:testDebugUnitTest --console=plain
```

- [ ] **Step 8: Commit the task**

```sh
git add atlasapp/src/main atlasapp/src/test
git commit -m "feat: add same-place resurfacing reminders"
```

### Task 5: Forced detail mode and focused Map navigation

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasMapHtmlBuilder.java`

**Interfaces:**
- Consumes: notification Intent extras from Tasks 3–4.
- Produces: forced `"short"`/`"long"` detail rendering and Map-tab focus handling in both
  `onCreate` and `onNewIntent`.

- [ ] **Step 1: Add explicit Event Detail mode handling**

Read `resurfacing_mode`; force mode only for exact `"short"` or `"long"` values. Preserve the
existing seven-day calculation for all normal in-app navigation.

- [ ] **Step 2: Add ReviewShell focus Intent handling**

Read `initial_review_tab`, `focus_lat`, `focus_lng`, and `focus_radius_m`, select Map, and retain
focus state across `onResume`. Implement `onNewIntent` for an existing reordered activity.

- [ ] **Step 3: Overload AtlasMapHtmlBuilder**

Keep `build(events)` as the no-focus path. Add `build(events, focusLat, focusLng)` so notification
navigation centers and zooms after GPS-to-AMap conversion while normal review still fits all
markers.

- [ ] **Step 4: Build**

```sh
./gradlew :atlasapp:assembleDebug --console=plain
```

- [ ] **Step 5: Commit the task**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasMapHtmlBuilder.java
git commit -m "feat: route resurfacing notifications to review"
```

### Task 6: Independent Me settings and permission recovery

**Files:**
- Modify: `atlasapp/src/main/res/layout/activity_me.xml`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MeActivity.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java`
- Modify: `atlasapp/src/main/res/values/strings.xml`
- Modify: `atlasapp/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: reminder preferences, scheduler, registrar, and manager.
- Produces: two default-on independent switches, permission status, initial location/notification
  permission request, and re-request on enable.

- [ ] **Step 1: Add the reminder settings section**

Add two `SwitchCompat` rows and two status TextViews. Match the existing warm Atlas cards and
typography.

- [ ] **Step 2: Implement independent switch behavior**

Daily toggle schedules/cancels only Basic. Location toggle syncs/removes only proximity alerts.
Guard population so programmatic `setChecked` does not invoke actions.

- [ ] **Step 3: Add permission recovery**

Include fine/coarse location in initial Main permission flow. On Android 13+, request the literal
post-notification permission. Re-request missing permissions when enabling from Me and refresh
status in `onResume`.

- [ ] **Step 4: Build and run tests**

```sh
./gradlew :atlasapp:testDebugUnitTest :atlasapp:assembleDebug --console=plain
```

- [ ] **Step 5: Commit the task**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/MeActivity.java \
  atlasapp/src/main/res/layout/activity_me.xml \
  atlasapp/src/main/res/values/strings.xml atlasapp/src/main/res/values-zh/strings.xml
git commit -m "feat: add independent resurfacing settings"
```

### Task 7: Safe permanent event deletion

**Files:**
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java`
- Modify: `atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java`
- Modify: `atlasapp/src/main/res/layout/activity_event_detail.xml`
- Modify: `atlasapp/src/main/res/values/strings.xml`
- Modify: `atlasapp/src/main/res/values-zh/strings.xml`
- Test: `atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasEventDeletionPathsTest.java`

**Interfaces:**
- Produces: visible confirmed deletion, safe referenced-path enumeration, event JSON removal, and
  proximity refresh.

- [ ] **Step 1: Write failing path-enumeration tests**

Use temporary Atlas roots and event JSON containing `path`, `photo_path`, `video_path`,
`saved_clip_paths`, captured-media files, and an outside-root path. Assert app-owned paths are
selected while outside-root paths are rejected.

- [ ] **Step 2: Run deletion tests and confirm RED**

```sh
./gradlew :atlasapp:testDebugUnitTest \
  --tests com.hry.camera.usbcamerademo.AtlasEventDeletionPathsTest \
  --console=plain
```

- [ ] **Step 3: Strengthen repository deletion**

Canonicalize paths, constrain deletion to approved roots, delete event-owned media directories
without targeting a session/root directory, and delete the event JSON last.

- [ ] **Step 4: Add visible Event Detail deletion**

Use an event-specific confirmation dialog. On success, sync location registrations, show
localized confirmation, and finish.

- [ ] **Step 5: Run focused and full tests**

```sh
./gradlew :atlasapp:testDebugUnitTest :atlasapp:assembleDebug --console=plain
```

- [ ] **Step 6: Commit the task**

```sh
git add atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java \
  atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java \
  atlasapp/src/main/res/layout/activity_event_detail.xml \
  atlasapp/src/main/res/values/strings.xml atlasapp/src/main/res/values-zh/strings.xml \
  atlasapp/src/test/java/com/hry/camera/usbcamerademo/AtlasEventDeletionPathsTest.java
git commit -m "feat: allow permanent moment deletion"
```

### Task 8: Atlas launcher identity and final verification

**Files:**
- Modify: `atlasapp/src/main/res/drawable-v24/ic_launcher_foreground.xml`
- Modify: `atlasapp/src/main/res/drawable/ic_launcher_background.xml`
- Modify: `atlasapp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `atlasapp/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: density-specific `ic_launcher.png` and `ic_launcher_round.png` resources if required by the selected adaptive icon approach

**Interfaces:**
- Produces: warm orange laughter/map launcher icon consistent with the notification icon.

- [ ] **Step 1: Replace the generic launcher foreground**

Use a deterministic vector-friendly smiling location-pin/atlas mark with no text or Android robot
imagery. Keep generous adaptive-icon safe-zone padding.

- [ ] **Step 2: Render and inspect launcher resources**

Build the debug APK and inspect generated launcher assets or install on a device/emulator when
available.

- [ ] **Step 3: Run full verification**

```sh
./gradlew :atlasapp:testDebugUnitTest --console=plain
./gradlew :atlasapp:assembleDebug --console=plain
git diff --check
git status --short
```

- [ ] **Step 4: Review requirement coverage**

Re-read the design traceability table and verify every requirement against code, tests, resources,
and manifest. Record any device-only checks that cannot be executed locally.

- [ ] **Step 5: Commit final resources and verification fixes**

```sh
git add atlasapp/src/main
git commit -m "style: refresh Atlas app icon"
```

- [ ] **Step 6: Keep the branch local**

Do not push. Report local commits and verification evidence, then wait for explicit user
authorization before any remote operation.
