package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AtlasContextAudioRecordTest {
    @Test
    public void serializesTimingPathAndLaughterLinks() throws Exception {
        AtlasContextAudioRecord record =
                new AtlasContextAudioRecord(
                        5,
                        150.0,
                        180.0,
                        "/clips/context.wav",
                        Arrays.asList(3, 4));

        JSONObject json = record.toJson();

        assertEquals(5, json.getInt("clip_id"));
        assertEquals(150.0, json.getDouble("start_sec"), 0.001);
        assertEquals(180.0, json.getDouble("end_sec"), 0.001);
        assertEquals(30.0, json.getDouble("duration_sec"), 0.001);
        assertEquals("/clips/context.wav", json.getString("path"));
        assertEquals(
                2,
                json.getJSONArray("linked_laughter_clip_ids").length());
    }

    @Test
    public void copiesMatchingRecordByPathThenClipId() throws Exception {
        JSONArray records = new JSONArray()
                .put(new AtlasContextAudioRecord(
                        5,
                        150.0,
                        180.0,
                        "/clips/context-a.wav",
                        Arrays.asList(3, 4)).toJson())
                .put(new AtlasContextAudioRecord(
                        6,
                        180.0,
                        210.0,
                        "/clips/context-b.wav",
                        Arrays.asList(7)).toJson());

        JSONObject byPath = new JSONObject();
        assertTrue(AtlasContextAudioRecord.copyMatchingRecord(
                records,
                -1,
                "/clips/context-a.wav",
                byPath));
        assertEquals(150.0, byPath.getDouble("start_sec"), 0.001);
        assertEquals(
                2,
                byPath.getJSONArray("linked_laughter_clip_ids").length());

        JSONObject byClipId = new JSONObject();
        assertTrue(AtlasContextAudioRecord.copyMatchingRecord(
                records,
                6,
                "/moved/context.wav",
                byClipId));
        assertEquals(180.0, byClipId.getDouble("start_sec"), 0.001);
    }

    @Test
    public void unmatchedTargetStaysUnchanged() throws Exception {
        JSONObject target = new JSONObject().put("type", "context");

        assertFalse(AtlasContextAudioRecord.copyMatchingRecord(
                new JSONArray(),
                8,
                "/clips/missing.wav",
                target));
        assertEquals(1, target.length());
    }
}
