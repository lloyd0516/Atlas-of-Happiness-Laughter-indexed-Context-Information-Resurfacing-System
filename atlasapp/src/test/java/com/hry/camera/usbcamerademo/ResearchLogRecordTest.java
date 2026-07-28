package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResearchLogRecordTest {
    @Test
    public void recordContainsRequiredEnvelopeWithoutContentFields() throws Exception {
        JSONObject record = ResearchLogRecord.build(
                "screen_opened",
                "row-1",
                1000L,
                "2026-07-28T19:30:00.000+08:00",
                "Asia/Shanghai",
                2000L,
                "01",
                "session-1",
                "moment-1",
                null,
                "2.0-main",
                20,
                "OPPO",
                new JSONObject().put("screen", "review"));

        assertEquals(1, record.getInt("schema_version"));
        assertEquals("screen_opened", record.getString("event_name"));
        assertEquals("01", record.getString("participant_id"));
        assertTrue(record.isNull("notification_instance_id"));
        assertFalse(record.toString().contains("with_whom"));
        assertFalse(record.toString().contains("latitude"));
        assertFalse(record.toString().contains("/storage/"));
    }
}
