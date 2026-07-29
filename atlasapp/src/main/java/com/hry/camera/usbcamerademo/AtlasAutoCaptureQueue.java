package com.hry.camera.usbcamerademo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** FIFO state for asynchronous automatic video and photo capture requests. */
final class AtlasAutoCaptureQueue {
    private final ArrayDeque<AtlasCaptureBundleRequest> pendingVideos =
            new ArrayDeque<>();
    private final ArrayDeque<AtlasCaptureBundleRequest.PhotoRequest>
            queuedPhotos = new ArrayDeque<>();
    private final ArrayDeque<AtlasCaptureBundleRequest.PhotoRequest>
            dispatchedPhotos = new ArrayDeque<>();
    private AtlasCaptureBundleRequest activeVideo;

    void enqueueVideo(AtlasCaptureBundleRequest request) {
        pendingVideos.addLast(requireBundle(request));
    }

    AtlasCaptureBundleRequest activateNextVideo() {
        if (activeVideo != null || pendingVideos.isEmpty()) {
            return null;
        }
        activeVideo = pendingVideos.removeFirst();
        return activeVideo;
    }

    AtlasCaptureBundleRequest activeVideo() {
        return activeVideo;
    }

    AtlasCaptureBundleRequest completeActiveVideo() {
        AtlasCaptureBundleRequest completed = activeVideo;
        activeVideo = null;
        return completed;
    }

    void enqueuePhoto(
            AtlasCaptureBundleRequest.PhotoRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "photo request missing");
        }
        queuedPhotos.addLast(request);
    }

    AtlasCaptureBundleRequest.PhotoRequest dispatchNextPhoto() {
        if (queuedPhotos.isEmpty()) {
            return null;
        }
        AtlasCaptureBundleRequest.PhotoRequest request =
                queuedPhotos.removeFirst();
        dispatchedPhotos.addLast(request);
        return request;
    }

    AtlasCaptureBundleRequest.PhotoRequest completeNextPhoto() {
        return dispatchedPhotos.isEmpty()
                ? null
                : dispatchedPhotos.removeFirst();
    }

    int pendingVideoCount() {
        return pendingVideos.size();
    }

    int queuedPhotoCount() {
        return queuedPhotos.size();
    }

    int dispatchedPhotoCount() {
        return dispatchedPhotos.size();
    }

    boolean hasWork() {
        return activeVideo != null
                || !pendingVideos.isEmpty()
                || !queuedPhotos.isEmpty()
                || !dispatchedPhotos.isEmpty();
    }

    List<AtlasCaptureBundleRequest> drainAllVideos() {
        ArrayList<AtlasCaptureBundleRequest> drained =
                new ArrayList<>();
        if (activeVideo != null) {
            drained.add(activeVideo);
            activeVideo = null;
        }
        while (!pendingVideos.isEmpty()) {
            drained.add(pendingVideos.removeFirst());
        }
        return drained;
    }

    List<AtlasCaptureBundleRequest.PhotoRequest> drainAllPhotos() {
        ArrayList<AtlasCaptureBundleRequest.PhotoRequest> drained =
                new ArrayList<>();
        while (!dispatchedPhotos.isEmpty()) {
            drained.add(dispatchedPhotos.removeFirst());
        }
        while (!queuedPhotos.isEmpty()) {
            drained.add(queuedPhotos.removeFirst());
        }
        return drained;
    }

    private AtlasCaptureBundleRequest requireBundle(
            AtlasCaptureBundleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "bundle request missing");
        }
        return request;
    }
}
