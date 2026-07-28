package com.hry.camera.usbcamerademo;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Converts visible Activity intervals into analysis-ready screen visits. */
final class ResearchScreenTracker {
    private static final Map<Activity, ScreenState> ACTIVE =
            new WeakHashMap<>();

    private ResearchScreenTracker() {
    }

    static synchronized void onResumed(Activity activity) {
        if (activity == null || ACTIVE.containsKey(activity)) {
            return;
        }
        Intent intent = activity.getIntent();
        String sessionId = firstNonEmpty(
                stringExtra(intent, "session_id"),
                stringExtra(intent, "research_session_id"));
        String momentId = firstNonEmpty(
                stringExtra(intent, "event_id"),
                stringExtra(intent, "research_moment_id"));
        String notificationInstanceId = stringExtra(
                intent, "research_notification_instance_id");
        String visitId = "visit_" + UUID.randomUUID().toString();
        ResearchVisitTimer timer = new ResearchVisitTimer();
        timer.start(SystemClock.elapsedRealtime());
        ScreenState state = new ScreenState(
                visitId,
                screenName(activity),
                sessionId,
                momentId,
                notificationInstanceId,
                timer);
        ACTIVE.put(activity, state);
        ResearchInteractionLogger.log(
                activity,
                ResearchEventNames.SCREEN_OPENED,
                sessionId,
                momentId,
                notificationInstanceId,
                ResearchInteractionLogger.properties(
                        "screen_name", state.screenName,
                        "entry_source", ResearchNavigation.source(
                                intent, "app_navigation"),
                        "visit_id", visitId));
    }

    static synchronized void onPaused(Activity activity) {
        if (activity == null) {
            return;
        }
        ScreenState state = ACTIVE.remove(activity);
        if (state == null) {
            return;
        }
        long durationMs = state.timer.pause(SystemClock.elapsedRealtime());
        ResearchInteractionLogger.log(
                activity,
                ResearchEventNames.SCREEN_CLOSED,
                state.sessionId,
                state.momentId,
                state.notificationInstanceId,
                ResearchInteractionLogger.properties(
                        "screen_name", state.screenName,
                        "visit_id", state.visitId,
                        "visible_duration_ms", durationMs));
    }

    private static String screenName(Activity activity) {
        if (activity instanceof MainActivity) {
            return "record";
        }
        if (activity instanceof ReviewShellActivity) {
            return "review";
        }
        if (activity instanceof MeActivity) {
            return "me";
        }
        if (activity instanceof SupplementPickerActivity) {
            return "supplement_picker";
        }
        if (activity instanceof EventSupplementActivity) {
            return "event_supplement";
        }
        if (activity instanceof EventDetailActivity) {
            return "moment_detail";
        }
        if (activity instanceof FullscreenPhotoActivity) {
            return "photo_viewer";
        }
        if (activity instanceof VideoPlayerActivity) {
            return "video_player";
        }
        if (activity instanceof MapReviewActivity) {
            return "legacy_map";
        }
        if (activity instanceof SettingsActivity) {
            return "developer_settings";
        }
        if (activity instanceof LogViewerActivity) {
            return "developer_logs";
        }
        return activity.getClass().getSimpleName();
    }

    private static String stringExtra(Intent intent, String key) {
        return intent == null ? null : intent.getStringExtra(key);
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && first.length() > 0 ? first : second;
    }

    private static final class ScreenState {
        final String visitId;
        final String screenName;
        final String sessionId;
        final String momentId;
        final String notificationInstanceId;
        final ResearchVisitTimer timer;

        ScreenState(
                String visitId,
                String screenName,
                String sessionId,
                String momentId,
                String notificationInstanceId,
                ResearchVisitTimer timer
        ) {
            this.visitId = visitId;
            this.screenName = screenName;
            this.sessionId = sessionId;
            this.momentId = momentId;
            this.notificationInstanceId = notificationInstanceId;
            this.timer = timer;
        }
    }
}
