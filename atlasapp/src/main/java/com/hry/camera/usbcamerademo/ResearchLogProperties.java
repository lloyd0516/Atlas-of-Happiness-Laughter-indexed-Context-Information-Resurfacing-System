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
