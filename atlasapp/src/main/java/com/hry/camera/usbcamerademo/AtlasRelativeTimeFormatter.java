package com.hry.camera.usbcamerademo;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Requirement 4: short-term review header uses a WeChat-style relative time rule instead of a
 * raw duration-since string, so "just now" / "3 minutes ago" / "today" / "yesterday" / weekday /
 * full date kick in at the same breakpoints users already know from chat apps.
 */
public final class AtlasRelativeTimeFormatter {
    private AtlasRelativeTimeFormatter() {
    }

    public static String format(Context context, long timestampMs, long nowMs) {
        long deltaMs = nowMs - timestampMs;
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String time = timeFormat.format(new Date(timestampMs));

        if (deltaMs < AppConfig.RECENCY_JUST_NOW_MS) {
            return context.getString(R.string.recency_just_now);
        }
        if (deltaMs < AppConfig.RECENCY_MINUTES_MS) {
            long minutes = Math.max(1, deltaMs / 60000L);
            return context.getString(R.string.recency_minutes_ago, minutes);
        }

        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestampMs);
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMs);

        if (isSameDay(target, now)) {
            return context.getString(R.string.recency_today_earlier) + " " + time;
        }

        Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        if (isSameDay(target, yesterday)) {
            return context.getString(R.string.recency_yesterday) + " " + time;
        }

        if (deltaMs < 7L * 24 * 60 * 60 * 1000L && target.get(Calendar.WEEK_OF_YEAR) == now.get(Calendar.WEEK_OF_YEAR)
                && target.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            SimpleDateFormat weekdayFormat = new SimpleDateFormat("EEEE", Locale.getDefault());
            return weekdayFormat.format(new Date(timestampMs)) + " " + time;
        }

        if (target.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            SimpleDateFormat monthDayFormat = new SimpleDateFormat("M月d日", Locale.getDefault());
            return monthDayFormat.format(new Date(timestampMs)) + " " + time;
        }

        SimpleDateFormat fullFormat = new SimpleDateFormat("yyyy年M月d日", Locale.getDefault());
        return fullFormat.format(new Date(timestampMs)) + " " + time;
    }

    /** Requirement 4 long-term header, e.g. "182天前 · 2026.06.29". */
    public static String formatLongTermHeader(Context context, long timestampMs, long nowMs) {
        long days = Math.max(0, (nowMs - timestampMs) / (24L * 60 * 60 * 1000L));
        SimpleDateFormat dotFormat = new SimpleDateFormat("yyyy.MM.dd", Locale.US);
        return context.getString(R.string.recency_days_ago, days) + " · " + dotFormat.format(new Date(timestampMs));
    }

    public static boolean isLongTerm(long timestampMs, long nowMs) {
        return (nowMs - timestampMs) >= AppConfig.LONG_TERM_THRESHOLD_MS;
    }

    private static boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
