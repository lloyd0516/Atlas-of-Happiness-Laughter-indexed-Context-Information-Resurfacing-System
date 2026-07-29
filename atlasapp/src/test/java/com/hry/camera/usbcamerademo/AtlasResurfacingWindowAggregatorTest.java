package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AtlasResurfacingWindowAggregatorTest {
    private static final long SESSION_START_MS = 1_000_000L;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void multipleLaughterItemsShareOneWindowAndMergeDuration()
            throws Exception {
        JSONArray audio = new JSONArray()
                .put(laughter(1, 5.0, 7.0))
                .put(laughter(2, 40.0, 41.5))
                .put(laughter(3, 89.0, 90.0))
                .put(laughter(4, 6.0, 8.0));

        List<AtlasResurfacingWindowAggregator.Window> windows =
                aggregate(audio, new JSONArray(), new JSONArray());

        assertEquals(1, windows.size());
        assertEquals(4, windows.get(0).laughterClips.size());
        assertEquals(
                5.5,
                windows.get(0).totalLaughterDurationSec,
                0.001);
    }

    @Test
    public void exactBoundaryStartsNextWindow() throws Exception {
        JSONArray audio = new JSONArray()
                .put(laughter(2, 89.999, 90.0))
                .put(laughter(3, 90.0, 91.0));

        List<AtlasResurfacingWindowAggregator.Window> windows =
                aggregate(audio, new JSONArray(), new JSONArray());

        assertEquals(2, windows.size());
        assertEquals(0, windows.get(0).bucketId);
        assertEquals(1, windows.get(1).bucketId);
        assertEquals(
                SESSION_START_MS,
                windows.get(0).startTimeMs);
        assertEquals(
                SESSION_START_MS + 90_000L,
                windows.get(1).startTimeMs);
    }

    @Test
    public void mediaBundleBelongsToOneExactLaughterWindow()
            throws Exception {
        File photo = temporaryFolder.newFile("bucket-photo.jpg");
        File video = temporaryFolder.newFile("bucket-video.mp4");
        JSONArray photos = new JSONArray().put(media(
                photo,
                "photo_path",
                "bucket-1",
                SESSION_START_MS + 91_000L,
                1,
                3,
                90));
        JSONArray videos = new JSONArray().put(media(
                video,
                "video_path",
                "bucket-1",
                SESSION_START_MS + 91_000L,
                1,
                3,
                90));
        JSONArray audio = new JSONArray()
                .put(laughter(0, 10.0, 11.0))
                .put(laughter(3, 95.0, 96.0));

        List<AtlasResurfacingWindowAggregator.Window> windows =
                aggregate(audio, photos, videos);

        assertEquals(0, windows.get(0).photoPaths.size());
        assertEquals(1, windows.get(1).photoPaths.size());
        assertEquals(1, windows.get(1).videoPaths.size());
    }

    @Test
    public void legacyFourClipMetadataFallsBackToTriggerTime()
            throws Exception {
        File photo = temporaryFolder.newFile("old-bucket.jpg");
        JSONArray photos = new JSONArray().put(media(
                photo,
                "photo_path",
                "old-bucket-0",
                SESSION_START_MS + 95_000L,
                0,
                4,
                120));
        JSONArray audio = new JSONArray()
                .put(laughter(0, 10.0, 11.0))
                .put(laughter(3, 95.0, 96.0));

        List<AtlasResurfacingWindowAggregator.Window> windows =
                aggregate(audio, photos, new JSONArray());

        assertEquals(0, windows.get(0).photoPaths.size());
        assertEquals(1, windows.get(1).photoPaths.size());
    }

    @Test
    public void contextOnlyBucketNeverCreatesCard() throws Exception {
        JSONArray audio = new JSONArray()
                .put(context(6, 180.0, 210.0, new JSONArray()));

        assertEquals(
                0,
                aggregate(audio, new JSONArray(), new JSONArray()).size());
    }

    @Test
    public void linkedContextUsesClosestLaughterAndIsAssignedOnce()
            throws Exception {
        JSONArray audio = new JSONArray()
                .put(laughter(2, 80.0, 81.0))
                .put(laughter(3, 100.0, 101.0))
                .put(context(
                        4,
                        120.0,
                        150.0,
                        new JSONArray().put(2).put(3)));

        List<AtlasResurfacingWindowAggregator.Window> windows =
                aggregate(audio, new JSONArray(), new JSONArray());

        assertEquals(2, windows.size());
        assertEquals(0, windows.get(0).contextClips.size());
        assertEquals(1, windows.get(1).contextClips.size());
    }

    @Test
    public void equalContextDistanceChoosesEarlierWindow()
            throws Exception {
        JSONArray audio = new JSONArray()
                .put(laughter(2, 80.0, 81.0))
                .put(laughter(3, 100.0, 101.0))
                .put(context(
                        3,
                        90.0,
                        120.0,
                        new JSONArray().put(2).put(3)));

        List<AtlasResurfacingWindowAggregator.Window> windows =
                aggregate(audio, new JSONArray(), new JSONArray());

        assertEquals(1, windows.get(0).contextClips.size());
        assertEquals(0, windows.get(1).contextClips.size());
    }

    @Test
    public void legacyContextUsesClipDistanceFallback()
            throws Exception {
        JSONArray audio = new JSONArray()
                .put(laughter(3, 100.0, 101.0))
                .put(context(4, 120.0, 150.0, null));

        List<AtlasResurfacingWindowAggregator.Window> windows =
                aggregate(audio, new JSONArray(), new JSONArray());

        assertEquals(1, windows.size());
        assertEquals(1, windows.get(0).contextClips.size());
    }

    private List<AtlasResurfacingWindowAggregator.Window> aggregate(
            JSONArray audio,
            JSONArray photos,
            JSONArray videos) {
        return AtlasResurfacingWindowAggregator.aggregate(
                audio,
                photos,
                videos,
                SESSION_START_MS,
                30,
                2,
                15000L);
    }

    private JSONObject laughter(
            int clipId,
            double startSec,
            double endSec) throws Exception {
        File file = temporaryFolder.newFile(
                "laughter-" + clipId + "-" + startSec + ".wav");
        return new JSONObject()
                .put("type", "laughter")
                .put("clip_id", clipId)
                .put("path", file.getAbsolutePath())
                .put("start_sec", startSec)
                .put("end_sec", endSec)
                .put("duration_sec", endSec - startSec);
    }

    private JSONObject context(
            int clipId,
            double startSec,
            double endSec,
            JSONArray links) throws Exception {
        File file = temporaryFolder.newFile(
                "context-" + clipId + "-" + startSec + ".wav");
        JSONObject json = new JSONObject()
                .put("type", "possible_related_speech_context")
                .put("clip_id", clipId)
                .put("path", file.getAbsolutePath())
                .put("start_sec", startSec)
                .put("end_sec", endSec)
                .put("duration_sec", endSec - startSec);
        if (links != null) {
            json.put("linked_laughter_clip_ids", links);
        }
        return json;
    }

    private JSONObject media(
            File file,
            String pathKey,
            String bundleId,
            long triggerTimeMs,
            int bucketId,
            int clipCount,
            int bucketDurationSec) throws Exception {
        return new JSONObject()
                .put(pathKey, file.getAbsolutePath())
                .put("capture_time_ms", triggerTimeMs)
                .put("bundle_id", bundleId)
                .put("bundle_trigger_time_ms", triggerTimeMs)
                .put("bundle_media_index", 0)
                .put("automation_bucket_id", bucketId)
                .put("automation_bucket_clip_count", clipCount)
                .put(
                        "automation_bucket_duration_sec",
                        bucketDurationSec);
    }
}
