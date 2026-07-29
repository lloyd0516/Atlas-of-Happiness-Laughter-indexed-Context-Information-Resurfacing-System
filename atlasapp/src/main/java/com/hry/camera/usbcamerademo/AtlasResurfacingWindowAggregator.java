package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Groups resurfacing content into session-aligned three-clip windows. */
final class AtlasResurfacingWindowAggregator {
    private AtlasResurfacingWindowAggregator() {
    }

    static final class Window {
        final int bucketId;
        final long startTimeMs;
        final long endTimeMs;
        final double totalLaughterDurationSec;
        final List<JSONObject> laughterClips;
        final List<JSONObject> contextClips;
        final List<String> photoPaths;
        final List<String> videoPaths;

        private Window(
                MutableWindow source,
                long sessionStartMs,
                int bucketDurationSec) {
            bucketId = source.bucketId;
            startTimeMs = sessionStartMs
                    + AtlasAggregationBucketPolicy
                    .bucketStartOffsetMs(
                            bucketId,
                            bucketDurationSec
                                    / AppConfig
                                    .AGGREGATION_CLIPS_PER_BUCKET);
            endTimeMs = sessionStartMs
                    + AtlasAggregationBucketPolicy
                    .bucketEndOffsetMs(
                            bucketId,
                            bucketDurationSec
                                    / AppConfig
                                    .AGGREGATION_CLIPS_PER_BUCKET);
            totalLaughterDurationSec =
                    calculateUnionDuration(source.laughter);
            laughterClips = immutableJson(source.laughter);
            contextClips = immutableJson(source.context);
            photoPaths = Collections.unmodifiableList(
                    new ArrayList<>(source.photoPaths));
            videoPaths = Collections.unmodifiableList(
                    new ArrayList<>(source.videoPaths));
        }
    }

    private static final class AudioItem {
        final JSONObject json;
        final int clipId;
        final String path;
        final double startSec;
        final double endSec;
        final double durationSec;
        int bucketId;

        AudioItem(
                JSONObject json,
                int clipId,
                String path,
                double startSec,
                double endSec,
                double durationSec) {
            this.json = json;
            this.clipId = clipId;
            this.path = path;
            this.startSec = startSec;
            this.endSec = endSec;
            this.durationSec = durationSec;
        }
    }

    private static final class MutableWindow {
        final int bucketId;
        final List<AudioItem> laughter = new ArrayList<>();
        final List<AudioItem> context = new ArrayList<>();
        final List<String> photoPaths = new ArrayList<>();
        final List<String> videoPaths = new ArrayList<>();

        MutableWindow(int bucketId) {
            this.bucketId = bucketId;
        }
    }

    static List<Window> aggregate(
            JSONArray audioClips,
            JSONArray photos,
            JSONArray videos,
            long sessionStartMs,
            int sessionClipDurationSec,
            int contextNeighborClips,
            long legacyBundleGroupWindowMs) {
        ArrayList<Window> result = new ArrayList<>();
        if (sessionStartMs <= 0L
                || sessionClipDurationSec <= 0
                || contextNeighborClips < 0
                || legacyBundleGroupWindowMs < 0L) {
            return result;
        }
        int bucketDurationSec =
                AtlasAggregationBucketPolicy.bucketDurationSec(
                        sessionClipDurationSec);
        TreeMap<Integer, MutableWindow> windows =
                new TreeMap<>();
        ArrayList<AudioItem> laughterItems = new ArrayList<>();
        ArrayList<AudioItem> contextItems = new ArrayList<>();
        parseAudio(
                audioClips,
                sessionStartMs,
                sessionClipDurationSec,
                laughterItems,
                contextItems);

        for (AudioItem laughter : laughterItems) {
            laughter.bucketId =
                    AtlasAggregationBucketPolicy.bucketId(
                            laughter.startSec,
                            sessionClipDurationSec);
            MutableWindow window = windows.get(laughter.bucketId);
            if (window == null) {
                window = new MutableWindow(laughter.bucketId);
                windows.put(laughter.bucketId, window);
            }
            window.laughter.add(laughter);
        }
        if (windows.isEmpty()) {
            return result;
        }

        assignMedia(
                windows,
                photos,
                videos,
                sessionStartMs,
                sessionClipDurationSec,
                bucketDurationSec,
                legacyBundleGroupWindowMs);
        assignContext(
                windows,
                laughterItems,
                contextItems,
                contextNeighborClips);
        for (MutableWindow window : windows.values()) {
            sortAudio(window.laughter);
            sortAudio(window.context);
            result.add(new Window(
                    window,
                    sessionStartMs,
                    bucketDurationSec));
        }
        return result;
    }

