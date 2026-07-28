package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ResearchPlaybackTrackerTest {
    @Test
    public void excludesPausedGapFromActualPlayback() {
        ResearchPlaybackTracker tracker = new ResearchPlaybackTracker();
        tracker.start(1000L);
        assertEquals(600L, tracker.pause(1600L));
        tracker.start(5000L);
        assertEquals(1000L, tracker.finish(5400L));
        assertEquals(1000L, tracker.playedDurationMs());
    }

    @Test
    public void duplicateCallbacksDoNotDoubleCount() {
        ResearchPlaybackTracker tracker = new ResearchPlaybackTracker();
        tracker.start(1000L);
        tracker.start(1200L);
        tracker.pause(1500L);
        tracker.pause(1800L);
        assertEquals(500L, tracker.playedDurationMs());
    }
}
