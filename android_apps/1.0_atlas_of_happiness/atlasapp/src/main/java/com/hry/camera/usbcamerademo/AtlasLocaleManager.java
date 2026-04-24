package com.hry.camera.usbcamerademo;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

public class AtlasLocaleManager {
    public static final String LANGUAGE_SYSTEM = "system";
    public static final String LANGUAGE_EN = "en";
    public static final String LANGUAGE_ZH = "zh";

    public static void apply(Activity activity) {
        if (activity == null) {
            return;
        }
        apply(activity, getSavedLanguage(activity));
    }

    public static void apply(Context context, String languageCode) {
        if (context == null) {
            return;
        }
        String target = languageCode;
        if (target == null || LANGUAGE_SYSTEM.equals(target)) {
            target = Locale.getDefault().getLanguage();
        }
        Locale locale = target.startsWith("zh") ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        Locale.setDefault(locale);
        Resources resources = context.getResources();
        Configuration configuration = new Configuration(resources.getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            configuration.setLocale(locale);
        } else {
            configuration.locale = locale;
        }
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
    }

    public static void saveLanguage(Context context, String languageCode) {
        SharedPreferences prefs = context.getSharedPreferences(JoyfulMomentConfig.PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(JoyfulMomentConfig.PREF_APP_LANGUAGE, languageCode).apply();
    }

    public static String getSavedLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(JoyfulMomentConfig.PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(JoyfulMomentConfig.PREF_APP_LANGUAGE, LANGUAGE_SYSTEM);
    }
}
