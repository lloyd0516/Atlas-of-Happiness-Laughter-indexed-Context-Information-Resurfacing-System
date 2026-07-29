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
                .put("bundle_media_index", 1)
                .put("automation_bucket_id", 12)
                .put("automation_bucket_clip_count", 3)
                .put("automation_bucket_duration_sec", 90);
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
        assertEquals(
                12,
                target.getInt("automation_bucket_id"));
        assertEquals(
                3,
                target.getInt("automation_bucket_clip_count"));
        assertEquals(
                90,
                target.getInt("automation_bucket_duration_sec"));
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

    @Test
    public void legacyBundleMetadataStillCopiesWithoutBucketFields()
            throws Exception {
        JSONObject source = new JSONObject()
                .put("bundle_id", "legacy-bundle")
                .put("bundle_trigger_time_ms", 9000L)
                .put("bundle_media_index", 0);
        JSONObject target = new JSONObject();

        AtlasCaptureBundleMetadata.copyIfPresent(source, target);

        assertEquals("legacy-bundle", target.getString("bundle_id"));
        assertFalse(target.has("automation_bucket_id"));
        assertFalse(target.has("automation_bucket_clip_count"));
        assertFalse(target.has("automation_bucket_duration_sec"));
    }

    @Test
    public void incompleteBucketMetadataCopiesNoneOfBucketGroup()
            throws Exception {
        JSONObject source = new JSONObject()
                .put("bundle_id", "bundle-2")
                .put("bundle_trigger_time_ms", 9000L)
                .put("bundle_media_index", 1)
                .put("automation_bucket_id", 12)
                .put("automation_bucket_clip_count", 3);
        JSONObject target = new JSONObject();

        AtlasCaptureBundleMetadata.copyIfPresent(source, target);

        assertEquals("bundle-2", target.getString("bundle_id"));
        assertFalse(target.has("automation_bucket_id"));
        assertFalse(target.has("automation_bucket_clip_count"));
        assertFalse(target.has("automation_bucket_duration_sec"));
    }
}
