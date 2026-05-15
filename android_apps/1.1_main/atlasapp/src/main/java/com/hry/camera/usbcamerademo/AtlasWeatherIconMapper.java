package com.hry.camera.usbcamerademo;

import android.text.TextUtils;

import java.util.Locale;

public class AtlasWeatherIconMapper {
    public static final String CLEAR = "clear";
    public static final String CLOUD = "cloud";
    public static final String RAIN = "rain";
    public static final String SNOW = "snow";
    public static final String STORM = "storm";
    public static final String FOG = "fog";

    public static String keyForCondition(String condition) {
        if (TextUtils.isEmpty(condition)) {
            return CLOUD;
        }
        String text = condition.toLowerCase(Locale.US);
        if (containsAny(text, "雷", "thunder", "storm")) {
            return STORM;
        }
        if (containsAny(text, "雪", "snow", "sleet")) {
            return SNOW;
        }
        if (containsAny(text, "雨", "rain", "drizzle", "shower")) {
            return RAIN;
        }
        if (containsAny(text, "雾", "霾", "沙", "尘", "fog", "mist", "haze", "dust", "sand", "smoke")) {
            return FOG;
        }
        if (containsAny(text, "晴", "clear", "sun")) {
            return CLEAR;
        }
        return CLOUD;
    }

    public static int drawableForKey(String key) {
        if (CLEAR.equals(key)) {
            return R.drawable.ic_weather_clear;
        }
        if (RAIN.equals(key)) {
            return R.drawable.ic_weather_rain;
        }
        if (SNOW.equals(key)) {
            return R.drawable.ic_weather_snow;
        }
        if (STORM.equals(key)) {
            return R.drawable.ic_weather_storm;
        }
        if (FOG.equals(key)) {
            return R.drawable.ic_weather_fog;
        }
        return R.drawable.ic_weather_cloud;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
