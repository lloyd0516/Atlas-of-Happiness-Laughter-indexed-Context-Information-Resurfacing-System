package com.hry.camera.usbcamerademo;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

public class AtlasDeviceConnectionStateTest {
    @Test
    public void connectionLifecycleUsesCameraReadinessAsTheConnectedSignal() throws Exception {
        assertTransition("DISCONNECTED", "CONNECT_REQUESTED", "CONNECTING");
        assertTransition("DISCONNECTED", "USB_ARRIVED", "CONNECTING");
        assertTransition("CONNECTING", "USB_PERMISSION_GRANTED", "CONNECTING");
        assertTransition("CONNECTING", "CAMERA_OPENED", "CONNECTED");
    }

    @Test
    public void removalPermissionAndCameraFailuresReturnToDisconnected() throws Exception {
        assertTransition("CONNECTING", "NO_DEVICE", "DISCONNECTED");
        assertTransition("CONNECTED", "USB_REMOVED", "DISCONNECTED");
        assertTransition("CONNECTING", "USB_PERMISSION_DENIED", "DISCONNECTED");
        assertTransition("CONNECTING", "CAMERA_OPEN_FAILED", "DISCONNECTED");
        assertTransition("CONNECTING", "PREVIEW_FAILED", "DISCONNECTED");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertTransition(String from, String event, String expected) throws Exception {
        Class<?> policy = Class.forName(
                "com.hry.camera.usbcamerademo.AtlasDeviceConnectionState");
        Class<? extends Enum> stateType = (Class<? extends Enum>) Class.forName(
                "com.hry.camera.usbcamerademo.AtlasDeviceConnectionState$State");
        Class<? extends Enum> eventType = (Class<? extends Enum>) Class.forName(
                "com.hry.camera.usbcamerademo.AtlasDeviceConnectionState$Event");
        Method transition = policy.getDeclaredMethod("transition", stateType, eventType);
        transition.setAccessible(true);
        Object result = transition.invoke(
                null,
                Enum.valueOf(stateType, from),
                Enum.valueOf(eventType, event));
        assertEquals(expected, String.valueOf(result));
    }
}
