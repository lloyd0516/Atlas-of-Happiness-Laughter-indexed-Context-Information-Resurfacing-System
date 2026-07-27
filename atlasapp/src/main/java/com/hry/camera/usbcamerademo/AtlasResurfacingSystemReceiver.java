package com.hry.camera.usbcamerademo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores reminder infrastructure after reboot, clock changes, and app replacement. */
public class AtlasResurfacingSystemReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pendingResult = goAsync();
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AtlasResurfacingManager.reconcileNow(appContext);
                } finally {
                    pendingResult.finish();
                }
            }
        }, "atlas-system-reconcile").start();
    }
}
