package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ResearchLogPropertiesTest {
    @Test
    public void mediaCompletionContainsNewAndCompatibleDurations() {
        JSONObject properties =
                ResearchLogProperties.mediaPlayCompleted(
                        4200L,
                        5000L,
                        3200L);

        assertEquals(4200L, properties.optLong("position_ms", -1L));
        assertEquals(5000L, properties.optLong("total_duration", -1L));
        assertEquals(3200L, properties.optLong("duration_played", -1L));
        assertEquals(5000L, properties.optLong("duration_ms", -1L));
        assertEquals(3200L, properties.optLong("played_duration_ms", -1L));
        assertEquals(5, properties.length());
    }

    @Test
    public void mediaCompletionPreservesNonPositivePlayerDurationForAnalysisFiltering() {
        JSONObject properties =
                ResearchLogProperties.mediaPlayCompleted(
                        0L,
                        -1L,
                        0L);

        assertEquals(-1L, properties.optLong("total_duration", 99L));
        assertEquals(-1L, properties.optLong("duration_ms", 99L));
        assertEquals(0L, properties.optLong("duration_played", -1L));
    }

    @Test
    public void firstDecisionIsNotAnUpdate() {
        JSONObject properties =
                ResearchLogProperties.momentSaveDecision(
                        null,
                        "save_push");

        assertEquals("save_push", properties.optString("action"));
        assertEquals(true, properties.optBoolean("push_allowed"));
        assertEquals(false, properties.optBoolean("is_update"));
    }

    @Test
    public void changingExistingDecisionIsAnUpdate() {
        JSONObject disablePush =
                ResearchLogProperties.momentSaveDecision(
                        "save_push",
                        "save_no_push");
        JSONObject enablePush =
                ResearchLogProperties.momentSaveDecision(
                        "save_no_push",
                        "save_push");
        JSONObject delete =
                ResearchLogProperties.momentSaveDecision(
                        "save_push",
                        "delete");

        assertEquals(true, disablePush.optBoolean("is_update"));
        assertEquals(false, disablePush.optBoolean("push_allowed"));
        assertEquals(true, enablePush.optBoolean("is_update"));
        assertEquals(true, enablePush.optBoolean("push_allowed"));
        assertEquals(true, delete.optBoolean("is_update"));
        assertEquals(false, delete.optBoolean("push_allowed"));
    }

    @Test
    public void directDeleteWithoutPreviousDecisionIsInitial() {
        JSONObject properties =
                ResearchLogProperties.momentSaveDecision(
                        null,
                        "delete");

        assertEquals("delete", properties.optString("action"));
        assertEquals(false, properties.optBoolean("push_allowed"));
        assertEquals(false, properties.optBoolean("is_update"));
    }

    @Test
    public void sameAndInvalidActionsDoNotProduceDecisionProperties() {
        assertNull(ResearchLogProperties.momentSaveDecision(
                "save_push", "save_push"));
        assertNull(ResearchLogProperties.momentSaveDecision(
                null, null));
        assertNull(ResearchLogProperties.momentSaveDecision(
                null, ""));
        assertNull(ResearchLogProperties.momentSaveDecision(
                null, "unsupported"));
    }
}
