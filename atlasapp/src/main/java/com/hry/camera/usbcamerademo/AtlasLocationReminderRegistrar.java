package com.hry.camera.usbcamerademo;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.support.v4.content.ContextCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps native LocationManager proximity alerts synchronized with eligible place clusters. */
final class AtlasLocationReminderRegistrar {
    static final String EXTRA_CLUSTER_KEY = "location_cluster_key";
    static final String EXTRA_CLUSTER_LAT = "location_cluster_lat";
    static final String EXTRA_CLUSTER_LNG = "location_cluster_lng";
    static final String EXTRA_REQUEST_CODE = "location_request_code";
    private static final int FLAG_MUTABLE_COMPAT = 0x02000000;

    private AtlasLocationReminderRegistrar() {
    }

    static void sync(Context context) {
        Context appContext = context.getApplicationContext();
        AtlasReminderPreferences preferences = new AtlasReminderPreferences(appContext);
        removeAll(appContext, preferences);
        if (!preferences.isLocationEnabled() || !hasLocationPermission(appContext)) {
            return;
        }

        LocationManager manager =
                (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return;
        }
        List<AtlasLocationClusterer.LocationCluster> clusters =
                new AtlasLocationClusterer().cluster(
                        new AtlasReviewRepository(appContext).loadEventSummaries());
        Set<Integer> registered = new HashSet<>();
        for (AtlasLocationClusterer.LocationCluster cluster : clusters) {
            PendingIntent intent = proximityIntent(appContext, cluster.requestCode);
            Intent fill = new Intent(appContext, AtlasLocationReminderReceiver.class);
            fill.putExtra(EXTRA_CLUSTER_KEY, cluster.clusterKey);
            fill.putExtra(EXTRA_CLUSTER_LAT, cluster.lat);
            fill.putExtra(EXTRA_CLUSTER_LNG, cluster.lng);
            fill.putExtra(EXTRA_REQUEST_CODE, cluster.requestCode);
            intent = PendingIntent.getBroadcast(
                    appContext,
                    cluster.requestCode,
                    fill,
                    pendingIntentFlags());
            try {
                manager.addProximityAlert(
                        cluster.lat,
                        cluster.lng,
                        AppConfig.SPECIAL_LOCATION_RADIUS_METERS,
                        -1L,
                        intent);
                registered.add(cluster.requestCode);
            } catch (SecurityException error) {
                AtlasDevLogger.e(appContext, "LocationReminder",
                        "Location permission unavailable during registration.", error);
                break;
            } catch (IllegalArgumentException error) {
                AtlasDevLogger.e(appContext, "LocationReminder",
                        "Invalid proximity registration.", error);
            }
        }
        preferences.setRegisteredLocationCodes(registered);
    }

    static void removeAll(Context context) {
        removeAll(context.getApplicationContext(),
                new AtlasReminderPreferences(context));
    }

    private static void removeAll(
            Context context, AtlasReminderPreferences preferences) {
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager != null) {
            for (Integer code : preferences.getRegisteredLocationCodes()) {
                try {
                    manager.removeProximityAlert(proximityIntent(context, code));
                } catch (SecurityException ignored) {
                    // Clearing persisted state still prevents treating stale registrations as live.
                }
            }
        }
        preferences.setRegisteredLocationCodes(new HashSet<Integer>());
    }

    static boolean hasLocationPermission(Context context) {
        return ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static PendingIntent proximityIntent(Context context, int requestCode) {
        Intent intent = new Intent(context, AtlasLocationReminderReceiver.class);
        return PendingIntent.getBroadcast(
                context, requestCode, intent, pendingIntentFlags());
    }

    private static int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= FLAG_MUTABLE_COMPAT;
        }
        return flags;
    }
}
