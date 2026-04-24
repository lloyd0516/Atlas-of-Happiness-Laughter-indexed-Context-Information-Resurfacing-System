package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class AtlasReviewRepository {
    public static class EventSummary {
        public String eventId;
        public String sessionId;
        public File eventFile;
        public JSONObject eventJson;
        public long startTimeMs;
        public long endTimeMs;
        public int periodCount;
        public int mediaCount;
        public Double lat;
        public Double lng;
        public String weather;
        public String timeRangeText;
    }

    public static class LogItem {
        public long sortTime;
        public String title;
        public String body;
    }

    private final Context context;
    private final File rootDir;
    private final SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
    private final SimpleDateFormat displayFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public AtlasReviewRepository(Context context) {
        this.context = context.getApplicationContext();
        File base = this.context.getExternalFilesDir(null);
        if (base == null) {
            base = this.context.getFilesDir();
        }
        rootDir = new File(base, "joyful_moment");
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
    }

    public File getRootDir() {
        return rootDir;
    }

    public List<EventSummary> loadEventSummaries() {
        ArrayList<EventSummary> result = new ArrayList<>();
        File[] sessionDirs = rootDir.listFiles();
        if (sessionDirs == null) {
            return result;
        }
        for (File sessionDir : sessionDirs) {
            if (sessionDir == null || !sessionDir.isDirectory()) {
                continue;
            }
            File[] files = sessionDir.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file == null || !file.isFile() || !file.getName().startsWith("event_") || !file.getName().endsWith(".json")) {
                    continue;
                }
                JSONObject json = readJson(file);
                if (json == null) {
                    continue;
                }
                try {
                    JSONObject normalized = normalizeEvent(sessionDir, file, json);
                    EventSummary summary = new EventSummary();
                    summary.eventId = normalized.optString("event_id", file.getName().replace(".json", ""));
                    summary.sessionId = sessionDir.getName();
                    summary.eventFile = file;
                    summary.eventJson = normalized;
                    summary.startTimeMs = normalized.optLong("start_time_ms", 0L);
                    summary.endTimeMs = normalized.optLong("end_time_ms", summary.startTimeMs);
                    summary.periodCount = normalized.optJSONArray("period_ids") != null ? normalized.optJSONArray("period_ids").length() : 0;
                    summary.mediaCount = countMedia(normalized);
                    JSONObject derived = normalized.optJSONObject("derived_context");
                    JSONObject gps = derived != null ? derived.optJSONObject("gps") : null;
                    if (gps != null && gps.has("lat") && gps.has("lng")) {
                        summary.lat = gps.optDouble("lat");
                        summary.lng = gps.optDouble("lng");
                    }
                    JSONObject weather = derived != null ? derived.optJSONObject("weather") : null;
                    if (weather != null) {
                        summary.weather = weather.optString("condition", "");
                    }
                    summary.timeRangeText = formatTimeRange(summary.startTimeMs, summary.endTimeMs);
                    result.add(summary);
                } catch (Exception ignored) {
                }
            }
        }
        Collections.sort(result, new Comparator<EventSummary>() {
            @Override
            public int compare(EventSummary o1, EventSummary o2) {
                long delta = o2.startTimeMs - o1.startTimeMs;
                if (delta == 0L) {
                    return o1.eventId.compareTo(o2.eventId);
                }
                return delta > 0L ? 1 : -1;
            }
        });
        return result;
    }

    public JSONObject loadEventById(String eventId) {
        if (TextUtils.isEmpty(eventId)) {
            return null;
        }
        List<EventSummary> events = loadEventSummaries();
        for (EventSummary item : events) {
            if (eventId.equals(item.eventId)) {
                return item.eventJson;
            }
        }
        return null;
    }

    public File resolveEventFile(JSONObject event) {
        if (event == null) {
            return null;
        }
        JSONObject meta = event.optJSONObject("_meta");
        if (meta != null) {
            String path = meta.optString("event_file_path", null);
            if (!TextUtils.isEmpty(path)) {
                return new File(path);
            }
        }
        String eventId = event.optString("event_id", null);
        String sessionId = meta != null ? meta.optString("session_id", null) : null;
        if (TextUtils.isEmpty(eventId) || TextUtils.isEmpty(sessionId)) {
            return null;
        }
        return new File(new File(rootDir, sessionId), eventId + ".json");
    }

    public boolean saveEvent(JSONObject event) {
        File file = resolveEventFile(event);
        if (file == null) {
            return false;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileOutputStream outputStream = new FileOutputStream(file, false);
            outputStream.write(event.toString(2).getBytes(Charset.forName("UTF-8")));
            outputStream.close();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean addTextNote(JSONObject event, String text, String source) {
        if (event == null || TextUtils.isEmpty(text)) {
            return false;
        }
        try {
            JSONObject userGenerated = ensureObject(event, "user_generated");
            JSONArray notes = ensureArray(userGenerated, "notes");
            JSONObject note = new JSONObject();
            note.put("text", text);
            note.put("timestamp", isoFormat.format(new Date()));
            note.put("timestamp_ms", System.currentTimeMillis());
            note.put("source", source);
            notes.put(note);
            return saveEvent(event);
        } catch (JSONException ignored) {
            return false;
        }
    }

    public boolean addAudioNote(JSONObject event, String path, String source) {
        return appendUserMedia(event, "audio_notes", path, source);
    }

    public boolean addPhotoNote(JSONObject event, String path, String source) {
        return appendUserMedia(event, "photos", path, source);
    }

    public boolean updateDerivedContext(JSONObject event, Double lat, Double lng, Long timestampMs, String weatherCondition, Double temperature) {
        if (event == null) {
            return false;
        }
        try {
            JSONObject derived = ensureObject(event, "derived_context");
            JSONObject gps = ensureObject(derived, "gps");
            if (lat != null) {
                gps.put("lat", lat);
            }
            if (lng != null) {
                gps.put("lng", lng);
            }
            if (timestampMs != null) {
                gps.put("timestamp", isoFormat.format(new Date(timestampMs)));
                gps.put("timestamp_ms", timestampMs);
            }
            JSONObject weather = ensureObject(derived, "weather");
            if (!TextUtils.isEmpty(weatherCondition)) {
                weather.put("condition", weatherCondition);
            }
            if (temperature != null) {
                weather.put("temperature", temperature);
            }
            return saveEvent(event);
        } catch (JSONException ignored) {
            return false;
        }
    }

    public List<LogItem> loadMergedLogs() {
        ArrayList<LogItem> result = new ArrayList<>();
        File session = findLatestSessionDir();
        addPlainTextLog(result, new File(rootDir, "dev_ui_log.txt"), "DevUI");
        if (session == null) {
            return result;
        }
        addLogs(result, session, "speechmatics_raw.jsonl", "Speechmatics");
        addLogs(result, session, "detection_log.jsonl", "Detection");
        addLogs(result, session, "period_log.jsonl", "Period");
        addLogs(result, session, "event_log.jsonl", "Event");
        Collections.sort(result, new Comparator<LogItem>() {
            @Override
            public int compare(LogItem o1, LogItem o2) {
                long delta = o1.sortTime - o2.sortTime;
                if (delta == 0L) {
                    return o1.title.compareTo(o2.title);
                }
                return delta > 0L ? 1 : -1;
            }
        });
        return result;
    }

    public String getConfigMirrorPath() {
        return JoyfulMomentConfig.mirrorConfigToExternalFile(context, JoyfulMomentConfig.load(context)).getAbsolutePath();
    }

    public int getCameraBrightnessPercent() {
        SharedPreferences prefs = context.getSharedPreferences(JoyfulMomentConfig.PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(JoyfulMomentConfig.PREF_CAMERA_BRIGHTNESS, 50);
    }

    public void saveCameraBrightnessPercent(int percent) {
        SharedPreferences prefs = context.getSharedPreferences(JoyfulMomentConfig.PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(JoyfulMomentConfig.PREF_CAMERA_BRIGHTNESS, percent).apply();
    }

    public String getGoogleWeatherApiKey() {
        return JoyfulMomentConfig.getGoogleWeatherApiKey(context);
    }

    public void saveGoogleWeatherApiKey(String key) {
        JoyfulMomentConfig.saveGoogleWeatherApiKey(context, key);
    }

    public String formatTimeRange(long startMs, long endMs) {
        if (startMs <= 0L) {
            return "--";
        }
        if (endMs <= 0L) {
            endMs = startMs;
        }
        return displayFormat.format(new Date(startMs)) + " - " + displayFormat.format(new Date(endMs));
    }

    private boolean appendUserMedia(JSONObject event, String arrayName, String path, String source) {
        if (event == null || TextUtils.isEmpty(path)) {
            return false;
        }
        try {
            JSONObject userGenerated = ensureObject(event, "user_generated");
            JSONArray array = ensureArray(userGenerated, arrayName);
            JSONObject item = new JSONObject();
            item.put("path", path);
            item.put("timestamp", isoFormat.format(new Date()));
            item.put("timestamp_ms", System.currentTimeMillis());
            item.put("source", source);
            array.put(item);
            return saveEvent(event);
        } catch (JSONException ignored) {
            return false;
        }
    }

    private int countMedia(JSONObject event) {
        int total = 0;
        JSONObject auto = event.optJSONObject("auto_captured");
        if (auto != null) {
            total += auto.optJSONArray("videos") != null ? auto.optJSONArray("videos").length() : 0;
            total += auto.optJSONArray("photos") != null ? auto.optJSONArray("photos").length() : 0;
            total += auto.optJSONArray("audio_clips") != null ? auto.optJSONArray("audio_clips").length() : 0;
        }
        JSONObject user = event.optJSONObject("user_generated");
        if (user != null) {
            total += user.optJSONArray("notes") != null ? user.optJSONArray("notes").length() : 0;
            total += user.optJSONArray("audio_notes") != null ? user.optJSONArray("audio_notes").length() : 0;
            total += user.optJSONArray("photos") != null ? user.optJSONArray("photos").length() : 0;
        }
        return total;
    }

    private void addPlainTextLog(List<LogItem> dst, File file, String titlePrefix) {
        if (file == null || !file.exists()) {
            return;
        }
        ArrayList<String> lines = readLines(file);
        long base = Math.max(1L, file.lastModified() - lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (TextUtils.isEmpty(line)) {
                continue;
            }
            LogItem item = new LogItem();
            item.sortTime = base + i;
            item.title = titlePrefix;
            item.body = line;
            dst.add(item);
        }
    }

    private void addLogs(List<LogItem> dst, File session, String fileName, String titlePrefix) {
        File file = new File(session, fileName);
        if (!file.exists()) {
            return;
        }
        ArrayList<String> lines = readLines(file);
        for (String line : lines) {
            try {
                JSONObject json = new JSONObject(line);
                LogItem item = new LogItem();
                item.sortTime = extractSortTime(json, file.lastModified());
                item.title = titlePrefix + " · " + json.optString("type", fileName);
                item.body = json.toString(2);
                dst.add(item);
            } catch (Exception ignored) {
            }
        }
    }

    private long extractSortTime(JSONObject json, long fallback) {
        if (json.has("device_time_ms")) {
            return json.optLong("device_time_ms", fallback);
        }
        if (json.has("session_start_ms")) {
            return json.optLong("session_start_ms", fallback);
        }
        if (json.has("timestamp_ms")) {
            return json.optLong("timestamp_ms", fallback);
        }
        if (json.has("device_start_ms")) {
            return json.optLong("device_start_ms", fallback);
        }
        if (json.has("offset_sec")) {
            return (long) (json.optDouble("offset_sec", 0.0) * 1000L);
        }
        JSONObject payload = json.optJSONObject("payload");
        if (payload != null) {
            if (payload.has("start_time")) {
                return (long) (payload.optDouble("start_time", 0.0) * 1000L);
            }
            if (payload.has("end_time")) {
                return (long) (payload.optDouble("end_time", 0.0) * 1000L);
            }
        }
        return fallback;
    }

    private File findLatestSessionDir() {
        File[] files = rootDir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        File latest = null;
        for (File file : files) {
            if (file != null && file.isDirectory()) {
                if (latest == null || file.lastModified() > latest.lastModified()) {
                    latest = file;
                }
            }
        }
        return latest;
    }

    private JSONObject normalizeEvent(File sessionDir, File eventFile, JSONObject raw) throws JSONException {
        if (raw.has("auto_captured")) {
            ensureMeta(raw, sessionDir, eventFile);
            ensureNormalizedCollections(raw);
            return raw;
        }
        JSONObject normalized = new JSONObject();
        normalized.put("event_id", raw.optString("event_id", eventFile.getName().replace(".json", "")));
        long startMs = raw.optLong("device_start_ms", eventFile.lastModified());
        long endMs = raw.optLong("device_end_ms", startMs);
        normalized.put("start_time", isoFormat.format(new Date(startMs)));
        normalized.put("end_time", isoFormat.format(new Date(endMs)));
        normalized.put("start_time_ms", startMs);
        normalized.put("end_time_ms", endMs);
        normalized.put("period_ids", copyArray(raw.optJSONArray("period_ids")));

        HashMap<String, JSONObject> periods = loadPeriodMap(sessionDir);
        JSONObject auto = new JSONObject();
        JSONArray videos = new JSONArray();
        JSONArray photos = new JSONArray();
        JSONArray audioClips = new JSONArray();

        JSONObject assets = raw.optJSONObject("assets");
        String videoPath = assets != null ? optNonEmpty(assets, "video") : null;
        String videoContentUri = assets != null ? optNonEmpty(assets, "video_content_uri") : null;
        if (!TextUtils.isEmpty(videoPath)) {
            JSONObject video = new JSONObject();
            video.put("video_path", videoPath);
            if (!TextUtils.isEmpty(videoContentUri)) {
                video.put("content_uri", videoContentUri);
            }
            video.put("timestamp", isoFormat.format(new Date(startMs)));
            video.put("linked_period_id", firstArrayString(raw.optJSONArray("laughter_period_ids"), firstArrayString(raw.optJSONArray("period_ids"), "")));
            videos.put(video);
        }
        JSONArray eventPhotos = assets != null ? assets.optJSONArray("photos") : null;
        if (eventPhotos != null) {
            for (int i = 0; i < eventPhotos.length(); i++) {
                String path = eventPhotos.optString(i, null);
                if (TextUtils.isEmpty(path)) {
                    continue;
                }
                JSONObject photo = new JSONObject();
                photo.put("photo_path", path);
                photo.put("timestamp", isoFormat.format(new Date(startMs)));
                photo.put("source", "video_frame");
                photos.put(photo);
            }
        }

        JSONArray periodIds = raw.optJSONArray("period_ids");
        if (periodIds != null) {
            for (int i = 0; i < periodIds.length(); i++) {
                String periodId = periodIds.optString(i, null);
                if (TextUtils.isEmpty(periodId)) {
                    continue;
                }
                JSONObject period = periods.get(periodId);
                if (period == null) {
                    continue;
                }
                String label = period.optString("label", "none");
                String savedPath = optNonEmpty(period, "saved_path");
                if (!TextUtils.isEmpty(savedPath)) {
                    JSONObject clip = new JSONObject();
                    if ("laughter".equals(label)) {
                        clip.put("type", "laughter");
                        clip.put("path", savedPath);
                        clip.put("period_id", periodId);
                    } else if ("possible_related_speech_context".equals(label)) {
                        clip.put("type", "possible_related_speech_context");
                        clip.put("path", savedPath);
                        clip.put("linked_period_ids", copyArray(period.optJSONArray("related_laughter_period_ids")));
                    }
                    if (clip.length() > 0) {
                        audioClips.put(clip);
                    }
                }
                JSONObject periodAssets = period.optJSONObject("assets");
                if (periodAssets != null) {
                    JSONArray periodPhotos = periodAssets.optJSONArray("photos");
                    if (periodPhotos != null) {
                        for (int j = 0; j < periodPhotos.length(); j++) {
                            String path = periodPhotos.optString(j, null);
                            if (TextUtils.isEmpty(path) || containsPhotoPath(photos, path)) {
                                continue;
                            }
                            JSONObject photo = new JSONObject();
                            photo.put("photo_path", path);
                            photo.put("timestamp", isoFormat.format(new Date(period.optLong("device_start_ms", startMs))));
                            photo.put("source", "video_frame");
                            photos.put(photo);
                        }
                    }
                }
            }
        }
        auto.put("videos", videos);
        auto.put("photos", photos);
        auto.put("audio_clips", audioClips);
        normalized.put("auto_captured", auto);

        JSONObject derived = new JSONObject();
        derived.put("gps", new JSONObject());
        derived.put("weather", new JSONObject());
        normalized.put("derived_context", derived);

        JSONObject user = new JSONObject();
        user.put("notes", new JSONArray());
        user.put("audio_notes", new JSONArray());
        user.put("photos", new JSONArray());
        normalized.put("user_generated", user);

        ensureMeta(normalized, sessionDir, eventFile);
        return normalized;
    }

    private void ensureNormalizedCollections(JSONObject event) throws JSONException {
        if (!event.has("period_ids") || event.isNull("period_ids")) {
            event.put("period_ids", new JSONArray());
        }
        JSONObject auto = ensureObject(event, "auto_captured");
        ensureArray(auto, "videos");
        ensureArray(auto, "photos");
        ensureArray(auto, "audio_clips");
        JSONObject derived = ensureObject(event, "derived_context");
        ensureObject(derived, "gps");
        ensureObject(derived, "weather");
        JSONObject user = ensureObject(event, "user_generated");
        ensureArray(user, "notes");
        ensureArray(user, "audio_notes");
        ensureArray(user, "photos");
        if (!event.has("start_time_ms")) {
            event.put("start_time_ms", 0L);
        }
        if (!event.has("end_time_ms")) {
            event.put("end_time_ms", event.optLong("start_time_ms", 0L));
        }
    }

    private void ensureMeta(JSONObject event, File sessionDir, File eventFile) throws JSONException {
        JSONObject meta = ensureObject(event, "_meta");
        meta.put("session_id", sessionDir.getName());
        meta.put("session_dir", sessionDir.getAbsolutePath());
        meta.put("event_file_path", eventFile.getAbsolutePath());
    }

    private HashMap<String, JSONObject> loadPeriodMap(File sessionDir) {
        HashMap<String, JSONObject> map = new HashMap<>();
        File file = new File(sessionDir, "period_log.jsonl");
        ArrayList<String> lines = readLines(file);
        HashMap<Integer, String> clipIdToPeriodId = new HashMap<>();
        for (String line : lines) {
            try {
                JSONObject json = new JSONObject(line);
                String periodId = json.optString("period_id", null);
                if (TextUtils.isEmpty(periodId)) {
                    continue;
                }
                clipIdToPeriodId.put(json.optInt("clip_id", -1), periodId);
                map.put(periodId, json);
            } catch (Exception ignored) {
            }
        }
        Iterator<String> iterator = map.keySet().iterator();
        while (iterator.hasNext()) {
            String periodId = iterator.next();
            JSONObject json = map.get(periodId);
            if (json == null) {
                continue;
            }
            JSONArray relatedClipIds = json.optJSONArray("related_laughter_clip_ids");
            JSONArray relatedPeriodIds = new JSONArray();
            if (relatedClipIds != null) {
                for (int i = 0; i < relatedClipIds.length(); i++) {
                    int clipId = relatedClipIds.optInt(i, -1);
                    String relatedPeriodId = clipIdToPeriodId.get(clipId);
                    if (!TextUtils.isEmpty(relatedPeriodId)) {
                        relatedPeriodIds.put(relatedPeriodId);
                    }
                }
            }
            try {
                json.put("related_laughter_period_ids", relatedPeriodIds);
            } catch (JSONException ignored) {
            }
        }
        return map;
    }

    private boolean containsPhotoPath(JSONArray photos, String path) {
        for (int i = 0; i < photos.length(); i++) {
            JSONObject photo = photos.optJSONObject(i);
            if (photo != null && path.equals(photo.optString("photo_path"))) {
                return true;
            }
        }
        return false;
    }

    private JSONObject readJson(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private ArrayList<String> readLines(File file) {
        ArrayList<String> lines = new ArrayList<>();
        if (file == null || !file.exists()) {
            return lines;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!TextUtils.isEmpty(line.trim())) {
                    lines.add(line);
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception ignored) {
            }
        }
        return lines;
    }

    private JSONObject ensureObject(JSONObject parent, String key) throws JSONException {
        JSONObject child = parent.optJSONObject(key);
        if (child == null) {
            child = new JSONObject();
            parent.put(key, child);
        }
        return child;
    }

    private JSONArray ensureArray(JSONObject parent, String key) throws JSONException {
        JSONArray child = parent.optJSONArray(key);
        if (child == null) {
            child = new JSONArray();
            parent.put(key, child);
        }
        return child;
    }

    private JSONArray copyArray(JSONArray array) {
        JSONArray copy = new JSONArray();
        if (array == null) {
            return copy;
        }
        for (int i = 0; i < array.length(); i++) {
            copy.put(array.opt(i));
        }
        return copy;
    }

    private String firstArrayString(JSONArray array, String fallback) {
        if (array != null && array.length() > 0) {
            return array.optString(0, fallback);
        }
        return fallback;
    }

    private String optNonEmpty(JSONObject json, String key) {
        if (json == null || !json.has(key) || json.isNull(key)) {
            return null;
        }
        String value = json.optString(key, null);
        return TextUtils.isEmpty(value) ? null : value;
    }
}
