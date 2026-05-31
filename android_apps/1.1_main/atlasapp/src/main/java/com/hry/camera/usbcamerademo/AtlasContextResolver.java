package com.hry.camera.usbcamerademo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;

import org.json.JSONObject;

import org.json.JSONArray;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AtlasContextResolver {
    public interface Callback {
        void onResolved(Double lat, Double lng, Double amapLat, Double amapLng, Float accuracyMeters, Long timestampMs, String locationName, String adcode, String weatherCondition, Double temperature);
        void onFailed(String reason);
    }

    public static void refreshContext(final Context context, final AtlasReviewRepository repository, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                            && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        callback.onFailed("location_permission_missing");
                        return;
                    }
                    LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                    if (manager == null) {
                        callback.onFailed("location_manager_missing");
                        return;
                    }
                    Location best = findBestLastKnownLocation(manager);
                    Location fresh = requestFreshLocation(manager);
                    best = chooseBetterLocation(best, fresh);
                    if (best == null) {
                        callback.onFailed("location_unavailable");
                        return;
                    }
                    resolveKnownCoordinates(
                            repository,
                            best.getLatitude(),
                            best.getLongitude(),
                            null,
                            null,
                            best.hasAccuracy() ? best.getAccuracy() : null,
                            best.getTime(),
                            null,
                            null,
                            callback
                    );
                } catch (Exception e) {
                    callback.onFailed(e.getMessage());
                }
            }
        }, "AtlasContextResolver").start();
    }

    public static void refreshContextForEvent(final AtlasReviewRepository repository, final JSONObject event, final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject derived = event == null ? null : event.optJSONObject("derived_context");
                    JSONObject gps = derived == null ? null : derived.optJSONObject("gps");
                    if (gps == null || !gps.has("lat") || !gps.has("lng")) {
                        callback.onFailed("event_location_missing");
                        return;
                    }
                    resolveKnownCoordinates(
                            repository,
                            gps.optDouble("lat"),
                            gps.optDouble("lng"),
                            gps.has("amap_lat") ? gps.optDouble("amap_lat") : null,
                            gps.has("amap_lng") ? gps.optDouble("amap_lng") : null,
                            gps.has("accuracy_m") ? (float) gps.optDouble("accuracy_m") : null,
                            gps.has("timestamp_ms") ? gps.optLong("timestamp_ms") : null,
                            gps.optString("address", null),
                            gps.optString("adcode", null),
                            callback
                    );
                } catch (Exception e) {
                    callback.onFailed(e.getMessage());
                }
            }
        }, "AtlasContextResolverEvent").start();
    }

    private static void resolveKnownCoordinates(
            AtlasReviewRepository repository,
            double lat,
            double lng,
            Double existingAmapLat,
            Double existingAmapLng,
            Float accuracyMeters,
            Long timestampMs,
            String existingAddress,
            String existingAdcode,
            Callback callback
    ) {
        double amapLat = existingAmapLat != null ? existingAmapLat : lat;
        double amapLng = existingAmapLng != null ? existingAmapLng : lng;
        Double temperature = null;
        String condition = null;
        String address = existingAddress;
        String adcode = existingAdcode;
        String apiKey = repository.getAmapApiKey();
        if (!TextUtils.isEmpty(apiKey)) {
            OkHttpClient client = new OkHttpClient.Builder().build();
            if (existingAmapLat == null || existingAmapLng == null) {
                double[] converted = convertGpsToAmap(client, apiKey, lat, lng);
                if (converted != null) {
                    amapLat = converted[0];
                    amapLng = converted[1];
                }
            }
            RegeoResult regeo = reverseGeocode(client, apiKey, amapLat, amapLng);
            if (regeo != null) {
                if (!TextUtils.isEmpty(regeo.address)) {
                    address = regeo.address;
                }
                if (!TextUtils.isEmpty(regeo.adcode)) {
                    adcode = regeo.adcode;
                }
            }
            WeatherResult weather = fetchWeather(client, apiKey, adcode);
            if (weather != null) {
                condition = weather.condition;
                temperature = weather.temperature;
            }
        }
        callback.onResolved(lat, lng, amapLat, amapLng, accuracyMeters, timestampMs, address, adcode, condition, temperature);
    }

    private static RegeoResult reverseGeocode(OkHttpClient client, String apiKey, double amapLat, double amapLng) {
        Response response = null;
        try {
            HttpUrl regeoUrl = HttpUrl.parse("https://restapi.amap.com/v3/geocode/regeo").newBuilder()
                    .addQueryParameter("key", apiKey)
                    .addQueryParameter("location", String.format(Locale.US, "%.6f,%.6f", amapLng, amapLat))
                    .addQueryParameter("extensions", "base")
                    .addQueryParameter("output", "JSON")
                    .build();
            response = client.newCall(new Request.Builder().url(regeoUrl).build()).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            JSONObject json = new JSONObject(response.body().string());
            if (!"1".equals(json.optString("status"))) {
                return null;
            }
            JSONObject regeo = json.optJSONObject("regeocode");
            if (regeo == null) {
                return null;
            }
            RegeoResult result = new RegeoResult();
            result.address = regeo.optString("formatted_address", null);
            JSONObject addressComponent = regeo.optJSONObject("addressComponent");
            if (addressComponent != null) {
                result.adcode = addressComponent.optString("adcode", null);
            }
            return result;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (response != null && response.body() != null) {
                response.body().close();
            }
        }
    }

    private static WeatherResult fetchWeather(OkHttpClient client, String apiKey, String adcode) {
        if (TextUtils.isEmpty(adcode)) {
            return null;
        }
        Response response = null;
        try {
            HttpUrl weatherUrl = HttpUrl.parse("https://restapi.amap.com/v3/weather/weatherInfo").newBuilder()
                    .addQueryParameter("key", apiKey)
                    .addQueryParameter("city", adcode)
                    .addQueryParameter("extensions", "base")
                    .addQueryParameter("output", "JSON")
                    .build();
            response = client.newCall(new Request.Builder().url(weatherUrl).build()).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            JSONObject json = new JSONObject(response.body().string());
            if (!"1".equals(json.optString("status"))) {
                return null;
            }
            JSONArray lives = json.optJSONArray("lives");
            JSONObject live = lives != null && lives.length() > 0 ? lives.optJSONObject(0) : null;
            if (live == null) {
                return null;
            }
            WeatherResult result = new WeatherResult();
            result.condition = live.optString("weather", null);
            String tempText = live.optString("temperature", null);
            if (!TextUtils.isEmpty(tempText)) {
                try {
                    result.temperature = Double.parseDouble(tempText);
                } catch (NumberFormatException ignored) {
                }
            }
            return result;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (response != null && response.body() != null) {
                response.body().close();
            }
        }
    }

    private static Location findBestLastKnownLocation(LocationManager manager) {
        Location best = null;
        List<String> providers = manager.getProviders(true);
        for (String provider : providers) {
            try {
                Location location = manager.getLastKnownLocation(provider);
                best = chooseBetterLocation(best, location);
            } catch (SecurityException ignored) {
            }
        }
        return best;
    }

    private static Location requestFreshLocation(final LocationManager manager) {
        final Location[] result = new Location[1];
        final CountDownLatch latch = new CountDownLatch(1);
        final LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                result[0] = chooseBetterLocation(result[0], location);
                latch.countDown();
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        try {
            List<String> providers = manager.getProviders(true);
            boolean requested = false;
            if (providers != null) {
                for (String provider : providers) {
                    try {
                        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                        requested = true;
                    } catch (Exception ignored) {
                    }
                }
            }
            if (!requested) {
                Criteria criteria = new Criteria();
                criteria.setAccuracy(Criteria.ACCURACY_FINE);
                criteria.setPowerRequirement(Criteria.POWER_MEDIUM);
                manager.requestSingleUpdate(criteria, listener, Looper.getMainLooper());
            }
            latch.await(10000L, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try {
                manager.removeUpdates(listener);
            } catch (Exception ignored) {
            }
        }
        return result[0];
    }

    private static Location chooseBetterLocation(Location current, Location candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        long timeDelta = candidate.getTime() - current.getTime();
        boolean significantlyNewer = timeDelta > 120000L;
        boolean significantlyOlder = timeDelta < -120000L;
        if (significantlyNewer) {
            return candidate;
        }
        if (significantlyOlder) {
            return current;
        }
        float currentAccuracy = current.hasAccuracy() ? current.getAccuracy() : Float.MAX_VALUE;
        float candidateAccuracy = candidate.hasAccuracy() ? candidate.getAccuracy() : Float.MAX_VALUE;
        return candidateAccuracy <= currentAccuracy ? candidate : current;
    }

    private static double[] convertGpsToAmap(OkHttpClient client, String apiKey, double lat, double lng) {
        Response response = null;
        try {
            HttpUrl url = HttpUrl.parse("https://restapi.amap.com/v3/assistant/coordinate/convert").newBuilder()
                    .addQueryParameter("key", apiKey)
                    .addQueryParameter("locations", String.format(Locale.US, "%.6f,%.6f", lng, lat))
                    .addQueryParameter("coordsys", "gps")
                    .addQueryParameter("output", "JSON")
                    .build();
            response = client.newCall(new Request.Builder().url(url).build()).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            JSONObject json = new JSONObject(response.body().string());
            if (!"1".equals(json.optString("status"))) {
                return null;
            }
            String locations = json.optString("locations", "");
            String[] parts = locations.split(",");
            if (parts.length < 2) {
                return null;
            }
            double convertedLng = Double.parseDouble(parts[0]);
            double convertedLat = Double.parseDouble(parts[1]);
            return new double[]{convertedLat, convertedLng};
        } catch (Exception ignored) {
            return null;
        } finally {
            if (response != null && response.body() != null) {
                response.body().close();
            }
        }
    }

    private static class RegeoResult {
        String address;
        String adcode;
    }

    private static class WeatherResult {
        String condition;
        Double temperature;
    }
}
