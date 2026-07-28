package com.hry.camera.usbcamerademo;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Connects one posted notification to its open or dismiss response. */
final class ResearchNotificationTracker {
    private static final String PREF_ACTIONS =
            "atlas_research_notification_actions";
    private static final String EXTRA_PREFIX =
            "research_notification_";
    private static final String EXTRA_INSTANCE_ID =
            EXTRA_PREFIX + "instance_id";
    private static final String EXTRA_TYPE =
            EXTRA_PREFIX + "type";
    private static final String EXTRA_ANDROID_ID =
            EXTRA_PREFIX + "android_id";
    private static final String EXTRA_POSTED_AT =
            EXTRA_PREFIX + "posted_at_ms";
    private static final String EXTRA_DESTINATION =
            EXTRA_PREFIX + "destination";
    private static final String EXTRA_SESSION_ID =
            EXTRA_PREFIX + "session_id";
    private static final String EXTRA_MOMENT_ID =
            EXTRA_PREFIX + "moment_id";
    private static final String EXTRA_CLUSTER_ID =
            EXTRA_PREFIX + "anonymous_cluster_id";
    private static final Object ACTION_LOCK = new Object();

    static final class Metadata {
        final String instanceId;
        final String type;
        final int androidNotificationId;
        final long postedAtMs;
        final String destination;
        final String sessionId;
        final String momentId;
        final String anonymousClusterId;

        Metadata(
                String instanceId,
                String type,
                int androidNotificationId,
                long postedAtMs,
                String destination,
                String sessionId,
                String momentId,
                String anonymousClusterId
        ) {
            this.instanceId = instanceId;
            this.type = type;
            this.androidNotificationId =
                    androidNotificationId;
            this.postedAtMs = postedAtMs;
            this.destination = destination;
            this.sessionId = sessionId;
            this.momentId = momentId;
            this.anonymousClusterId =
                    anonymousClusterId;
        }
    }

    private ResearchNotificationTracker() {
    }

    static Intent attach(Intent intent, Metadata metadata) {
        if (intent == null || metadata == null) {
            return intent;
        }
        intent.putExtra(
                EXTRA_INSTANCE_ID, metadata.instanceId);
        intent.putExtra(EXTRA_TYPE, metadata.type);
        intent.putExtra(
                EXTRA_ANDROID_ID,
                metadata.androidNotificationId);
        intent.putExtra(
                EXTRA_POSTED_AT, metadata.postedAtMs);
        intent.putExtra(
                EXTRA_DESTINATION, metadata.destination);
        intent.putExtra(
                EXTRA_SESSION_ID, metadata.sessionId);
        intent.putExtra(
                EXTRA_MOMENT_ID, metadata.momentId);
        intent.putExtra(
                EXTRA_CLUSTER_ID,
                metadata.anonymousClusterId);
        return intent;
    }

    static PendingIntent dismissIntent(
            Context context,
            Metadata metadata
    ) {
        Intent intent = attach(
                new Intent(
                        context,
                        AtlasNotificationDismissReceiver.class),
                metadata);
        return PendingIntent.getBroadcast(
                context,
                requestCode(metadata.instanceId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean logOpened(Context context, Intent intent) {
        return logResponse(
                context,
                intent,
                "opened",
                ResearchEventNames.NOTIFICATION_OPENED);
    }

    static boolean logDismissed(
            Context context,
            Intent intent
    ) {
        return logResponse(
                context,
                intent,
                "dismissed",
                ResearchEventNames.NOTIFICATION_DISMISSED);
    }

    static void logPosted(
            Context context,
            Metadata metadata
    ) {
        ResearchInteractionLogger.log(
                context,
                ResearchEventNames.NOTIFICATION_POSTED,
                metadata.sessionId,
                metadata.momentId,
                metadata.instanceId,
                properties(metadata));
    }

    static void logPostFailed(
            Context context,
            Metadata metadata,
            String failureReason
    ) {
        JSONObject properties = properties(metadata);
        try {
            properties.put(
                    "failure_reason", failureReason);
        } catch (Exception ignored) {
        }
        ResearchInteractionLogger.log(
                context,
                ResearchEventNames.NOTIFICATION_POST_FAILED,
                metadata.sessionId,
                metadata.momentId,
                metadata.instanceId,
                properties);
    }

    static void logSkipped(
            Context context,
            String notificationType,
            String reason
    ) {
        ResearchInteractionLogger.log(
                context,
                ResearchEventNames.NOTIFICATION_SKIPPED,
                null,
                null,
                null,
                ResearchInteractionLogger.properties(
                        "notification_type",
                        notificationType,
                        "reason", reason));
    }

    static int requestCode(String instanceId) {
        return instanceId == null
                ? 0
                : instanceId.hashCode() & 0x7fffffff;
    }

    private static boolean logResponse(
            Context context,
            Intent intent,
            String action,
            String eventName
    ) {
        Metadata metadata = fromIntent(intent);
        if (context == null
                || metadata == null
                || metadata.instanceId == null
                || metadata.instanceId.length() == 0) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        String actionKey =
                ResearchNotificationData.actionKey(
                        metadata.instanceId, action);
        SharedPreferences preferences =
                appContext.getSharedPreferences(
                        PREF_ACTIONS, Context.MODE_PRIVATE);
        synchronized (ACTION_LOCK) {
            if (preferences.getBoolean(
                    actionKey, false)) {
                return false;
            }
            if (!preferences.edit()
                    .putBoolean(actionKey, true)
                    .commit()) {
                return false;
            }
        }

        JSONObject properties = properties(metadata);
        try {
            properties.put(
                    "response_delay_ms",
                    ResearchNotificationData.responseDelay(
                            metadata.postedAtMs,
                            System.currentTimeMillis()));
        } catch (Exception ignored) {
        }
        boolean logged = ResearchInteractionLogger.log(
                appContext,
                eventName,
                metadata.sessionId,
                metadata.momentId,
                metadata.instanceId,
                properties);
        if (!logged) {
            synchronized (ACTION_LOCK) {
                preferences.edit()
                        .remove(actionKey)
                        .commit();
            }
        }
        return logged;
    }

    private static Metadata fromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        return new Metadata(
                intent.getStringExtra(EXTRA_INSTANCE_ID),
                intent.getStringExtra(EXTRA_TYPE),
                intent.getIntExtra(EXTRA_ANDROID_ID, 0),
                intent.getLongExtra(EXTRA_POSTED_AT, 0L),
                intent.getStringExtra(EXTRA_DESTINATION),
                intent.getStringExtra(EXTRA_SESSION_ID),
                intent.getStringExtra(EXTRA_MOMENT_ID),
                intent.getStringExtra(EXTRA_CLUSTER_ID));
    }

    private static JSONObject properties(Metadata metadata) {
        return ResearchNotificationData.properties(
                metadata.type,
                metadata.androidNotificationId,
                metadata.postedAtMs,
                metadata.destination,
                metadata.momentId,
                metadata.anonymousClusterId);
    }
}
