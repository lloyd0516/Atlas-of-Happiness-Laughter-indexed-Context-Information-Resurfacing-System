package com.hry.camera.usbcamerademo;

/** Measures actual media playback while excluding paused and hidden intervals. */
final class ResearchPlaybackTracker {
    private long runningSinceMs = -1L;
    private long playedDurationMs;

    void start(long elapsedRealtimeMs) {
        if (runningSinceMs < 0L) {
            runningSinceMs = elapsedRealtimeMs;
        }
    }

    long pause(long elapsedRealtimeMs) {
        if (runningSinceMs >= 0L) {
            playedDurationMs += Math.max(
                    0L, elapsedRealtimeMs - runningSinceMs);
            runningSinceMs = -1L;
        }
        return playedDurationMs;
    }

    long finish(long elapsedRealtimeMs) {
        return pause(elapsedRealtimeMs);
    }

    long playedDurationMs() {
        return playedDurationMs;
    }
}
