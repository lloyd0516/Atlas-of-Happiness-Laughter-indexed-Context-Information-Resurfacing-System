package com.hry.camera.usbcamerademo;

import org.json.JSONObject;

/** Pure helpers for notification research metadata and response timing. */
final class ResearchNotificationData {
    private ResearchNotificationData() {
    }

    static long responseDelay(long postedAtMs, long responseAtMs) {
        return Math.max(0L, responseAtMs - postedAtMs);
    }

    static String actionKey(String instanceId, String action) {
        return String.valueOf(instanceId)
                + ":" + String.valueOf(action);
    }

    static JSONObject properties(
            String type,
            int androidNotificationId,
            long postedAtMs,
            String destination,
            String momentId,
            String anonymousClusterId
    ) {
        JSONObject properties =
                ResearchInteractionLogger.properties(
                        "notification_type", type,
                        "android_notification_id",
                        androidNotificationId,
                        "posted_timestamp_ms", postedAtMs,
                        "destination", destination);
        if (anonymousClusterId != null
                && anonymousClusterId.length() > 0) {
            try {
                properties.put(
                        "anonymous_cluster_id",
                        anonymousClusterId);
            } catch (Exception ignored) {
            }
        }
        return properties;
    }
}
