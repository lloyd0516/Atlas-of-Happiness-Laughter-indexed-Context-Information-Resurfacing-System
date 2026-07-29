package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AtlasCaptureBundleRequestTest {
    @Test
    public void createsStableIdentityAndTwoIndexedPhotoRequests() {
        AtlasCaptureBundleRequest bundle =
                AtlasCaptureBundleRequest.create(
                        "event-7",
                        12,
                        5000L,
                        5);

        assertEquals("event-7", bundle.eventId);
        assertEquals(12, bundle.automationBucketId);
        assertEquals(5000L, bundle.triggerTimeMs);
        assertEquals(5, bundle.videoDurationSec);
        assertTrue(bundle.bundleId.contains("event-7"));

        AtlasCaptureBundleRequest.PhotoRequest first =
                bundle.photoRequest(0);
        AtlasCaptureBundleRequest.PhotoRequest second =
                bundle.photoRequest(1);
        assertSame(bundle, first.bundle);
        assertSame(bundle, second.bundle);
        assertEquals(0, first.mediaIndex);
        assertEquals(1, second.mediaIndex);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPhotoIndexOutsideFixedBundle() {
        AtlasCaptureBundleRequest.create(
                "event-7",
                12,
                5000L,
                5)
                .photoRequest(2);
    }
}
