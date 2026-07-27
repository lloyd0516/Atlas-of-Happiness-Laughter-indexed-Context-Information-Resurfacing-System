package com.hry.camera.usbcamerademo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AtlasLocationReminderPolicyTest {
    @Test
    public void sixHourOldEventIsEligible() {
        long now = 10L * 60L * 60L * 1000L;
        assertTrue(AtlasReminderSchedule.isOldEnough(
                now - AppConfig.SPECIAL_MIN_EVENT_AGE_MS, now));
    }

    @Test
    public void youngerEventIsRejected() {
        long now = 10L * 60L * 60L * 1000L;
        assertFalse(AtlasReminderSchedule.isOldEnough(
                now - AppConfig.SPECIAL_MIN_EVENT_AGE_MS + 1L, now));
    }

    @Test
    public void twoHourCooldownBoundaryIsEligible() {
        long lastSent = 1000L;
        assertTrue(AtlasReminderSchedule.cooldownElapsed(
                lastSent, lastSent + AppConfig.SPECIAL_NOTIFICATION_COOLDOWN_MS));
        assertFalse(AtlasReminderSchedule.cooldownElapsed(
                lastSent, lastSent + AppConfig.SPECIAL_NOTIFICATION_COOLDOWN_MS - 1L));
    }

    @Test
    public void noPreviousLocationNotificationPassesCooldown() {
        assertTrue(AtlasReminderSchedule.cooldownElapsed(0L, 1L));
    }
}
