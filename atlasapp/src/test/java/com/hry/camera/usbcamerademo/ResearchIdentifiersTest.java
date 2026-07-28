package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ResearchIdentifiersTest {
    @Test
    public void anonymousIdsAreStableAndDoNotExposeInput() {
        String path = "/storage/emulated/0/private.wav";
        String id = ResearchIdentifiers.anonymousId("media", path);

        assertEquals(id, ResearchIdentifiers.anonymousId("media", path));
        assertFalse(id.contains("private.wav"));
        assertNotEquals(id, ResearchIdentifiers.anonymousId(
                "media", "/storage/emulated/0/other.wav"));
        assertTrue(id.startsWith("media_"));
    }

    @Test
    public void notificationInstancesAreUnique() {
        assertNotEquals(
                ResearchIdentifiers.notificationInstanceId(),
                ResearchIdentifiers.notificationInstanceId());
    }
}
