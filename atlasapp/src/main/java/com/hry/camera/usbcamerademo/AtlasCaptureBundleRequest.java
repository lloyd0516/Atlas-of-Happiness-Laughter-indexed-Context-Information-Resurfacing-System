package com.hry.camera.usbcamerademo;

/** Immutable identity and timing for one automatic 2-photo + 1-video capture. */
final class AtlasCaptureBundleRequest {
    final String bundleId;
    final String eventId;
    final int automationBucketId;
    final int automationBucketClipCount;
    final int automationBucketDurationSec;
    final long triggerTimeMs;
    final int videoDurationSec;

    private AtlasCaptureBundleRequest(
            String bundleId,
            String eventId,
            int automationBucketId,
            int automationBucketClipCount,
            int automationBucketDurationSec,
            long triggerTimeMs,
            int videoDurationSec) {
        if (bundleId == null || bundleId.length() == 0) {
            throw new IllegalArgumentException("bundle id missing");
        }
        if (eventId == null || eventId.length() == 0) {
            throw new IllegalArgumentException("event id missing");
        }
        if (triggerTimeMs <= 0L) {
            throw new IllegalArgumentException("trigger time invalid");
        }
        if (automationBucketClipCount <= 0
                || automationBucketDurationSec <= 0) {
            throw new IllegalArgumentException(
                    "automation bucket metadata invalid");
        }
        if (videoDurationSec <= 0) {
            throw new IllegalArgumentException("video duration invalid");
        }
        this.bundleId = bundleId;
        this.eventId = eventId;
        this.automationBucketId = automationBucketId;
        this.automationBucketClipCount =
                automationBucketClipCount;
        this.automationBucketDurationSec =
                automationBucketDurationSec;
        this.triggerTimeMs = triggerTimeMs;
        this.videoDurationSec = videoDurationSec;
    }

    static AtlasCaptureBundleRequest create(
            String eventId,
            int automationBucketId,
            int automationBucketDurationSec,
            long triggerTimeMs,
            int videoDurationSec) {
        String id = eventId
                + "_capture_"
                + triggerTimeMs
                + "_bucket_"
                + automationBucketId;
        return new AtlasCaptureBundleRequest(
                id,
                eventId,
                automationBucketId,
                AppConfig.AGGREGATION_CLIPS_PER_BUCKET,
                automationBucketDurationSec,
                triggerTimeMs,
                videoDurationSec);
    }

    PhotoRequest photoRequest(int mediaIndex) {
        if (mediaIndex < 0
                || mediaIndex
                >= AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE) {
            throw new IllegalArgumentException(
                    "Unsupported photo media index: " + mediaIndex);
        }
        return new PhotoRequest(this, mediaIndex);
    }

    static final class PhotoRequest {
        final AtlasCaptureBundleRequest bundle;
        final int mediaIndex;

        private PhotoRequest(
                AtlasCaptureBundleRequest bundle,
                int mediaIndex) {
            this.bundle = bundle;
            this.mediaIndex = mediaIndex;
        }
    }
}
