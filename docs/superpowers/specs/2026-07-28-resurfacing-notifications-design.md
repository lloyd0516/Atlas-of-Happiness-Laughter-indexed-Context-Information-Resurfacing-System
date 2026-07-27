# Atlas 2.0 Resurfacing Notifications Design

## 1. Goal and scope

Extend the `user-study-prototype` Android app so that only moments whose event-level save
decision is `save_push` can produce local Android resurfacing notifications.

The feature has two independent channels:

1. **Basic daily resurfacing**
   - Around 19:30 local time, select at most one event from yesterday for short-term review.
   - Independently select at most one event from seven calendar days ago for long-term review.
   - Do not create a placeholder notification when either category has no eligible event.
2. **Special location resurfacing**
   - Reuse the existing `LocationManager` permission and `derived_context.gps` event data.
   - When the device enters a 50-metre radius around an eligible historical location, show a
     location-level reminder.
   - The notification must not identify a particular event. It opens the Review map tab centered
     on the triggering location.

The implementation also adds:

- independent, default-on switches for daily and same-location reminders in the Me tab;
- permanent event deletion from Event Detail;
- a new launcher icon and a monochrome notification small icon;
- deterministic selection, persistent deduplication, restart recovery, and unit-testable policy
  logic.

This remains a local, file-backed feature. “Push” means an Android system notification, not a
remote push service.

## 2. Constraints inherited from Atlas 2.0

- Keep the existing single-module Java Android structure and support-library UI.
- Keep `minSdkVersion 22`, `targetSdkVersion 28`, and the existing USB camera/native libraries.
- Do not introduce Google Play Services, WorkManager, Room, or a remote backend.
- Continue to treat `AtlasReviewRepository` and the event JSON files as the source of truth.
- Continue using the existing `LocationManager` GPS flow and AMap-derived event context.
- Perform notification and location work in background receivers; keep front-end changes small.
- Store all tunable timing and distance values in `AppConfig`, not inline in receivers or
  activities.
- Both reminder switches default to enabled. A user can disable either one without affecting the
  other.

## 3. Requirement traceability

| Requirement | Design response |
|---|---|
| Daily short and long reminders | One exact system alarm invokes two independent date queries. |
| No empty notification | A category calls `NotificationManager.notify` only when selection returns an event. |
| Location trigger within 50 m | Native `LocationManager.addProximityAlert` registrations built from eligible event GPS. |
| Robustness | Exact one-shot alarm, next-alarm rescheduling, boot/time/timezone/package recovery, app-start reconciliation, persistent deduplication, bounded daily retry, and receiver logging. |
| Separate Basic notifications | Fixed, distinct short and long notification IDs and PendingIntent request codes. |
| Different reconstruction tone | Short copy is concrete and factual; long copy is gentle and reflective. |
| Delete at any time | Visible Event Detail delete action with confirmation, media cleanup, event removal, and location registration refresh. |
| Two-stage event selection | First user-supplement presence, then media count; deterministic tie-breaking only after those stages. |
| Same-location daily suppression | A date-scoped `StringSet` of location cluster keys is persisted locally. |
| Special copy does not identify an event | Location notification uses generic place-memory copy and opens the map. |
| Independent settings | Two switches in Me, both default `true`. |
| No hardcoded 19:30/50 m | All scheduling, age, cooldown, clustering, and radius values live in `AppConfig`. |
| Improved app icon | Warm orange laughter/map launcher mark plus white monochrome notification icon. |

Additional confirmed Special constraints:

- a location notification can only be backed by a `save_push` event;
- a backing event must be at least six hours old at trigger time;
- the same location cluster can notify at most once per local calendar day;
- notifications for different location clusters must be at least two hours apart.

## 4. Proposed component boundaries

### 4.1 Policy and preference classes

#### `AppConfig`

Add the following configuration constants:

