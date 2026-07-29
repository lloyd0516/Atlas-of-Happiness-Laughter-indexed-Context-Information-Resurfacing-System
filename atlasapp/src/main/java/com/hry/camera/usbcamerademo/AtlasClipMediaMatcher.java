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

/** Selects one deterministic capture bundle near a laughter clip. */
final class AtlasClipMediaMatcher {
    private AtlasClipMediaMatcher() {
    }

    static final class MatchedCaptureBundle {
        final String bundleId;
        final long bundleTimeMs;
        final List<String> photoPaths;
        final List<String> videoPaths;

        private MatchedCaptureBundle(
                String bundleId,
                long bundleTimeMs,
                List<String> photoPaths,
                List<String> videoPaths) {
            this.bundleId = bundleId;
            this.bundleTimeMs = bundleTimeMs;
            this.photoPaths = Collections.unmodifiableList(
                    new ArrayList<>(photoPaths));
            this.videoPaths = Collections.unmodifiableList(
                    new ArrayList<>(videoPaths));
        }
    }

    private static final class MediaCandidate {
        final String path;
        final long captureTimeMs;
        final String bundleId;
        final long bundleTriggerTimeMs;
        final int mediaIndex;

        MediaCandidate(
                String path,
                long captureTimeMs,
                String bundleId,
                long bundleTriggerTimeMs,
                int mediaIndex) {
            this.path = path;
            this.captureTimeMs = captureTimeMs;
            this.bundleId = bundleId;
            this.bundleTriggerTimeMs = bundleTriggerTimeMs;
            this.mediaIndex = mediaIndex;
        }
    }

    private static final class BundleCandidate {
        final String bundleId;
        long explicitTriggerTimeMs = Long.MAX_VALUE;
        long earliestCaptureTimeMs = Long.MAX_VALUE;
        final List<MediaCandidate> photos = new ArrayList<>();
        final List<MediaCandidate> videos = new ArrayList<>();

        BundleCandidate(String bundleId) {
            this.bundleId = bundleId;
        }

        void add(MediaCandidate candidate, boolean video) {
            if (candidate.bundleTriggerTimeMs > 0L) {
                explicitTriggerTimeMs = Math.min(
                        explicitTriggerTimeMs,
                        candidate.bundleTriggerTimeMs);
            }
            earliestCaptureTimeMs = Math.min(
                    earliestCaptureTimeMs,
                    candidate.captureTimeMs);
            if (video) {
                videos.add(candidate);
            } else {
                photos.add(candidate);
            }
        }

        long bundleTimeMs() {
            return explicitTriggerTimeMs != Long.MAX_VALUE
                    ? explicitTriggerTimeMs
                    : earliestCaptureTimeMs;
        }
    }

    private static final class LegacyPair {
        final BundleCandidate videoBundle;
        final MediaCandidate video;
        final MediaCandidate photo;
        final long deltaMs;

        LegacyPair(
                BundleCandidate videoBundle,
                MediaCandidate video,
                MediaCandidate photo) {
            this.videoBundle = videoBundle;
            this.video = video;
            this.photo = photo;
            this.deltaMs = absoluteDelta(
                    video.captureTimeMs,
                    photo.captureTimeMs);
        }
    }

    private static final Comparator<MediaCandidate>
            CAPTURE_TIME_COMPARATOR =
            new Comparator<MediaCandidate>() {
                @Override
                public int compare(
                        MediaCandidate left,
                        MediaCandidate right) {
                    int byTime = compareLong(
                            left.captureTimeMs,
                            right.captureTimeMs);
                    return byTime != 0
                            ? byTime
                            : left.path.compareTo(right.path);
                }
            };

    private static final Comparator<MediaCandidate>
            DISPLAY_ORDER_COMPARATOR =
            new Comparator<MediaCandidate>() {
                @Override
                public int compare(
                        MediaCandidate left,
                        MediaCandidate right) {
                    int leftIndex = left.mediaIndex >= 0
                            ? left.mediaIndex
                            : Integer.MAX_VALUE;
                    int rightIndex = right.mediaIndex >= 0
                            ? right.mediaIndex
                            : Integer.MAX_VALUE;
                    if (leftIndex != rightIndex) {
                        return leftIndex < rightIndex ? -1 : 1;
                    }
                    return CAPTURE_TIME_COMPARATOR.compare(
                            left,
                            right);
                }
            };

