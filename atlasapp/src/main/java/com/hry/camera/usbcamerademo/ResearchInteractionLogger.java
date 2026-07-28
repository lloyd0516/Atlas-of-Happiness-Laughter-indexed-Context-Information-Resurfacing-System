package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Process-wide facade for the local, append-only research interaction log. */
final class ResearchInteractionLogger {
    private static final Object LOCK = new Object();
    private static ResearchJsonlWriter writer;
    private static File logFile;

    private ResearchInteractionLogger() {
    }

    static void initialize(Context context) {
        if (context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            if (writer != null) {
                return;
            }
            logFile = resolveLogFile(appContext);
            writer = new ResearchJsonlWriter(
                    logFile,
                    new ResearchJsonlWriter.ErrorReporter() {
                        @Override
                        public void onWriteFailure(
                                IOException error,
                                int pendingCount
                        ) {
                            AtlasDevLogger.e(
                                    appContext,
                                    "ResearchLog",
                                    "write failed; pending=" + pendingCount,
                                    error);
                        }
                    });
            if (logFile.length() == 0L) {
                writer.append(buildRecord(
                        appContext,
                        ResearchEventNames.LOG_STARTED,
                        null,
                        null,
                        null,
                        properties(
                                "schema_version",
                                ResearchEventNames.SCHEMA_VERSION)));
            }
        }
    }

    static boolean log(
            Context context,
            String eventName,
            String sessionId,
            String momentId,
            String notificationInstanceId,
            JSONObject properties
    ) {
        if (context == null || eventName == null || eventName.length() == 0) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        try {
            initialize(appContext);
            ResearchJsonlWriter activeWriter;
            synchronized (LOCK) {
                activeWriter = writer;
            }
            return activeWriter != null && activeWriter.append(buildRecord(
                    appContext,
                    eventName,
                    sessionId,
                    momentId,
                    notificationInstanceId,
                    properties));
        } catch (Exception error) {
            AtlasDevLogger.e(
                    appContext,
                    "ResearchLog",
                    "record failed: " + eventName,
                    error);
            return false;
        }
    }

    static JSONObject properties(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return new JSONObject();
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "properties require key/value pairs");
        }
        JSONObject json = new JSONObject();
        try {
            for (int i = 0; i < keyValues.length; i += 2) {
                Object key = keyValues[i];
                if (!(key instanceof String)) {
                    throw new IllegalArgumentException(
                            "property key must be a String");
                }
                Object value = keyValues[i + 1];
                json.put((String) key, value == null ? JSONObject.NULL : value);
            }
            return json;
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "invalid research properties", error);
        }
    }

    static File getLogFile(Context context) {
        if (context == null) {
            return null;
        }
        synchronized (LOCK) {
            if (logFile != null) {
                return logFile;
            }
        }
        return resolveLogFile(context.getApplicationContext());
    }

    private static JSONObject buildRecord(
            Context context,
            String eventName,
            String sessionId,
            String momentId,
            String notificationInstanceId,
            JSONObject properties
    ) {
        long now = System.currentTimeMillis();
        try {
            return ResearchLogRecord.build(
                    eventName,
                    UUID.randomUUID().toString(),
                    now,
                    localTime(now),
                    TimeZone.getDefault().getID(),
                    SystemClock.elapsedRealtime(),
                    currentParticipant(context),
                    sessionId,
                    momentId,
                    notificationInstanceId,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                    Build.MODEL,
                    properties);
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "could not build research record", error);
        }
    }

    private static File resolveLogFile(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File root = new File(base, "joyful_moment");
        if (!root.exists()) {
            root.mkdirs();
        }
        return new File(root, ResearchEventNames.FILE_NAME);
    }

    private static String currentParticipant(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(
                JoyfulMomentConfig.PREF_NAME, Context.MODE_PRIVATE);
        String value = preferences.getString(
                "joyful_participant_number", null);
        return value == null || value.length() == 0 ? null : value;
    }

    private static String localTime(long timestampMs) {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        String raw = format.format(new Date(timestampMs));
        if (raw.length() >= 5) {
            return raw.substring(0, raw.length() - 2)
                    + ":"
                    + raw.substring(raw.length() - 2);
        }
        return raw;
    }
}
