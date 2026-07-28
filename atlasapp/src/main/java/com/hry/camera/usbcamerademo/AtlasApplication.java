package com.hry.camera.usbcamerademo;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.content.ComponentCallbacks2;
import android.os.SystemClock;

public class AtlasApplication extends Application {
    private Thread.UncaughtExceptionHandler previousHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        ResearchInteractionLogger.initialize(this);
        ResearchSessionTracker.recoverInterrupted(
                this,
                System.currentTimeMillis(),
                SystemClock.elapsedRealtime());
        AtlasDevLogger.i(this, "AtlasApplication", AtlasDevLogger.buildSessionBanner("process_start"));
        AtlasResurfacingManager.initialize(this);
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                AtlasDevLogger.e(AtlasApplication.this, "UncaughtException",
                        "thread=" + (thread == null ? "unknown" : thread.getName()), throwable);
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, throwable);
                }
            }
        });
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { log(activity, "created"); }
            @Override public void onActivityStarted(Activity activity) { log(activity, "started"); }
            @Override public void onActivityResumed(Activity activity) { log(activity, "resumed"); }
            @Override public void onActivityPaused(Activity activity) { log(activity, "paused"); }
            @Override public void onActivityStopped(Activity activity) { log(activity, "stopped"); }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { log(activity, "save_instance_state"); }
            @Override public void onActivityDestroyed(Activity activity) { log(activity, "destroyed"); }
            private void log(Activity activity, String state) {
                AtlasDevLogger.i(AtlasApplication.this, "ActivityLifecycle",
                        activity.getClass().getSimpleName() + " " + state);
            }
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        AtlasDevLogger.w(this, "AtlasApplication", "onTrimMemory level=" + level
                + " ui_hidden=" + (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN));
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        AtlasDevLogger.w(this, "AtlasApplication", "onLowMemory");
    }
}
