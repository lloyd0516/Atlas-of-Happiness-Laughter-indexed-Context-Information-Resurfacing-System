package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class JoyfulMomentController {
    private static final int CONTEXT_AUTO_MAX_ATTEMPTS = AppConfig.CONTEXT_AUTO_MAX_ATTEMPTS;
    private static final long CONTEXT_NEARBY_BACKFILL_WINDOW_MS = AppConfig.CONTEXT_NEARBY_BACKFILL_WINDOW_MS;
    private static final double LAUGHTER_AUDIO_PADDING_SEC = AppConfig.LAUGHTER_AUDIO_PADDING_SEC;

    public interface HostCallbacks {
        void onJoyfulStatusChanged(String text);
        void onJoyfulPromptRequested(String periodId);
        void onJoyfulAutoVideoRequested(
                AtlasCaptureBundleRequest request);
        void onJoyfulAutoPhotoRequested(
                AtlasCaptureBundleRequest.PhotoRequest request);
        void onJoyfulUsbAudioChunk(byte[] pcm16le, int byteLen, int sampleRate, int channelCount);
        /** Requirement 1: recording page shows the wall-clock time of the most recent accepted laughter. */
        void onLastLaughterDetected(long timestampMs);
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
        final List<JoyfulMomentClusterer.DetectionRecord> laughterDetections = new ArrayList<>();
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
    private String participantNumber = "00";
    private long sessionStartMs;
    private long sessionStartElapsedMs;
    private File sessionDir;
    private volatile boolean sessionRunning;

    private final HashMap<Integer, ClipState> clipStates = new HashMap<>();
    private final HashMap<String, ActiveDetection> activeDetections = new HashMap<>();
    private final ArrayList<JoyfulMomentClusterer.DetectionRecord> detectionRecords = new ArrayList<>();
    private final ArrayList<JoyfulMomentClusterer.PeriodRecord> periodRecords = new ArrayList<>(); // legacy internal list; no longer emitted as an aggregation level
    private final HashMap<Integer, JoyfulMomentClusterer.EventRecord> eventRecords = new HashMap<>();
    private final HashSet<String> contextResolveRequestedEventIds = new HashSet<>();
    private JoyfulMomentClusterer.EventRecord currentOpenEvent;
    private double currentEventLastDetectionStartSec = -1.0;
    private Runnable pendingEventFinalizeRunnable;
    private int nextDetectionNumber = 1;
    private int nextEventNumber = 0;
    private int nextParticipantEventNumber = 1;
    private int latestClosedClipId = -1;
    private String lastTriggeredPeriodId;
    private String lastTriggeredEventId;
    private int lastAutomationBucketId = -1;
    private volatile long lastLaughterDetectedAtMs = 0L;

    /** Wall-clock time of the most recently accepted laughter detection, or 0 if none this app run. */
    public long getLastLaughterDetectedAtMs() {
        return lastLaughterDetectedAtMs;
    }

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

    /** Requirement 2: lets the host list "which events came from the session that was just stopped". */
    public synchronized String getSessionId() {
        return sessionId;
    }

    public void updateConfig(JoyfulMomentConfig config) {
        this.config = config;
        this.clusterer = new JoyfulMomentClusterer(config);
        JoyfulMomentConfig.save(context, config);
        emitStatus(buildStatusText());
    }

    public synchronized void startSession() {
        startSession("00");
    }

    public synchronized void startSession(String requestedParticipantNumber) {
        if (sessionRunning) {
            return;
        }
        participantNumber = normalizeParticipantNumber(requestedParticipantNumber);
        sessionId = eventStore.newSessionId();
        sessionDir = eventStore.createSessionDir(sessionId);
        sessionStartMs = System.currentTimeMillis();
        sessionStartElapsedMs = SystemClock.elapsedRealtime();
        sessionRunning = true;
        latestClosedClipId = -1;
        nextDetectionNumber = 1;
        clipStates.clear();
        activeDetections.clear();
        detectionRecords.clear();
        periodRecords.clear();
        eventRecords.clear();
        contextResolveRequestedEventIds.clear();
        currentOpenEvent = null;
        currentEventLastDetectionStartSec = -1.0;
        nextEventNumber = 0;
        nextParticipantEventNumber = computeNextParticipantEventNumber(participantNumber);
        cancelPendingEventFinalize();
        lastTriggeredPeriodId = null;
        lastTriggeredEventId = null;
        lastAutomationBucketId = -1;
        lastLaughterDetectedAtMs = 0L;
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
                        if (sessionRunning) {
                            sessionRunning = false;
                            ResearchSessionTracker.stop(
                                    context,
                                    sessionId,
                                    "engine_stopped",
                                    detectionRecords.size(),
                                    eventRecords.size(),
                                    sessionStartElapsedMs,
                                    SystemClock.elapsedRealtime());
                        }
                        emitStatus(buildStatusText());
                    }
                }
        );
        realtimeEngine.start();
        ResearchSessionTracker.start(
                context,
                participantNumber,
                sessionId,
                sessionStartMs,
                sessionStartElapsedMs);
        emitStatus(buildStatusText());
    }

    public synchronized void stopSession() {
        stopSession("unspecified");
    }

    public synchronized void stopSession(String stopReason) {
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
        ResearchSessionTracker.stop(
                context,
                sessionId,
                stopReason,
                detectionRecords.size(),
                eventRecords.size(),
                sessionStartElapsedMs,
                SystemClock.elapsedRealtime());
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
        clipState.startSec = startSec;
        clipState.endSec = endSec;
        latestClosedClipId = Math.max(latestClosedClipId, clipId);
        finalizeRemainingClips(false);
    }

    private JoyfulMomentClusterer.DetectionRecord createDetectionRecord(double startSec, double endSec, double confidence, String channel) {
        JoyfulMomentClusterer.DetectionRecord record =
                clusterer.newDetection(System.currentTimeMillis(), startSec, endSec, confidence, channel);
        return record;
    }

    private void applyDetectionRecord(JoyfulMomentClusterer.DetectionRecord record) {
        detectionRecords.add(record);
        lastLaughterDetectedAtMs = record.deviceTimeMs;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                hostCallbacks.onLastLaughterDetected(lastLaughterDetectedAtMs);
            }
        });
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
                if (!containsDetection(clipState.laughterDetections, record.detId)) {
                    clipState.laughterDetections.add(record);
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
            finalizeClipIfReady(clipState, forceAll);
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
        if ("laughter".equals(label) && !force && !isLaughterWindowAvailable(clipState)) {
            return;
        }

        ArrayList<String> savedPaths = new ArrayList<>();
        if (!"none".equals(label) && clipState.tmpPath != null) {
            File clipsDir = new File(sessionDir, "clips");
            if (!clipsDir.exists()) {
                clipsDir.mkdirs();
            }
            if ("laughter".equals(label)) {
                savedPaths.addAll(saveLaughterWindows(clipState, clipsDir, force));
            } else {
                File saved = new File(clipsDir, String.format(Locale.US, "clip_%06d_%s.wav", clipState.clipId, label));
                if (clipState.tmpPath.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    clipState.tmpPath.renameTo(saved);
                    clipState.tmpPath = saved;
                    savedPaths.add(saved.getAbsolutePath());
                }
            }
        }

        clipState.finalized = true;
        if (!"none".equals(label)) {
            if (savedPaths.isEmpty()) {
                attachClipToEvent(clipState, label, null);
            } else {
                for (String savedPath : savedPaths) {
                    attachClipToEvent(clipState, label, savedPath);
                }
            }
        }
        writeSessionSummary("running");
        emitStatus(buildStatusText());
    }

    private boolean containsDetection(List<JoyfulMomentClusterer.DetectionRecord> records, String detId) {
        for (JoyfulMomentClusterer.DetectionRecord record : records) {
            if (record != null && detId != null && detId.equals(record.detId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLaughterWindowAvailable(ClipState clipState) {
        double latestRecordedEndSec = latestClosedRecordingEndSec();
        for (JoyfulMomentClusterer.DetectionRecord record : clipState.laughterDetections) {
            if (record != null && record.endSec + LAUGHTER_AUDIO_PADDING_SEC > latestRecordedEndSec) {
                return false;
            }
        }
        return !clipState.laughterDetections.isEmpty();
    }

    private double latestClosedRecordingEndSec() {
        double latestEndSec = 0.0;
        for (ClipState state : clipStates.values()) {
            if (state != null && state.tmpPath != null && state.tmpPath.exists()) {
                latestEndSec = Math.max(latestEndSec, state.endSec);
            }
        }
        return latestEndSec;
    }

    private ArrayList<String> saveLaughterWindows(ClipState clipState, File clipsDir, boolean force) {
        ArrayList<String> savedPaths = new ArrayList<>();
        double actualRecordingEndSec = latestClosedRecordingEndSec();
        for (JoyfulMomentClusterer.DetectionRecord record : clipState.laughterDetections) {
            if (record == null) {
                continue;
            }
            double windowStartSec = Math.max(0.0, record.startSec - LAUGHTER_AUDIO_PADDING_SEC);
            double requestedEndSec = Math.max(record.endSec, record.startSec) + LAUGHTER_AUDIO_PADDING_SEC;
            double windowEndSec = force ? Math.min(requestedEndSec, actualRecordingEndSec) : requestedEndSec;
            if (windowEndSec <= windowStartSec) {
                continue;
            }
            ArrayList<JoyfulMomentWavClipWriter.SourceClip> sources = sourceClipsForWindow(windowStartSec, windowEndSec);
            File saved = new File(clipsDir, String.format(Locale.US, "clip_%06d_%s_%s.wav", clipState.clipId, "laughter", record.detId));
            try {
                if (JoyfulMomentWavClipWriter.writeWindow(saved, sources, windowStartSec, windowEndSec)) {
                    savedPaths.add(saved.getAbsolutePath());
                    appendLaughterClipSaved(record, saved, windowStartSec, windowEndSec);
                }
            } catch (Exception e) {
                appendLaughterClipSaveFailed(record, saved, e.getMessage());
            }
        }
        return savedPaths;
    }

    private ArrayList<JoyfulMomentWavClipWriter.SourceClip> sourceClipsForWindow(double windowStartSec, double windowEndSec) {
        ArrayList<JoyfulMomentWavClipWriter.SourceClip> sources = new ArrayList<>();
        int startClipId = Math.max(0, (int) Math.floor(windowStartSec / Math.max(1, config.clipDurationSec)));
        int endClipId = Math.max(startClipId, (int) Math.floor(Math.max(windowStartSec, windowEndSec - 0.001) / Math.max(1, config.clipDurationSec)));
        for (int clipId = startClipId; clipId <= endClipId; clipId++) {
            ClipState source = clipStates.get(clipId);
            if (source != null && source.tmpPath != null && source.tmpPath.exists()) {
                sources.add(new JoyfulMomentWavClipWriter.SourceClip(source.tmpPath, source.startSec, source.endSec));
            }
        }
        return sources;
    }

    private void appendLaughterClipSaved(JoyfulMomentClusterer.DetectionRecord record, File saved, double windowStartSec, double windowEndSec) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", "audio.laughter_window.saved");
            json.put("det_id", record.detId);
            json.put("laughter_start_sec", record.startSec);
            json.put("laughter_end_sec", record.endSec);
            json.put("window_start_sec", windowStartSec);
            json.put("window_end_sec", windowEndSec);
            json.put("path", saved.getAbsolutePath());
        } catch (JSONException ignored) {
        }
        appendJson("detection_log.jsonl", json);
    }

    private void appendLaughterClipSaveFailed(JoyfulMomentClusterer.DetectionRecord record, File saved, String reason) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", "audio.laughter_window.save_failed");
            json.put("det_id", record == null ? JSONObject.NULL : record.detId);
            json.put("path", saved == null ? JSONObject.NULL : saved.getAbsolutePath());
            json.put("reason", reason);
        } catch (JSONException ignored) {
        }
        appendJson("detection_log.jsonl", json);
    }

    private void assignDetectionToEvent(JoyfulMomentClusterer.DetectionRecord record) {
        if (currentOpenEvent != null
                && currentEventLastDetectionStartSec >= 0.0
                && record.startSec - currentEventLastDetectionStartSec > config.eventWindowSec) {
            finalizeCurrentEvent("gap_exceeded");
        }
        if (currentOpenEvent == null || currentOpenEvent.finalized) {
            currentOpenEvent = clusterer.buildEvent(sessionStartMs, nextEventNumber++, participantNumber, nextParticipantEventNumber++, record.startSec, record.endSec);
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
        writeEventRecord(currentOpenEvent);
        refreshContextForEventIfNeeded(currentOpenEvent.eventId);
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
        writeEventRecord(currentOpenEvent);

        final String finalEventId = currentOpenEvent.eventId;
        refreshContextForEventIfNeeded(finalEventId);
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
        refreshContextForFinalizedEvent(eventId, 1);
    }

    private void refreshContextForEventIfNeeded(final String eventId) {
        if (eventId == null || contextResolveRequestedEventIds.contains(eventId)) {
            return;
        }
        contextResolveRequestedEventIds.add(eventId);
        refreshContextForFinalizedEvent(eventId, 1);
    }

    private void refreshContextForFinalizedEvent(final String eventId, final int attempt) {
        final AtlasReviewRepository repository = new AtlasReviewRepository(context);
        JSONObject existingEvent = repository.loadEventById(eventId);
        if (repository.hasUsefulDerivedContext(existingEvent)) {
            appendContextStatus("context.auto_resolve_skipped_complete", eventId, attempt, null);
            return;
        }
        AtlasContextResolver.Callback callback = new AtlasContextResolver.Callback() {
            @Override
            public void onResolved(Double lat, Double lng, Double amapLat, Double amapLng, Float accuracyMeters, Long timestampMs, String locationName, String adcode, String weatherCondition, Double temperature) {
                JSONObject eventJson = repository.loadEventById(eventId);
                boolean saved = eventJson != null && repository.updateDerivedContext(eventJson, lat, lng, amapLat, amapLng, accuracyMeters, timestampMs, locationName, adcode, weatherCondition, temperature);
                JSONObject updatedEventJson = repository.loadEventById(eventId);
                boolean complete = repository.hasUsefulDerivedContext(updatedEventJson);
                int backfilled = complete ? repository.backfillMissingContextFromNearby(updatedEventJson, CONTEXT_NEARBY_BACKFILL_WINDOW_MS) : 0;
                JSONObject status = new JSONObject();
                try {
                    status.put("type", "context.auto_resolved");
                    status.put("event_id", eventId);
                    status.put("attempt", attempt);
                    status.put("saved", saved);
                    status.put("complete", complete);
                    status.put("nearby_backfilled_count", backfilled);
                    status.put("has_location", lat != null && lng != null);
                    status.put("has_address", locationName != null && locationName.length() > 0);
                    status.put("has_weather", weatherCondition != null && weatherCondition.length() > 0);
                    status.put("adcode", adcode);
                } catch (JSONException ignored) {
                }
                appendJson("detection_log.jsonl", status);
                if (!complete) {
                    scheduleContextRetry(eventId, attempt, "partial_context");
                }
            }

            @Override
            public void onFailed(String reason) {
                JSONObject status = new JSONObject();
                try {
                    status.put("type", "context.auto_resolve_failed");
                    status.put("event_id", eventId);
                    status.put("attempt", attempt);
                    status.put("reason", reason);
                } catch (JSONException ignored) {
                }
                appendJson("detection_log.jsonl", status);
                scheduleContextRetry(eventId, attempt, reason);
            }
        };
        if (hasEventLocation(existingEvent)) {
            AtlasContextResolver.refreshContextForEvent(repository, existingEvent, callback);
        } else {
            AtlasContextResolver.refreshContext(context, repository, callback);
        }
    }

    private void scheduleContextRetry(final String eventId, final int attempt, String reason) {
        if (attempt >= CONTEXT_AUTO_MAX_ATTEMPTS) {
            JSONObject status = new JSONObject();
            try {
                status.put("type", "context.auto_retry_exhausted");
                status.put("event_id", eventId);
                status.put("attempt", attempt);
                status.put("reason", reason);
            } catch (JSONException ignored) {
            }
            appendJson("detection_log.jsonl", status);
            return;
        }
        long delayMs = contextRetryDelayMs(attempt + 1);
        JSONObject status = new JSONObject();
        try {
            status.put("type", "context.auto_retry_scheduled");
            status.put("event_id", eventId);
            status.put("next_attempt", attempt + 1);
            status.put("delay_ms", delayMs);
            status.put("reason", reason);
        } catch (JSONException ignored) {
        }
        appendJson("detection_log.jsonl", status);
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshContextForFinalizedEvent(eventId, attempt + 1);
            }
        }, delayMs);
    }

    private long contextRetryDelayMs(int attempt) {
        if (attempt <= 2) {
            return 15000L;
        }
        if (attempt == 3) {
            return 45000L;
        }
        if (attempt == 4) {
            return 120000L;
        }
        if (attempt == 5) {
            return 300000L;
        }
        if (attempt <= 8) {
            return 600000L;
        }
        if (attempt <= 12) {
            return 900000L;
        }
        return 1800000L;
    }

    private boolean hasEventLocation(JSONObject eventJson) {
        if (eventJson == null) {
            return false;
        }
        JSONObject derived = eventJson.optJSONObject("derived_context");
        JSONObject gps = derived != null ? derived.optJSONObject("gps") : null;
        return gps != null && gps.has("lat") && gps.has("lng");
    }

    private void appendContextStatus(String type, String eventId, int attempt, String reason) {
        JSONObject status = new JSONObject();
        try {
            status.put("type", type);
            status.put("event_id", eventId);
            status.put("attempt", attempt);
            if (reason != null) {
                status.put("reason", reason);
            }
        } catch (JSONException ignored) {
        }
        appendJson("detection_log.jsonl", status);
    }

    private void triggerAutomationForDetectionIfNewClip(JoyfulMomentClusterer.DetectionRecord record, String eventId) {
        int automationBucketSec = Math.max(1, config.clipDurationSec) * AppConfig.AUTO_CAPTURE_RATE_LIMIT_CLIP_MULTIPLIER;
        int automationBucketId = (int) (record.startSec / automationBucketSec);
        if (automationBucketId == lastAutomationBucketId) {
            JSONObject skipped = new JSONObject();
            try {
                skipped.put("type", "automation.skipped_same_rate_limit_window");
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
        final AtlasCaptureBundleRequest request =
                AtlasCaptureBundleRequest.create(
                        eventId,
                        automationBucketId,
                        System.currentTimeMillis(),
                        config.triggerVideoDurationSec);
        JSONObject status = new JSONObject();
        try {
            status.put("type", "automation.triggered_by_detection");
            status.put("event_id", eventId);
            status.put("bundle_id", request.bundleId);
            status.put(
                    "bundle_trigger_time_ms",
                    request.triggerTimeMs);
            status.put(
                    "auto_video_duration_sec",
                    request.videoDurationSec);
            status.put(
                    "auto_photo_count",
                    AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE);
            status.put("automation_bucket_id", automationBucketId);
            status.put("automation_bucket_sec", automationBucketSec);
            status.put("clip_duration_sec", config.clipDurationSec);
        } catch (JSONException ignored) {
        }
        appendJson("detection_log.jsonl", status);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                hostCallbacks.onJoyfulAutoVideoRequested(request);
            }
        });
        scheduleAutoPhotos(request);
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
        writeEventRecord(eventRecord);
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

    private void scheduleAutoPhotos(
            final AtlasCaptureBundleRequest request) {
        for (int i = 0;
                i < AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE;
                i++) {
            final AtlasCaptureBundleRequest.PhotoRequest photoRequest =
                    request.photoRequest(i);
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    hostCallbacks.onJoyfulAutoPhotoRequested(
                            photoRequest);
                }
            }, AppConfig.autoCapturePhotoDelayMs(i));
        }
    }

    public synchronized void onAutoVideoCaptureStarted() {
        onAutoVideoCaptureStarted(
                lastTriggeredEventId,
                System.currentTimeMillis());
    }

    public synchronized void onAutoVideoCaptureStarted(
            String eventId,
            long captureTimeMs) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", "asset.auto_video.started");
            json.put("event_id", eventId);
            json.put("capture_time_ms", captureTimeMs);
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
        onAutoVideoSaved(
                lastTriggeredEventId,
                path,
                contentUri,
                System.currentTimeMillis());
    }

    public synchronized void onAutoVideoSaved(String eventId, String path, String contentUri) {
        onAutoVideoSaved(
                eventId,
                path,
                contentUri,
                System.currentTimeMillis());
    }

    public synchronized void onAutoVideoSaved(
            String eventId,
            String path,
            String contentUri,
            long captureTimeMs) {
        if (path == null) {
            appendAssetStatus(
                    "asset.auto_video.save_failed",
                    eventId,
                    "missing_path",
                    null);
            return;
        }
        String stablePath = copyAssetIntoSession(eventId, path, "videos", "event_video");
        if (stablePath == null) {
            stablePath = path;
        }
        JoyfulMomentClusterer.EventRecord eventRecord = findEventById(eventId);
        if (eventRecord != null) {
            eventRecord.videoPath = stablePath;
            eventRecord.videoContentUri = contentUri;
            if (!eventRecord.videoPaths.contains(stablePath)) {
                eventRecord.videoPaths.add(stablePath);
                eventRecord.videoContentUris.add(contentUri);
                eventRecord.videoAssets.add(
                        new JoyfulMomentClusterer.MediaAssetRecord(
                                stablePath,
                                contentUri,
                                captureTimeMs > 0L
                                        ? captureTimeMs
                                        : System.currentTimeMillis()));
            }
            appendJson("event_log.jsonl", safeJson(eventRecord));
            writeEventRecord(eventRecord);
        }
        appendAssetStatus("asset.auto_video.saved", eventId, "ok", stablePath);
    }

    public synchronized void onAutoPhotoSaved(String path) {
        onAutoPhotoSaved(
                lastTriggeredEventId,
                path,
                System.currentTimeMillis());
    }

    public synchronized void onAutoPhotoSaved(String eventId, String path) {
        onAutoPhotoSaved(
                eventId,
                path,
                System.currentTimeMillis());
    }

    public synchronized void onAutoPhotoSaved(
            String eventId,
            String path,
            long captureTimeMs) {
        if (path == null) {
            appendAssetStatus(
                    "asset.auto_photo.save_failed",
                    eventId,
                    "missing_path",
                    null);
            return;
        }
        String stablePath = copyAssetIntoSession(eventId, path, "photos", "event_photo");
        if (stablePath == null) {
            stablePath = path;
        }
        JoyfulMomentClusterer.EventRecord eventRecord = findEventById(eventId);
        if (eventRecord != null && !eventRecord.photoPaths.contains(stablePath)) {
            eventRecord.photoPaths.add(stablePath);
            eventRecord.photoAssets.add(
                    new JoyfulMomentClusterer.MediaAssetRecord(
                            stablePath,
                            null,
                            captureTimeMs > 0L
                                    ? captureTimeMs
                                    : System.currentTimeMillis()));
            appendJson("event_log.jsonl", safeJson(eventRecord));
            writeEventRecord(eventRecord);
        }
        appendAssetStatus("asset.auto_photo.saved", eventId, "ok", stablePath);
    }

    private String copyAssetIntoSession(String eventId, String sourcePath, String folderName, String prefix) {
        if (sessionDir == null || sourcePath == null) {
            return null;
        }
        File source = new File(sourcePath);
        if (!source.exists() || !source.isFile()) {
            return null;
        }
        String targetEventId = eventId != null ? eventId : "unlinked";
        File dstDir = new File(sessionDir, "captured_media/" + targetEventId + "/" + folderName);
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
        appendAssetStatus(type, lastTriggeredEventId, reason, path);
    }

    private void appendAssetStatus(String type, String eventId, String reason, String path) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", type);
            json.put("event_id", eventId);
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

    private void writeEventRecord(JoyfulMomentClusterer.EventRecord eventRecord) {
        if (sessionDir == null || eventRecord == null || eventRecord.eventId == null) {
            return;
        }
        File file = new File(sessionDir, eventRecord.eventId + ".json");
        JSONObject json = safeJson(eventRecord);
        JSONObject existing = readJsonFile(file);
        try {
            if (existing != null && existing.has("derived_context")) {
                json.put("derived_context", existing.optJSONObject("derived_context"));
            }
            if (existing != null && existing.has("user_generated")) {
                json.put("user_generated", existing.optJSONObject("user_generated"));
            }
        } catch (JSONException ignored) {
        }
        eventStore.writeJson(file, json);
    }

    private JSONObject readJsonFile(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
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
            json.put("participant_number", participantNumber);
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

    private String normalizeParticipantNumber(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) {
            value = "00";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        if (digits.length() == 0) {
            return "00";
        }
        if (digits.length() == 1) {
            return "0" + digits.toString();
        }
        return digits.toString();
    }

    private int computeNextParticipantEventNumber(String participant) {
        int max = 0;
        File root = eventStore.getRootDir();
        File[] sessions = root.listFiles();
        if (sessions == null) {
            return 1;
        }
        String prefix = participant + "_event_";
        for (File session : sessions) {
            if (session == null || !session.isDirectory()) {
                continue;
            }
            File[] files = session.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                String name = file.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".json")) {
                    continue;
                }
                String numberText = name.substring(prefix.length(), name.length() - ".json".length());
                try {
                    max = Math.max(max, Integer.parseInt(numberText));
                } catch (Exception ignored) {
                }
            }
        }
        return max + 1;
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
