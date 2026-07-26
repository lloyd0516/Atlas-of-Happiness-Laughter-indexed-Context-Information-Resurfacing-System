package com.hry.camera.usbcamerademo;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AtlasDevLogger {
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final String FILE_NAME = "dev_ui_log.txt";

    private AtlasDevLogger() {}

    public static void session(Context context, String tag, String message) {
        log(context, "SESSION", tag, message, null);
    }

    public static void i(Context context, String tag, String message) {
        log(context, "INFO", tag, message, null);
    }

    public static void w(Context context, String tag, String message) {
        log(context, "WARN", tag, message, null);
    }

    public static void e(Context context, String tag, String message, Throwable throwable) {
        log(context, "ERROR", tag, message, throwable);
    }

    public static File getLogFile(Context context) {
        if (context == null) {
            return null;
        }
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File root = new File(base, "joyful_moment");
        if (!root.exists()) {
            root.mkdirs();
        }
        return new File(root, FILE_NAME);
    }

    private static void log(Context context, String level, String tag, String message, Throwable throwable) {
        File file = getLogFile(context);
        if (file == null) {
            return;
        }
        StringBuilder line = new StringBuilder();
        line.append(TIME_FORMAT.format(new Date()))
                .append(" [").append(level).append("] ")
                .append(tag == null ? "Atlas" : tag)
                .append(" - ")
                .append(message == null ? "" : message);
        if (throwable != null) {
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            line.append("\n").append(writer.toString().trim());
        }
        line.append("\n");
        synchronized (LOCK) {
            FileOutputStream outputStream = null;
            try {
                outputStream = new FileOutputStream(file, true);
                outputStream.write(line.toString().getBytes(Charset.forName("UTF-8")));
            } catch (Exception ignored) {
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    public static String buildSessionBanner(String screen) {
        return "===== " + screen
                + " | model=" + Build.MODEL
                + " | sdk=" + Build.VERSION.SDK_INT
                + " | time=" + TIME_FORMAT.format(new Date())
                + " =====";
    }
}
