package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class AtlasCaptureBundleMetadataTest {
    @Test
    public void copiesCompleteBundleMetadata() throws Exception {
        JSONObject source = new JSONObject()
                .put("bundle_id", "bundle-2")
                .put("bundle_trigger_time_ms", 9000L)
                .put("bundle_media_index", 1);
        JSONObject target = new JSONObject();

        AtlasCaptureBundleMetadata.copyIfPresent(
                source,
                target);

        assertEquals(
                "bundle-2",
                target.getString("bundle_id"));
        assertEquals(
                9000L,
                target.getLong("bundle_trigger_time_ms"));
        assertEquals(
                1,
                target.getInt("bundle_media_index"));
    }

    @Test
    public void oldRecordStaysWithoutInventedBundleIdentity()
            throws Exception {
        JSONObject target = new JSONObject();

        AtlasCaptureBundleMetadata.copyIfPresent(
                new JSONObject().put("capture_time_ms", 9000L),
                target);

        assertFalse(target.has("bundle_id"));
        assertFalse(target.has("bundle_trigger_time_ms"));
        assertFalse(target.has("bundle_media_index"));
    }
}
