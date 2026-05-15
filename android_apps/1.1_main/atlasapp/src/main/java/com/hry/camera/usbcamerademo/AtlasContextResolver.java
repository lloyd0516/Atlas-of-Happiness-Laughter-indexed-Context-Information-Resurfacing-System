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
                    double amapLat = best.getLatitude();
                    double amapLng = best.getLongitude();
                    Double temperature = null;
                    String condition = null;
                    String address = null;
                    String adcode = null;
                    String apiKey = repository.getAmapApiKey();
                    if (!TextUtils.isEmpty(apiKey)) {
                        try {
                            OkHttpClient client = new OkHttpClient.Builder().build();
                            double[] converted = convertGpsToAmap(client, apiKey, best.getLatitude(), best.getLongitude());
                            if (converted != null) {
                                amapLat = converted[0];
                                amapLng = converted[1];
                            }
                            HttpUrl regeoUrl = HttpUrl.parse("https://restapi.amap.com/v3/geocode/regeo").newBuilder()
                                    .addQueryParameter("key", apiKey)
                                    .addQueryParameter("location", String.format(Locale.US, "%.6f,%.6f", amapLng, amapLat))
                                    .addQueryParameter("extensions", "base")
                                    .addQueryParameter("output", "JSON")
                                    .build();
                            Request regeoRequest = new Request.Builder().url(regeoUrl).build();
                            Response response = client.newCall(regeoRequest).execute();
                            try {
                                if (response.isSuccessful() && response.body() != null) {
                                    String body = response.body().string();
                                    JSONObject json = new JSONObject(body);
                                    if ("1".equals(json.optString("status"))) {
                                        JSONObject regeo = json.optJSONObject("regeocode");
                                        if (regeo != null) {
                                            address = regeo.optString("formatted_address", null);
                                            JSONObject addressComponent = regeo.optJSONObject("addressComponent");
                                            if (addressComponent != null) {
                                                adcode = addressComponent.optString("adcode", null);
                                            }
                                        }
                                    }
                                }
                            } finally {
                                if (response.body() != null) {
                                    response.body().close();
                                }
                            }
                            if (!TextUtils.isEmpty(adcode)) {
                                HttpUrl weatherUrl = HttpUrl.parse("https://restapi.amap.com/v3/weather/weatherInfo").newBuilder()
                                        .addQueryParameter("key", apiKey)
                                        .addQueryParameter("city", adcode)
                                        .addQueryParameter("extensions", "base")
                                        .addQueryParameter("output", "JSON")
                                        .build();
                                Request weatherRequest = new Request.Builder().url(weatherUrl).build();
                                Response weatherResponse = client.newCall(weatherRequest).execute();
                                try {
                                    if (weatherResponse.isSuccessful() && weatherResponse.body() != null) {
                                        String body = weatherResponse.body().string();
                                        JSONObject json = new JSONObject(body);
                                        if ("1".equals(json.optString("status"))) {
                                            JSONArray lives = json.optJSONArray("lives");
                                            JSONObject live = lives != null && lives.length() > 0 ? lives.optJSONObject(0) : null;
                                            if (live != null) {
                                                condition = live.optString("weather", null);
                                                String tempText = live.optString("temperature", null);
                                                if (!TextUtils.isEmpty(tempText)) {
                                                    try {
                                                        temperature = Double.parseDouble(tempText);
                                                    } catch (NumberFormatException ignored) {
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    if (weatherResponse.body() != null) {
                                        weatherResponse.body().close();
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    callback.onResolved(best.getLatitude(), best.getLongitude(), amapLat, amapLng, best.hasAccuracy() ? best.getAccuracy() : null, best.getTime(), address, adcode, condition, temperature);
                } catch (Exception e) {
                    callback.onFailed(e.getMessage());
                }
            }
        }, "AtlasContextResolver").start();
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
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setPowerRequirement(Criteria.POWER_MEDIUM);
        final LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                result[0] = location;
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
            manager.requestSingleUpdate(criteria, listener, Looper.getMainLooper());
            latch.await(3500L, TimeUnit.MILLISECONDS);
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
}
