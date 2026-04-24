package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

public class JoyfulMomentConfig {
    public static final String LEVEL_FREQUENT = "frequent";
    public static final String LEVEL_MEDIUM = "medium";
    public static final String LEVEL_SPARSE = "sparse";
    public static final String LEVEL_CUSTOM = "custom";

    public static final String PREF_NAME = "joyful_moment_prefs";
    public static final String PREF_CONFIG_JSON = "config_json";
    public static final String PREF_APP_LANGUAGE = "app_language";
    public static final String PREF_CAMERA_BRIGHTNESS = "camera_brightness";
    public static final String PREF_SPEECHMATICS_API_KEY = "speechmatics_api_key";
    public static final String PREF_SPEECHMATICS_RT_URL = "speechmatics_rt_url";
    public static final String PREF_GOOGLE_WEATHER_API_KEY = "google_weather_api_key";

    public String detectionLevel = LEVEL_MEDIUM;
    public int chunkMs = 200;
    public int clipDurationSec = 30;
    public int contextNeighborClips = 2;
    public int eventWindowSec = 600;
    public int triggerVideoDurationSec = 5;
    public int triggerPhotoCount = 2;
    public String speechmaticsLanguage = "en";
    public String speechmaticsOperatingPoint = "enhanced";
    public Integer speechmaticsMaxDelaySec = null;
    public String outputRoot = "run_logs/joyful_moment";
    public String speechmaticsEventTypes = "laughter";

    public static JoyfulMomentConfig preset(String level) {
        JoyfulMomentConfig config = new JoyfulMomentConfig();
        config.detectionLevel = level;
        if (LEVEL_FREQUENT.equals(level)) {
            config.chunkMs = 150;
            config.clipDurationSec = 20;
            config.contextNeighborClips = 3;
            config.eventWindowSec = 480;
            config.triggerVideoDurationSec = 6;
            config.triggerPhotoCount = 3;
        } else if (LEVEL_SPARSE.equals(level)) {
            config.chunkMs = 250;
            config.clipDurationSec = 45;
            config.contextNeighborClips = 1;
            config.eventWindowSec = 900;
            config.triggerVideoDurationSec = 4;
            config.triggerPhotoCount = 1;
        } else if (LEVEL_CUSTOM.equals(level)) {
            config.detectionLevel = LEVEL_CUSTOM;
        }
        return config;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("chunk_ms", chunkMs);
        json.put("clip_duration_s", clipDurationSec);
        json.put("context_neighbor_clips", contextNeighborClips);
        json.put("event_window_s", eventWindowSec);
        json.put("trigger_video_duration_s", triggerVideoDurationSec);
        json.put("trigger_photo_count", triggerPhotoCount);
        json.put("detection_level", detectionLevel);
        json.put("speechmatics_language", speechmaticsLanguage);
        json.put("speechmatics_operating_point", speechmaticsOperatingPoint);
        if (speechmaticsMaxDelaySec == null || speechmaticsMaxDelaySec <= 0) {
            json.put("speechmatics_max_delay_s", JSONObject.NULL);
        } else {
            json.put("speechmatics_max_delay_s", speechmaticsMaxDelaySec);
        }
        json.put("output_root", outputRoot);
        json.put("speechmatics_event_types", speechmaticsEventTypes);
        return json;
    }

