package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AtlasLaughterCountPolicyTest {
    @Test
    public void typedLaughterAudioTakesPriorityOverPeriods()
            throws JSONException {
        JSONObject event = eventWithAudioTypes(
                "laughter",
                "laughter",
                "possible_related_speech_context");
        event.put(
                "period_ids",
                new JSONArray()
                        .put("period_1")
                        .put("period_2")
                        .put("period_3"));

        assertEquals(2, AtlasLaughterCountPolicy.count(event));
    }

    @Test
    public void legacyEventFallsBackToPeriodIds()
            throws JSONException {
        JSONObject event = new JSONObject()
                .put("auto_captured", new JSONObject())
                .put(
                        "period_ids",
                        new JSONArray()
                                .put("period_1")
                                .put("period_2"));

        assertEquals(2, AtlasLaughterCountPolicy.count(event));
    }

    @Test
    public void emptyEventStaysZero() {
        assertEquals(
                0,
                AtlasLaughterCountPolicy.count(new JSONObject()));
    }

    private JSONObject eventWithAudioTypes(String... types)
            throws JSONException {
        JSONArray audioClips = new JSONArray();
        for (String type : types) {
            audioClips.put(new JSONObject().put("type", type));
        }
        return new JSONObject().put(
                "auto_captured",
                new JSONObject().put("audio_clips", audioClips));
    }
}
