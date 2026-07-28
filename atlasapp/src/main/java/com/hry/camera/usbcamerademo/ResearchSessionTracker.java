package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists the active capture session so abnormal process exits remain observable. */
final class ResearchSessionTracker {
    private static final String PREF_NAME = "atlas_research_session";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_PARTICIPANT_ID = "participant_id";
    private static final String KEY_START_WALL_MS = "start_wall_ms";
    private static final String KEY_START_ELAPSED_MS = "start_elapsed_ms";

    private ResearchSessionTracker() {
    }

    static synchronized void start(
            Context context,
            String participantId,
            String sessionId,
            long startWallMs,
            long startElapsedMs
    ) {
        if (context == null || isEmpty(sessionId)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = preferences(appContext);
        if (preferences.getBoolean(KEY_ACTIVE, false)) {
            recoverInterrupted(appContext, startWallMs, startElapsedMs);
            if (preferences.getBoolean(KEY_ACTIVE, false)) {
                AtlasDevLogger.w(
                        appContext,
                        "ResearchSession",
                        "Previous active session could not be recovered.");
                return;
            }
        }

        boolean persisted = preferences.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putString(KEY_SESSION_ID, sessionId)
                .putString(KEY_PARTICIPANT_ID, participantId)
                .putLong(KEY_START_WALL_MS, startWallMs)
                .putLong(KEY_START_ELAPSED_MS, startElapsedMs)
                .commit();
        if (!persisted) {
            AtlasDevLogger.w(
                    appContext,
                    "ResearchSession",
                    "Could not persist capture session start.");
        }
        ResearchInteractionLogger.log(
                appContext,
                ResearchEventNames.SESSION_STARTED,
                sessionId,
                null,
                null,
                ResearchInteractionLogger.properties(
                        "start_timestamp_ms", startWallMs,
                        "duration_estimated", false));
    }

    static synchronized void stop(
            Context context,
            String sessionId,
            String stopReason,
            int detectionCount,
            int momentCount,
            long startElapsedMs,
            long stopElapsedMs
    ) {
        if (context == null || isEmpty(sessionId)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = preferences(appContext);
        String activeSessionId = preferences.getString(KEY_SESSION_ID, null);
        long persistedStartElapsed = preferences.getLong(
                KEY_START_ELAPSED_MS, startElapsedMs);
        long effectiveStartElapsed = sessionId.equals(activeSessionId)
                ? persistedStartElapsed : startElapsedMs;
        long durationMs = ResearchSessionTiming.normalDuration(
                effectiveStartElapsed, stopElapsedMs);

        boolean logged = ResearchInteractionLogger.log(
                appContext,
                ResearchEventNames.SESSION_STOPPED,
                sessionId,
                null,
                null,
                ResearchInteractionLogger.properties(
                        "duration_ms", durationMs,
                        "stop_reason",
                        isEmpty(stopReason) ? "unspecified" : stopReason,
                        "detection_count", Math.max(0, detectionCount),
                        "moment_count", Math.max(0, momentCount),
                        "duration_estimated", false));
        if (logged && sessionId.equals(activeSessionId)) {
            clear(preferences);
        }
    }

    static synchronized void recoverInterrupted(
            Context context,
            long nowWallMs,
            long nowElapsedMs
    ) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences preferences = preferences(appContext);
        if (!preferences.getBoolean(KEY_ACTIVE, false)) {
            return;
        }
        String sessionId = preferences.getString(KEY_SESSION_ID, null);
        long startWallMs = preferences.getLong(KEY_START_WALL_MS, nowWallMs);
        if (isEmpty(sessionId)) {
            clear(preferences);
            return;
        }
        long durationMs = ResearchSessionTiming.estimatedWallDuration(
                startWallMs, nowWallMs);
        boolean logged = ResearchInteractionLogger.log(
                appContext,
                ResearchEventNames.SESSION_INTERRUPTED,
                sessionId,
                null,
                null,
                ResearchInteractionLogger.properties(
                        "start_timestamp_ms", startWallMs,
                        "recovered_timestamp_ms", nowWallMs,
                        "duration_ms", durationMs,
                        "duration_estimated", true,
                        "stop_reason", "process_interrupted"));
        if (logged) {
            clear(preferences);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static void clear(SharedPreferences preferences) {
        preferences.edit().clear().commit();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }
}
