package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONObject;

/** Derives the user-visible laughter count without exposing period bookkeeping. */
final class AtlasLaughterCountPolicy {
    private AtlasLaughterCountPolicy() {
    }

    static int count(JSONObject event) {
        JSONObject auto = event != null
                ? event.optJSONObject("auto_captured")
                : null;
        JSONArray audioClips = auto != null
                ? auto.optJSONArray("audio_clips")
                : null;
        int laughterCount = 0;
        if (audioClips != null) {
            for (int i = 0; i < audioClips.length(); i++) {
                JSONObject clip = audioClips.optJSONObject(i);
                if (clip != null
                        && "laughter".equals(
                        clip.optString("type", ""))) {
                    laughterCount += 1;
                }
            }
        }
        if (laughterCount > 0) {
            return laughterCount;
        }

        JSONArray periodIds = event != null
                ? event.optJSONArray("period_ids")
                : null;
        return periodIds != null ? periodIds.length() : 0;
    }
}
