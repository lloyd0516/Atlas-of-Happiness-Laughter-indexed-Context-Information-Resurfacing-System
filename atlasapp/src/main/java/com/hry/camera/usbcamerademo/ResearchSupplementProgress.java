package com.hry.camera.usbcamerademo;

import org.json.JSONObject;

/** Tracks supplement completion without retaining any user-provided answer. */
final class ResearchSupplementProgress {
    private final boolean[] recorded;
    private final boolean[] answered;

    ResearchSupplementProgress(int stepCount) {
        int safeCount = Math.max(0, stepCount);
        recorded = new boolean[safeCount];
        answered = new boolean[safeCount];
    }

    void record(int stepIndex, boolean hasAnswer) {
        if (stepIndex < 0 || stepIndex >= recorded.length) {
            return;
        }
        recorded[stepIndex] = true;
        answered[stepIndex] = hasAnswer;
    }

    JSONObject properties() {
        int answeredCount = 0;
        int skippedCount = 0;
        for (int i = 0; i < recorded.length; i++) {
            if (!recorded[i]) {
                continue;
            }
            if (answered[i]) {
                answeredCount++;
            } else {
                skippedCount++;
            }
        }
        return ResearchInteractionLogger.properties(
                "answered_step_count", answeredCount,
                "skipped_step_count", skippedCount,
                "total_step_count", recorded.length);
    }
}
