package com.hry.camera.usbcamerademo;

/**
 * Central place for tunable parameters that used to be hardcoded magic numbers.
 * Grouped by subsystem so a designer/PM can find and change a knob without reading code.
 */
public final class AppConfig {
    private AppConfig() {
    }

    // ---- Detection / clustering defaults (mirrors JoyfulMomentConfig.LEVEL_MEDIUM) ----
    public static final int DEFAULT_CHUNK_MS = 200;
    public static final int DEFAULT_CLIP_DURATION_SEC = 30;
    public static final int DEFAULT_CONTEXT_NEIGHBOR_CLIPS = 2;

    /**
     * Max gap between two accepted laughter detections to keep them in the same laughter_event.
     * Requirement: default changed from 10min to 20min.
     */
    public static final int DEFAULT_EVENT_WINDOW_SEC = 20 * 60;

    public static final int DEFAULT_LAUGHTER_CONFIDENCE_THRESHOLD_PCT = 70;
    public static final int DEFAULT_LAUGHTER_MIN_DURATION_MS = 0;
    public static final int DEFAULT_TRIGGER_VIDEO_DURATION_SEC = 5;
    public static final int DEFAULT_TRIGGER_PHOTO_COUNT = 2;

    // ---- "Frequent" preset overrides ----
    public static final int FREQUENT_CHUNK_MS = 150;
    public static final int FREQUENT_CLIP_DURATION_SEC = 20;
    public static final int FREQUENT_CONTEXT_NEIGHBOR_CLIPS = 3;
    public static final int FREQUENT_EVENT_WINDOW_SEC = 480;
    public static final int FREQUENT_TRIGGER_VIDEO_DURATION_SEC = 6;
    public static final int FREQUENT_TRIGGER_PHOTO_COUNT = 3;

    // ---- "Sparse" preset overrides ----
    public static final int SPARSE_CHUNK_MS = 250;
    public static final int SPARSE_CLIP_DURATION_SEC = 45;
    public static final int SPARSE_CONTEXT_NEIGHBOR_CLIPS = 1;
    public static final int SPARSE_EVENT_WINDOW_SEC = 900;
    public static final int SPARSE_TRIGGER_VIDEO_DURATION_SEC = 4;
    public static final int SPARSE_TRIGGER_PHOTO_COUNT = 1;

    /** Padding applied around a laughter detection when extracting the related audio window. */
    public static final double LAUGHTER_AUDIO_PADDING_SEC = 2.5;

    /**
     * Auto photo/video capture is rate-limited to at most once per this many clips.
     * Requirement: changed from 2*clip (60s @ 30s clips) to 4*clip (120s @ 30s clips).
     */
    public static final int AUTO_CAPTURE_RATE_LIMIT_CLIP_MULTIPLIER = 4;

    // ---- Long-term vs short-term event display ----
    /** Events older than this (from "now") render with the long-term (compact) layout. */
    public static final long LONG_TERM_THRESHOLD_MS = 7L * 24 * 60 * 60 * 1000L; // 1 week

    /** WeChat-style relative time breakpoints for the short-term review header. */
    public static final long RECENCY_JUST_NOW_MS = 60 * 1000L;
    public static final long RECENCY_MINUTES_MS = 60 * 60 * 1000L;

    // ---- GPS / AMap request budget (free tier - must stay low-frequency) ----
    /** Timeout waiting for a single fresh GPS fix before falling back to last-known location. */
    public static final long GPS_FRESH_FIX_TIMEOUT_MS = 12000L;
    /** Minimum acceptable accuracy (meters); below this we still accept but keep retrying in background if possible. */
    public static final float GPS_DESIRED_ACCURACY_METERS = 50f;
    /** Hard ceiling on GPS fix accuracy improvement retries per event before giving up. */
    public static final int GPS_MAX_FIX_ATTEMPTS = 3;
    /** Delay between GPS re-fix attempts within one resolution pass (not the outer AMap-call retry). */
    public static final long GPS_FIX_RETRY_DELAY_MS = 4000L;
    /** Max attempts to resolve AMap-derived context (regeo/weather) for one event. */
    public static final int CONTEXT_AUTO_MAX_ATTEMPTS = 18;
    /** Window used to backfill missing context from a nearby already-resolved event. */
    public static final long CONTEXT_NEARBY_BACKFILL_WINDOW_MS = 6L * 60L * 60L * 1000L;

    // ---- Post-recording supplement flow ----
    /** Auto-photo trigger delays after a laughter-labeled clip is finalized. */
    public static final long AUTO_PHOTO_TRIGGER_DELAY_1_MS = 1500L;
    public static final long AUTO_PHOTO_TRIGGER_DELAY_2_MS = 3500L;
}
