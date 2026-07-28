package com.hry.camera.usbcamerademo;

/** Stable schema-v1 names for the local research interaction log. */
final class ResearchEventNames {
    static final int SCHEMA_VERSION = 1;
    static final String FILE_NAME = "research_interaction_log.jsonl";

    static final String LOG_STARTED = "research_log_started";
    static final String SESSION_STARTED = "capture_session_started";
    static final String SESSION_STOPPED = "capture_session_stopped";
    static final String SESSION_INTERRUPTED = "capture_session_interrupted";
    static final String SCREEN_OPENED = "screen_opened";
    static final String SCREEN_CLOSED = "screen_closed";
    static final String MOMENT_SAVE_DECISION = "moment_save_decision";
    static final String MOMENT_DETAIL_OPENED = "moment_detail_opened";
    static final String MOMENT_DETAIL_CLOSED = "moment_detail_closed";
    static final String MOMENT_EDIT_STARTED = "moment_edit_started";
    static final String MOMENT_EDIT_COMPLETED = "moment_edit_completed";
    static final String MOMENT_DELETED = "moment_deleted";
    static final String SUPPLEMENT_FLOW_OPENED = "supplement_flow_opened";
    static final String SUPPLEMENT_STEP_SKIPPED = "supplement_step_skipped";
    static final String SUPPLEMENT_FLOW_COMPLETED = "supplement_flow_completed";
    static final String DETAIL_SECTION_EXPANDED = "detail_section_expanded";
    static final String DETAIL_SECTION_COLLAPSED = "detail_section_collapsed";
    static final String MEDIA_OPENED = "media_opened";
    static final String MEDIA_PLAY_STARTED = "media_play_started";
    static final String MEDIA_PLAY_PAUSED = "media_play_paused";
    static final String MEDIA_PLAY_COMPLETED = "media_play_completed";
    static final String MEDIA_PLAY_FAILED = "media_play_failed";
    static final String REVIEW_TAB_SELECTED = "review_tab_selected";
    static final String REVIEW_CALENDAR_MONTH_CHANGED =
            "review_calendar_month_changed";
    static final String REVIEW_CALENDAR_DAY_SELECTED =
            "review_calendar_day_selected";
    static final String MAP_OPENED = "map_opened";
    static final String MAP_CARD_CHANGED = "map_card_changed";
    static final String MAP_MOMENT_OPENED = "map_moment_opened";
    static final String MAP_RECENTER_REQUESTED = "map_recenter_requested";
    static final String SETTING_CHANGED = "setting_changed";
    static final String NOTIFICATION_POSTED = "notification_posted";
    static final String NOTIFICATION_POST_FAILED = "notification_post_failed";
    static final String NOTIFICATION_OPENED = "notification_opened";
    static final String NOTIFICATION_DISMISSED = "notification_dismissed";
    static final String NOTIFICATION_SKIPPED = "notification_skipped";

    private ResearchEventNames() {
    }
}
