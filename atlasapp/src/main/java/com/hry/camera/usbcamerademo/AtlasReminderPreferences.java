package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Persistent user choices and idempotency state for resurfacing notifications. */
final class AtlasReminderPreferences {
    private static final String FILE_NAME = "atlas_resurfacing_reminders";
    private static final String KEY_DAILY_ENABLED = "daily_enabled";
    private static final String KEY_LOCATION_ENABLED = "location_enabled";
    private static final String KEY_SHORT_SENT_DATE = "short_sent_date";
    private static final String KEY_LONG_SENT_DATE = "long_sent_date";
    private static final String KEY_LOCATION_SENT_KEYS = "location_sent_keys";
    private static final String KEY_LAST_LOCATION_SENT_AT = "last_location_sent_at";
    private static final String KEY_REGISTERED_LOCATION_CODES = "registered_location_codes";

    private final SharedPreferences preferences;

    AtlasReminderPreferences(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    boolean isDailyEnabled() {
        return preferences.getBoolean(KEY_DAILY_ENABLED, true);
    }

    void setDailyEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_DAILY_ENABLED, enabled).apply();
    }

    boolean isLocationEnabled() {
        return preferences.getBoolean(KEY_LOCATION_ENABLED, true);
    }

    void setLocationEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_LOCATION_ENABLED, enabled).apply();
    }

    boolean wasDailyCategorySent(String category, String localDate) {
        return localDate != null && localDate.equals(preferences.getString(
                dailySentKey(category), null));
    }

    void markDailyCategorySent(String category, String localDate) {
        preferences.edit().putString(dailySentKey(category), localDate).commit();
    }

    private String dailySentKey(String category) {
        return "long".equals(category) ? KEY_LONG_SENT_DATE : KEY_SHORT_SENT_DATE;
    }

    boolean wasLocationSentToday(String localDate, String locationKey) {
        return getLocationSentKeys().contains(combineLocationKey(localDate, locationKey));
    }

    void markLocationSentToday(String localDate, String locationKey, long sentAtMs) {
        Set<String> keys = pruneAndCopyLocationKeys(localDate);
        keys.add(combineLocationKey(localDate, locationKey));
        preferences.edit()
                .putStringSet(KEY_LOCATION_SENT_KEYS, keys)
                .putLong(KEY_LAST_LOCATION_SENT_AT, sentAtMs)
                .commit();
    }

    long getLastLocationSentAt() {
        return preferences.getLong(KEY_LAST_LOCATION_SENT_AT, 0L);
    }

    Set<Integer> getRegisteredLocationCodes() {
        Set<String> stored = preferences.getStringSet(
                KEY_REGISTERED_LOCATION_CODES, Collections.<String>emptySet());
        Set<Integer> result = new HashSet<>();
        for (String value : stored) {
            try {
                result.add(Integer.parseInt(value));
            } catch (NumberFormatException ignored) {
                // A corrupt entry should not prevent the remaining alerts from being removed.
            }
        }
        return result;
    }

    void setRegisteredLocationCodes(Set<Integer> requestCodes) {
        Set<String> stored = new HashSet<>();
        if (requestCodes != null) {
            for (Integer requestCode : requestCodes) {
                if (requestCode != null) {
                    stored.add(String.valueOf(requestCode));
                }
            }
        }
        preferences.edit().putStringSet(KEY_REGISTERED_LOCATION_CODES, stored).commit();
    }

    private Set<String> getLocationSentKeys() {
        return new HashSet<>(preferences.getStringSet(
                KEY_LOCATION_SENT_KEYS, Collections.<String>emptySet()));
    }

    private Set<String> pruneAndCopyLocationKeys(String localDate) {
        Set<String> result = new HashSet<>();
        String prefix = localDate + "|";
        for (String key : getLocationSentKeys()) {
            if (key != null && key.startsWith(prefix)) {
                result.add(key);
            }
        }
        return result;
    }

    private String combineLocationKey(String localDate, String locationKey) {
        return localDate + "|" + locationKey;
    }
}