```java
public static final int DAILY_REVIEW_HOUR = 19;
public static final int DAILY_REVIEW_MINUTE = 30;
public static final int SHORT_TERM_DAY_OFFSET = 1;
public static final int LONG_TERM_DAY_OFFSET = 7;
public static final float SPECIAL_LOCATION_RADIUS_METERS = 50f;
public static final long SPECIAL_MIN_EVENT_AGE_MS = 6L * 60L * 60L * 1000L;
public static final long SPECIAL_NOTIFICATION_COOLDOWN_MS = 2L * 60L * 60L * 1000L;
public static final long DAILY_NOTIFICATION_RETRY_DELAY_MS = 15L * 60L * 1000L;
public static final int DAILY_NOTIFICATION_MAX_RETRIES = 2;
```

`SPECIAL_LOCATION_RADIUS_METERS` is also the clustering threshold. Two historical GPS points
within 50 metres are treated as one place for registration and daily deduplication.

#### `AtlasReminderPreferences`

This class is the only reader/writer for reminder switches and delivery state.

Preference keys:

```text
daily_resurfacing_enabled           boolean, default true
location_resurfacing_enabled        boolean, default true
daily_short_last_sent_date          yyyy-MM-dd
daily_long_last_sent_date           yyyy-MM-dd
special_sent_location_keys          StringSet entries formatted yyyy-MM-dd|clusterKey
special_last_sent_at_ms             long
registered_proximity_request_codes  StringSet of decimal request codes
```

Rules:

- copy a `StringSet` before mutating it because `SharedPreferences` may return a live set;
- prune Special entries not belonging to the current local date before reading or writing;
- mark a category/location as delivered only after `NotificationManager.notify` returns without
  throwing;
- disabling a switch does not delete delivery history or event data.

### 4.2 Selection and clustering classes

#### `AtlasResurfacingSelector`

Pure Java policy over `AtlasReviewRepository.EventSummary`.

Public behavior:

```java
EventSummary selectForCalendarDay(
        List<EventSummary> events,
        long dayStartMs,
        long dayEndMs,
        long preferredTimeMs);

boolean isPushEligible(EventSummary event);
boolean hasUserSupplement(EventSummary event);
int countMedia(EventSummary event);
```

Eligibility requires:

```text
event.eventJson.save_decision.action == "save_push"
```

Two-stage ranking:

1. Events with any user supplement sort before events without a user supplement.
2. Within the same tier, higher media count sorts first.

User supplement means at least one of:

- non-empty `social_context.with_whom`;
- non-empty `social_context.doing_what`;
- non-empty `social_context.mood`;
- at least one `user_generated.notes` item;
- at least one `user_generated.audio_notes` item;
- at least one `user_generated.photos` item.

Media count includes:

- `auto_captured.audio_clips`;
- `auto_captured.photos`;
- `auto_captured.videos`;
- `user_generated.audio_notes`;
- `user_generated.photos`.

Text notes and social-context fields establish the first-stage user-supplement tier but are not
counted again as media.

If events remain tied after the two required stages:

1. choose the event whose start time is closest to 19:30 on the selected day;
2. compare `eventId` lexicographically to make the result deterministic.

Date windows use `Calendar` in the device’s current timezone, setting hour/minute/second/millisecond
explicitly. They must not be calculated as `24 * 60 * 60 * 1000` subtraction because that breaks
across daylight-saving changes.

#### `AtlasLocationClusterer`

Pure Java Haversine-distance grouping over push-eligible events with GPS coordinates.

Each `LocationCluster` exposes:

```java
String clusterKey;
double centerLat;
double centerLng;
List<EventSummary> events;
int requestCode;
```

Rules:

- only `save_push` events with `derived_context.gps.lat/lng` participate;
- group a point into the first cluster whose center is within
  `SPECIAL_LOCATION_RADIUS_METERS`;
- recompute a cluster center as the arithmetic mean of member coordinates;
- derive `clusterKey` from center coordinates rounded to five decimal places;
- allocate collision-free request codes during registration and persist them so old proximity
  alerts can be removed before rebuilding.

The six-hour minimum age is checked by the receiver at trigger time. A newly saved event may
register its location immediately, but cannot produce a notification until it is six hours old.
This avoids needing a separate six-hour maturation alarm.

