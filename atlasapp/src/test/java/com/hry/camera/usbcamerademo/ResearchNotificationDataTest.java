package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

public class ResearchNotificationDataTest {
    @Test
    public void responseDelayIsNonNegative() {
        assertEquals(
                5000L,
                ResearchNotificationData.responseDelay(
                        1000L, 6000L));
        assertEquals(
                0L,
                ResearchNotificationData.responseDelay(
                        6000L, 1000L));
    }

    @Test
    public void openedAndDismissedHaveDifferentIdempotencyKeys() {
        assertEquals(
                "n1:opened",
                ResearchNotificationData.actionKey(
                        "n1", "opened"));
        assertNotEquals(
                ResearchNotificationData.actionKey(
                        "n1", "opened"),
                ResearchNotificationData.actionKey(
                        "n1", "dismissed"));
    }

    @Test
    public void locationPropertiesUseAnonymousClusterOnly()
            throws Exception {
        JSONObject properties =
                ResearchNotificationData.properties(
                        "location",
                        2301,
                        1000L,
                        "map",
                        null,
                        ResearchIdentifiers.anonymousId(
                                "location", "39.9,116.4"));

        assertFalse(properties.toString().contains("39.9"));
        assertFalse(properties.toString().contains("116.4"));
    }
}
