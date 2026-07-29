package com.hry.camera.usbcamerademo;

import org.json.JSONException;
import org.json.JSONObject;

/** Copies complete optional capture-bundle metadata between JSON formats. */
final class AtlasCaptureBundleMetadata {
    private AtlasCaptureBundleMetadata() {
    }

    static void copyIfPresent(
            JSONObject source,
            JSONObject target) throws JSONException {
        if (source == null || target == null) {
            return;
        }
        String bundleId = source.optString("bundle_id", "");
        long triggerTimeMs =
                source.optLong("bundle_trigger_time_ms", -1L);
        int mediaIndex =
                source.optInt("bundle_media_index", -1);
        if (bundleId.length() == 0
                || triggerTimeMs <= 0L
                || mediaIndex < 0) {
            return;
        }
        target.put("bundle_id", bundleId);
        target.put(
                "bundle_trigger_time_ms",
                triggerTimeMs);
        target.put("bundle_media_index", mediaIndex);
    }
}
