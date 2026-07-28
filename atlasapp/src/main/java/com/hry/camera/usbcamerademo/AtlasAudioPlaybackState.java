package com.hry.camera.usbcamerademo;

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

    private static boolean hasPath(String path) {
        return path != null && path.length() > 0;
    }
}
