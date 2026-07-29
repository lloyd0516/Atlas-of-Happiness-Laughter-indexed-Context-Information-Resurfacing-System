package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