## 5. Basic daily notification flow

### 5.1 Scheduling

`AtlasDailyReminderScheduler` owns a single one-shot alarm:

```java
scheduleNext(Context context, long nowMs)
cancel(Context context)
scheduleRetry(Context context, int retryAttempt)
```

Scheduling behavior:

1. Build the next local-time 19:30 using `Calendar`.
2. If today’s 19:30 is not in the future, schedule tomorrow’s 19:30.
3. On API 23+, use `AlarmManager.setExactAndAllowWhileIdle`.
4. On API 22, use `AlarmManager.setExact`.
5. Use an explicit broadcast PendingIntent targeting `AtlasDailyReminderReceiver`.
6. The receiver schedules the following day’s alarm before querying files, so an exception in
   the current run cannot lose the next day’s schedule.

`AtlasResurfacingManager.initialize(Context)` reconciles the alarm whenever the app process starts.
`AtlasResurfacingSystemReceiver` also calls reconciliation for:

- `BOOT_COMPLETED`;
- `TIME_SET`;
- `TIMEZONE_CHANGED`;
- `MY_PACKAGE_REPLACED`.

If daily resurfacing is disabled, reconciliation cancels the alarm.

### 5.2 Receiver execution

`AtlasDailyReminderReceiver` uses `goAsync()` and a dedicated background thread so repository
scanning does not block the broadcast main thread.

Execution order:

1. schedule the next regular 19:30 alarm;
2. stop if the daily switch is off;
3. calculate yesterday and seven-days-ago calendar windows;
4. load event summaries once;
5. independently select Short and Long candidates;
6. for each category, check its date-specific delivery marker;
7. post only categories with a candidate and no delivery marker;
8. persist each category marker after its notification is posted.

An empty candidate list is a successful no-op and does not trigger a retry.

Unexpected repository/notification exceptions are logged through `AtlasDevLogger`. The receiver
uses a separate retry PendingIntent and tries again after
`DAILY_NOTIFICATION_RETRY_DELAY_MS`, up to `DAILY_NOTIFICATION_MAX_RETRIES`. Already delivered
categories remain suppressed during a retry.

### 5.3 Notification identity and navigation

Fixed IDs:

```java
NOTIFICATION_ID_DAILY_SHORT = 2101;
NOTIFICATION_ID_DAILY_LONG = 2102;
```

They are deliberately different so both notifications remain visible when both categories have
data. A later day replaces the older notification of the same category instead of accumulating
unbounded daily notifications.

PendingIntent destinations:

- Short: `EventDetailActivity` with `event_id`, `session_id`, and
  `resurfacing_mode = "short"`.
- Long: `EventDetailActivity` with `event_id`, `session_id`, and
  `resurfacing_mode = "long"`.

`EventDetailActivity` honors the explicit mode only when the extra is `"short"` or `"long"`;
normal in-app navigation continues using the current seven-day age rule.

Copy:

```text
Chinese Short title: 昨天 20:36，一段笑声
Chinese Short body: 轻轻点开，回看昨天的快乐时刻

Chinese Long title with location: 一周前的这时候，你在王府井
Chinese Long fallback title: 一周前的这时候，留下一段笑声
Chinese Long body: 有些快乐，隔一段时间再看会不一样
```

English equivalents live in `values/strings.xml`; Chinese equivalents live in
`values-zh/strings.xml`. Notification copy uses the saved Atlas locale.

## 6. Special location notification flow

### 6.1 Registration

`AtlasLocationReminderRegistrar.sync(Context)`:

1. removes all previously persisted proximity PendingIntents;
2. stops if the location switch is off;
3. stops and logs a status if fine-location permission is absent;
4. loads all push-eligible events with GPS;
5. clusters locations within 50 metres;
6. registers one `LocationManager.addProximityAlert` per cluster with no expiration;
7. persists the exact request codes used.

