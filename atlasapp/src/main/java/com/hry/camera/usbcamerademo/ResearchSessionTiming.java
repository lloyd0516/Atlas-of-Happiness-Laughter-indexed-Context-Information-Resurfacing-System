package com.hry.camera.usbcamerademo;

/** Pure duration calculations for capture-session research records. */
final class ResearchSessionTiming {
    private ResearchSessionTiming() {
    }

    static long normalDuration(long startElapsedMs, long stopElapsedMs) {
        return Math.max(0L, stopElapsedMs - startElapsedMs);
    }

    static long estimatedWallDuration(long startWallMs, long stopWallMs) {
        return Math.max(0L, stopWallMs - startWallMs);
    }
}