    private static void parseAudio(
            JSONArray source,
            long sessionStartMs,
            int clipDurationSec,
            List<AudioItem> laughter,
            List<AudioItem> context) {
        if (source == null) {
            return;
        }
        for (int i = 0; i < source.length(); i++) {
            JSONObject json = source.optJSONObject(i);
            if (json == null) {
                continue;
            }
            String type = json.optString("type", "");
            boolean isLaughter = "laughter".equals(type);
            boolean isContext =
                    "possible_related_speech_context".equals(type);
            if (!isLaughter && !isContext) {
                continue;
            }
            String path = json.optString("path", "");
            if (path.length() == 0 || !new File(path).isFile()) {
                continue;
            }
            int clipId = json.optInt("clip_id", -1);
            double startSec = resolveStartSec(
                    json,
                    sessionStartMs,
                    clipDurationSec,
                    clipId);
            if (Double.isNaN(startSec) || startSec < 0.0) {
                continue;
            }
            double durationSec = Math.max(
                    0.0,
                    json.optDouble("duration_sec", 0.0));
            double endSec = json.has("end_sec")
                    ? json.optDouble("end_sec", startSec)
                    : startSec + durationSec;
            if (endSec < startSec) {
                endSec = startSec;
            }
            if (durationSec <= 0.0) {
                durationSec = Math.max(0.0, endSec - startSec);
            }
            AudioItem item = new AudioItem(
                    json,
                    clipId,
                    path,
                    startSec,
                    endSec,
                    durationSec);
            if (isLaughter) {
                laughter.add(item);
            } else {
                context.add(item);
            }
        }
    }

    private static double resolveStartSec(
            JSONObject json,
            long sessionStartMs,
            int clipDurationSec,
            int clipId) {
        if (json.has("start_sec") && !json.isNull("start_sec")) {
            return json.optDouble("start_sec", Double.NaN);
        }
        long deviceTimeMs =
                json.optLong("device_time_ms", -1L);
        if (deviceTimeMs >= sessionStartMs) {
            return (deviceTimeMs - sessionStartMs) / 1000.0;
        }
        if (clipId >= 0) {
            return clipId * (double) clipDurationSec;
        }
        return Double.NaN;
    }

    private static void assignMedia(
            Map<Integer, MutableWindow> windows,
            JSONArray photos,
            JSONArray videos,
            long sessionStartMs,
            int clipDurationSec,
            int bucketDurationSec,
            long legacyBundleGroupWindowMs) {
        List<AtlasClipMediaMatcher.MatchedCaptureBundle> bundles =
                AtlasClipMediaMatcher.collectBundles(
                        photos,
                        videos,
                        legacyBundleGroupWindowMs);
        Set<Integer> assignedBuckets = new HashSet<>();
        for (AtlasClipMediaMatcher.MatchedCaptureBundle bundle : bundles) {
            int bucketId = -1;
            if (bundle.automationBucketId >= 0
                    && bundle.automationBucketClipCount
                    == AppConfig.AGGREGATION_CLIPS_PER_BUCKET
                    && bundle.automationBucketDurationSec
                    == bucketDurationSec) {
                bucketId = bundle.automationBucketId;
            } else if (bundle.bundleTimeMs >= sessionStartMs) {
                double offsetSec =
                        (bundle.bundleTimeMs - sessionStartMs)
                                / 1000.0;
                bucketId =
                        AtlasAggregationBucketPolicy.bucketId(
                                offsetSec,
                                clipDurationSec);
            }
            MutableWindow window = windows.get(bucketId);
            if (window == null || assignedBuckets.contains(bucketId)) {
                continue;
            }
            window.photoPaths.addAll(bundle.photoPaths);
            window.videoPaths.addAll(bundle.videoPaths);
            assignedBuckets.add(bucketId);
        }
    }