The proximity PendingIntent is explicit and targets `AtlasLocationReminderReceiver`. On Android
12+, it uses the numeric mutable flag because the system must add
`LocationManager.KEY_PROXIMITY_ENTERING` to the delivered Intent while the project still compiles
against SDK 28.

Registration is synchronized after:

- application initialization;
- device reboot, time/timezone change, or package replacement;
- enabling the same-location switch;
- saving an event with `save_push`;
- permanently deleting an event.

### 6.2 Trigger validation

`AtlasLocationReminderReceiver` ignores exit transitions. For an enter transition it validates
all state again instead of trusting a potentially stale registration:

1. location reminders are enabled;
2. notification permission is available on platforms that require it;
3. at least one current `save_push` event remains within 50 metres of the trigger center;
4. at least one such event is at least six hours old;
5. today’s sent-location Set does not contain this cluster key;
6. at least two hours have elapsed since the previous Special notification, regardless of
   location.

Only after all checks pass does it post the notification and persist:

```text
yyyy-MM-dd|clusterKey
special_last_sent_at_ms
```

Different eligible locations can therefore notify on the same day, but not within two hours of
one another. Re-entering the same mall repeatedly cannot notify more than once that day.

### 6.3 Notification and Map navigation

Copy does not expose or select an event:

```text
Chinese title: 这个地方留下过一些笑声回忆
Chinese body: 打开地图，看看你曾在这里记录的快乐时刻

English title: Some laughter memories were made here
English body: Open the map to revisit moments connected to this place
```

Special notification IDs are derived from local date plus `clusterKey`. This allows notifications
from two different places to remain separately visible while the persisted location Set prevents
duplicates.

The PendingIntent opens `ReviewShellActivity` with:

```text
initial_review_tab = "map"
focus_lat
focus_lng
focus_radius_m
```

`ReviewShellActivity` processes these extras in both `onCreate` and `onNewIntent`, selects the Map
tab, and passes the focus point to `AtlasMapHtmlBuilder`.

`AtlasMapHtmlBuilder` receives an optional focus coordinate. When present, it converts GPS to AMap
coordinates in the existing JavaScript map flow, centers the map on that point, and uses a
close-place zoom instead of calling `setFitView`. Normal map navigation without focus extras keeps
the current fit-all-markers behavior.

## 7. Notification infrastructure and permissions

`AtlasNotificationHelper` owns:

- channel creation;
- notification IDs;
- locale-aware copy formatting;
- PendingIntent construction;
- `NotificationManager.notify`;
- common styling.

Channels:

```text
atlas_daily_resurfacing     Daily laughter memories
atlas_location_resurfacing  Place-based laughter memories
```

Both channels use default importance, sound, vibration, the Atlas orange accent color, category
`REMINDER`, `BigTextStyle`, auto-cancel, and the new monochrome Atlas small icon.

Manifest permissions added as raw permission names where the SDK 28 compile API lacks constants:

```text
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.ACCESS_BACKGROUND_LOCATION
android.permission.POST_NOTIFICATIONS
```

Because both switches default on:

- `MainActivity` includes fine/coarse location in its initial runtime permission request;
- on Android 13+, it requests `android.permission.POST_NOTIFICATIONS` using the literal permission
  string;
- the Me page can re-request a missing permission when the user turns a feature on;
- a denied permission never silently changes the user’s saved switch to off;
- the Me page shows that the feature is enabled but waiting for permission.

Android can still suppress notifications when the user disables the app’s system notification
permission, and device vendors can restrict background location. The app cannot bypass those user
or operating-system decisions. It will log the blocked state and reconcile registrations whenever
the app is opened again.

## 8. Me-tab settings

`activity_me.xml` gains a “Resurfacing reminders” section with two independent `SwitchCompat`
controls:

```text
Daily review reminders       default on
Same-place reminders         default on
```

Each row includes a short description and a status line for missing notification/location
permission.

`MeActivity` behavior:

- populate switches without firing listeners;
- daily off: persist false and cancel the daily alarm;
- daily on: persist true, ensure notification permission, and schedule the next alarm;
- location off: persist false and remove registered proximity alerts;
- location on: persist true, ensure notification and fine-location permissions, then rebuild
  proximity alerts;
