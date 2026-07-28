package com.hry.camera.usbcamerademo;

import org.json.JSONException;
import org.json.JSONObject;

/** Pure builder for one schema-v1 research JSONL row. */
final class ResearchLogRecord {
    private ResearchLogRecord() {
    }

    static JSONObject build(
            String eventName,
            String eventId,
            long timestampMs,
            String timestampLocal,
            String timezoneId,
            long elapsedRealtimeMs,
            String participantId,
            String sessionId,
            String momentId,
            String notificationInstanceId,
            String appVersionName,
            int appVersionCode,
            String deviceModel,
            JSONObject properties
    ) throws JSONException {
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
                .put("notification_instance_id",
                        nullable(notificationInstanceId))
                .put("app_version_name", appVersionName)
                .put("app_version_code", appVersionCode)
                .put("device_model", deviceModel)
                .put("properties",
                        properties == null ? new JSONObject() : properties);
    }

    private static Object nullable(String value) {
        return value == null || value.length() == 0 ? JSONObject.NULL : value;
    }
}
