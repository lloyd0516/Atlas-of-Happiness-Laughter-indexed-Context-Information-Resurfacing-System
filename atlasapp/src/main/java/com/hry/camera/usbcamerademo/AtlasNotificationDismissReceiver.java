package com.hry.camera.usbcamerademo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Records a user swipe/dismiss for one notification instance. */
public class AtlasNotificationDismissReceiver
        extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ResearchNotificationTracker.logDismissed(
                context, intent);
    }
}
