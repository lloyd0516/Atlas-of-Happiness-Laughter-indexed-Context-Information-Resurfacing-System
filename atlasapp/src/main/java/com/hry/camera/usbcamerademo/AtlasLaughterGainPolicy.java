package com.hry.camera.usbcamerademo;

/**
 * Maps measured laughter loudness to a positive-only playback gain.
 *
 * <p>The partially compensating curve is monotonic: it reduces extreme loudness gaps without
 * flattening the original ordering between clips.</p>
 */
final class AtlasLaughterGainPolicy {
    private AtlasLaughterGainPolicy() {
    }

    static double computeGainDb(double measuredDbfs) {
        if (Double.isNaN(measuredDbfs) || Double.isInfinite(measuredDbfs)) {
            return 0.0;
        }
        double gap = AppConfig.LAUGHTER_PLAYBACK_QUIET_THRESHOLD_DBFS - measuredDbfs;
        if (gap <= 0.0) {
            return 0.0;
        }
        double requested = gap * AppConfig.LAUGHTER_PLAYBACK_COMPENSATION_RATIO;
        return Math.max(0.0, Math.min(
                AppConfig.LAUGHTER_PLAYBACK_MAX_BOOST_DB,
                requested));
    }
}
