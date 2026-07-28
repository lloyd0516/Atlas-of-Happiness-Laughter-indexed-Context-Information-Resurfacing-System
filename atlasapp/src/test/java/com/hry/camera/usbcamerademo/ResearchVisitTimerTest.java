package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ResearchVisitTimerTest {
    @Test
    public void accumulatesOnlyVisibleIntervals() {
        ResearchVisitTimer timer = new ResearchVisitTimer();
        timer.start(100L);
        assertEquals(200L, timer.pause(300L));
        timer.start(500L);
        assertEquals(250L, timer.pause(550L));
        assertEquals(250L, timer.totalVisibleMs());
    }

    @Test
    public void duplicateStartAndPauseDoNotDoubleCount() {
        ResearchVisitTimer timer = new ResearchVisitTimer();
        timer.start(100L);
        timer.start(120L);
        assertEquals(100L, timer.pause(200L));
        assertEquals(100L, timer.pause(300L));
    }
}
