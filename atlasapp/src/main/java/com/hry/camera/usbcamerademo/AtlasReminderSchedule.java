package com.hry.camera.usbcamerademo;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Pure calendar and policy helpers shared by daily and location reminders. */
final class AtlasReminderSchedule {
    private static final String LOCAL_DATE_PATTERN = "yyyy-MM-dd";

    private AtlasReminderSchedule() {
    }

    static long nextDailyTrigger(long nowMs, TimeZone timeZone) {
        Calendar trigger = Calendar.getInstance(timeZone);
        trigger.setTimeInMillis(nowMs);
        trigger.set(Calendar.HOUR_OF_DAY, AppConfig.DAILY_REVIEW_HOUR);
        trigger.set(Calendar.MINUTE, AppConfig.DAILY_REVIEW_MINUTE);
        trigger.set(Calendar.SECOND, 0);
        trigger.set(Calendar.MILLISECOND, 0);
        if (trigger.getTimeInMillis() <= nowMs) {
            trigger.add(Calendar.DAY_OF_YEAR, 1);
        }
        return trigger.getTimeInMillis();
    }

    static long[] dayWindow(long nowMs, int dayOffset, TimeZone timeZone) {
        Calendar start = Calendar.getInstance(timeZone);
        start.setTimeInMillis(nowMs);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        start.add(Calendar.DAY_OF_YEAR, -dayOffset);

        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_YEAR, 1);
        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }

    static long preferredTimeInWindow(long dayStartMs, TimeZone timeZone) {
        Calendar preferred = Calendar.getInstance(timeZone);
        preferred.setTimeInMillis(dayStartMs);
        preferred.set(Calendar.HOUR_OF_DAY, AppConfig.DAILY_REVIEW_HOUR);
        preferred.set(Calendar.MINUTE, AppConfig.DAILY_REVIEW_MINUTE);
        preferred.set(Calendar.SECOND, 0);
        preferred.set(Calendar.MILLISECOND, 0);
        return preferred.getTimeInMillis();
    }

    static boolean dailyTimeHasPassed(long nowMs, TimeZone timeZone) {
        Calendar today = Calendar.getInstance(timeZone);
        today.setTimeInMillis(nowMs);
        today.set(Calendar.HOUR_OF_DAY, AppConfig.DAILY_REVIEW_HOUR);
        today.set(Calendar.MINUTE, AppConfig.DAILY_REVIEW_MINUTE);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        return nowMs >= today.getTimeInMillis();
    }

    static boolean isOldEnough(long eventStartedAtMs, long nowMs) {
        return eventStartedAtMs > 0L
                && nowMs >= eventStartedAtMs
                && nowMs - eventStartedAtMs >= AppConfig.SPECIAL_MIN_EVENT_AGE_MS;
    }

    static boolean cooldownElapsed(long lastSentAtMs, long nowMs) {
        return lastSentAtMs <= 0L
                || (nowMs >= lastSentAtMs
                && nowMs - lastSentAtMs >= AppConfig.SPECIAL_NOTIFICATION_COOLDOWN_MS);
    }

    static String localDate(long timestampMs, TimeZone timeZone) {
        SimpleDateFormat formatter = new SimpleDateFormat(LOCAL_DATE_PATTERN, Locale.US);
        formatter.setTimeZone(timeZone);
        return formatter.format(new Date(timestampMs));
    }
}
