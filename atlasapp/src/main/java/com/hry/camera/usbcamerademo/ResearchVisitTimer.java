package com.hry.camera.usbcamerademo;

/** Accumulates visible/active intervals while excluding paused gaps. */
final class ResearchVisitTimer {
    private long runningSinceMs = -1L;
    private long totalVisibleMs;

    void start(long elapsedRealtimeMs) {
        if (runningSinceMs < 0L) {
            runningSinceMs = elapsedRealtimeMs;
        }
    }

    long pause(long elapsedRealtimeMs) {
        if (runningSinceMs >= 0L) {
            totalVisibleMs += Math.max(
                    0L, elapsedRealtimeMs - runningSinceMs);
            runningSinceMs = -1L;
        }
        return totalVisibleMs;
    }

    long totalVisibleMs() {
        return totalVisibleMs;
    }
}