    static MatchedCaptureBundle findNearestBundle(
            JSONArray photos,
            JSONArray videos,
            long clipTimeMs,
            long maxDeltaMs,
            long legacyGroupWindowMs) {
        if (clipTimeMs <= 0L
                || maxDeltaMs < 0L
                || legacyGroupWindowMs < 0L) {
            return null;
        }

        List<MediaCandidate> photoCandidates =
                parseCandidates(photos, "photo_path");
        List<MediaCandidate> videoCandidates =
                parseCandidates(videos, "video_path");
        Map<String, BundleCandidate> explicitBundles =
                new HashMap<>();
        List<MediaCandidate> legacyPhotos = new ArrayList<>();
        List<MediaCandidate> legacyVideos = new ArrayList<>();

        distributeCandidates(
                photoCandidates,
                false,
                explicitBundles,
                legacyPhotos);
        distributeCandidates(
                videoCandidates,
                true,
                explicitBundles,
                legacyVideos);

        ArrayList<BundleCandidate> allBundles =
                new ArrayList<>(explicitBundles.values());
        allBundles.addAll(inferLegacyBundles(
                legacyPhotos,
                legacyVideos,
                legacyGroupWindowMs));

        BundleCandidate winner = chooseNearestBundle(
                allBundles,
                clipTimeMs,
                maxDeltaMs);
        return winner != null ? toMatch(winner) : null;
    }

    private static List<MediaCandidate> parseCandidates(
            JSONArray items,
            String pathKey) {
        ArrayList<MediaCandidate> result = new ArrayList<>();
        if (items == null || pathKey == null
                || pathKey.length() == 0) {
            return result;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            long captureTimeMs =
                    item.optLong("capture_time_ms", -1L);
            String path = item.optString(pathKey, "");
            if (captureTimeMs <= 0L
                    || path.length() == 0
                    || !new File(path).isFile()) {
                continue;
            }
            result.add(new MediaCandidate(
                    path,
                    captureTimeMs,
                    item.optString("bundle_id", ""),
                    item.optLong(
                            "bundle_trigger_time_ms",
                            -1L),
                    item.optInt("bundle_media_index", -1)));
        }
        return result;
    }

    private static void distributeCandidates(
            List<MediaCandidate> candidates,
            boolean video,
            Map<String, BundleCandidate> explicitBundles,
            List<MediaCandidate> legacy) {
        for (MediaCandidate candidate : candidates) {
            if (candidate.bundleId.length() == 0) {
                legacy.add(candidate);
                continue;
            }
            BundleCandidate bundle =
                    explicitBundles.get(candidate.bundleId);
            if (bundle == null) {
                bundle = new BundleCandidate(candidate.bundleId);
                explicitBundles.put(candidate.bundleId, bundle);
            }
            bundle.add(candidate, video);
        }
    }

