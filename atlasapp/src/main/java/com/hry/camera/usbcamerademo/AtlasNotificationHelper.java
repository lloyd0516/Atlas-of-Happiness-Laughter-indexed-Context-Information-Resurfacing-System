package com.hry.camera.usbcamerademo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Creates Atlas-styled channels and notification payloads. */
final class AtlasNotificationHelper {
    static final int NOTIFICATION_ID_DAILY_SHORT = 2101;
    static final int NOTIFICATION_ID_DAILY_LONG = 2102;
    static final String CHANNEL_DAILY = "atlas_daily_resurfacing";
    static final String CHANNEL_LOCATION = "atlas_location_resurfacing";
    private static final int COLOR_ORANGE = Color.rgb(255, 152, 47);

    private AtlasNotificationHelper() {
    }

    static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        applySavedLocale(context);
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel daily = new NotificationChannel(
                CHANNEL_DAILY,
                context.getString(R.string.notification_channel_daily),
                NotificationManager.IMPORTANCE_DEFAULT);
        daily.setDescription(context.getString(R.string.notification_channel_daily_description));
        NotificationChannel location = new NotificationChannel(
                CHANNEL_LOCATION,
                context.getString(R.string.notification_channel_location),
                NotificationManager.IMPORTANCE_DEFAULT);
        location.setDescription(
                context.getString(R.string.notification_channel_location_description));
        manager.createNotificationChannel(daily);
        manager.createNotificationChannel(location);
    }

    static boolean postDaily(
            Context context,
            AtlasReviewRepository.EventSummary event,
            boolean longTerm) {
        int notificationId = longTerm
                ? NOTIFICATION_ID_DAILY_LONG
                : NOTIFICATION_ID_DAILY_SHORT;
        ResearchNotificationTracker.Metadata metadata =
                new ResearchNotificationTracker.Metadata(
                        ResearchIdentifiers.notificationInstanceId(),
                        longTerm ? "long" : "short",
                        notificationId,
                        System.currentTimeMillis(),
                        longTerm
                                ? "long_detail"
                                : "short_detail",
                        event == null ? null : event.sessionId,
                        event == null ? null : event.eventId,
                        null);
        if (event == null) {
            ResearchNotificationTracker.logPostFailed(
                    context, metadata, "invalid_moment");
            return false;
        }
        if (!canPost(context)) {
            ResearchNotificationTracker.logPostFailed(
                    context, metadata, "permission_denied");
            return false;
        }
        try {
            ensureChannels(context);
            applySavedLocale(context);
            int titleRes = longTerm
                    ? R.string.notification_long_title
                    : R.string.notification_short_title;
            String title = context.getString(titleRes);
            String body;
            if (longTerm) {
                String place = TextUtils.isEmpty(
                        event.locationName)
                        ? context.getString(
                                R.string.notification_place_fallback)
                        : event.locationName;
                body = context.getString(
                        R.string.notification_long_body, place);
            } else {
                String time = new SimpleDateFormat(
                        "HH:mm", Locale.getDefault())
                        .format(new Date(event.startTimeMs));
                body = context.getString(
                        R.string.notification_short_body, time);
            }

            Intent detail = new Intent(
                    context, EventDetailActivity.class);
            detail.putExtra("event_id", event.eventId);
            detail.putExtra("session_id", event.sessionId);
            detail.putExtra(
                    EventDetailActivity.EXTRA_RESURFACING_MODE,
                    longTerm ? "long" : "short");
            ResearchNavigation.withSource(
                    detail,
                    longTerm
                            ? "notification_long"
                            : "notification_short");
            ResearchNotificationTracker.attach(
                    detail, metadata);
            detail.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int requestCode =
                    ResearchNotificationTracker.requestCode(
                            metadata.instanceId);
            PendingIntent contentIntent =
                    PendingIntent.getActivity(
                            context,
                            requestCode,
                            detail,
                            PendingIntent.FLAG_UPDATE_CURRENT
                                    | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent deleteIntent =
                    ResearchNotificationTracker.dismissIntent(
                            context, metadata);

            Notification notification = build(
                    context,
                    CHANNEL_DAILY,
                    title,
                    body,
                    contentIntent,
                    deleteIntent);
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                ResearchNotificationTracker.logPostFailed(
                        context,
                        metadata,
                        "manager_unavailable");
                return false;
            }
            manager.notify(notificationId, notification);
            ResearchNotificationTracker.logPosted(
                    context, metadata);
            return true;
        } catch (RuntimeException error) {
            ResearchNotificationTracker.logPostFailed(
                    context, metadata, "runtime_exception");
            throw error;
        }
    }

    static boolean postLocation(
            Context context, String clusterKey, double lat, double lng) {
        int notificationId = 2300
                + (clusterKey == null
                ? 0
                : clusterKey.hashCode() & 0x3fff);
        ResearchNotificationTracker.Metadata metadata =
                new ResearchNotificationTracker.Metadata(
                        ResearchIdentifiers.notificationInstanceId(),
                        "location",
                        notificationId,
                        System.currentTimeMillis(),
                        "map",
                        null,
                        null,
                        ResearchIdentifiers.anonymousId(
                                "location", clusterKey));
        if (clusterKey == null
                || Double.isNaN(lat)
                || Double.isNaN(lng)) {
            ResearchNotificationTracker.logPostFailed(
                    context, metadata, "invalid_cluster");
            return false;
        }
        if (!canPost(context)) {
            ResearchNotificationTracker.logPostFailed(
                    context, metadata, "permission_denied");
            return false;
        }
        try {
            ensureChannels(context);
            applySavedLocale(context);
            Intent review = new Intent(
                    context, ReviewShellActivity.class);
            review.putExtra("initial_review_tab", "map");
            review.putExtra("focus_lat", lat);
            review.putExtra("focus_lng", lng);
            review.putExtra(
                    "focus_radius_m",
                    AppConfig.SPECIAL_LOCATION_RADIUS_METERS);
            ResearchNavigation.withSource(
                    review, "notification_location");
            ResearchNotificationTracker.attach(
                    review, metadata);
            review.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int requestCode =
                    ResearchNotificationTracker.requestCode(
                            metadata.instanceId);
            PendingIntent contentIntent =
                    PendingIntent.getActivity(
                            context,
                            requestCode,
                            review,
                            PendingIntent.FLAG_UPDATE_CURRENT
                                    | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent deleteIntent =
                    ResearchNotificationTracker.dismissIntent(
                            context, metadata);
            String title = context.getString(
                    R.string.notification_location_title);
            String body = context.getString(
                    R.string.notification_location_body);
            Notification notification = build(
                    context,
                    CHANNEL_LOCATION,
                    title,
                    body,
                    contentIntent,
                    deleteIntent);
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                ResearchNotificationTracker.logPostFailed(
                        context,
                        metadata,
                        "manager_unavailable");
                return false;
            }
            manager.notify(notificationId, notification);
            ResearchNotificationTracker.logPosted(
                    context, metadata);
            return true;
        } catch (RuntimeException error) {
            ResearchNotificationTracker.logPostFailed(
                    context, metadata, "runtime_exception");
            throw error;
        }
    }

    static boolean canPost(Context context) {
        return Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(
                context, "android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
    }

    static Notification build(
            Context context,
            String channel,
            String title,
            String body,
            PendingIntent contentIntent,
            PendingIntent deleteIntent) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, channel);
        } else {
            builder = new Notification.Builder(context);
            builder.setPriority(Notification.PRIORITY_DEFAULT);
        }
        return builder
                .setSmallIcon(R.drawable.ic_atlas_notification)
                .setColor(COLOR_ORANGE)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setDeleteIntent(deleteIntent)
                .build();
    }

    private static void applySavedLocale(Context context) {
        AtlasLocaleManager.apply(
                context, AtlasLocaleManager.getSavedLanguage(context));
    }
}
