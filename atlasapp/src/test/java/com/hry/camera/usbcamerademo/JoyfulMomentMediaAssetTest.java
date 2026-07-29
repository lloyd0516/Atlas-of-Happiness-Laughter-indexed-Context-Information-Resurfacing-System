package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class JoyfulMomentMediaAssetTest {
    @Test
    public void eventJsonKeepsLegacyPathsAndAddsStructuredCaptureTimes()
            throws Exception {
        JoyfulMomentClusterer.EventRecord event =
                new JoyfulMomentClusterer.EventRecord();
        event.photoPaths.add("/session/event_photo_1000.jpg");
        event.videoPaths.add("/session/event_video_2000.mp4");
        event.videoContentUris.add("content://video/2");
        event.photoAssets.add(
                new JoyfulMomentClusterer.MediaAssetRecord(
                        "/session/event_photo_1000.jpg",
                        null,
                        1500L,
                        "bundle-1",
                        1000L,
                        0,
                        12,
                        3,
                        90));
        event.videoAssets.add(
                new JoyfulMomentClusterer.MediaAssetRecord(
                        "/session/event_video_2000.mp4",
                        "content://video/2",
                        1100L,
                        "bundle-1",
                        1000L,
                        0,
                        12,
                        3,
                        90));

        JSONObject assets = event.toJson().getJSONObject("assets");

        assertEquals(
                "/session/event_photo_1000.jpg",
                assets.getJSONArray("photos").getString(0));
        assertEquals(
                1500L,
                assets.getJSONArray("photo_records")
                        .getJSONObject(0)
                        .getLong("capture_time_ms"));
        assertEquals(
                1100L,
                assets.getJSONArray("videos")
                        .getJSONObject(0)
                        .getLong("capture_time_ms"));
        assertEquals(
                "content://video/2",
                assets.getJSONArray("videos")
                        .getJSONObject(0)
                        .getString("content_uri"));
        assertBundleMetadata(
                assets.getJSONArray("photo_records")
                        .getJSONObject(0));
        assertBundleMetadata(
                assets.getJSONArray("videos")
                        .getJSONObject(0));
    }

    @Test
    public void legacyStructuredAssetDoesNotInventBundleMetadata()
            throws Exception {
        JoyfulMomentClusterer.EventRecord event =
                new JoyfulMomentClusterer.EventRecord();
        event.photoAssets.add(
                new JoyfulMomentClusterer.MediaAssetRecord(
                        "/session/legacy.jpg",
                        null,
                        1000L));

        JSONObject photo = event.toJson()
                .getJSONObject("assets")
                .getJSONArray("photo_records")
                .getJSONObject(0);

        assertFalse(photo.has("bundle_id"));
        assertFalse(photo.has("bundle_trigger_time_ms"));
        assertFalse(photo.has("bundle_media_index"));
    }

    private void assertBundleMetadata(JSONObject json)
            throws Exception {
        assertEquals("bundle-1", json.getString("bundle_id"));
        assertEquals(
                1000L,
                json.getLong("bundle_trigger_time_ms"));
        assertEquals(0, json.getInt("bundle_media_index"));
        assertEquals(12, json.getInt("automation_bucket_id"));
        assertEquals(
                3,
                json.getInt("automation_bucket_clip_count"));
        assertEquals(
                90,
                json.getInt("automation_bucket_duration_sec"));
    }
}
