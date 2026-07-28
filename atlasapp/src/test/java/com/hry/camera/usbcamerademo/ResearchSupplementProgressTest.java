package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ResearchSupplementProgressTest {
    @Test
    public void reportsOnlyAggregateCountsWithoutAnswerContent() {
        ResearchSupplementProgress progress =
                new ResearchSupplementProgress(3);

        progress.record(0, true);
        progress.record(1, false);
        progress.record(2, true);

        JSONObject properties = progress.properties();
        assertEquals(2, properties.optInt("answered_step_count"));
        assertEquals(1, properties.optInt("skipped_step_count"));
        assertEquals(3, properties.optInt("total_step_count"));
        assertFalse(properties.has("answers"));
        assertFalse(properties.has("with_whom"));
        assertFalse(properties.has("doing_what"));
        assertFalse(properties.has("mood"));
    }

    @Test
    public void repeatedStepResultReplacesRatherThanDoubleCounts() {
        ResearchSupplementProgress progress =
                new ResearchSupplementProgress(3);

        progress.record(0, false);
        progress.record(0, true);

        JSONObject properties = progress.properties();
        assertEquals(1, properties.optInt("answered_step_count"));
        assertEquals(0, properties.optInt("skipped_step_count"));
    }
}
