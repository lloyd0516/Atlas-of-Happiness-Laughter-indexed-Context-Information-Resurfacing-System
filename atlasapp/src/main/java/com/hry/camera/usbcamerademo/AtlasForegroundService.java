package com.hry.camera.usbcamerademo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class AtlasForegroundService extends Service {
    public static final String ACTION_START = "com.hry.camera.usbcamerademo.action.ATLAS_KEEPALIVE_START";
    public static final String ACTION_STOP = "com.hry.camera.usbcamerademo.action.ATLAS_KEEPALIVE_STOP";

    private static final String TAG = "AtlasForegroundService";
    private static final String CHANNEL_ID = "atlas_joyful_keepalive";
    private static final int NOTIFICATION_ID = 1101;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void startKeepAlive(Context context) {
        Intent intent = new Intent(context, AtlasForegroundService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopKeepAlive(Context context) {
        Intent intent = new Intent(context, AtlasForegroundService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Atlas is listening for laughter"));
        acquireLocks();
        AtlasDevLogger.i(this, TAG, "created foreground keepalive service");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        AtlasDevLogger.i(this, TAG, "onStartCommand action=" + action + " flags=" + flags + " startId=" + startId);
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireLocks();
        startForeground(NOTIFICATION_ID, buildNotification("Atlas is listening for laughter"));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        AtlasDevLogger.i(this, TAG, "destroyed foreground keepalive service");
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AtlasOfHappiness:JoyfulWakeLock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
            AtlasDevLogger.i(this, TAG, "partial wakelock acquired");
        }
        if (wifiLock == null) {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "AtlasOfHappiness:JoyfulWifiLock");
                wifiLock.setReferenceCounted(false);
            }
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
            AtlasDevLogger.i(this, TAG, "wifi lock acquired");
        }
    }

    private void releaseLocks() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception e) {
            AtlasDevLogger.e(this, TAG, "wifi lock release failed", e);
        }
        wifiLock = null;
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            AtlasDevLogger.e(this, TAG, "wakelock release failed", e);
        }
        wakeLock = null;
    }

    private Notification buildNotification(String text) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_atlas_notification)
                .setContentTitle("Atlas of Happiness")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_SERVICE);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Atlas Joyful Keepalive",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps microphone/network processing alive while the screen is off.");
        manager.createNotificationChannel(channel);
    }
}
