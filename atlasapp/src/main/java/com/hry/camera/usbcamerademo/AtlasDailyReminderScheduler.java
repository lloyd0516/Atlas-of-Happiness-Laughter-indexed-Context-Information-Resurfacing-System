package com.hry.camera.usbcamerademo;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.TimeZone;

/** Schedules one-shot alarms so every delivery recalculates the next local 19:30. */
final class AtlasDailyReminderScheduler {
    static final String EXTRA_RETRY_COUNT = "daily_retry_count";
    private static final int REQUEST_REGULAR = 0x4101;
    private static final int REQUEST_RETRY = 0x4102;

    private AtlasDailyReminderScheduler() {
    }

    static void reconcile(Context context) {
        AtlasReminderPreferences preferences = new AtlasReminderPreferences(context);
        if (preferences.isDailyEnabled()) {
            long nowMs = System.currentTimeMillis();
            scheduleNext(context, nowMs);
            String today = AtlasReminderSchedule.localDate(
                    nowMs, TimeZone.getDefault());
            boolean missingCategory =
                    !preferences.wasDailyCategorySent(
                            AtlasDailyReminderReceiver.CATEGORY_SHORT, today)
                    || !preferences.wasDailyCategorySent(
                            AtlasDailyReminderReceiver.CATEGORY_LONG, today);
            if (missingCategory && AtlasReminderSchedule.dailyTimeHasPassed(
                    nowMs, TimeZone.getDefault())) {
                schedule(
                        context,
                        nowMs + AppConfig.DAILY_REVIEW_CATCH_UP_DELAY_MS,
                        REQUEST_RETRY,
                        0);
            }
        } else {
            cancel(context);
        }
    }

    static void scheduleNext(Context context, long nowMs) {
        long triggerAt = AtlasReminderSchedule.nextDailyTrigger(
                nowMs, TimeZone.getDefault());
        schedule(context, triggerAt, REQUEST_REGULAR, 0);
    }

    static void scheduleRetry(Context context, int retryCount) {
        if (retryCount > AppConfig.DAILY_REVIEW_MAX_RETRIES) {
            return;
        }
        schedule(context,
                System.currentTimeMillis() + AppConfig.DAILY_REVIEW_RETRY_DELAY_MS,
                REQUEST_RETRY,
                retryCount);
    }

    static void cancel(Context context) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        alarmManager.cancel(pendingIntent(context, REQUEST_REGULAR, 0));
        alarmManager.cancel(pendingIntent(context, REQUEST_RETRY, 0));
    }

    private static void schedule(
            Context context, long triggerAtMs, int requestCode, int retryCount) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        PendingIntent operation = pendingIntent(context, requestCode, retryCount);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAtMs, operation);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, operation);
            }
        } catch (SecurityException exactAlarmDenied) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAtMs, operation);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, operation);
            }
            AtlasDevLogger.w(context, "DailyReminder",
                    "Exact alarm unavailable; scheduled an inexact fallback.");
        }
    }

    private static PendingIntent pendingIntent(
            Context context, int requestCode, int retryCount) {
        Intent intent = new Intent(context, AtlasDailyReminderReceiver.class);
        intent.putExtra(EXTRA_RETRY_COUNT, retryCount);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