    private static void assignContext(
            Map<Integer, MutableWindow> windows,
            List<AudioItem> laughter,
            List<AudioItem> context,
            int contextNeighborClips) {
        HashMap<Integer, List<AudioItem>> laughterByClip =
                new HashMap<>();
        for (AudioItem item : laughter) {
            List<AudioItem> items = laughterByClip.get(item.clipId);
            if (items == null) {
                items = new ArrayList<>();
                laughterByClip.put(item.clipId, items);
            }
            items.add(item);
        }
        for (AudioItem item : context) {
            ArrayList<AudioItem> candidates = new ArrayList<>();
            JSONArray links = item.json.optJSONArray(
                    "linked_laughter_clip_ids");
            if (links != null && links.length() > 0) {
                for (int i = 0; i < links.length(); i++) {
                    List<AudioItem> linked =
                            laughterByClip.get(links.optInt(i, -1));
                    if (linked != null) {
                        candidates.addAll(linked);
                    }
                }
            } else if (item.clipId >= 0) {
                for (AudioItem laughterItem : laughter) {
                    if (laughterItem.clipId >= 0
                            && Math.abs(
                                    laughterItem.clipId - item.clipId)
                            <= contextNeighborClips) {
                        candidates.add(laughterItem);
                    }
                }
            }
            AudioItem winner = chooseContextOwner(
                    item,
                    candidates);
            if (winner != null) {
                MutableWindow window =
                        windows.get(winner.bucketId);
                if (window != null) {
                    window.context.add(item);
                }
            }
        }
    }

    private static AudioItem chooseContextOwner(
            AudioItem context,
            List<AudioItem> candidates) {
        AudioItem winner = null;
        double bestDelta = Double.MAX_VALUE;
        for (AudioItem candidate : candidates) {
            double delta = Math.abs(
                    context.startSec - candidate.startSec);
            if (winner == null
                    || delta < bestDelta
                    || (delta == bestDelta
                            && candidate.bucketId
                            < winner.bucketId)
                    || (delta == bestDelta
                            && candidate.bucketId
                            == winner.bucketId
                            && candidate.startSec
                            < winner.startSec)) {
                winner = candidate;
                bestDelta = delta;
            }
        }
        return winner;
    }

    private static void sortAudio(List<AudioItem> items) {
        Collections.sort(
                items,
                new Comparator<AudioItem>() {
                    @Override
                    public int compare(
                            AudioItem left,
                            AudioItem right) {
                        int byTime = Double.compare(
                                left.startSec,
                                right.startSec);
                        return byTime != 0
                                ? byTime
                                : left.path.compareTo(right.path);
                    }
                });
    }

    private static List<JSONObject> immutableJson(
            List<AudioItem> items) {
        ArrayList<JSONObject> result = new ArrayList<>();
        for (AudioItem item : items) {
            result.add(item.json);
        }
        return Collections.unmodifiableList(result);
    }

    private static double calculateUnionDuration(
            List<AudioItem> items) {
        ArrayList<AudioItem> intervals = new ArrayList<>();
        double fallbackDuration = 0.0;
        for (AudioItem item : items) {
            if (item.endSec > item.startSec) {
                intervals.add(item);
            } else {
                fallbackDuration += item.durationSec;
            }
        }
        sortAudio(intervals);
        double total = fallbackDuration;
        double currentStart = Double.NaN;
        double currentEnd = Double.NaN;
        for (AudioItem item : intervals) {
            if (Double.isNaN(currentStart)) {
                currentStart = item.startSec;
                currentEnd = item.endSec;
            } else if (item.startSec <= currentEnd) {
                currentEnd = Math.max(currentEnd, item.endSec);
            } else {
                total += currentEnd - currentStart;
                currentStart = item.startSec;
                currentEnd = item.endSec;
            }
        }
        if (!Double.isNaN(currentStart)) {
            total += currentEnd - currentStart;
        }
        return total;
    }
}
