package com.hry.camera.usbcamerademo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AtlasContextResolver {
    public interface Callback {
        void onResolved(Double lat, Double lng, Long timestampMs, String weatherCondition, Double temperature);
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
                    Location best = null;
                    List<String> providers = manager.getProviders(true);
                    for (String provider : providers) {
                        try {
                            Location location = manager.getLastKnownLocation(provider);
                            if (location != null && (best == null || location.getTime() > best.getTime())) {
                                best = location;
                            }
                        } catch (SecurityException ignored) {
                        }
                    }
                    if (best == null) {
                        callback.onFailed("location_unavailable");
                        return;
                    }
                    Double temperature = null;
                    String condition = null;
                    String apiKey = repository.getGoogleWeatherApiKey();
                    if (!TextUtils.isEmpty(apiKey)) {
                        OkHttpClient client = new OkHttpClient.Builder().build();
                        String url = String.format(Locale.US,
                                "https://weather.googleapis.com/v1/currentConditions:lookup?key=%s&location.latitude=%s&location.longitude=%s&unitsSystem=METRIC",
                                URLEncoder.encode(apiKey, "UTF-8"),
                                URLEncoder.encode(String.valueOf(best.getLatitude()), "UTF-8"),
                                URLEncoder.encode(String.valueOf(best.getLongitude()), "UTF-8"));
                        Request request = new Request.Builder().url(url).build();
                        Response response = client.newCall(request).execute();
                        if (response.body() != null) {
                            String body = response.body().string();
                            JSONObject json = new JSONObject(body);
                            JSONObject weatherCondition = json.optJSONObject("weatherCondition");
                            if (weatherCondition != null) {
                                JSONObject description = weatherCondition.optJSONObject("description");
                                if (description != null) {
                                    condition = description.optString("text", null);
                                }
                            }
                            JSONObject temp = json.optJSONObject("temperature");
                            if (temp != null && temp.has("degrees")) {
                                temperature = temp.optDouble("degrees");
                            }
                        }
                    }
                    callback.onResolved(best.getLatitude(), best.getLongitude(), best.getTime(), condition, temperature);
                } catch (Exception e) {
                    callback.onFailed(e.getMessage());
                }
            }
        }, "AtlasContextResolver").start();
    }
}
