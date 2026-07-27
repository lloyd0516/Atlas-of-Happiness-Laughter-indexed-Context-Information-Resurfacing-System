package com.hry.camera.usbcamerademo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;
import java.util.TimeZone;

/** Independently delivers yesterday's Short and seven-days-ago Long reminders. */
public class AtlasDailyReminderReceiver extends BroadcastReceiver {
    static final String CATEGORY_SHORT = "short";
    static final String CATEGORY_LONG = "long";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final PendingResult pendingResult = goAsync();
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int retryCount = intent == null
                        ? 0 : intent.getIntExtra(
                        AtlasDailyReminderScheduler.EXTRA_RETRY_COUNT, 0);
                try {
                    AtlasDailyReminderScheduler.scheduleNext(
                            appContext, System.currentTimeMillis());
                    deliver(appContext);
                } catch (Exception error) {
                    AtlasDevLogger.e(appContext, "DailyReminder",
                            "Daily resurfacing failed; retry=" + retryCount, error);
                    AtlasDailyReminderScheduler.scheduleRetry(
                            appContext, retryCount + 1);
                } finally {
                    pendingResult.finish();
                }
            }
        }, "atlas-daily-reminder").start();
    }

    private void deliver(Context context) {
        AtlasReminderPreferences preferences = new AtlasReminderPreferences(context);
        if (!preferences.isDailyEnabled()) {
            AtlasDailyReminderScheduler.cancel(context);
            return;
        }

        long nowMs = System.currentTimeMillis();
        TimeZone timeZone = TimeZone.getDefault();
        String today = AtlasReminderSchedule.localDate(nowMs, timeZone);
        AtlasReviewRepository repository = new AtlasReviewRepository(context);
        List<AtlasReviewRepository.EventSummary> events =
                repository.loadEventSummaries();
        AtlasResurfacingSelector selector = new AtlasResurfacingSelector();

        deliverCategory(
                context, preferences, selector, events, nowMs, timeZone, today,
                CATEGORY_SHORT, AppConfig.SHORT_TERM_DAY_OFFSET, false);
        deliverCategory(
                context, preferences, selector, events, nowMs, timeZone, today,
                CATEGORY_LONG, AppConfig.LONG_TERM_DAY_OFFSET, true);
    }

    private void deliverCategory(
            Context context,
            AtlasReminderPreferences preferences,
            AtlasResurfacingSelector selector,
            List<AtlasReviewRepository.EventSummary> events,
            long nowMs,
            TimeZone timeZone,
            String today,
            String category,
            int dayOffset,
            boolean longTerm) {
        if (preferences.wasDailyCategorySent(category, today)) {
            return;
        }
        long[] window = AtlasReminderSchedule.dayWindow(nowMs, dayOffset, timeZone);
        AtlasReviewRepository.EventSummary selected =
                selector.selectForCalendarDay(
                        events,
                        window[0],
                        window[1],
                        AtlasReminderSchedule.preferredTimeInWindow(window[0], timeZone));
        if (selected == null) {
            return;
        }
        if (!AtlasNotificationHelper.postDaily(context, selected, longTerm)) {
            throw new IllegalStateException("Notification permission or manager unavailable");
        }
        preferences.markDailyCategorySent(category, today);
    }
}
