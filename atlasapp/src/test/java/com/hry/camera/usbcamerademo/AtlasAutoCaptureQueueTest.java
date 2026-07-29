package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class AtlasAutoCaptureQueueTest {
    @Test
    public void photoCompletionKeepsOriginalBundleAndIndex() {
        AtlasCaptureBundleRequest first =
                AtlasCaptureBundleRequest.create(
                        "event-a",
                        1,
                        1000L,
                        5);
        AtlasCaptureBundleRequest second =
                AtlasCaptureBundleRequest.create(
                        "event-b",
                        2,
                        2000L,
                        5);
        AtlasAutoCaptureQueue queue = new AtlasAutoCaptureQueue();

        queue.enqueuePhoto(first.photoRequest(0));
        queue.enqueuePhoto(first.photoRequest(1));
        queue.enqueuePhoto(second.photoRequest(0));

        assertSame(first, queue.dispatchNextPhoto().bundle);
        assertSame(first, queue.dispatchNextPhoto().bundle);
        assertSame(second, queue.dispatchNextPhoto().bundle);
        assertEquals(0, queue.completeNextPhoto().mediaIndex);
        assertEquals(1, queue.completeNextPhoto().mediaIndex);
        assertSame(second, queue.completeNextPhoto().bundle);
    }

    @Test
    public void videosRemainFifoAcrossActivationAndCompletion() {
        AtlasCaptureBundleRequest first =
                AtlasCaptureBundleRequest.create(
                        "event-a",
                        1,
                        1000L,
                        5);
        AtlasCaptureBundleRequest second =
                AtlasCaptureBundleRequest.create(
                        "event-b",
                        2,
                        2000L,
                        6);
        AtlasAutoCaptureQueue queue = new AtlasAutoCaptureQueue();

        queue.enqueueVideo(first);
        queue.enqueueVideo(second);

        assertSame(first, queue.activateNextVideo());
        assertSame(first, queue.completeActiveVideo());
        assertSame(second, queue.activateNextVideo());
    }
}
