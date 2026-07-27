package com.hry.camera.usbcamerademo;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AtlasReminderScheduleTest {
    private static long localMillis(
            TimeZone zone, int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    @Test
    public void nextTriggerBefore1930IsToday() {
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        long now = localMillis(zone, 2026, Calendar.JULY, 28, 19, 29);
        assertEquals(
                localMillis(zone, 2026, Calendar.JULY, 28, 19, 30),
                AtlasReminderSchedule.nextDailyTrigger(now, zone));
    }

    @Test
    public void nextTriggerAt1930IsTomorrow() {
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        long now = localMillis(zone, 2026, Calendar.JULY, 28, 19, 30);
        assertEquals(
                localMillis(zone, 2026, Calendar.JULY, 29, 19, 30),
                AtlasReminderSchedule.nextDailyTrigger(now, zone));
    }

    @Test
    public void dayWindowUsesCalendarDayAcrossDst() {
        TimeZone zone = TimeZone.getTimeZone("America/New_York");
        long now = localMillis(zone, 2026, Calendar.MARCH, 9, 12, 0);
        long[] window = AtlasReminderSchedule.dayWindow(now, 1, zone);
        assertEquals(localMillis(zone, 2026, Calendar.MARCH, 8, 0, 0), window[0]);
        assertEquals(localMillis(zone, 2026, Calendar.MARCH, 9, 0, 0), window[1]);
        assertEquals(23L * 60L * 60L * 1000L, window[1] - window[0]);
    }

    @Test
    public void catchUpOnlyAppliesAfterDailyTime() {
        TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
        assertFalse(AtlasReminderSchedule.dailyTimeHasPassed(
                localMillis(zone, 2026, Calendar.JULY, 28, 19, 29), zone));
        assertTrue(AtlasReminderSchedule.dailyTimeHasPassed(
                localMillis(zone, 2026, Calendar.JULY, 28, 19, 30), zone));
    }
}