- refresh status text in `onResume`.

The two switches never modify one another.

## 9. Event deletion

`activity_event_detail.xml` gains a visible “Delete this moment” button below the context section.

`EventDetailActivity`:

1. shows an event-specific irreversible-deletion confirmation;
2. resolves the matching `EventSummary`;
3. calls `AtlasReviewRepository.deleteEventPermanently`;
4. on success, calls `AtlasLocationReminderRegistrar.sync`;
5. finishes the detail screen and shows a localized confirmation.

`AtlasReviewRepository.deleteEventPermanently` is strengthened to remove:

- normalized `path`;
- `photo_path`;
- `video_path`;
- `saved_clip_paths`;
- event-owned files below `captured_media/<eventId>`;
- event-owned files below `user_generated/<eventId>`;
- the event JSON itself.

It must canonicalize each candidate path and delete only files under the Atlas
`joyful_moment` root or app-owned media path. It must not recursively delete a session/root
directory. JSONL audit logs are retained, but without the event JSON the event no longer appears
in any Review query.

## 10. Application lifecycle integration

`AtlasApplication.onCreate` calls:

```java
AtlasNotificationHelper.ensureChannels(this);
AtlasResurfacingManager.initialize(this);
```

The manager performs idempotent reconciliation:

- schedule or cancel the daily alarm according to its switch;
- synchronize or remove proximity alerts according to its switch and permission;
- log failures without crashing application startup.

The foreground listening service remains unchanged. Resurfacing scheduling does not depend on an
active Joyful recording session or `MainActivity` remaining alive.

## 11. Visual identity

Create a square launcher icon with:

- Atlas warm orange as the dominant color;
- a simple smiling/laughter mark integrated with a location pin or map contour;
- large, readable geometry that survives small Android launcher sizes;
- no text, watermark, photographic detail, or stock Android robot imagery.

Generate a project master PNG, inspect it, and derive density-specific launcher PNGs. Keep the
adaptive icon background and foreground compatible with the existing Android resource structure.

Create a separate deterministic white monochrome vector drawable for Notification `smallIcon`.
Android masks small icons, so the full-color launcher bitmap must not be used as the status-bar
icon.

## 12. Files to create

```text
atlasapp/src/main/java/com/hry/camera/usbcamerademo/
  AtlasReminderPreferences.java
  AtlasResurfacingSelector.java
  AtlasLocationClusterer.java
  AtlasDailyReminderScheduler.java
  AtlasDailyReminderReceiver.java
  AtlasLocationReminderRegistrar.java
  AtlasLocationReminderReceiver.java
  AtlasResurfacingSystemReceiver.java
  AtlasResurfacingManager.java
  AtlasNotificationHelper.java

atlasapp/src/main/res/drawable/
  ic_atlas_notification.xml

atlasapp/src/test/java/com/hry/camera/usbcamerademo/
  AtlasResurfacingSelectorTest.java
  AtlasLocationClustererTest.java
  AtlasReminderScheduleTest.java
  AtlasLocationReminderPolicyTest.java
```

## 13. Files to modify

```text
atlasapp/src/main/AndroidManifest.xml
atlasapp/src/main/java/com/hry/camera/usbcamerademo/AppConfig.java
atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasApplication.java
atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasReviewRepository.java
atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventDetailActivity.java
atlasapp/src/main/java/com/hry/camera/usbcamerademo/EventSupplementActivity.java
atlasapp/src/main/java/com/hry/camera/usbcamerademo/MainActivity.java
atlasapp/src/main/java/com/hry/camera/usbcamerademo/MeActivity.java
atlasapp/src/main/java/com/hry/camera/usbcamerademo/ReviewShellActivity.java
atlasapp/src/main/java/com/hry/camera/usbcamerademo/AtlasMapHtmlBuilder.java
atlasapp/src/main/res/layout/activity_event_detail.xml
atlasapp/src/main/res/layout/activity_me.xml
atlasapp/src/main/res/values/strings.xml
atlasapp/src/main/res/values-zh/strings.xml
atlasapp/src/main/res/mipmap-*/ic_launcher.png
atlasapp/src/main/res/mipmap-*/ic_launcher_round.png
atlasapp/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
atlasapp/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
```

