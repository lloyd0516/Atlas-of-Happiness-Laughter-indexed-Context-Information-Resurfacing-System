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

    // ---- Fixed automatic capture bundle ----
    public static final int AUTO_CAPTURE_PHOTOS_PER_BUNDLE = 2;
    public static final int AUTO_CAPTURE_VIDEOS_PER_BUNDLE = 1;
    public static final long AUTO_CAPTURE_PHOTO_DELAY_1_MS = 1500L;
    public static final long AUTO_CAPTURE_PHOTO_DELAY_2_MS = 3500L;
    @Deprecated
    public static final int DEFAULT_TRIGGER_PHOTO_COUNT =
            AUTO_CAPTURE_PHOTOS_PER_BUNDLE;

    // ---- "Frequent" preset overrides ----
    public static final int FREQUENT_CHUNK_MS = 150;
    public static final int FREQUENT_CLIP_DURATION_SEC = 20;
    public static final int FREQUENT_CONTEXT_NEIGHBOR_CLIPS = 3;
    public static final int FREQUENT_EVENT_WINDOW_SEC = 480;
    public static final int FREQUENT_TRIGGER_VIDEO_DURATION_SEC = 6;

    // ---- "Sparse" preset overrides ----
    public static final int SPARSE_CHUNK_MS = 250;
    public static final int SPARSE_CLIP_DURATION_SEC = 45;
    public static final int SPARSE_CONTEXT_NEIGHBOR_CLIPS = 1;
    public static final int SPARSE_EVENT_WINDOW_SEC = 900;
    public static final int SPARSE_TRIGGER_VIDEO_DURATION_SEC = 4;

    /** Padding applied around a laughter detection when extracting the related audio window. */
    public static final double LAUGHTER_AUDIO_PADDING_SEC = 2.5;

    // ---- App-only laughter playback enhancement ----
    /** Duration of one RMS analysis frame. */
    public static final int LAUGHTER_PLAYBACK_FRAME_MS = 20;
    /** Analyze the loudest fraction of frames so extraction padding does not mask a brief laugh. */
    public static final double LAUGHTER_PLAYBACK_TOP_FRAME_RATIO = 0.05;
    /** Clips at or above this effective level are played without gain. */
    public static final double LAUGHTER_PLAYBACK_QUIET_THRESHOLD_DBFS = -24.0;
    /** Quiet clips receive this fraction of the distance to the normal threshold. */
    public static final double LAUGHTER_PLAYBACK_COMPENSATION_RATIO = 0.75;
    /** Playback gain is positive-only and capped at this value. */
    public static final double LAUGHTER_PLAYBACK_MAX_BOOST_DB = 18.0;
    /** Samples above this output level enter soft saturation. */
    public static final double LAUGHTER_PLAYBACK_PEAK_GUARD_DBFS = -1.0;
    /** Increment when the analysis or gain algorithm changes so old cache entries are ignored. */
    public static final int LAUGHTER_PLAYBACK_ALGORITHM_VERSION = 1;

    // ---- Laughter clip media association ----
    /** Each clip may use media captured within this symmetric time window. */
    public static final long CLIP_MEDIA_MATCH_WINDOW_MS = 90L * 1000L;
    /** Time window used only to infer capture bundles in legacy records. */
    public static final long LEGACY_CAPTURE_BUNDLE_GROUP_WINDOW_MS =
            15L * 1000L;
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

    // ---- Resurfacing notifications ----
    /** Local wall-clock time used by the daily reminder alarm. */
    public static final int DAILY_REVIEW_HOUR = 19;
    public static final int DAILY_REVIEW_MINUTE = 30;
    /** Calendar-day offsets used for the two independent daily notification categories. */
    public static final int SHORT_TERM_DAY_OFFSET = 1;
    public static final int LONG_TERM_DAY_OFFSET = 7;
    /** Retry a missed daily category after this delay, up to the configured attempt count. */
    public static final long DAILY_REVIEW_RETRY_DELAY_MS = 15L * 60L * 1000L;
    public static final int DAILY_REVIEW_MAX_RETRIES = 2;
    /** Small delay used to coalesce recovery after boot/app start when today's alarm was missed. */
    public static final long DAILY_REVIEW_CATCH_UP_DELAY_MS = 5L * 1000L;

    /** A historical place matches when the current fix is within this radius. */
    public static final float SPECIAL_LOCATION_RADIUS_METERS = 50f;
    /** Never resurface a place backed only by moments newer than this. */
    public static final long SPECIAL_MIN_EVENT_AGE_MS = 6L * 60L * 60L * 1000L;
    /** Global quiet period between any two location-triggered notifications. */
    public static final long SPECIAL_NOTIFICATION_COOLDOWN_MS = 2L * 60L * 60L * 1000L;
    /** Only a recent, accurate last-known fix may veto a native proximity-enter event. */
    public static final long SPECIAL_CURRENT_FIX_MAX_AGE_MS = 2L * 60L * 1000L;

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

    public static long autoCapturePhotoDelayMs(int mediaIndex) {
        if (mediaIndex == 0) {
            return AUTO_CAPTURE_PHOTO_DELAY_1_MS;
        }
        if (mediaIndex == 1) {
            return AUTO_CAPTURE_PHOTO_DELAY_2_MS;
        }
        throw new IllegalArgumentException(
                "Unsupported photo media index: " + mediaIndex);
    }
}
