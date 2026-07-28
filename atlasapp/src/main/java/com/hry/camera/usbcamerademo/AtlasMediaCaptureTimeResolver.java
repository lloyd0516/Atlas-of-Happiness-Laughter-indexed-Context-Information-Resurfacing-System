package com.hry.camera.usbcamerademo;

import org.json.JSONObject;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recovers only trustworthy media capture timestamps from new and legacy event data. */
final class AtlasMediaCaptureTimeResolver {
    private static final Pattern STABLE_CAPTURE_FILE = Pattern.compile(
            "^event_(?:photo|video)_(\\d{10,17})(?:\\.[^.]+)?$");

    private AtlasMediaCaptureTimeResolver() {
    }

    static long resolve(
            JSONObject item,
            String pathKey,
            long eventStartMs,
            long eventEndMs) {
        if (item == null || pathKey == null) {
            return -1L;
        }

        long explicit = item.optLong("capture_time_ms", -1L);
        if (explicit > 0L) {
            return explicit;
        }

        String path = item.optString(pathKey, "");
        if (path.length() == 0) {
            return -1L;
        }
        long filenameTime = parseStableFilenameTime(new File(path).getName());
        if (filenameTime > 0L) {
            return filenameTime;
        }

        File file = new File(path);
        long modified = file.isFile() ? file.lastModified() : -1L;
        if (modified <= 0L || eventStartMs <= 0L || eventEndMs <= 0L) {
            return -1L;
        }
        long window = AppConfig.CLIP_MEDIA_MATCH_WINDOW_MS;
        long lower = eventStartMs > window ? eventStartMs - window : 0L;
        long upper = eventEndMs > Long.MAX_VALUE - window
                ? Long.MAX_VALUE
                : eventEndMs + window;
        return modified >= lower && modified <= upper ? modified : -1L;
    }

    private static long parseStableFilenameTime(String fileName) {
        Matcher matcher = STABLE_CAPTURE_FILE.matcher(fileName);
        if (!matcher.matches()) {
            return -1L;
        }
        try {
            long value = Long.parseLong(matcher.group(1));
            return value > 0L ? value : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
