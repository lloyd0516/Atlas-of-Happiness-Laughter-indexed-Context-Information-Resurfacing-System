package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ResearchSessionTimingTest {
    @Test
    public void normalDurationUsesMonotonicClock() {
        assertEquals(
                6500L,
                ResearchSessionTiming.normalDuration(1000L, 7500L));
    }

    @Test
    public void durationNeverBecomesNegative() {
        assertEquals(
                0L,
                ResearchSessionTiming.normalDuration(7500L, 1000L));
        assertEquals(
                0L,
                ResearchSessionTiming.estimatedWallDuration(9000L, 8000L));
    }
}