## 14. Error handling and robustness rules

- Every receiver uses explicit component intents.
- Repository scans run off the broadcast main thread with `goAsync`.
- Every receiver catches top-level exceptions, writes `AtlasDevLogger` entries, and always calls
  `PendingResult.finish`.
- The next daily alarm is scheduled before current-day processing.
- Daily category markers are written only after their corresponding notification posts.
- Special location/date markers and the global cooldown timestamp are written only after the
  notification posts.
- Receiver execution rechecks switches, eligibility, age, and permissions so stale alarms or
  proximity registrations cannot send an invalid reminder.
- Rebuilding proximity alerts removes all request codes from the prior persisted registration
  set before adding the new set.
- PendingIntent request codes and notification IDs distinguish Short, Long, Special places, alarm,
  and retry operations.
- Deletion refreshes proximity registrations so a deleted event cannot remain a location trigger.
- A malformed event JSON is skipped and logged; it cannot prevent other valid events from being
  considered.

## 15. Test strategy

### Unit tests

`AtlasResurfacingSelectorTest` covers:

- rejects events without `save_push`;
- uses exact local calendar-day windows;
- prefers a supplemented event over an unsupplemented event with more media;
- uses media count within the same supplementation tier;
- uses preferred-time distance and Event ID only as tie-breakers;
- returns `null` for an empty eligible category;
- counts media without double-counting text/social supplementation.

`AtlasLocationClustererTest` covers:

- Haversine matching inside and outside 50 metres;
- clusters overlapping historical points into one location;
- produces stable cluster keys;
- ignores missing GPS and non-`save_push` events;
- assigns collision-free request codes.

`AtlasReminderScheduleTest` covers:

- before 19:30 schedules today;
- at/after 19:30 schedules tomorrow;
- local-day boundaries remain correct across timezone and daylight-saving changes;
- yesterday and seven-day windows select the intended calendar dates.

`AtlasLocationReminderPolicyTest` covers:

- rejects an event younger than six hours;
- allows an event exactly six hours old;
- suppresses the same cluster twice on one date;
- allows the same cluster on the next date;
- suppresses a different cluster inside the two-hour global cooldown;
- allows a different cluster exactly two hours later.

### Build and regression verification

Run:

```sh
./gradlew :atlasapp:testDebugUnitTest --console=plain
./gradlew :atlasapp:assembleDebug --console=plain
```

### Device verification

Use a physical device because AlarmManager, notification permission, USB camera, and proximity
alerts cannot be validated by plain JVM tests.

Acceptance scenarios:

1. Both switches are on after a clean install.
2. Turning off daily reminders cancels only the daily alarm.
3. Turning off same-place reminders removes only proximity alerts.
4. One eligible yesterday event produces one Short notification.
5. One eligible seven-day event produces one Long notification.
6. Both candidates produce two simultaneously visible notifications.
7. Missing Short or Long candidates produce no placeholder.
8. Short and Long taps force their corresponding detail modes.
9. Entering a matching place posts generic place copy and centers the Map tab.
10. Re-entering that place on the same day does not post again.
11. Entering a different place inside two hours does not post.
12. A moment younger than six hours cannot back a Special notification.
13. Reboot restores daily scheduling and location registrations.
14. Deleting an event removes it from all Review views and future notification candidates.
15. Denying location or notification permission does not crash the app and is reflected in Me.

## 16. Out of scope

- Remote/Firebase push delivery.
- Cloud synchronization of notification preferences or delivery history.
- A notification inbox inside the app.
- Editing the original event’s `save_push`/`save_no_push` decision after the post-recording choice.
- Replacing AMap or the existing GPS resolver.
- Migrating the project to AndroidX, Compose, WorkManager, or a database.
