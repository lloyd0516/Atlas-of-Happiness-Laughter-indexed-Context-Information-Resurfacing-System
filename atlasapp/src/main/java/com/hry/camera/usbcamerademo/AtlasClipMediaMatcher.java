package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Selects one deterministic, accessible media item near a laughter clip. */
final class AtlasClipMediaMatcher {
    private AtlasClipMediaMatcher() {
    }

    static String findNearestPath(
            JSONArray items,
            String pathKey,
            long clipTimeMs,
            long maxDeltaMs) {
        if (items == null
                || pathKey == null
                || pathKey.length() == 0
                || clipTimeMs <= 0L
                || maxDeltaMs < 0L) {
            return null;
        }

        String bestPath = null;
        long bestTime = -1L;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            long captureTimeMs = item.optLong("capture_time_ms", -1L);
            String path = item.optString(pathKey, "");
            if (captureTimeMs <= 0L
                    || path.length() == 0
                    || !new File(path).isFile()) {
                continue;
            }
            long delta = captureTimeMs >= clipTimeMs
                    ? captureTimeMs - clipTimeMs
                    : clipTimeMs - captureTimeMs;
            if (delta > maxDeltaMs) {
                continue;
            }

            boolean isBetter = bestPath == null
                    || delta < bestDelta
                    || (delta == bestDelta && captureTimeMs < bestTime)
                    || (delta == bestDelta
                            && captureTimeMs == bestTime
                            && path.compareTo(bestPath) < 0);
            if (isBetter) {
                bestPath = path;
                bestTime = captureTimeMs;
                bestDelta = delta;
            }
        }
        return bestPath;
    }
}