    private static List<BundleCandidate> inferLegacyBundles(
            List<MediaCandidate> photos,
            List<MediaCandidate> videos,
            long groupWindowMs) {
        Collections.sort(photos, CAPTURE_TIME_COMPARATOR);
        Collections.sort(videos, CAPTURE_TIME_COMPARATOR);

        ArrayList<BundleCandidate> bundles = new ArrayList<>();
        HashMap<String, BundleCandidate> videoBundles =
                new HashMap<>();
        for (MediaCandidate video : videos) {
            BundleCandidate bundle = new BundleCandidate(
                    legacyBundleId(
                            video.captureTimeMs,
                            video.path));
            bundle.add(video, true);
            videoBundles.put(video.path, bundle);
            bundles.add(bundle);
        }

        ArrayList<LegacyPair> pairs = new ArrayList<>();
        for (MediaCandidate video : videos) {
            BundleCandidate bundle = videoBundles.get(video.path);
            for (MediaCandidate photo : photos) {
                if (absoluteDelta(
                        video.captureTimeMs,
                        photo.captureTimeMs) <= groupWindowMs) {
                    pairs.add(new LegacyPair(
                            bundle,
                            video,
                            photo));
                }
            }
        }
        Collections.sort(
                pairs,
                new Comparator<LegacyPair>() {
                    @Override
                    public int compare(
                            LegacyPair left,
                            LegacyPair right) {
                        int byDelta = compareLong(
                                left.deltaMs,
                                right.deltaMs);
                        if (byDelta != 0) {
                            return byDelta;
                        }
                        int byVideo = CAPTURE_TIME_COMPARATOR
                                .compare(left.video, right.video);
                        if (byVideo != 0) {
                            return byVideo;
                        }
                        return CAPTURE_TIME_COMPARATOR
                                .compare(left.photo, right.photo);
                    }
                });

        Set<String> assignedPhotoPaths = new HashSet<>();
        for (LegacyPair pair : pairs) {
            if (assignedPhotoPaths.contains(pair.photo.path)
                    || pair.videoBundle.photos.size()
                    >= AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE) {
                continue;
            }
            pair.videoBundle.add(pair.photo, false);
            assignedPhotoPaths.add(pair.photo.path);
        }

        ArrayList<MediaCandidate> remainingPhotos =
                new ArrayList<>();
        for (MediaCandidate photo : photos) {
            if (!assignedPhotoPaths.contains(photo.path)) {
                remainingPhotos.add(photo);
            }
        }
        for (int i = 0; i < remainingPhotos.size(); i++) {
            MediaCandidate first = remainingPhotos.get(i);
            BundleCandidate bundle = new BundleCandidate(
                    legacyBundleId(
                            first.captureTimeMs,
                            first.path));
            bundle.add(first, false);
            if (i + 1 < remainingPhotos.size()) {
                MediaCandidate second =
                        remainingPhotos.get(i + 1);
                if (absoluteDelta(
                        first.captureTimeMs,
                        second.captureTimeMs) <= groupWindowMs) {
                    bundle.add(second, false);
                    i++;
                }
            }
            bundles.add(bundle);
        }
        return bundles;
    }

    private static BundleCandidate chooseNearestBundle(
            List<BundleCandidate> bundles,
            long clipTimeMs,
            long maxDeltaMs) {
        BundleCandidate best = null;
        long bestDelta = Long.MAX_VALUE;
        for (BundleCandidate bundle : bundles) {
            long bundleTimeMs = bundle.bundleTimeMs();
            if (bundleTimeMs <= 0L
                    || bundleTimeMs == Long.MAX_VALUE) {
                continue;
            }
            long delta = absoluteDelta(
                    bundleTimeMs,
                    clipTimeMs);
            if (delta > maxDeltaMs) {
                continue;
            }
            if (best == null
                    || delta < bestDelta
                    || (delta == bestDelta
                            && bundleTimeMs
                            < best.bundleTimeMs())
                    || (delta == bestDelta
                            && bundleTimeMs
                            == best.bundleTimeMs()
                            && bundle.bundleId.compareTo(
                                    best.bundleId) < 0)) {
                best = bundle;
                bestDelta = delta;
            }
        }
        return best;
    }

    private static MatchedCaptureBundle toMatch(
            BundleCandidate bundle) {
        Collections.sort(
                bundle.photos,
                DISPLAY_ORDER_COMPARATOR);
        Collections.sort(
                bundle.videos,
                DISPLAY_ORDER_COMPARATOR);
        ArrayList<String> photoPaths = new ArrayList<>();
        for (MediaCandidate photo : bundle.photos) {
            if (photoPaths.size()
                    >= AppConfig.AUTO_CAPTURE_PHOTOS_PER_BUNDLE) {
                break;
            }
            photoPaths.add(photo.path);
        }
        ArrayList<String> videoPaths = new ArrayList<>();
        for (MediaCandidate video : bundle.videos) {
            if (videoPaths.size()
                    >= AppConfig.AUTO_CAPTURE_VIDEOS_PER_BUNDLE) {
                break;
            }
            videoPaths.add(video.path);
        }
        return new MatchedCaptureBundle(
                bundle.bundleId,
                bundle.bundleTimeMs(),
                photoPaths,
                videoPaths);
    }

    private static String legacyBundleId(
            long bundleTimeMs,
            String anchorPath) {
        return "legacy@" + bundleTimeMs + ":" + anchorPath;
    }

    private static long absoluteDelta(long left, long right) {
        return left >= right ? left - right : right - left;
    }

    private static int compareLong(long left, long right) {
        return left < right ? -1 : (left == right ? 0 : 1);
    }

}
