package com.hry.camera.usbcamerademo;

import android.content.Context;

/** Single reconciliation entry point used by app and system lifecycle events. */
final class AtlasResurfacingManager {
    private AtlasResurfacingManager() {
    }

    static void initialize(Context context) {
        if (context == null) {
            return;
        }
        AtlasNotificationHelper.ensureChannels(context);
        AtlasDailyReminderScheduler.reconcile(context);
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                AtlasLocationReminderRegistrar.sync(appContext);
            }
        }, "atlas-location-registration").start();
    }

    static void reconcileNow(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        AtlasNotificationHelper.ensureChannels(appContext);
        AtlasDailyReminderScheduler.reconcile(appContext);
        AtlasLocationReminderRegistrar.sync(appContext);
    }

    static void refreshLocationsAsync(final Context context) {
        if (context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                AtlasLocationReminderRegistrar.sync(appContext);
            }
        }, "atlas-location-refresh").start();
    }
}
