package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AtlasClipMediaMatcherTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void choosesNearestIndependentCandidateInsideInclusiveWindow()
            throws Exception {
        File early = temporaryFolder.newFile("event_photo_1000.jpg");
        File near = temporaryFolder.newFile("event_photo_2000.jpg");
        JSONArray items = new JSONArray()
                .put(item(early, 1000L))
                .put(item(near, 2000L));

        assertEquals(
                near.getAbsolutePath(),
                AtlasClipMediaMatcher.findNearestPath(
                        items,
                        "photo_path",
                        2500L,
                        1500L));
    }

    @Test
    public void exactBoundaryIsIncludedAndOutsideBoundaryIsRejected()
            throws Exception {
        File boundary = temporaryFolder.newFile("event_video_1000.mp4");
        JSONArray items = new JSONArray().put(item(boundary, 1000L));

        assertEquals(
                boundary.getAbsolutePath(),
                AtlasClipMediaMatcher.findNearestPath(
                        items,
                        "video_path",
                        91000L,
                        90000L));
        assertNull(AtlasClipMediaMatcher.findNearestPath(
                items,
                "video_path",
                91001L,
                90000L));
    }

    @Test
    public void equalDeltaPrefersEarlierThenLexicographicPath()
            throws Exception {
        File earlier = temporaryFolder.newFile("a.jpg");
        File later = temporaryFolder.newFile("b.jpg");
        JSONArray items = new JSONArray()
                .put(item(later, 11000L))
                .put(item(earlier, 9000L));

        assertEquals(
                earlier.getAbsolutePath(),
                AtlasClipMediaMatcher.findNearestPath(
                        items,
                        "photo_path",
                        10000L,
                        90000L));
    }

    @Test
    public void equalTimePrefersLexicographicPath() throws Exception {
        File laterPath = temporaryFolder.newFile("z.jpg");
        File earlierPath = temporaryFolder.newFile("a.jpg");
        JSONArray items = new JSONArray()
                .put(item(laterPath, 10000L))
                .put(item(earlierPath, 10000L));

        assertEquals(
                earlierPath.getAbsolutePath(),
                AtlasClipMediaMatcher.findNearestPath(
                        items,
                        "photo_path",
                        10000L,
                        90000L));
    }

    @Test
    public void missingUnknownAndTooDistantItemsReturnNull() throws Exception {
        JSONArray items = new JSONArray()
                .put(new JSONObject()
                        .put("photo_path", "/missing.jpg")
                        .put("capture_time_ms", 10000L))
                .put(new JSONObject().put("photo_path", "/unknown.jpg"));

        assertNull(AtlasClipMediaMatcher.findNearestPath(
                items,
                "photo_path",
                200000L,
                90000L));
    }

    @Test
    public void skipsMissingNearestAndUsesNextAccessibleCandidate()
            throws Exception {
        File accessible = temporaryFolder.newFile("accessible.jpg");
        JSONArray items = new JSONArray()
                .put(new JSONObject()
                        .put("photo_path", "/missing-nearest.jpg")
                        .put("capture_time_ms", 10000L))
                .put(item(accessible, 11000L));

        assertEquals(
                accessible.getAbsolutePath(),
                AtlasClipMediaMatcher.findNearestPath(
                        items,
                        "photo_path",
                        10000L,
                        90000L));
    }

    @Test
    public void sameMediaCanBeSelectedForNeighboringClips() throws Exception {
        File shared = temporaryFolder.newFile("shared.jpg");
        JSONArray items = new JSONArray().put(item(shared, 10000L));

        assertEquals(
                shared.getAbsolutePath(),
                AtlasClipMediaMatcher.findNearestPath(
                        items,
                        "photo_path",
                        9000L,
                        90000L));
        assertEquals(
                shared.getAbsolutePath(),
                AtlasClipMediaMatcher.findNearestPath(
                        items,
                        "photo_path",
                        11000L,
                        90000L));
    }

    @Test
    public void invalidClipTimeOrWindowReturnsNull() throws Exception {
        File media = temporaryFolder.newFile("media.jpg");
        JSONArray items = new JSONArray().put(item(media, 10000L));

        assertNull(AtlasClipMediaMatcher.findNearestPath(
                items, "photo_path", 0L, 90000L));
        assertNull(AtlasClipMediaMatcher.findNearestPath(
                items, "photo_path", 10000L, -1L));
    }

    private JSONObject item(File file, long captureTimeMs) throws Exception {
        String key = file.getName().endsWith(".mp4")
                ? "video_path"
                : "photo_path";
        return new JSONObject()
                .put(key, file.getAbsolutePath())
                .put("capture_time_ms", captureTimeMs);
    }
}
