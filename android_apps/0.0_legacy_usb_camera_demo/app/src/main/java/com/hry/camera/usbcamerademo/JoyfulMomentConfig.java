package com.hry.camera.usbcamerademo;

import org.json.JSONException;
import org.json.JSONObject;

public class JoyfulMomentConfig {
    public static final String LEVEL_FREQUENT = "frequent";
    public static final String LEVEL_MEDIUM = "medium";
    public static final String LEVEL_SPARSE = "sparse";

    public String detectionLevel = LEVEL_MEDIUM;
    public int chunkMs = 200;
    public int clipDurationSec = 30;
    public int contextNeighborClips = 2;
    public int eventWindowSec = 600;
    public int triggerVideoDurationSec = 5;
    public int triggerPhotoCount = 2;
    public String speechmaticsLanguage = "en";
    public String speechmaticsEventTypes = "laughter";

    public static JoyfulMomentConfig preset(String level) {
        JoyfulMomentConfig config = new JoyfulMomentConfig();
        config.detectionLevel = level;
        if (LEVEL_FREQUENT.equals(level)) {
            config.clipDurationSec = 20;
            config.contextNeighborClips = 3;
            config.eventWindowSec = 480;
        } else if (LEVEL_SPARSE.equals(level)) {
            config.clipDurationSec = 45;
            config.contextNeighborClips = 1;
            config.eventWindowSec = 900;
        }
        return config;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("detectionLevel", detectionLevel);
        json.put("chunkMs", chunkMs);
        json.put("clipDurationSec", clipDurationSec);
        json.put("contextNeighborClips", contextNeighborClips);
        json.put("eventWindowSec", eventWindowSec);
        json.put("triggerVideoDurationSec", triggerVideoDurationSec);
        json.put("triggerPhotoCount", triggerPhotoCount);
        json.put("speechmaticsLanguage", speechmaticsLanguage);
        json.put("speechmaticsEventTypes", speechmaticsEventTypes);
        return json;
    }

    public static JoyfulMomentConfig fromJson(JSONObject json) {
        JoyfulMomentConfig config = new JoyfulMomentConfig();
        if (json == null) {
            return config;
        }
        config.detectionLevel = json.optString("detectionLevel", config.detectionLevel);
        config.chunkMs = json.optInt("chunkMs", config.chunkMs);
        config.clipDurationSec = json.optInt("clipDurationSec", config.clipDurationSec);
        config.contextNeighborClips = json.optInt("contextNeighborClips", config.contextNeighborClips);
        config.eventWindowSec = json.optInt("eventWindowSec", config.eventWindowSec);
        config.triggerVideoDurationSec = json.optInt("triggerVideoDurationSec", config.triggerVideoDurationSec);
        config.triggerPhotoCount = json.optInt("triggerPhotoCount", config.triggerPhotoCount);
        config.speechmaticsLanguage = json.optString("speechmaticsLanguage", config.speechmaticsLanguage);
        config.speechmaticsEventTypes = json.optString("speechmaticsEventTypes", config.speechmaticsEventTypes);
        return config;
    }

    public String toSummaryText() {
        return "level=" + detectionLevel
                + " clip=" + clipDurationSec + "s"
                + " neighbor=" + contextNeighborClips
                + " event=" + eventWindowSec + "s";
    }
}
