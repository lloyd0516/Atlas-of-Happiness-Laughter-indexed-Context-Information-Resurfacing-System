package com.hry.camera.usbcamerademo;

import java.util.Locale;

final class AtlasAudioPlaybackState {
    enum Status {
        IDLE,
        PLAYING,
        PAUSED,
        ERROR
    }

    enum Event {
        PLAY_REQUESTED,
        TOGGLE_REQUESTED,
        COMPLETED,
        STOPPED,
        FAILED
    }

    static final class State {
        final String path;
        final Status status;

        State(String path, Status status) {
            this.path = path;
            this.status = status == null ? Status.IDLE : status;
        }
    }

    private AtlasAudioPlaybackState() {
    }

    static State transition(State current, Event event, String path) {
        State safe = current == null ? new State(null, Status.IDLE) : current;
        if (event == null) {
            return safe;
        }
        switch (event) {
            case PLAY_REQUESTED:
                return hasPath(path)
                        ? new State(path, Status.PLAYING)
                        : new State(null, Status.ERROR);
            case TOGGLE_REQUESTED:
                if (hasPath(path) && path.equals(safe.path)) {
                    if (safe.status == Status.PLAYING) {
                        return new State(path, Status.PAUSED);
                    }
                    if (safe.status == Status.PAUSED) {
                        return new State(path, Status.PLAYING);
                    }
                }
                return hasPath(path)
                        ? new State(path, Status.PLAYING)
                        : new State(null, Status.ERROR);
            case FAILED:
                return new State(null, Status.ERROR);
            case COMPLETED:
            case STOPPED:
            default:
                return new State(null, Status.IDLE);
        }
    }

    static float progress(long positionMs, long durationMs) {
        if (durationMs <= 0L) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, positionMs / (float) durationMs));
    }

    static String formatTime(long positionMs) {
        long totalSeconds = Math.max(0L, positionMs) / 1000L;
        return String.format(
                Locale.US,
                "%02d:%02d",
                totalSeconds / 60L,
                totalSeconds % 60L);
    }

    private static boolean hasPath(String path) {
        return path != null && path.length() > 0;
    }
}
