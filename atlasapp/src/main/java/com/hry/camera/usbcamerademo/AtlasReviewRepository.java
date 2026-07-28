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
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
        public Double amapLat;
        public Double amapLng;
        public Float accuracyMeters;
        public String locationName;
        public String weather;
        public String weatherIconKey;
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
                if (!isEventJsonFile(file)) {
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
                        if (gps.has("amap_lat") && gps.has("amap_lng")) {
                            summary.amapLat = gps.optDouble("amap_lat");
                            summary.amapLng = gps.optDouble("amap_lng");
                        }
                        if (gps.has("accuracy_m")) {
                            summary.accuracyMeters = (float) gps.optDouble("accuracy_m");
                        }
                        summary.locationName = gps.optString("address", "");
                    }
                    JSONObject weather = derived != null ? derived.optJSONObject("weather") : null;
                    if (weather != null) {
                        summary.weather = weather.optString("condition", "");
                        summary.weatherIconKey = weather.optString("icon_key", AtlasWeatherIconMapper.keyForCondition(summary.weather));
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

    /** Requirement 2: list only this session's laughter events, for the post-recording supplement picker. */
    public List<EventSummary> loadEventSummariesForSession(String sessionId) {
        ArrayList<EventSummary> result = new ArrayList<>();
        if (TextUtils.isEmpty(sessionId)) {
            return result;
        }
        for (EventSummary item : loadEventSummaries()) {
            if (sessionId.equals(item.sessionId)) {
                result.add(item);
            }
        }
        return result;
    }

    public static class TodayStats {
        public int laughterCount;
        public int recordedMinutes;
        public int joyfulMomentCount;
    }

    /** Requirement 1.II: "今日数据" card — laughter clip count / recorded minutes / joyful moments, today only. */
    public TodayStats computeTodayStats() {
        TodayStats stats = new TodayStats();
        Calendar startOfDay = Calendar.getInstance();
        startOfDay.set(Calendar.HOUR_OF_DAY, 0);
        startOfDay.set(Calendar.MINUTE, 0);
        startOfDay.set(Calendar.SECOND, 0);
        startOfDay.set(Calendar.MILLISECOND, 0);
        long todayStartMs = startOfDay.getTimeInMillis();
        long todayEndMs = todayStartMs + 24L * 60 * 60 * 1000L;

        long recordedMs = 0L;
        for (EventSummary event : loadEventSummaries()) {
            if (event.startTimeMs < todayStartMs || event.startTimeMs >= todayEndMs) {
                continue;
            }
            stats.joyfulMomentCount++;
            recordedMs += Math.max(0L, event.endTimeMs - event.startTimeMs);
            JSONObject auto = event.eventJson != null ? event.eventJson.optJSONObject("auto_captured") : null;
            JSONArray audioClips = auto != null ? auto.optJSONArray("audio_clips") : null;
            if (audioClips != null) {
                for (int i = 0; i < audioClips.length(); i++) {
                    JSONObject clip = audioClips.optJSONObject(i);
                    if (clip != null && "laughter".equals(clip.optString("type", ""))) {
                        stats.laughterCount++;
                    }
                }
            }
        }
        stats.recordedMinutes = (int) Math.round(recordedMs / 60000.0);
        return stats;
    }

    public JSONObject loadEventById(String eventId) {
        return loadEventById(null, eventId);
    }

    public JSONObject loadEventById(String sessionId, String eventId) {
        if (TextUtils.isEmpty(eventId)) {
            return null;
        }
        List<EventSummary> events = loadEventSummaries();
        for (EventSummary item : events) {
            if (eventId.equals(item.eventId) && (TextUtils.isEmpty(sessionId) || sessionId.equals(item.sessionId))) {
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
            note.put("item_id", newItemId());
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

    public boolean editTextNote(JSONObject event, String itemId, String newText) {
        JSONObject note = findItemById(event, "notes", itemId);
        if (note == null || TextUtils.isEmpty(newText)) {
            return false;
        }
        try {
            note.put("text", newText);
            note.put("edited_at_ms", System.currentTimeMillis());
            return saveEvent(event);
        } catch (JSONException ignored) {
            return false;
        }
    }

    public boolean deleteTextNote(JSONObject event, String itemId) {
        return deleteItemById(event, "notes", itemId, false);
    }

    public boolean addAudioNote(JSONObject event, String path, String source) {
        return appendUserMedia(event, "audio_notes", path, source);
    }

    public boolean deleteAudioNote(JSONObject event, String itemId) {
        return deleteItemById(event, "audio_notes", itemId, true);
    }

    public boolean addPhotoNote(JSONObject event, String path, String source) {
        return appendUserMedia(event, "photos", path, source);
    }

    public boolean deletePhotoNote(JSONObject event, String itemId) {
        return deleteItemById(event, "photos", itemId, true);
    }

    /**
     * "和谁 / 在做什么 / 心情" — 一致地贴到 long/short 两种呈现，随时可编辑，字段为空即视为未填写。
     */
    public boolean updateSocialContext(JSONObject event, String withWhom, String doingWhat, String mood) {
        if (event == null) {
            return false;
        }
        try {
            JSONObject userGenerated = ensureObject(event, "user_generated");
            JSONObject socialContext = ensureObject(userGenerated, "social_context");
            socialContext.put("with_whom", withWhom == null ? "" : withWhom.trim());
            socialContext.put("doing_what", doingWhat == null ? "" : doingWhat.trim());
            socialContext.put("mood", mood == null ? "" : mood.trim());
            socialContext.put("updated_at_ms", System.currentTimeMillis());
            return saveEvent(event);
        } catch (JSONException ignored) {
            return false;
        }
    }

    public JSONObject getSocialContext(JSONObject event) {
        JSONObject userGenerated = event == null ? null : event.optJSONObject("user_generated");
        JSONObject socialContext = userGenerated == null ? null : userGenerated.optJSONObject("social_context");
        return socialContext == null ? new JSONObject() : socialContext;
    }

    public boolean hasSocialContext(JSONObject event) {
        JSONObject socialContext = getSocialContext(event);
        return !TextUtils.isEmpty(socialContext.optString("with_whom", ""))
                || !TextUtils.isEmpty(socialContext.optString("doing_what", ""))
                || !TextUtils.isEmpty(socialContext.optString("mood", ""));
    }

    /** 组合 "和Mark、朋友聚餐" 这类展示用短语，供长期回顾 tag 使用；三项均为空则返回 null。 */
    public String buildSocialContextTag(JSONObject event) {
        JSONObject socialContext = getSocialContext(event);
        String withWhom = socialContext.optString("with_whom", "");
        String doingWhat = socialContext.optString("doing_what", "");
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(withWhom)) {
            sb.append(context.getString(R.string.social_tag_with_prefix)).append(withWhom);
        }
        if (!TextUtils.isEmpty(doingWhat)) {
            if (sb.length() > 0) {
                sb.append(context.getString(R.string.social_tag_separator));
            }
            sb.append(doingWhat);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** 需求5：删除 / 保存并推送 / 保存不推送，三选一，事件级持久化。 */
    public boolean saveDecision(JSONObject event, String action) {
        if (event == null || TextUtils.isEmpty(action)) {
            return false;
        }
        try {
            JSONObject decision = ensureObject(event, "save_decision");
            decision.put("action", action);
            decision.put("decided_at_ms", System.currentTimeMillis());
            return saveEvent(event);
        } catch (JSONException ignored) {
            return false;
        }
    }

    public String getSaveDecisionAction(JSONObject event) {
        JSONObject decision = event == null ? null : event.optJSONObject("save_decision");
        return decision == null ? null : decision.optString("action", null);
    }

    /**
     * Requirement 2.III: timeline cards show a cover pic — the first auto-captured photo by
     * default, or whatever the user picked instead via setCoverPhoto.
     */
    public String getCoverPhoto(JSONObject event) {
        if (event == null) {
            return null;
        }
        JSONObject userGenerated = event.optJSONObject("user_generated");
        String userChosen = userGenerated == null ? null : optNonEmpty(userGenerated, "cover_photo_path");
        if (!TextUtils.isEmpty(userChosen)) {
            return userChosen;
        }
        JSONObject auto = event.optJSONObject("auto_captured");
        JSONArray photos = auto == null ? null : auto.optJSONArray("photos");
        if (photos != null && photos.length() > 0) {
            JSONObject first = photos.optJSONObject(0);
            return first == null ? null : optNonEmpty(first, "photo_path");
        }
        return null;
    }

    public boolean setCoverPhoto(JSONObject event, String path) {
        if (event == null || TextUtils.isEmpty(path)) {
            return false;
        }
        try {
            JSONObject userGenerated = ensureObject(event, "user_generated");
            userGenerated.put("cover_photo_path", path);
            return saveEvent(event);
        } catch (JSONException ignored) {
            return false;
        }
    }

    /** All photo paths (auto-captured + user-added) available to pick as a cover, most recent first. */
    public List<String> getAllPhotoPaths(JSONObject event) {
        ArrayList<String> paths = new ArrayList<>();
        if (event == null) {
            return paths;
        }
        JSONObject auto = event.optJSONObject("auto_captured");
        JSONArray autoPhotos = auto == null ? null : auto.optJSONArray("photos");
        if (autoPhotos != null) {
            for (int i = 0; i < autoPhotos.length(); i++) {
                JSONObject photo = autoPhotos.optJSONObject(i);
                String path = photo == null ? null : optNonEmpty(photo, "photo_path");
                if (!TextUtils.isEmpty(path)) {
                    paths.add(path);
                }
            }
        }
        JSONObject userGenerated = event.optJSONObject("user_generated");
        JSONArray userPhotos = userGenerated == null ? null : userGenerated.optJSONArray("photos");
        if (userPhotos != null) {
            for (int i = 0; i < userPhotos.length(); i++) {
                JSONObject photo = userPhotos.optJSONObject(i);
                String path = photo == null ? null : optNonEmpty(photo, "path");
                if (!TextUtils.isEmpty(path)) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    /** 物理删除事件及其关联媒体文件，用于 save_decision = delete。破坏性操作，调用方需先做二次确认。 */
    public boolean deleteEventPermanently(EventSummary summary) {
        if (summary == null || summary.eventJson == null || summary.eventFile == null) {
            return false;
        }
        List<File> targets = collectOwnedDeletionTargets(
                summary.eventJson, rootDir, summary.eventFile);
        for (File target : targets) {
            if (sameCanonicalFile(target, summary.eventFile)) {
                continue;
            }
            deleteRecursively(target);
        }
        return summary.eventFile.exists() && summary.eventFile.delete();
    }

    static List<File> collectOwnedDeletionTargets(
            JSONObject event, File root, File eventFile) {
        LinkedHashMap<String, File> targets = new LinkedHashMap<>();
        if (event == null || root == null || eventFile == null) {
            return new ArrayList<>();
        }
        String eventId = event.optString("event_id", "");
        if (!eventId.matches("[A-Za-z0-9_.-]+")) {
            return new ArrayList<>();
        }
        File session = eventFile.getParentFile();
        if (session == null || !isInside(root, session) || !isInside(root, eventFile)) {
            return new ArrayList<>();
        }
        File capturedRoot = new File(new File(session, "captured_media"), eventId);
        File userRoot = new File(new File(session, "user_generated"), eventId);
        collectOwnedJsonPaths(event, capturedRoot, userRoot, targets);
        addIfOwned(capturedRoot, capturedRoot, userRoot, targets);
        addIfOwned(userRoot, capturedRoot, userRoot, targets);
        addCanonical(eventFile, targets);
        return new ArrayList<>(targets.values());
    }

    private static void collectOwnedJsonPaths(
            Object node,
            File capturedRoot,
            File userRoot,
            LinkedHashMap<String, File> targets) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if (value instanceof String
                        && ("path".equals(key)
                        || "photo_path".equals(key)
                        || "video_path".equals(key))) {
                    addIfOwned(
                            new File((String) value), capturedRoot, userRoot, targets);
                } else {
                    collectOwnedJsonPaths(value, capturedRoot, userRoot, targets);
                }
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectOwnedJsonPaths(
                        array.opt(i), capturedRoot, userRoot, targets);
            }
        }
    }

    private static void addIfOwned(
            File candidate,
            File capturedRoot,
            File userRoot,
            LinkedHashMap<String, File> targets) {
        if (isInsideOrSame(capturedRoot, candidate)
                || isInsideOrSame(userRoot, candidate)) {
            addCanonical(candidate, targets);
        }
    }

    private static void addCanonical(
            File file, LinkedHashMap<String, File> targets) {
        try {
            File canonical = file.getCanonicalFile();
            targets.put(canonical.getPath(), canonical);
        } catch (Exception ignored) {
        }
    }

    private static boolean isInside(File parent, File child) {
        return !sameCanonicalFile(parent, child) && isInsideOrSame(parent, child);
    }

    private static boolean isInsideOrSame(File parent, File child) {
        if (parent == null || child == null) {
            return false;
        }
        try {
            String parentPath = parent.getCanonicalPath();
            String childPath = child.getCanonicalPath();
            return childPath.equals(parentPath)
                    || childPath.startsWith(parentPath + File.separator);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean sameCanonicalFile(File left, File right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return true;
        }
        boolean success = true;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    success = deleteRecursively(child) && success;
                }
            }
        }
        return target.delete() && success;
    }

    private String newItemId() {
        return "item_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 100000);
    }

    private JSONObject findItemById(JSONObject event, String arrayName, String itemId) {
        if (event == null || TextUtils.isEmpty(itemId)) {
            return null;
        }
        JSONObject userGenerated = event.optJSONObject("user_generated");
        JSONArray array = userGenerated == null ? null : userGenerated.optJSONArray(arrayName);
        if (array == null) {
            return null;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && itemId.equals(item.optString("item_id", null))) {
                return item;
            }
        }
        return null;
    }

    /** deleteFile=true 时同时物理删除该条目引用的媒体文件（音频/照片笔记）。 */
    private boolean deleteItemById(JSONObject event, String arrayName, String itemId, boolean deleteFile) {
        if (event == null || TextUtils.isEmpty(itemId)) {
            return false;
        }
        JSONObject userGenerated = event.optJSONObject("user_generated");
        JSONArray array = userGenerated == null ? null : userGenerated.optJSONArray(arrayName);
        if (array == null) {
            return false;
        }
        JSONArray rebuilt = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && itemId.equals(item.optString("item_id", null))) {
                removed = true;
                if (deleteFile) {
                    String path = item.optString("path", null);
                    if (!TextUtils.isEmpty(path)) {
                        try {
                            File file = new File(path);
                            if (file.exists()) {
                                file.delete();
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                continue;
            }
            rebuilt.put(item);
        }
        if (!removed) {
            return false;
        }
        try {
            userGenerated.put(arrayName, rebuilt);
            return saveEvent(event);
        } catch (JSONException ignored) {
            return false;
        }
    }

    public boolean updateDerivedContext(JSONObject event, Double lat, Double lng, Double amapLat, Double amapLng, Float accuracyMeters, Long timestampMs, String locationName, String adcode, String weatherCondition, Double temperature) {
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
            if (amapLat != null) {
                gps.put("amap_lat", amapLat);
            }
            if (amapLng != null) {
                gps.put("amap_lng", amapLng);
            }
            if (accuracyMeters != null) {
                gps.put("accuracy_m", accuracyMeters);
            }
            if (timestampMs != null) {
                gps.put("timestamp", isoFormat.format(new Date(timestampMs)));
                gps.put("timestamp_ms", timestampMs);
            }
            if (!TextUtils.isEmpty(locationName)) {
                gps.put("address", locationName);
            }
            if (!TextUtils.isEmpty(adcode)) {
                gps.put("adcode", adcode);
            }
            JSONObject weather = ensureObject(derived, "weather");
            if (!TextUtils.isEmpty(weatherCondition)) {
                weather.put("condition", weatherCondition);
                weather.put("icon_key", AtlasWeatherIconMapper.keyForCondition(weatherCondition));
            }
            if (temperature != null) {
                weather.put("temperature", temperature);
            }
            if (!TextUtils.isEmpty(adcode)) {
                weather.put("adcode", adcode);
            }
            weather.put("provider", "amap");
            return saveEvent(event);
        } catch (JSONException ignored) {
            return false;
        }
    }

    public boolean hasUsefulDerivedContext(JSONObject event) {
        if (event == null) {
            return false;
        }
        JSONObject derived = event.optJSONObject("derived_context");
        JSONObject gps = derived != null ? derived.optJSONObject("gps") : null;
        JSONObject weather = derived != null ? derived.optJSONObject("weather") : null;
        boolean hasLocation = gps != null && gps.has("lat") && gps.has("lng") && !TextUtils.isEmpty(gps.optString("address", ""));
        boolean hasWeather = weather != null && !TextUtils.isEmpty(weather.optString("condition", ""));
        return hasLocation && hasWeather;
    }

    public int backfillMissingContextFromNearby(JSONObject sourceEvent, long windowMs) {
        if (!hasUsefulDerivedContext(sourceEvent)) {
            return 0;
        }
        JSONObject sourceDerived = sourceEvent.optJSONObject("derived_context");
        if (sourceDerived == null) {
            return 0;
        }
        String sourceEventId = sourceEvent.optString("event_id", "");
        JSONObject sourceMeta = sourceEvent.optJSONObject("_meta");
        String sourceSessionId = sourceMeta != null ? sourceMeta.optString("session_id", "") : "";
        long sourceStartMs = sourceEvent.optLong("start_time_ms", sourceEvent.optLong("device_start_ms", 0L));
        int count = 0;
        List<EventSummary> events = loadEventSummaries();
        for (EventSummary item : events) {
            if (item == null || item.eventJson == null) {
                continue;
            }
            if (sourceEventId.equals(item.eventId) && sourceSessionId.equals(item.sessionId)) {
                continue;
            }
            long targetStartMs = item.eventJson.optLong("start_time_ms", item.eventJson.optLong("device_start_ms", item.startTimeMs));
            if (sourceStartMs > 0L && targetStartMs > 0L && Math.abs(sourceStartMs - targetStartMs) > windowMs) {
                continue;
            }
            if (hasUsefulDerivedContext(item.eventJson)) {
                continue;
            }
            try {
                item.eventJson.put("derived_context", new JSONObject(sourceDerived.toString()));
                if (saveEvent(item.eventJson)) {
                    count += 1;
                }
            } catch (JSONException ignored) {
            }
        }
        return count;
    }

    public String getAmapApiKey() {
        return JoyfulMomentConfig.getAmapApiKey(context);
    }

    public void saveAmapApiKey(String key) {
        JoyfulMomentConfig.saveAmapApiKey(context, key);
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

    public String getOpenWeatherApiKey() {
        return JoyfulMomentConfig.getOpenWeatherApiKey(context);
    }

    public void saveOpenWeatherApiKey(String key) {
        JoyfulMomentConfig.saveOpenWeatherApiKey(context, key);
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
            item.put("item_id", newItemId());
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
        String eventLinkedPeriodId = firstArrayString(
                raw.optJSONArray("laughter_period_ids"),
                firstArrayString(raw.optJSONArray("period_ids"), ""));
        JSONArray eventVideos = assets != null ? assets.optJSONArray("videos") : null;
        if (eventVideos != null) {
            for (int i = 0; i < eventVideos.length(); i++) {
                JSONObject rawVideo = eventVideos.optJSONObject(i);
                String path = null;
                String contentUri = null;
                if (rawVideo != null) {
                    path = optNonEmpty(rawVideo, "path");
                    contentUri = optNonEmpty(rawVideo, "content_uri");
                } else {
                    path = eventVideos.optString(i, null);
                }
                if (TextUtils.isEmpty(path) || containsVideoPath(videos, path)) {
                    continue;
                }
                JSONObject timeSource = rawVideo != null
                        ? rawVideo
                        : new JSONObject().put("path", path);
                appendNormalizedVideo(
                        videos,
                        path,
                        contentUri,
                        timeSource,
                        "path",
                        startMs,
                        endMs,
                        rawVideo != null
                                ? rawVideo.optString(
                                        "linked_period_id",
                                        eventLinkedPeriodId)
                                : eventLinkedPeriodId);
            }
        }
        String videoPath = assets != null ? optNonEmpty(assets, "video") : null;
        String videoContentUri = assets != null
                ? optNonEmpty(assets, "video_content_uri")
                : null;
        if (!TextUtils.isEmpty(videoPath)
                && !containsVideoPath(videos, videoPath)) {
            JSONObject timeSource =
                    new JSONObject().put("video_path", videoPath);
            appendNormalizedVideo(
                    videos,
                    videoPath,
                    videoContentUri,
                    timeSource,
                    "video_path",
                    startMs,
                    endMs,
                    eventLinkedPeriodId);
        }
        JSONArray photoRecords = assets != null
                ? assets.optJSONArray("photo_records")
                : null;
        if (photoRecords != null) {
            for (int i = 0; i < photoRecords.length(); i++) {
                JSONObject rawPhoto = photoRecords.optJSONObject(i);
                if (rawPhoto == null) {
                    continue;
                }
                String path = optNonEmpty(rawPhoto, "path");
                if (TextUtils.isEmpty(path)
                        || containsPhotoPath(photos, path)) {
                    continue;
                }
                appendNormalizedPhoto(
                        photos,
                        path,
                        rawPhoto,
                        "path",
                        startMs,
                        endMs,
                        "auto_photo");
            }
        }
        JSONArray eventPhotos = assets != null ? assets.optJSONArray("photos") : null;
        if (eventPhotos != null) {
            for (int i = 0; i < eventPhotos.length(); i++) {
                String path = eventPhotos.optString(i, null);
                if (TextUtils.isEmpty(path)
                        || containsPhotoPath(photos, path)) {
                    continue;
                }
                appendNormalizedPhoto(
                        photos,
                        path,
                        new JSONObject().put("photo_path", path),
                        "photo_path",
                        startMs,
                        endMs,
                        "legacy_auto_photo");
            }
        }

        JSONArray savedClipPaths = raw.optJSONArray("saved_clip_paths");
        JSONArray laughterClipIds = raw.optJSONArray("laughter_clip_ids");
        JSONArray contextClipIds = raw.optJSONArray("context_clip_ids");
        HashMap<String, JSONObject> detections = loadDetectionMap(sessionDir);
        if (savedClipPaths != null) {
            for (int i = 0; i < savedClipPaths.length(); i++) {
                String path = savedClipPaths.optString(i, null);
                if (TextUtils.isEmpty(path)) {
                    continue;
                }
                int clipId = parseClipId(path);
                JSONObject clip = new JSONObject();
                clip.put("path", path);
                clip.put("clip_id", clipId);
                clip.put("timestamp", isoFormat.format(new Date(startMs)));
                if (containsInt(contextClipIds, clipId) || path.contains("possible_related_speech_context")) {
                    clip.put("type", "possible_related_speech_context");
                } else if (containsInt(laughterClipIds, clipId) || path.contains("laughter")) {
                    clip.put("type", "laughter");
                } else {
                    clip.put("type", "context_audio");
                }
                // Requirement 4: laughter clip cards need the clip's own exact start/end, not just
                // the event's. The filename embeds det_id (see JoyfulMomentController line ~537);
                // join back to detection_log.jsonl for start_sec/end_sec/device_time_ms/duration_sec.
                String detId = parseDetId(path);
                JSONObject detection = detId != null ? detections.get(detId) : null;
                if (detection != null) {
                    clip.put("det_id", detId);
                    clip.put("device_time_ms", detection.optLong("device_time_ms", startMs));
                    clip.put("start_sec", detection.optDouble("start_sec", 0.0));
                    clip.put("end_sec", detection.optDouble("end_sec", 0.0));
                    clip.put("duration_sec", detection.optDouble("duration_sec", 0.0));
                }
                audioClips.put(clip);
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
                            appendNormalizedPhoto(
                                    photos,
                                    path,
                                    new JSONObject().put("photo_path", path),
                                    "photo_path",
                                    startMs,
                                    endMs,
                                    "period_asset");
                        }
                    }
                }
            }
        }
        auto.put("videos", videos);
        auto.put("photos", photos);
        auto.put("audio_clips", audioClips);
        normalized.put("auto_captured", auto);

        JSONObject derived = raw.optJSONObject("derived_context");
        if (derived == null) {
            derived = new JSONObject();
        }
        ensureObject(derived, "gps");
        ensureObject(derived, "weather");
        normalized.put("derived_context", derived);

        JSONObject user = new JSONObject();
        user.put("notes", new JSONArray());
        user.put("audio_notes", new JSONArray());
        user.put("photos", new JSONArray());
        normalized.put("user_generated", user);

        ensureMeta(normalized, sessionDir, eventFile);
        return normalized;
    }

    private void appendNormalizedVideo(
            JSONArray videos,
            String path,
            String contentUri,
            JSONObject timeSource,
            String timePathKey,
            long eventStartMs,
            long eventEndMs,
            String linkedPeriodId) throws JSONException {
        JSONObject video = new JSONObject();
        video.put("video_path", path);
        if (!TextUtils.isEmpty(contentUri)) {
            video.put("content_uri", contentUri);
        }
        if (!TextUtils.isEmpty(linkedPeriodId)) {
            video.put("linked_period_id", linkedPeriodId);
        }
        putResolvedCaptureTime(
                video,
                timeSource,
                timePathKey,
                eventStartMs,
                eventEndMs);
        videos.put(video);
    }

    private void appendNormalizedPhoto(
            JSONArray photos,
            String path,
            JSONObject timeSource,
            String timePathKey,
            long eventStartMs,
            long eventEndMs,
            String source) throws JSONException {
        JSONObject photo = new JSONObject();
        photo.put("photo_path", path);
        if (!TextUtils.isEmpty(source)) {
            photo.put("source", source);
        }
        putResolvedCaptureTime(
                photo,
                timeSource,
                timePathKey,
                eventStartMs,
                eventEndMs);
        photos.put(photo);
    }

    private void putResolvedCaptureTime(
            JSONObject target,
            JSONObject timeSource,
            String timePathKey,
            long eventStartMs,
            long eventEndMs) throws JSONException {
        long captureTimeMs = AtlasMediaCaptureTimeResolver.resolve(
                timeSource,
                timePathKey,
                eventStartMs,
                eventEndMs);
        if (captureTimeMs <= 0L) {
            return;
        }
        target.put("capture_time_ms", captureTimeMs);
        target.put("timestamp", isoFormat.format(new Date(captureTimeMs)));
    }

    private boolean isEventJsonFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName();
        return name.endsWith(".json") && (name.startsWith("event_") || name.contains("_event_"));
    }

    private void ensureNormalizedCollections(JSONObject event) throws JSONException {
        if (!event.has("period_ids") || event.isNull("period_ids")) {
            event.put("period_ids", new JSONArray());
        }
        JSONObject auto = ensureObject(event, "auto_captured");
        ensureArray(auto, "videos");
        ensureArray(auto, "photos");
        JSONArray audioClips = ensureArray(auto, "audio_clips");
        backfillAudioClipsFromSavedPaths(event, audioClips);
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
        backfillMediaCaptureTimes(event, auto);
    }

    private void backfillMediaCaptureTimes(
            JSONObject event,
            JSONObject auto) throws JSONException {
        long startMs = event.optLong(
                "start_time_ms",
                event.optLong("device_start_ms", 0L));
        long endMs = Math.max(
                startMs,
                event.optLong(
                        "end_time_ms",
                        event.optLong("device_end_ms", startMs)));
        backfillMediaArrayCaptureTimes(
                auto.optJSONArray("photos"),
                "photo_path",
                startMs,
                endMs);
        backfillMediaArrayCaptureTimes(
                auto.optJSONArray("videos"),
                "video_path",
                startMs,
                endMs);
    }

    private void backfillMediaArrayCaptureTimes(
            JSONArray items,
            String pathKey,
            long eventStartMs,
            long eventEndMs) throws JSONException {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            long captureTimeMs = AtlasMediaCaptureTimeResolver.resolve(
                    item,
                    pathKey,
                    eventStartMs,
                    eventEndMs);
            if (captureTimeMs > 0L) {
                item.put("capture_time_ms", captureTimeMs);
                item.put(
                        "timestamp",
                        isoFormat.format(new Date(captureTimeMs)));
            } else {
                item.remove("capture_time_ms");
                item.remove("timestamp");
            }
        }
    }

    private void backfillAudioClipsFromSavedPaths(JSONObject event, JSONArray audioClips) throws JSONException {
        if (event == null || audioClips == null || audioClips.length() > 0) {
            return;
        }
        JSONArray savedClipPaths = event.optJSONArray("saved_clip_paths");
        if (savedClipPaths == null || savedClipPaths.length() == 0) {
            return;
        }
        JSONArray laughterClipIds = event.optJSONArray("laughter_clip_ids");
        JSONArray contextClipIds = event.optJSONArray("context_clip_ids");
        long startMs = event.optLong("start_time_ms", event.optLong("device_start_ms", System.currentTimeMillis()));
        for (int i = 0; i < savedClipPaths.length(); i++) {
            String path = savedClipPaths.optString(i, null);
            if (TextUtils.isEmpty(path)) {
                continue;
            }
            int clipId = parseClipId(path);
            JSONObject clip = new JSONObject();
            clip.put("path", path);
            clip.put("clip_id", clipId);
            clip.put("timestamp", isoFormat.format(new Date(startMs)));
            if (containsInt(contextClipIds, clipId) || path.contains("possible_related_speech_context")) {
                clip.put("type", "possible_related_speech_context");
            } else if (containsInt(laughterClipIds, clipId) || path.contains("laughter")) {
                clip.put("type", "laughter");
            } else {
                clip.put("type", "context_audio");
            }
            audioClips.put(clip);
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

    /**
     * Requirement 4: laughter clip cards need an exact HH:mm:ss range, but that timing is only
     * ever recorded in detection_log.jsonl ("detection.layer" records keyed by det_id) — the
     * period_id/clip_id bookkeeping on the event itself carries no start/end. det_id is embedded
     * in the clip filename (clip_%06d_laughter_%s.wav, see JoyfulMomentController), so joining by
     * filename is the only reliable way to recover it at read time.
     */
    private HashMap<String, JSONObject> loadDetectionMap(File sessionDir) {
        HashMap<String, JSONObject> map = new HashMap<>();
        File file = new File(sessionDir, "detection_log.jsonl");
        ArrayList<String> lines = readLines(file);
        for (String line : lines) {
            try {
                JSONObject json = new JSONObject(line);
                if (!"detection.layer".equals(json.optString("type", null))) {
                    continue;
                }
                String detId = json.optString("det_id", null);
                if (TextUtils.isEmpty(detId)) {
                    continue;
                }
                map.put(detId, json);
            } catch (Exception ignored) {
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

    private boolean containsVideoPath(JSONArray videos, String path) {
        for (int i = 0; i < videos.length(); i++) {
            JSONObject video = videos.optJSONObject(i);
            if (video != null && path.equals(video.optString("video_path"))) {
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

    private boolean containsInt(JSONArray array, int value) {
        if (array == null) {
            return false;
        }
        for (int i = 0; i < array.length(); i++) {
            if (array.optInt(i, Integer.MIN_VALUE) == value) {
                return true;
            }
        }
        return false;
    }

    private int parseClipId(String path) {
        if (TextUtils.isEmpty(path)) {
            return -1;
        }
        int idx = path.lastIndexOf("clip_");
        if (idx < 0) {
            return -1;
        }
        int start = idx + 5;
        int end = start;
        while (end < path.length() && Character.isDigit(path.charAt(end))) {
            end += 1;
        }
        if (end <= start) {
            return -1;
        }
        try {
            return Integer.parseInt(path.substring(start, end));
        } catch (Exception ignored) {
            return -1;
        }
    }

    /** Clip filenames embed the detection id, e.g. clip_000012_laughter_det_000003.wav (see JoyfulMomentController). */
    private String parseDetId(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        int idx = path.lastIndexOf("det_");
        if (idx < 0) {
            return null;
        }
        int end = idx + 4;
        while (end < path.length() && Character.isDigit(path.charAt(end))) {
            end += 1;
        }
        if (end <= idx + 4) {
            return null;
        }
        int dot = path.indexOf('.', end);
        return dot > 0 ? path.substring(idx, dot) : path.substring(idx, end);
    }
}
