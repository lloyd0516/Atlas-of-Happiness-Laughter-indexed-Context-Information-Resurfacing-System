package com.hry.camera.usbcamerademo;

import java.util.List;

/** Shared presentation helpers for visible laughter-clip counts. */
final class AtlasLaughterCountPresentation {
    private AtlasLaughterCountPresentation() {
    }

    static int total(
            List<AtlasReviewRepository.EventSummary> events) {
        int total = 0;
        if (events == null) {
            return total;
        }
        for (AtlasReviewRepository.EventSummary event : events) {
            if (event != null) {
                total += Math.max(0, event.laughterClipCount);
            }
        }
        return total;
    }

    static String chineseLabel(int laughterClipCount) {
        return laughterClipCount > 0
                ? laughterClipCount + "段笑声"
                : "暂无笑声片段";
    }
}
