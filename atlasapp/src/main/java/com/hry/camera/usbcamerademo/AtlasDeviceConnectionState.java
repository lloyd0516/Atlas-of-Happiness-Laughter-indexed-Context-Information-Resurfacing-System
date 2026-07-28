package com.hry.camera.usbcamerademo;

/** Pure state reducer for the USB camera status shown on the recording home page. */
final class AtlasDeviceConnectionState {
    enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    enum Event {
        CONNECT_REQUESTED,
        USB_ARRIVED,
        USB_PERMISSION_GRANTED,
        CAMERA_OPENED,
        NO_DEVICE,
        USB_REMOVED,
        USB_PERMISSION_DENIED,
        CAMERA_OPEN_FAILED,
        PREVIEW_FAILED
    }

    private AtlasDeviceConnectionState() {
    }

    static State transition(State current, Event event) {
        if (event == null) {
            return current == null ? State.DISCONNECTED : current;
        }
        switch (event) {
            case CONNECT_REQUESTED:
            case USB_ARRIVED:
            case USB_PERMISSION_GRANTED:
                return State.CONNECTING;
            case CAMERA_OPENED:
                return State.CONNECTED;
            case NO_DEVICE:
            case USB_REMOVED:
            case USB_PERMISSION_DENIED:
            case CAMERA_OPEN_FAILED:
            case PREVIEW_FAILED:
            default:
                return State.DISCONNECTED;
        }
    }
}
