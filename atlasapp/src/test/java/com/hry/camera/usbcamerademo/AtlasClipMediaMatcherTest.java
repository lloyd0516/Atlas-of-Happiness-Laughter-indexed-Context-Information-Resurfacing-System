package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class AtlasClipMediaMatcherTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void explicitBundleReturnsTwoPhotosAndOneVideo()
            throws Exception {
        File p0 = temporaryFolder.newFile("p0.jpg");
        File p1 = temporaryFolder.newFile("p1.jpg");
        File video = temporaryFolder.newFile("v.mp4");
        JSONArray photos = new JSONArray()
                .put(item(
                        p1,
                        "photo_path",
                        13500L,
                        "b1",
                        10000L,
                        1))
                .put(item(
                        p0,
                        "photo_path",
                        11500L,
                        "b1",
                        10000L,
                        0));
        JSONArray videos = new JSONArray()
                .put(item(
                        video,
                        "video_path",
                        10100L,
                        "b1",
                        10000L,
                        0));

        AtlasClipMediaMatcher.MatchedCaptureBundle match =
                AtlasClipMediaMatcher.findNearestBundle(
                        photos,
                        videos,
                        9000L,
                        90000L,
                        15000L);

        assertNotNull(match);
        assertEquals("b1", match.bundleId);
        assertEquals(2, match.photoPaths.size());
        assertEquals(
                p0.getAbsolutePath(),
                match.photoPaths.get(0));
        assertEquals(
                p1.getAbsolutePath(),
                match.photoPaths.get(1));
        assertEquals(1, match.videoPaths.size());
        assertEquals(
                video.getAbsolutePath(),
                match.videoPaths.get(0));
    }

    @Test
    public void explicitPartialBundleDoesNotBorrowFromNeighbor()
            throws Exception {
        File nearPhoto = temporaryFolder.newFile("near.jpg");
        File otherPhoto = temporaryFolder.newFile("other.jpg");
        File otherVideo = temporaryFolder.newFile("other.mp4");
        JSONArray photos = new JSONArray()
                .put(item(
                        nearPhoto,
                        "photo_path",
                        10000L,
                        "near",
                        10000L,
                        0))
                .put(item(
                        otherPhoto,
                        "photo_path",
                        20000L,
                        "other",
                        20000L,
                        0));
        JSONArray videos = new JSONArray()
                .put(item(
                        otherVideo,
                        "video_path",
                        20000L,
                        "other",
                        20000L,
                        0));

        AtlasClipMediaMatcher.MatchedCaptureBundle match =
                AtlasClipMediaMatcher.findNearestBundle(
                        photos,
                        videos,
                        10000L,
                        90000L,
                        15000L);

        assertNotNull(match);
        assertEquals("near", match.bundleId);
        assertEquals(1, match.photoPaths.size());
        assertEquals(0, match.videoPaths.size());
    }

    @Test
    public void exactBoundaryIsIncludedAndOutsideBoundaryIsRejected()
            throws Exception {
        File included = temporaryFolder.newFile("included.jpg");
        File excluded = temporaryFolder.newFile("excluded.jpg");
        JSONArray includedPhotos = new JSONArray()
                .put(item(
                        included,
                        "photo_path",
                        1000L,
                        "included",
                        1000L,
                        0));
        JSONArray excludedPhotos = new JSONArray()
                .put(item(
                        excluded,
                        "photo_path",
                        999L,
                        "excluded",
                        999L,
                        0));

        assertNotNull(AtlasClipMediaMatcher.findNearestBundle(
                includedPhotos,
                new JSONArray(),
                91000L,
                90000L,
                15000L));
        assertNull(AtlasClipMediaMatcher.findNearestBundle(
                excludedPhotos,
                new JSONArray(),
                91000L,
                90000L,
                15000L));
    }

    @Test
    public void bundleTiePrefersEarlierTimeThenBundleId()
            throws Exception {
        File earlier = temporaryFolder.newFile("earlier.jpg");
        File later = temporaryFolder.newFile("later.jpg");
        JSONArray photos = new JSONArray()
                .put(item(
                        later,
                        "photo_path",
                        11000L,
                        "z-later",
                        11000L,
                        0))
                .put(item(
                        earlier,
                        "photo_path",
                        9000L,
                        "z-earlier",
                        9000L,
                        0));

        AtlasClipMediaMatcher.MatchedCaptureBundle byTime =
                AtlasClipMediaMatcher.findNearestBundle(
                        photos,
                        new JSONArray(),
                        10000L,
                        90000L,
                        15000L);
        assertEquals("z-earlier", byTime.bundleId);

        File a = temporaryFolder.newFile("a.jpg");
        File b = temporaryFolder.newFile("b.jpg");
        JSONArray sameTime = new JSONArray()
                .put(item(
                        b,
                        "photo_path",
                        10000L,
                        "b",
                        10000L,
                        0))
                .put(item(
                        a,
                        "photo_path",
                        10000L,
                        "a",
                        10000L,
                        0));
        AtlasClipMediaMatcher.MatchedCaptureBundle byId =
                AtlasClipMediaMatcher.findNearestBundle(
                        sameTime,
                        new JSONArray(),
                        10000L,
                        90000L,
                        15000L);
        assertEquals("a", byId.bundleId);
    }

    @Test
    public void sameBundleCanBeSelectedForNeighboringClips()
            throws Exception {
        File shared = temporaryFolder.newFile("shared.jpg");
        JSONArray photos = new JSONArray()
                .put(item(
                        shared,
                        "photo_path",
                        10000L,
                        "shared",
                        10000L,
                        0));

        assertEquals(
                "shared",
                AtlasClipMediaMatcher.findNearestBundle(
                        photos,
                        new JSONArray(),
                        9000L,
                        90000L,
                        15000L).bundleId);
        assertEquals(
                "shared",
                AtlasClipMediaMatcher.findNearestBundle(
                        photos,
                        new JSONArray(),
                        11000L,
                        90000L,
                        15000L).bundleId);
    }

    @Test
    public void missingFilesAreOmittedWithoutCrossBundleFill()
            throws Exception {
        File accessible = temporaryFolder.newFile("accessible.jpg");
        JSONArray photos = new JSONArray()
                .put(new JSONObject()
                        .put("photo_path", "/missing.jpg")
                        .put("capture_time_ms", 10100L)
                        .put("bundle_id", "near")
                        .put("bundle_trigger_time_ms", 10000L)
                        .put("bundle_media_index", 0))
                .put(item(
                        accessible,
                        "photo_path",
                        10200L,
                        "near",
                        10000L,
                        1));

        AtlasClipMediaMatcher.MatchedCaptureBundle match =
                AtlasClipMediaMatcher.findNearestBundle(
                        photos,
                        new JSONArray(),
                        10000L,
                        90000L,
                        15000L);

        assertNotNull(match);
        assertEquals(1, match.photoPaths.size());
        assertEquals(
                accessible.getAbsolutePath(),
                match.photoPaths.get(0));
    }

    @Test
    public void malformedExplicitBundleIsCappedAtTwoPhotosAndOneVideo()
            throws Exception {
        JSONArray photos = new JSONArray();
        JSONArray videos = new JSONArray();
        for (int i = 0; i < 3; i++) {
            File photo = temporaryFolder.newFile(
                    "photo" + i + ".jpg");
            photos.put(item(
                    photo,
                    "photo_path",
                    10000L + i,
                    "b1",
                    10000L,
                    i));
        }
        for (int i = 0; i < 2; i++) {
            File video = temporaryFolder.newFile(
                    "video" + i + ".mp4");
            videos.put(item(
                    video,
                    "video_path",
                    10000L + i,
                    "b1",
                    10000L,
                    i));
        }

        AtlasClipMediaMatcher.MatchedCaptureBundle match =
                AtlasClipMediaMatcher.findNearestBundle(
                        photos,
                        videos,
                        10000L,
                        90000L,
                        15000L);

        assertEquals(2, match.photoPaths.size());
        assertEquals(1, match.videoPaths.size());
    }

    @Test
    public void legacyMediaWithinFifteenSecondsFormsTwoPlusOneBundle()
            throws Exception {
        File video = temporaryFolder.newFile("legacy.mp4");
        File p0 = temporaryFolder.newFile("legacy0.jpg");
        File p1 = temporaryFolder.newFile("legacy1.jpg");
        JSONArray photos = new JSONArray()
                .put(legacyItem(p0, "photo_path", 11500L))
                .put(legacyItem(p1, "photo_path", 13500L));
        JSONArray videos = new JSONArray()
                .put(legacyItem(video, "video_path", 10000L));

        AtlasClipMediaMatcher.MatchedCaptureBundle match =
                AtlasClipMediaMatcher.findNearestBundle(
                        photos,
                        videos,
                        9000L,
                        90000L,
                        15000L);

        assertNotNull(match);
        assertEquals(2, match.photoPaths.size());
        assertEquals(1, match.videoPaths.size());
    }

    @Test
    public void legacyPhotoOutsideGroupingWindowIsNotBorrowed()
            throws Exception {
        File video = temporaryFolder.newFile("legacy-video.mp4");
        File near = temporaryFolder.newFile("legacy-near.jpg");
        File far = temporaryFolder.newFile("legacy-far.jpg");
        JSONArray photos = new JSONArray()
                .put(legacyItem(near, "photo_path", 11000L))
                .put(legacyItem(far, "photo_path", 30001L));
        JSONArray videos = new JSONArray()
                .put(legacyItem(
                        video,
                        "video_path",
                        10000L));

        AtlasClipMediaMatcher.MatchedCaptureBundle match =
                AtlasClipMediaMatcher.findNearestBundle(
                        photos,
                        videos,
                        10000L,
                        90000L,
                        15000L);

        assertNotNull(match);
        assertEquals(1, match.photoPaths.size());
        assertEquals(
                near.getAbsolutePath(),
                match.photoPaths.get(0));
        assertEquals(1, match.videoPaths.size());
    }

    @Test
    public void legacyPhotosCanFormPhotoOnlyBundle()
            throws Exception {
        File p0 = temporaryFolder.newFile("photo-only-0.jpg");
        File p1 = temporaryFolder.newFile("photo-only-1.jpg");
        JSONArray photos = new JSONArray()
                .put(legacyItem(p0, "photo_path", 10000L))
                .put(legacyItem(p1, "photo_path", 12000L));

        AtlasClipMediaMatcher.MatchedCaptureBundle match =
                AtlasClipMediaMatcher.findNearestBundle(
                        photos,
                        new JSONArray(),
                        11000L,
                        90000L,
                        15000L);

        assertNotNull(match);
        assertEquals(2, match.photoPaths.size());
        assertEquals(0, match.videoPaths.size());
    }

    @Test
    public void invalidClipTimeOrWindowReturnsNull()
            throws Exception {
        File photo = temporaryFolder.newFile("invalid.jpg");
        JSONArray photos = new JSONArray()
                .put(legacyItem(photo, "photo_path", 10000L));

        assertNull(AtlasClipMediaMatcher.findNearestBundle(
                photos,
                new JSONArray(),
                0L,
                90000L,
                15000L));
        assertNull(AtlasClipMediaMatcher.findNearestBundle(
                photos,
                new JSONArray(),
                10000L,
                -1L,
                15000L));
        assertNull(AtlasClipMediaMatcher.findNearestBundle(
                photos,
                new JSONArray(),
                10000L,
                90000L,
                -1L));
    }

    private JSONObject item(
            File file,
            String pathKey,
            long captureTimeMs,
            String bundleId,
            long bundleTimeMs,
            int mediaIndex) throws Exception {
        return legacyItem(file, pathKey, captureTimeMs)
                .put("bundle_id", bundleId)
                .put("bundle_trigger_time_ms", bundleTimeMs)
                .put("bundle_media_index", mediaIndex);
    }

    private JSONObject legacyItem(
            File file,
            String pathKey,
            long captureTimeMs) throws Exception {
        return new JSONObject()
                .put(pathKey, file.getAbsolutePath())
                .put("capture_time_ms", captureTimeMs);
    }
}
