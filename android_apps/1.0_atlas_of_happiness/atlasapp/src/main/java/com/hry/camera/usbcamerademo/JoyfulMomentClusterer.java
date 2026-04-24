package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class JoyfulMomentClusterer {
    public static class DetectionRecord {
        public String detId;
        public long deviceTimeMs;
        public double startSec;
        public double endSec;
        public double confidence;
        public String channel;

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("type", "detection.layer");
            json.put("det_id", detId);
            json.put("device_time_ms", deviceTimeMs);
            json.put("start_sec", startSec);
            json.put("end_sec", endSec);
            json.put("duration_sec", Math.max(0.0, endSec - startSec));
            json.put("confidence", confidence);
            json.put("channel", channel);
            return json;
        }
    }

    public static class PeriodRecord {
        public String periodId;
        public int clipId;
        public long deviceStartMs;
        public long deviceEndMs;
        public String label;
        public boolean hasSpeech;
        public boolean hasLaughter;
        public String savedPath;
        public String parentEventId;
        public boolean triggerPrompt;
        public boolean triggerAutoVideo;
        public int triggerAutoPhotoCount;
        public String videoPath;
        public final List<String> photoPaths = new ArrayList<>();
        public final List<String> detectionIds = new ArrayList<>();
        public final List<Integer> relatedLaughterClipIds = new ArrayList<>();

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("type", "period.layer");
            json.put("period_id", periodId);
            json.put("clip_id", clipId);
            json.put("device_start_ms", deviceStartMs);
            json.put("device_end_ms", deviceEndMs);
            json.put("label", label);
            json.put("has_speech", hasSpeech);
            json.put("has_laughter", hasLaughter);
            json.put("saved_path", savedPath);
            json.put("parent_event_id", parentEventId);
            json.put("detection_ids", new JSONArray(detectionIds));
            json.put("related_laughter_clip_ids", new JSONArray(relatedLaughterClipIds));

            JSONObject trigger = new JSONObject();
            trigger.put("prompt_user_note", triggerPrompt);
            trigger.put("auto_video_capture", triggerAutoVideo);
            trigger.put("auto_photo_count", triggerAutoPhotoCount);
            json.put("trigger", trigger);

            JSONObject assets = new JSONObject();
            assets.put("video", videoPath == null ? JSONObject.NULL : videoPath);
            assets.put("photos", new JSONArray(photoPaths));
            json.put("assets", assets);
            return json;
        }
    }

    public static class EventRecord {
        public String eventId;
        public int eventBucketId;
        public long deviceStartMs;
        public long deviceEndMs;
        public final List<String> periodIds = new ArrayList<>();
        public final List<String> laughterPeriodIds = new ArrayList<>();
        public final List<String> contextPeriodIds = new ArrayList<>();
        public final List<Integer> contextClipIds = new ArrayList<>();
        public final List<String> detectionIds = new ArrayList<>();
        public final List<String> savedClipPaths = new ArrayList<>();
        public String videoPath;
        public String videoContentUri;
        public final List<String> photoPaths = new ArrayList<>();

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("type", "event.layer");
            json.put("event_id", eventId);
            json.put("event_bucket_id", eventBucketId);
            json.put("device_start_ms", deviceStartMs);
            json.put("device_end_ms", deviceEndMs);
            json.put("period_ids", new JSONArray(periodIds));
            json.put("laughter_period_ids", new JSONArray(laughterPeriodIds));
            json.put("context_period_ids", new JSONArray(contextPeriodIds));
            json.put("context_clip_ids", new JSONArray(contextClipIds));
            json.put("detection_ids", new JSONArray(detectionIds));
            json.put("saved_clip_paths", new JSONArray(savedClipPaths));

            JSONObject assets = new JSONObject();
            assets.put("video", videoPath == null ? JSONObject.NULL : videoPath);
            assets.put("video_content_uri", videoContentUri == null ? JSONObject.NULL : videoContentUri);
            assets.put("photos", new JSONArray(photoPaths));
            json.put("assets", assets);
            return json;
        }
    }

    private final JoyfulMomentConfig config;
    private int nextDetId = 1;

    public JoyfulMomentClusterer(JoyfulMomentConfig config) {
        this.config = config;
    }

    public DetectionRecord newDetection(long deviceTimeMs, double startSec, double endSec, double confidence, String channel) {
        DetectionRecord record = new DetectionRecord();
        record.detId = String.format(Locale.US, "det_%06d", nextDetId++);
        record.deviceTimeMs = deviceTimeMs;
        record.startSec = startSec;
        record.endSec = endSec;
        record.confidence = confidence;
        record.channel = channel;
        return record;
    }

    public PeriodRecord buildPeriod(long sessionStartMs, int clipId, String label) {
        PeriodRecord record = new PeriodRecord();
        record.periodId = String.format(Locale.US, "period_%06d", clipId);
        record.clipId = clipId;
        record.deviceStartMs = sessionStartMs + clipId * 1000L * config.clipDurationSec;
        record.deviceEndMs = record.deviceStartMs + 1000L * config.clipDurationSec;
        record.label = label;
        return record;
    }

    public EventRecord buildEvent(long sessionStartMs, int eventBucketId) {
        EventRecord record = new EventRecord();
        record.eventId = String.format(Locale.US, "event_%04d", eventBucketId);
        record.eventBucketId = eventBucketId;
        record.deviceStartMs = sessionStartMs + eventBucketId * 1000L * config.eventWindowSec;
        record.deviceEndMs = record.deviceStartMs + 1000L * config.eventWindowSec;
        return record;
    }
}
