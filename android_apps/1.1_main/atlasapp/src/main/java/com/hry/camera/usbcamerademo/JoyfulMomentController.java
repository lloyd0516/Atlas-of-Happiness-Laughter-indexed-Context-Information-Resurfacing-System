package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class JoyfulMomentController {
    public interface HostCallbacks {
        void onJoyfulStatusChanged(String text);
        void onJoyfulPromptRequested(String periodId);
        void onJoyfulAutoVideoRequested(int durationSec);
        void onJoyfulAutoPhotoRequested();
        void onJoyfulUsbAudioChunk(byte[] pcm16le, int byteLen, int sampleRate, int channelCount);
    }

    private static class ActiveDetection {
        String detId;
        double startSec;
        double confidence;
        JSONObject startedPayload;
        String channel;
    }

    private static class ClipState {
        int clipId;
        double startSec;
        double endSec;
        File tmpPath;
        boolean hasLaughter;
        boolean hasSpeech;
        boolean finalized;
        final List<String> detectionIds = new ArrayList<>();
        final List<Integer> relatedLaughterClipIds = new ArrayList<>();
        JoyfulMomentClusterer.PeriodRecord periodRecord;
    }

    private final Context context;
    private final HostCallbacks hostCallbacks;
    private final JoyfulMomentEventStore eventStore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private JoyfulMomentConfig config;
    private JoyfulMomentClusterer clusterer;
    private JoyfulMomentRealtimeEngine realtimeEngine;

    private String sessionId;
    private long sessionStartMs;
    private File sessionDir;
    private boolean sessionRunning;

    private final HashMap<Integer, ClipState> clipStates = new HashMap<>();
    private final HashMap<String, ActiveDetection> activeDetections = new HashMap<>();
    private final ArrayList<JoyfulMomentClusterer.DetectionRecord> detectionRecords = new ArrayList<>();
    private final ArrayList<JoyfulMomentClusterer.PeriodRecord> periodRecords = new ArrayList<>(); // legacy internal list; no longer emitted as an aggregation level
    private final HashMap<Integer, JoyfulMomentClusterer.EventRecord> eventRecords = new HashMap<>();
    private JoyfulMomentClusterer.EventRecord currentOpenEvent;
    private double currentEventLastDetectionStartSec = -1.0;
    private Runnable pendingEventFinalizeRunnable;
    private int nextDetectionNumber = 1;
    private int nextEventNumber = 0;
    private int latestClosedClipId = -1;
    private String lastTriggeredPeriodId;
    private String lastTriggeredEventId;
    private int lastAutomationBucketId = -1;

    public JoyfulMomentController(Context context, HostCallbacks hostCallbacks) {
        this.context = context.getApplicationContext();
        this.hostCallbacks = hostCallbacks;
        this.eventStore = new JoyfulMomentEventStore(context);
        this.config = JoyfulMomentConfig.load(context);
        this.clusterer = new JoyfulMomentClusterer(config);
    }

    public JoyfulMomentConfig getConfig() {
        return config;
    }

    public boolean isSessionRunning() {
        return sessionRunning;
    }

    public void updateConfig(JoyfulMomentConfig config) {
        this.config = config;
        this.clusterer = new JoyfulMomentClusterer(config);
        JoyfulMomentConfig.save(context, config);
        emitStatus(buildStatusText());
    }

    public synchronized void startSession() {
        if (sessionRunning) {
            return;
        }
        sessionId = eventStore.newSessionId();
        sessionDir = eventStore.createSessionDir(sessionId);
        sessionStartMs = System.currentTimeMillis();
        sessionRunning = true;
        latestClosedClipId = -1;
        nextDetectionNumber = 1;
        clipStates.clear();
        activeDetections.clear();
        detectionRecords.clear();
        periodRecords.clear();
        eventRecords.clear();
        currentOpenEvent = null;
        currentEventLastDetectionStartSec = -1.0;
        nextEventNumber = 0;
        cancelPendingEventFinalize();
        lastTriggeredPeriodId = null;
        lastTriggeredEventId = null;
        lastAutomationBucketId = -1;
        writeSessionSummary("started");

        realtimeEngine = new JoyfulMomentRealtimeEngine(
                context,
                config,
                sessionDir,
                JoyfulMomentConfig.getSpeechmaticsApiKey(context),
                JoyfulMomentConfig.getSpeechmaticsRtUrl(context),
                new JoyfulMomentRealtimeEngine.Listener() {
                    @Override
                    public void onSpeechmaticsMessage(JSONObject payload) {
                        handleSpeechmaticsPayload(payload);
                    }

                    @Override
                    public void onAudioChunkSent(double offsetSec, int byteLen) {
                        JSONObject json = new JSONObject();
                        try {
                            json.put("type", "audio.chunk.sent");
                            json.put("offset_sec", offsetSec);
                            json.put("byte_len", byteLen);
                        } catch (JSONException ignored) {
                        }
                        appendJson("speechmatics_raw.jsonl", json);
                    }

                    @Override
                    public void onAudioPcmChunk(byte[] pcm16le, int byteLen, int sampleRate, int channelCount) {
                        hostCallbacks.onJoyfulUsbAudioChunk(pcm16le, byteLen, sampleRate, channelCount);
                    }

                    @Override
                    public void onClipClosed(int clipId, double startSec, double endSec, File tmpPath) {
                        handleClipClosed(clipId, startSec, endSec, tmpPath);
                    }

                    @Override
                    public void onEngineInfo(JSONObject info) {
                        appendJson("speechmatics_raw.jsonl", info);
                    }

                    @Override
                    public void onEngineError(String errorText) {
                        JSONObject json = new JSONObject();
                        try {
                            json.put("type", "engine.error");
                            json.put("message", errorText);
                        } catch (JSONException ignored) {
                        }
                        appendJson("detection_log.jsonl", json);
                        emitStatus("Joyful error: " + errorText);
                    }

                    @Override
                    public void onEngineStopped() {
                        finalizeRemainingClips(true);
                        finalizeCurrentEvent("engine_stopped");
                        writeSessionSummary("engine_stopped");
                        emitStatus(buildStatusText());
                    }
                }
        );
        realtimeEngine.start();
        emitStatus(buildStatusText());
    }

    public synchronized void stopSession() {
        if (!sessionRunning) {
            return;
        }
        sessionRunning = false;
        if (realtimeEngine != null) {
            JoyfulMomentRealtimeEngine engine = realtimeEngine;
            realtimeEngine = null;
            engine.stop();
        } else {
            finalizeRemainingClips(true);
        }
        finalizeCurrentEvent("session_stopped");
        writeSessionSummary("stopped");
        emitStatus(buildStatusText());
    }

    public synchronized void debugSimulateLaughterNow() {
        if (!sessionRunning) {
            startSession();
        }
        long elapsedSec = Math.max(0, (System.currentTimeMillis() - sessionStartMs) / 1000L);
        double startSec = elapsedSec;
        double endSec = startSec + 2.5;
        JoyfulMomentClusterer.DetectionRecord record = createDetectionRecord(startSec, endSec, 0.88, "debug");
        applyDetectionRecord(record);
        int clipId = (int) (startSec / config.clipDurationSec);
        ClipState clipState = ensureClipState(clipId, clipId * config.clipDurationSec, (clipId + 1) * config.clipDurationSec, new File(sessionDir, "debug.wav"));
        clipState.hasSpeech = true;
        latestClosedClipId = Math.max(latestClosedClipId, clipId);
        finalizeClipIfReady(clipState, true);
    }

    private synchronized void handleSpeechmaticsPayload(JSONObject payload) {
        JSONObject raw = new JSONObject();
        try {
            raw.put("type", "speechmatics.message");
            raw.put("recv_device_time_ms", System.currentTimeMillis());
            raw.put("payload", payload);
        } catch (JSONException ignored) {
        }
        appendJson("detection_log.jsonl", raw);

        String message = payload.optString("message", "");
        if ("AudioEventStarted".equals(message) || "AudioEventEnded".equals(message)) {
            JSONObject event = payload.optJSONObject("event");
            if (event != null && "laughter".equals(event.optString("type"))) {
                handleLaughterEvent(message, payload, event);
            }
        }
        if (message.contains("Transcript")) {
            List<double[]> ranges = extractSpeechRanges(payload);
            for (double[] range : ranges) {
                markSpeechRange(range[0], range[1]);
                JSONObject speechJson = new JSONObject();
                try {
                    speechJson.put("type", "detection.speech.range");
                    speechJson.put("start_sec", range[0]);
                    speechJson.put("end_sec", range[1]);
                } catch (JSONException ignored) {
                }
                appendJson("detection_log.jsonl", speechJson);
            }
        }
    }

    private void handleLaughterEvent(String messageType, JSONObject payload, JSONObject event) {
        String channel = payload.optString("channel", "default");
        if ("AudioEventStarted".equals(messageType)) {
            ActiveDetection active = new ActiveDetection();
            active.detId = String.format(Locale.US, "det_%06d", nextDetectionNumber++);
            active.startSec = event.optDouble("start_time", 0.0);
            active.confidence = event.optDouble("confidence", 0.0);
            active.startedPayload = payload;
            active.channel = channel;
            activeDetections.put(channel, active);

            JSONObject edge = new JSONObject();
            try {
                edge.put("type", "detection.edge.started");
                edge.put("det_id", active.detId);
                edge.put("start_sec", active.startSec);
                edge.put("confidence", active.confidence);
                edge.put("channel", channel);
            } catch (JSONException ignored) {
            }
            appendJson("detection_log.jsonl", edge);
            return;
        }

        ActiveDetection active = activeDetections.remove(channel);
        double startSec = event.optDouble("start_time", active != null ? active.startSec : 0.0);
        double endSec = event.optDouble("end_time", startSec);
        double confidence = event.optDouble("confidence", active != null ? active.confidence : 0.0);
        double confidenceThreshold = config.laughterConfidenceThreshold();
        double durationSec = Math.max(0.0, endSec - startSec);
        if (confidence < confidenceThreshold) {
            JSONObject rejected = new JSONObject();
            try {
                rejected.put("type", "detection.rejected");
                rejected.put("reason", "confidence_below_threshold");
                rejected.put("threshold", confidenceThreshold);
                rejected.put("start_sec", startSec);
                rejected.put("end_sec", endSec);
                rejected.put("duration_sec", durationSec);
                rejected.put("confidence", confidence);
                rejected.put("channel", channel);
            } catch (JSONException ignored) {
            }
            appendJson("detection_log.jsonl", rejected);
            return;
        }
        double minDurationSec = config.laughterMinDurationSec();
        if (minDurationSec > 0.0 && durationSec < minDurationSec) {
            JSONObject rejected = new JSONObject();
            try {
                rejected.put("type", "detection.rejected");
                rejected.put("reason", "duration_below_threshold");
                rejected.put("threshold_sec", minDurationSec);
                rejected.put("start_sec", startSec);
                rejected.put("end_sec", endSec);
                rejected.put("duration_sec", durationSec);
                rejected.put("confidence", confidence);
                rejected.put("channel", channel);
            } catch (JSONException ignored) {
            }
            appendJson("detection_log.jsonl", rejected);
            return;
        }
        JoyfulMomentClusterer.DetectionRecord record = createDetectionRecord(
                startSec,
                endSec < startSec ? startSec : endSec,
                confidence,
                channel
        );
        if (active != null) {
            record.detId = active.detId;
        }
        applyDetectionRecord(record);
    }

    private synchronized void handleClipClosed(int clipId, double startSec, double endSec, File tmpPath) {
        ClipState clipState = ensureClipState(clipId, startSec, endSec, tmpPath);
        clipState.tmpPath = tmpPath;
        latestClosedClipId = Math.max(latestClosedClipId, clipId);
        if (clipState.hasLaughter) {
            finalizeClipIfReady(clipState, true);
        }
        finalizeRemainingClips(false);
    }

    private JoyfulMomentClusterer.DetectionRecord createDetectionRecord(double startSec, double endSec, double confidence, String channel) {
        JoyfulMomentClusterer.DetectionRecord record =
                clusterer.newDetection(System.currentTimeMillis(), startSec, endSec, confidence, channel);
        return record;
    }

    private void applyDetectionRecord(JoyfulMomentClusterer.DetectionRecord record) {
        detectionRecords.add(record);
        int startClipId = (int) (record.startSec / config.clipDurationSec);
        int endClipId = (int) (record.endSec / config.clipDurationSec);
        for (int clipId = startClipId; clipId <= endClipId; clipId++) {
            double clipStart = clipId * config.clipDurationSec;
            double clipEnd = clipStart + config.clipDurationSec;
            if (overlaps(record.startSec, record.endSec, clipStart, clipEnd)) {
                ClipState clipState = ensureClipState(clipId, clipStart, clipEnd, null);
                clipState.hasLaughter = true;
                if (!clipState.detectionIds.contains(record.detId)) {
                    clipState.detectionIds.add(record.detId);
                }
            }
        }
        appendJson("detection_log.jsonl", safeJson(record));
        assignDetectionToEvent(record);
    }

    private void markSpeechRange(double startSec, double endSec) {
        int startClipId = (int) (startSec / config.clipDurationSec);
        int endClipId = (int) (endSec / config.clipDurationSec);
        for (int clipId = startClipId; clipId <= endClipId; clipId++) {
            double clipStart = clipId * config.clipDurationSec;
            double clipEnd = clipStart + config.clipDurationSec;
            if (overlaps(startSec, endSec, clipStart, clipEnd)) {
                ClipState clipState = ensureClipState(clipId, clipStart, clipEnd, null);
                clipState.hasSpeech = true;
            }
        }
    }

    private synchronized void finalizeRemainingClips(boolean forceAll) {
        ArrayList<Integer> clipIds = new ArrayList<>(clipStates.keySet());
        java.util.Collections.sort(clipIds);
        for (Integer clipId : clipIds) {
            ClipState clipState = clipStates.get(clipId);
            if (clipState == null || clipState.finalized) {
                continue;
            }
            if (!forceAll && clipId > latestClosedClipId - config.contextNeighborClips && !clipState.hasLaughter) {
                continue;
            }
            finalizeClipIfReady(clipState, forceAll || clipState.hasLaughter);
        }
    }

    private void finalizeClipIfReady(ClipState clipState, boolean force) {
        if (clipState.finalized) {
            return;
        }
        String label = "none";
        clipState.relatedLaughterClipIds.clear();

        if (clipState.hasLaughter) {
            label = "laughter";
        } else if (clipState.hasSpeech) {
            for (ClipState other : clipStates.values()) {
                if (other != null && other.hasLaughter && other.clipId != clipState.clipId
                        && Math.abs(other.clipId - clipState.clipId) <= config.contextNeighborClips) {
                    clipState.relatedLaughterClipIds.add(other.clipId);
                }
            }
            if (!clipState.relatedLaughterClipIds.isEmpty()) {
                label = "possible_related_speech_context";
            } else if (!force) {
                return;
            }
        } else if (!force && clipState.clipId > latestClosedClipId - config.contextNeighborClips) {
            return;
        }

        String savedPath = null;
        if (!"none".equals(label) && clipState.tmpPath != null) {
            File clipsDir = new File(sessionDir, "clips");
            if (!clipsDir.exists()) {
                clipsDir.mkdirs();
            }
            File saved = new File(clipsDir, String.format(Locale.US, "clip_%06d_%s.wav", clipState.clipId, label));
            if (clipState.tmpPath.exists()) {
                //noinspection ResultOfMethodCallIgnored
                clipState.tmpPath.renameTo(saved);
                savedPath = saved.getAbsolutePath();
            }
        } else if (clipState.tmpPath != null && clipState.tmpPath.exists()) {
            //noinspection ResultOfMethodCallIgnored
            clipState.tmpPath.delete();
        }

        clipState.finalized = true;
        if (!"none".equals(label)) {
            attachClipToEvent(clipState, label, savedPath);
        }
        writeSessionSummary("running");
        emitStatus(buildStatusText());
    }

    private void assignDetectionToEvent(JoyfulMomentClusterer.DetectionRecord record) {
        if (currentOpenEvent != null
                && currentEventLastDetectionStartSec >= 0.0
                && record.startSec - currentEventLastDetectionStartSec > config.eventWindowSec) {
            finalizeCurrentEvent("gap_exceeded");
        }
        if (currentOpenEvent == null || currentOpenEvent.finalized) {
            currentOpenEvent = clusterer.buildEvent(sessionStartMs, nextEventNumber++, record.startSec, record.endSec);
            eventRecords.put(currentOpenEvent.eventIndex, currentOpenEvent);
            currentEventLastDetectionStartSec = record.startSec;
        } else {
            currentOpenEvent.endSec = Math.max(currentOpenEvent.endSec, record.endSec);
            currentOpenEvent.deviceEndMs = sessionStartMs + Math.round(currentOpenEvent.endSec * 1000.0);
            currentEventLastDetectionStartSec = record.startSec;
        }
        if (!currentOpenEvent.detectionIds.contains(record.detId)) {
            currentOpenEvent.detectionIds.add(record.detId);
        }
        lastTriggeredEventId = currentOpenEvent.eventId;
        appendJson("event_log.jsonl", safeJson(currentOpenEvent));
        eventStore.writeJson(new File(sessionDir, currentOpenEvent.eventId + ".json"), safeJson(currentOpenEvent));
        scheduleCurrentEventFinalize(currentOpenEvent.eventId);
        triggerAutomationForDetectionIfNewClip(record, currentOpenEvent.eventId);
    }

    private void scheduleCurrentEventFinalize(final String eventId) {
        cancelPendingEventFinalize();
        pendingEventFinalizeRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (JoyfulMomentController.this) {
                    if (currentOpenEvent != null && eventId.equals(currentOpenEvent.eventId)) {
                        finalizeCurrentEvent("timeout_no_new_laughter");
                    }
                }
            }
        };
        mainHandler.postDelayed(pendingEventFinalizeRunnable, Math.max(1, config.eventWindowSec) * 1000L);
    }

    private void cancelPendingEventFinalize() {
        if (pendingEventFinalizeRunnable != null) {
            mainHandler.removeCallbacks(pendingEventFinalizeRunnable);
            pendingEventFinalizeRunnable = null;
        }
    }

    private void finalizeCurrentEvent(String reason) {
        cancelPendingEventFinalize();
        if (currentOpenEvent == null || currentOpenEvent.finalized) {
            currentOpenEvent = null;
            return;
        }
        currentOpenEvent.finalized = true;
        currentOpenEvent.deviceEndMs = sessionStartMs + Math.round(currentOpenEvent.endSec * 1000.0);
        lastTriggeredEventId = currentOpenEvent.eventId;
        lastTriggeredPeriodId = null;
        JSONObject status = new JSONObject();
        try {
            status.put("type", "event.finalized");
            status.put("event_id", currentOpenEvent.eventId);
            status.put("reason", reason);
            status.put("end_sec", currentOpenEvent.endSec);
        } catch (JSONException ignored) {
        }
        appendJson("detection_log.jsonl", status);
        appendJson("event_log.jsonl", safeJson(currentOpenEvent));
        eventStore.writeJson(new File(sessionDir, currentOpenEvent.eventId + ".json"), safeJson(currentOpenEvent));

        final String finalEventId = currentOpenEvent.eventId;
        refreshContextForFinalizedEvent(finalEventId);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                hostCallbacks.onJoyfulPromptRequested(finalEventId);
            }
        });
        currentOpenEvent = null;
        currentEventLastDetectionStartSec = -1.0;
    }

    private void refreshContextForFinalizedEvent(final String eventId) {
        final AtlasReviewRepository repository = new AtlasReviewRepository(context);
        AtlasContextResolver.refreshContext(context, repository, new AtlasContextResolver.Callback() {
            @Override
            public void onResolved(Double lat, Double lng, Double amapLat, Double amapLng, Float accuracyMeters, Long timestampMs, String locationName, String adcode, String weatherCondition, Double temperature) {
                JSONObject eventJson = repository.loadEventById(eventId);
                boolean saved = eventJson != null && repository.updateDerivedContext(eventJson, lat, lng, amapLat, amapLng, accuracyMeters, timestampMs, locationName, adcode, weatherCondition, temperature);
                JSONObject status = new JSONObject();
                try {
                    status.put("type", "context.auto_resolved");
                    status.put("event_id", eventId);
                    status.put("saved", saved);
                    status.put("has_location", lat != null && lng != null);
                    status.put("has_address", locationName != null && locationName.length() > 0);
                    status.put("has_weather", weatherCondition != null && weatherCondition.length() > 0);
                    status.put("adcode", adcode);
                } catch (JSONException ignored) {
                }
                appendJson("detection_log.jsonl", status);
            }

            @Override
            public void onFailed(String reason) {
                JSONObject status = new JSONObject();
                try {
                    status.put("type", "context.auto_resolve_failed");
                    status.put("event_id", eventId);
                    status.put("reason", reason);
                } catch (JSONException ignored) {
                }
                appendJson("detection_log.jsonl", status);
            }
        });
    }

    private void triggerAutomationForDetectionIfNewClip(JoyfulMomentClusterer.DetectionRecord record, String eventId) {
        int automationBucketSec = Math.max(1, config.clipDurationSec) * 2;
        int automationBucketId = (int) (record.startSec / automationBucketSec);
        if (automationBucketId == lastAutomationBucketId) {
            JSONObject skipped = new JSONObject();
            try {
                skipped.put("type", "automation.skipped_same_2clip_window");
                skipped.put("event_id", eventId);
                skipped.put("det_id", record.detId);
                skipped.put("automation_bucket_id", automationBucketId);
                skipped.put("automation_bucket_sec", automationBucketSec);
                skipped.put("clip_duration_sec", config.clipDurationSec);
            } catch (JSONException ignored) {
            }
            appendJson("detection_log.jsonl", skipped);
            return;
        }
        lastAutomationBucketId = automationBucketId;
        triggerAutomationForDetection(eventId, automationBucketId, automationBucketSec);
    }

    private void triggerAutomationForDetection(String eventId, int automationBucketId, int automationBucketSec) {
        lastTriggeredEventId = eventId;
        lastTriggeredPeriodId = null;
        final int finalTriggerVideoDurationSec = config.triggerVideoDurationSec;
        JSONObject status = new JSONObject();
        try {
            status.put("type", "automation.triggered_by_detection");
            status.put("event_id", eventId);
            status.put("auto_video_duration_sec", finalTriggerVideoDurationSec);
            status.put("auto_photo_count", config.triggerPhotoCount);
            status.put("automation_bucket_id", automationBucketId);
            status.put("automation_bucket_sec", automationBucketSec);
            status.put("clip_duration_sec", config.clipDurationSec);
        } catch (JSONException ignored) {
        }
        appendJson("detection_log.jsonl", status);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                hostCallbacks.onJoyfulAutoVideoRequested(finalTriggerVideoDurationSec);
            }
        });
        scheduleAutoPhotos();
    }

    private void attachClipToEvent(ClipState clipState, String label, String savedPath) {
        JoyfulMomentClusterer.EventRecord eventRecord = findEventForClip(clipState);
        if (eventRecord == null) {
            return;
        }
        if ("laughter".equals(label) && !eventRecord.laughterClipIds.contains(clipState.clipId)) {
            eventRecord.laughterClipIds.add(clipState.clipId);
        }
        if ("possible_related_speech_context".equals(label) && !eventRecord.contextClipIds.contains(clipState.clipId)) {
            eventRecord.contextClipIds.add(clipState.clipId);
        }
        addUniqueAll(eventRecord.detectionIds, clipState.detectionIds);
        if (savedPath != null && !eventRecord.savedClipPaths.contains(savedPath)) {
            eventRecord.savedClipPaths.add(savedPath);
        }
        appendJson("event_log.jsonl", safeJson(eventRecord));
        eventStore.writeJson(new File(sessionDir, eventRecord.eventId + ".json"), safeJson(eventRecord));
    }

    private JoyfulMomentClusterer.EventRecord findEventForClip(ClipState clipState) {
        double centerSec = (clipState.startSec + clipState.endSec) / 2.0;
        double contextWindowSec = Math.max(0, config.contextNeighborClips) * config.clipDurationSec;
        JoyfulMomentClusterer.EventRecord best = currentOpenEvent;
        for (JoyfulMomentClusterer.EventRecord record : eventRecords.values()) {
            if (record == null) {
                continue;
            }
            if (centerSec >= record.startSec - contextWindowSec && centerSec <= record.endSec + contextWindowSec) {
                best = record;
            }
        }
        return best;
    }

    private void addUniqueAll(List<String> dst, List<String> src) {
        for (String item : src) {
            if (!dst.contains(item)) {
                dst.add(item);
            }
        }
    }

    private ClipState ensureClipState(int clipId, double startSec, double endSec, File tmpPath) {
        ClipState state = clipStates.get(clipId);
        if (state == null) {
            state = new ClipState();
            state.clipId = clipId;
            state.startSec = startSec;
            state.endSec = endSec;
            state.tmpPath = tmpPath;
            clipStates.put(clipId, state);
        } else if (tmpPath != null) {
            state.tmpPath = tmpPath;
        }
        return state;
    }

    private boolean overlaps(double aStart, double aEnd, double bStart, double bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    private List<double[]> extractSpeechRanges(JSONObject payload) {
        ArrayList<double[]> ranges = new ArrayList<>();
        JSONArray results = payload.optJSONArray("results");
        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                if (item.has("start_time") && item.has("end_time")) {
                    ranges.add(new double[] {item.optDouble("start_time"), item.optDouble("end_time")});
                }
            }
        }
        if (payload.has("start_time") && payload.has("end_time")) {
            ranges.add(new double[] {payload.optDouble("start_time"), payload.optDouble("end_time")});
        }
        return ranges;
    }

    private void scheduleAutoPhotos() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hostCallbacks.onJoyfulAutoPhotoRequested();
            }
        }, 1500L);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hostCallbacks.onJoyfulAutoPhotoRequested();
            }
        }, 3500L);
    }

    public synchronized void onAutoVideoCaptureStarted() {
        JSONObject json = new JSONObject();
        try {
            json.put("type", "asset.auto_video.started");
            json.put("event_id", lastTriggeredEventId);
        } catch (JSONException ignored) {
        }
        appendJson("detection_log.jsonl", json);
    }

    public synchronized void onAutoVideoCaptureSkipped(String reason) {
        appendAssetStatus("asset.auto_video.skipped", reason, null);
    }

    public synchronized void onAutoPhotoCaptureSkipped(String reason) {
        appendAssetStatus("asset.auto_photo.skipped", reason, null);
    }

    public synchronized void onAutoVideoSaved(String path, String contentUri) {
        if (path == null) {
            appendAssetStatus("asset.auto_video.save_failed", "missing_path", null);
            return;
        }
        String stablePath = copyAssetIntoSession(path, "videos", "event_video");
        if (stablePath == null) {
            stablePath = path;
        }
        JoyfulMomentClusterer.EventRecord eventRecord = findEventById(lastTriggeredEventId);
        if (eventRecord != null) {
            eventRecord.videoPath = stablePath;
            eventRecord.videoContentUri = contentUri;
            if (!eventRecord.videoPaths.contains(stablePath)) {
                eventRecord.videoPaths.add(stablePath);
                eventRecord.videoContentUris.add(contentUri);
            }
            appendJson("event_log.jsonl", safeJson(eventRecord));
            eventStore.writeJson(new File(sessionDir, eventRecord.eventId + ".json"), safeJson(eventRecord));
        }
        appendAssetStatus("asset.auto_video.saved", "ok", stablePath);
    }

    public synchronized void onAutoPhotoSaved(String path) {
        if (path == null) {
            appendAssetStatus("asset.auto_photo.save_failed", "missing_path", null);
            return;
        }
        String stablePath = copyAssetIntoSession(path, "photos", "event_photo");
        if (stablePath == null) {
            stablePath = path;
        }
        JoyfulMomentClusterer.EventRecord eventRecord = findEventById(lastTriggeredEventId);
        if (eventRecord != null && !eventRecord.photoPaths.contains(stablePath)) {
            eventRecord.photoPaths.add(stablePath);
            appendJson("event_log.jsonl", safeJson(eventRecord));
            eventStore.writeJson(new File(sessionDir, eventRecord.eventId + ".json"), safeJson(eventRecord));
        }
        appendAssetStatus("asset.auto_photo.saved", "ok", stablePath);
    }

    private String copyAssetIntoSession(String sourcePath, String folderName, String prefix) {
        if (sessionDir == null || sourcePath == null) {
            return null;
        }
        File source = new File(sourcePath);
        if (!source.exists() || !source.isFile()) {
            return null;
        }
        String eventId = lastTriggeredEventId != null ? lastTriggeredEventId : "unlinked";
        File dstDir = new File(sessionDir, "captured_media/" + eventId + "/" + folderName);
        if (!dstDir.exists()) {
            dstDir.mkdirs();
        }
        String extension = "";
        int dot = source.getName().lastIndexOf('.');
        if (dot >= 0) {
            extension = source.getName().substring(dot);
        }
        File dst = new File(dstDir, prefix + "_" + System.currentTimeMillis() + extension);
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(dst, false);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return dst.getAbsolutePath();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void appendAssetStatus(String type, String reason, String path) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", type);
            json.put("event_id", lastTriggeredEventId);
            json.put("reason", reason);
            if (path != null) {
                json.put("path", path);
            }
        } catch (JSONException ignored) {
        }
        appendJson("detection_log.jsonl", json);
    }

    private JoyfulMomentClusterer.PeriodRecord findPeriodById(String periodId) {
        if (periodId == null) {
            return null;
        }
        for (JoyfulMomentClusterer.PeriodRecord record : periodRecords) {
            if (periodId.equals(record.periodId)) {
                return record;
            }
        }
        return null;
    }

    private JoyfulMomentClusterer.EventRecord findEventById(String eventId) {
        if (eventId == null) {
            return null;
        }
        for (JoyfulMomentClusterer.EventRecord record : eventRecords.values()) {
            if (eventId.equals(record.eventId)) {
                return record;
            }
        }
        return null;
    }

    private void appendJson(String fileName, JSONObject json) {
        if (sessionDir == null) {
            return;
        }
        eventStore.appendJsonLine(new File(sessionDir, fileName), json);
    }

    private void writeSessionSummary(String state) {
        if (sessionDir == null) {
            return;
        }
        JSONObject json = new JSONObject();
        try {
            json.put("session_id", sessionId);
            json.put("state", state);
            json.put("session_start_ms", sessionStartMs);
            json.put("config", config.toJson());
            json.put("detection_count", detectionRecords.size());
            json.put("aggregation_levels", "detection,event");
            json.put("event_gap_threshold_sec", config.eventWindowSec);
            json.put("confidence_threshold", config.laughterConfidenceThreshold());
            json.put("laughter_min_duration_sec", config.laughterMinDurationSec());
            json.put("event_count", eventRecords.size());
            json.put("clip_count_total", clipStates.size());
        } catch (JSONException ignored) {
        }
        eventStore.writeJson(new File(sessionDir, "summary.json"), json);
    }

    private int countPeriodsByLabel(String label) {
        int count = 0;
        for (JoyfulMomentClusterer.PeriodRecord record : periodRecords) {
            if (label.equals(record.label)) {
                count += 1;
            }
        }
        return count;
    }

    public synchronized String getLastTriggeredEventId() {
        return lastTriggeredEventId;
    }

    public synchronized String getLastTriggeredPeriodId() {
        return lastTriggeredPeriodId;
    }

    private JSONObject safeJson(JoyfulMomentClusterer.DetectionRecord record) {
        try {
            return record.toJson();
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private JSONObject safeJson(JoyfulMomentClusterer.PeriodRecord record) {
        try {
            return record.toJson();
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private JSONObject safeJson(JoyfulMomentClusterer.EventRecord record) {
        try {
            return record.toJson();
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private void emitStatus(final String text) {
        hostCallbacks.onJoyfulStatusChanged(text);
    }

    private String buildStatusText() {
        String sessionText = sessionRunning ? "running" : "idle";
        return "Joyful: " + sessionText + " / " + config.toSummaryText()
                + " / det=" + detectionRecords.size()
                + " event=" + eventRecords.size();
    }
}
