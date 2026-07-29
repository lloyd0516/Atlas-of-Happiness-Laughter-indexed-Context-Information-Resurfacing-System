package com.hry.camera.usbcamerademo;

/** Shared session-relative bucket boundaries for capture and resurfacing. */
final class AtlasAggregationBucketPolicy {
    private AtlasAggregationBucketPolicy() {
    }

    static int bucketDurationSec(int clipDurationSec) {
        if (clipDurationSec <= 0) {
            throw new IllegalArgumentException(
                    "clip duration must be positive");
        }
        return Math.multiplyExact(
                clipDurationSec,
                AppConfig.AGGREGATION_CLIPS_PER_BUCKET);
    }

    static int bucketId(
            double sessionOffsetSec,
            int clipDurationSec) {
        if (Double.isNaN(sessionOffsetSec)
                || Double.isInfinite(sessionOffsetSec)
                || sessionOffsetSec < 0.0) {
            throw new IllegalArgumentException(
                    "session offset must be finite and non-negative");
        }
        return (int) Math.floor(
                sessionOffsetSec
                        / bucketDurationSec(clipDurationSec));
    }

    static long bucketStartOffsetMs(
            int bucketId,
            int clipDurationSec) {
        if (bucketId < 0) {
            throw new IllegalArgumentException(
                    "bucket id must be non-negative");
        }
        return Math.multiplyExact(
                (long) bucketId,
                Math.multiplyExact(
                        (long) bucketDurationSec(clipDurationSec),
                        1000L));
    }

    static long bucketEndOffsetMs(
            int bucketId,
            int clipDurationSec) {
        return bucketStartOffsetMs(
                Math.addExact(bucketId, 1),
                clipDurationSec);
    }
}
