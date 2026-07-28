package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AtlasMediaCaptureTimeResolverTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void explicitCaptureTimeWins() throws Exception {
        File file = temporaryFolder.newFile("event_photo_1111111111111.jpg");
        JSONObject item = new JSONObject()
                .put("photo_path", file.getAbsolutePath())
                .put("capture_time_ms", 5000L);

        assertEquals(
                5000L,
                AtlasMediaCaptureTimeResolver.resolve(
                        item,
                        "photo_path",
                        1000L,
                        9000L));
    }

    @Test
    public void legacyStableFilenameRecoversMilliseconds() throws Exception {
        File file = temporaryFolder.newFile(
                "event_video_1720000000123.mp4");
        JSONObject item =
                new JSONObject().put("video_path", file.getAbsolutePath());

        assertEquals(
                1720000000123L,
                AtlasMediaCaptureTimeResolver.resolve(
                        item,
                        "video_path",
                        1719999999000L,
                        1720000001000L));
    }

    @Test
    public void plausibleFileTimeIsLastFallback() throws Exception {
        File file = temporaryFolder.newFile("legacy.jpg");
        assertTrue(file.setLastModified(5000L));
        JSONObject item =
                new JSONObject().put("photo_path", file.getAbsolutePath());

        assertEquals(
                5000L,
                AtlasMediaCaptureTimeResolver.resolve(
                        item,
                        "photo_path",
                        4000L,
                        6000L));
    }

    @Test
    public void syntheticTimestampAndImplausibleFileTimeDoNotBecomeCaptureTime()
            throws Exception {
        File file = temporaryFolder.newFile("legacy.jpg");
        assertTrue(file.setLastModified(200000L));
        JSONObject item = new JSONObject()
                .put("photo_path", file.getAbsolutePath())
                .put("timestamp", "1970-01-01T00:00:04.000+0000");

        assertEquals(
                -1L,
                AtlasMediaCaptureTimeResolver.resolve(
                        item,
                        "photo_path",
                        4000L,
                        6000L));
    }

    @Test
    public void malformedStableFilenameIsNotAccepted() throws Exception {
        File file = temporaryFolder.newFile("event_photo_not-a-time.jpg");
        assertTrue(file.setLastModified(200000L));
        JSONObject item =
                new JSONObject().put("photo_path", file.getAbsolutePath());

        assertEquals(
                -1L,
                AtlasMediaCaptureTimeResolver.resolve(
                        item,
                        "photo_path",
                        4000L,
                        6000L));
    }
}
