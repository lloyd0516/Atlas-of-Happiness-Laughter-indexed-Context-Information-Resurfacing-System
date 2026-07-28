package com.hry.camera.usbcamerademo;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;

import java.util.List;
import java.util.TimeZone;

/** Revalidates all same-place policy gates at delivery time. */
public class AtlasLocationReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent != null
                && intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)
                && !intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) {
            return;
        }
        final PendingResult pendingResult = goAsync();
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    handle(appContext, intent);
                } catch (Exception error) {
                    AtlasDevLogger.e(appContext, "LocationReminder",
                            "Location resurfacing failed.", error);
                } finally {
                    pendingResult.finish();
                }
            }
        }, "atlas-location-reminder").start();
    }

    private void handle(Context context, Intent intent) {
        if (intent == null) {
            ResearchNotificationTracker.logSkipped(
                    context,
                    "location",
                    "invalid_cluster_payload");
            return;
        }
        AtlasReminderPreferences preferences = new AtlasReminderPreferences(context);
        if (!preferences.isLocationEnabled()) {
            ResearchNotificationTracker.logSkipped(
                    context,
                    "location",
                    "setting_disabled");
            AtlasLocationReminderRegistrar.removeAll(context);
            return;
        }
        double clusterLat = intent.getDoubleExtra(
                AtlasLocationReminderRegistrar.EXTRA_CLUSTER_LAT, Double.NaN);
        double clusterLng = intent.getDoubleExtra(
                AtlasLocationReminderRegistrar.EXTRA_CLUSTER_LNG, Double.NaN);
        String clusterKey = intent.getStringExtra(
                AtlasLocationReminderRegistrar.EXTRA_CLUSTER_KEY);
        if (Double.isNaN(clusterLat) || Double.isNaN(clusterLng) || clusterKey == null) {
            ResearchNotificationTracker.logSkipped(
                    context,
                    "location",
                    "invalid_cluster_payload");
            return;
        }

        Location current = bestLastKnownLocation(context);
        boolean currentFixCanVeto = current != null
                && current.getTime() > 0L
                && System.currentTimeMillis() - current.getTime()
                <= AppConfig.SPECIAL_CURRENT_FIX_MAX_AGE_MS
                && current.hasAccuracy()
                && current.getAccuracy() <= AppConfig.GPS_DESIRED_ACCURACY_METERS;
        if (currentFixCanVeto && AtlasLocationClusterer.distanceMeters(
                current.getLatitude(), current.getLongitude(), clusterLat, clusterLng)
                > AppConfig.SPECIAL_LOCATION_RADIUS_METERS) {
            ResearchNotificationTracker.logSkipped(
                    context,
                    "location",
                    "current_fix_outside_radius");
            return;
        }

        long nowMs = System.currentTimeMillis();
        boolean hasOldEligibleMoment = false;
        AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
        List<AtlasReviewRepository.EventSummary> events =
                new AtlasReviewRepository(context).loadEventSummaries();
        for (AtlasReviewRepository.EventSummary event : events) {
            if (!selector.isPushEligible(event)
                    || event.lat == null || event.lng == null
                    || !AtlasReminderSchedule.isOldEnough(event.startTimeMs, nowMs)) {
                continue;
            }
            if (AtlasLocationClusterer.distanceMeters(
                    clusterLat, clusterLng, event.lat, event.lng)
                    <= AppConfig.SPECIAL_LOCATION_RADIUS_METERS) {
                hasOldEligibleMoment = true;
                break;
            }
        }
        if (!hasOldEligibleMoment) {
            ResearchNotificationTracker.logSkipped(
                    context,
                    "location",
                    "no_old_eligible_moment");
            return;
        }

        String today = AtlasReminderSchedule.localDate(nowMs, TimeZone.getDefault());
        if (preferences.wasLocationSentToday(
                today, clusterKey)) {
            ResearchNotificationTracker.logSkipped(
                    context,
                    "location",
                    "already_sent_place_today");
            return;
        }
        if (!AtlasReminderSchedule.cooldownElapsed(
                preferences.getLastLocationSentAt(), nowMs)) {
            ResearchNotificationTracker.logSkipped(
                    context,
                    "location",
                    "cooldown_active");
            return;
        }
        if (!AtlasNotificationHelper.postLocation(
                context, clusterKey, clusterLat, clusterLng)) {
            return;
        }
        preferences.markLocationSentToday(today, clusterKey, nowMs);
    }

    private Location bestLastKnownLocation(Context context) {
        if (!AtlasLocationReminderRegistrar.hasLocationPermission(context)) {
            return null;
        }
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return null;
        }
        Location best = null;
        try {
            for (String provider : manager.getProviders(true)) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null) {
                    continue;
                }
                if (best == null || candidate.getTime() > best.getTime()
                        || (candidate.getTime() == best.getTime()
                        && candidate.getAccuracy() < best.getAccuracy())) {
                    best = candidate;
                }
            }
        } catch (SecurityException ignored) {
            return null;
        }
        return best;
    }
}
