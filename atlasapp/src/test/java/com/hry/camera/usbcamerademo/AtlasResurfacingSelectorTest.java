package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class AtlasResurfacingSelectorTest {
    private AtlasReviewRepository.EventSummary event(
            String id, long startMs, String action, boolean supplemented, int mediaCount)
            throws Exception {
        AtlasReviewRepository.EventSummary summary = new AtlasReviewRepository.EventSummary();
        summary.eventId = id;
        summary.startTimeMs = startMs;
        JSONObject event = new JSONObject();
        if (action != null) {
            event.put("save_decision", new JSONObject().put("action", action));
        }
        JSONObject user = new JSONObject();
        JSONArray notes = new JSONArray();
        if (supplemented) {
            notes.put(new JSONObject().put("text", "remember this"));
        }
        user.put("notes", notes);
        user.put("audio_notes", new JSONArray());
        user.put("photos", new JSONArray());
        user.put("social_context", new JSONObject());
        event.put("user_generated", user);
        JSONObject auto = new JSONObject();
        JSONArray clips = new JSONArray();
        for (int i = 0; i < mediaCount; i++) {
            clips.put(new JSONObject().put("path", "clip-" + i + ".wav"));
        }
        auto.put("audio_clips", clips);
        auto.put("photos", new JSONArray());
        auto.put("videos", new JSONArray());
        event.put("auto_captured", auto);
        summary.eventJson = event;
        return summary;
    }

    @Test
    public void rejectsSaveNoPushAndMissingDecision() throws Exception {
        AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
        assertFalse(selector.isPushEligible(event("a", 1_000L, "save_no_push", true, 2)));
        assertFalse(selector.isPushEligible(event("b", 1_000L, null, true, 2)));
    }

    @Test
    public void supplementedEventWinsBeforeMediaCount() throws Exception {
        AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
        AtlasReviewRepository.EventSummary supplemented =
                event("supplemented", 2_000L, "save_push", true, 1);
        AtlasReviewRepository.EventSummary mediaHeavy =
                event("media-heavy", 3_000L, "save_push", false, 8);
        AtlasReviewRepository.EventSummary selected = selector.selectForCalendarDay(
                Arrays.asList(mediaHeavy, supplemented), 0L, 10_000L, 5_000L);
        assertEquals("supplemented", selected.eventId);
    }

    @Test
    public void mediaCountBreaksTieWithinSupplementTier() throws Exception {
        AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
        AtlasReviewRepository.EventSummary low =
                event("low", 2_000L, "save_push", true, 1);
        AtlasReviewRepository.EventSummary high =
                event("high", 3_000L, "save_push", true, 3);
        assertEquals("high", selector.selectForCalendarDay(
                Arrays.asList(low, high), 0L, 10_000L, 5_000L).eventId);
    }

    @Test
    public void preferredTimeBreaksFullTie() throws Exception {
        AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
        AtlasReviewRepository.EventSummary far =
                event("far", 1_000L, "save_push", true, 2);
        AtlasReviewRepository.EventSummary near =
                event("near", 4_500L, "save_push", true, 2);
        assertEquals("near", selector.selectForCalendarDay(
                Arrays.asList(far, near), 0L, 10_000L, 5_000L).eventId);
    }

    @Test
    public void eventIdMakesSelectionDeterministic() throws Exception {
        AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
        AtlasReviewRepository.EventSummary b = event("b", 4_000L, "save_push", true, 2);
        AtlasReviewRepository.EventSummary a = event("a", 4_000L, "save_push", true, 2);
        assertEquals("a", selector.selectForCalendarDay(
                Arrays.asList(b, a), 0L, 10_000L, 5_000L).eventId);
    }

    @Test
    public void noEligibleEventReturnsNull() throws Exception {
        AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
        assertNull(selector.selectForCalendarDay(
                Collections.singletonList(event("x", 4_000L, "save_no_push", true, 2)),
                0L, 10_000L, 5_000L));
    }
}