    public static JoyfulMomentConfig fromJson(JSONObject json) {
        JoyfulMomentConfig config = new JoyfulMomentConfig();
        if (json == null) {
            return config;
        }
        config.detectionLevel = optStringAny(json, config.detectionLevel, "detection_level", "detectionLevel");
        config.chunkMs = optIntAny(json, config.chunkMs, "chunk_ms", "chunkMs");
        config.clipDurationSec = optIntAny(json, config.clipDurationSec, "clip_duration_s", "clipDurationSec");
        config.contextNeighborClips = optIntAny(json, config.contextNeighborClips, "context_neighbor_clips", "contextNeighborClips");
        config.eventWindowSec = optIntAny(json, config.eventWindowSec, "event_window_s", "eventWindowSec");
        config.triggerVideoDurationSec = optIntAny(json, config.triggerVideoDurationSec, "trigger_video_duration_s", "triggerVideoDurationSec");
        config.triggerPhotoCount = optIntAny(json, config.triggerPhotoCount, "trigger_photo_count", "triggerPhotoCount");
        config.speechmaticsLanguage = optStringAny(json, config.speechmaticsLanguage, "speechmatics_language", "speechmaticsLanguage");
        config.speechmaticsOperatingPoint = optStringAny(json, config.speechmaticsOperatingPoint, "speechmatics_operating_point", "speechmaticsOperatingPoint");
        config.outputRoot = optStringAny(json, config.outputRoot, "output_root", "outputRoot");
        config.speechmaticsEventTypes = optStringAny(json, config.speechmaticsEventTypes, "speechmatics_event_types", "speechmaticsEventTypes");
        if (json.has("speechmatics_max_delay_s") && !json.isNull("speechmatics_max_delay_s")) {
            config.speechmaticsMaxDelaySec = json.optInt("speechmatics_max_delay_s", 0);
            if (config.speechmaticsMaxDelaySec != null && config.speechmaticsMaxDelaySec <= 0) {
                config.speechmaticsMaxDelaySec = null;
            }
        } else if (json.has("speechmaticsMaxDelaySec")) {
            int legacy = json.optInt("speechmaticsMaxDelaySec", 0);
            config.speechmaticsMaxDelaySec = legacy > 0 ? legacy : null;
        }
        return config;
    }

    private static int optIntAny(JSONObject json, int fallback, String... keys) {
        for (String key : keys) {
            if (json.has(key) && !json.isNull(key)) {
                return json.optInt(key, fallback);
            }
        }
        return fallback;
    }

    private static String optStringAny(JSONObject json, String fallback, String... keys) {
        for (String key : keys) {
            if (json.has(key) && !json.isNull(key)) {
                String value = json.optString(key, fallback);
                if (value != null && value.length() > 0) {
                    return value;
                }
            }
        }
        return fallback;
    }

    public String toSummaryText() {
        return "level=" + detectionLevel
                + " clip=" + clipDurationSec + "s"
                + " neighbor=" + contextNeighborClips
                + " event=" + eventWindowSec + "s";
    }

    public static JoyfulMomentConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String configJson = prefs.getString(PREF_CONFIG_JSON, null);
        if (configJson == null) {
            return JoyfulMomentConfig.preset(JoyfulMomentConfig.LEVEL_MEDIUM);
        }
        try {
            return JoyfulMomentConfig.fromJson(new JSONObject(configJson));
        } catch (JSONException ignored) {
            return JoyfulMomentConfig.preset(JoyfulMomentConfig.LEVEL_MEDIUM);
        }
    }

    public static void save(Context context, JoyfulMomentConfig config) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        try {
            editor.putString(PREF_CONFIG_JSON, config.toJson().toString());
            editor.apply();
            mirrorConfigToExternalFile(context, config);
        } catch (JSONException ignored) {
        }
    }

    public static File mirrorConfigToExternalFile(Context context, JoyfulMomentConfig config) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, "joyful_moment");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, "config.json");
        try {
            FileOutputStream outputStream = new FileOutputStream(file, false);
            outputStream.write(config.toJson().toString(2).getBytes(Charset.forName("UTF-8")));
            outputStream.close();
        } catch (Exception ignored) {
        }
        return file;
    }

    public static String getSpeechmaticsApiKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(PREF_SPEECHMATICS_API_KEY, "");
        return saved != null && saved.trim().length() > 0 ? saved.trim() : BuildConfig.SPEECHMATICS_API_KEY;
    }

    public static void saveSpeechmaticsApiKey(Context context, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_SPEECHMATICS_API_KEY, value == null ? "" : value.trim()).apply();
    }

    public static String getSpeechmaticsRtUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(PREF_SPEECHMATICS_RT_URL, "");
        return saved != null && saved.trim().length() > 0 ? saved.trim() : BuildConfig.SPEECHMATICS_RT_URL;
    }

    public static void saveSpeechmaticsRtUrl(Context context, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_SPEECHMATICS_RT_URL, value == null ? "" : value.trim()).apply();
    }

    public static String getGoogleWeatherApiKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(PREF_GOOGLE_WEATHER_API_KEY, "");
        return saved != null && saved.trim().length() > 0 ? saved.trim() : BuildConfig.GOOGLE_WEATHER_API_KEY;
    }

    public static void saveGoogleWeatherApiKey(Context context, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_GOOGLE_WEATHER_API_KEY, value == null ? "" : value.trim()).apply();
    }
}
