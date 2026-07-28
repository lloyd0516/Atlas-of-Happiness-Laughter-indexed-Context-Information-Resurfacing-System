package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JoyfulMomentMediaAssetTest {
    @Test
    public void eventJsonKeepsLegacyPathsAndAddsStructuredCaptureTimes()
            throws Exception {
        JoyfulMomentClusterer.EventRecord event =
                new JoyfulMomentClusterer.EventRecord();
        event.photoPaths.add("/session/event_photo_1000.jpg");
        event.videoPaths.add("/session/event_video_2000.mp4");
        event.videoContentUris.add("content://video/2");
        event.photoAssets.add(new JoyfulMomentClusterer.MediaAssetRecord(
                "/session/event_photo_1000.jpg", null, 1000L));
        event.videoAssets.add(new JoyfulMomentClusterer.MediaAssetRecord(
                "/session/event_video_2000.mp4", "content://video/2", 2000L));

        JSONObject assets = event.toJson().getJSONObject("assets");

        assertEquals(
                "/session/event_photo_1000.jpg",
                assets.getJSONArray("photos").getString(0));
        assertEquals(
                1000L,
                assets.getJSONArray("photo_records")
                        .getJSONObject(0)
                        .getLong("capture_time_ms"));
        assertEquals(
                2000L,
                assets.getJSONArray("videos")
                        .getJSONObject(0)
                        .getLong("capture_time_ms"));
        assertEquals(
                "content://video/2",
                assets.getJSONArray("videos")
                        .getJSONObject(0)
                        .getString("content_uri"));
    }
}
