package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Stable timing and laughter ownership for one saved context-audio clip. */
final class AtlasContextAudioRecord {
    final int clipId;
    final double startSec;
    final double endSec;
    final String path;
    final List<Integer> linkedLaughterClipIds;

    AtlasContextAudioRecord(
            int clipId,
            double startSec,
            double endSec,
            String path,
            List<Integer> linkedLaughterClipIds) {
        if (clipId < 0) {
            throw new IllegalArgumentException("clip id invalid");
        }
        if (startSec < 0.0 || endSec < startSec) {
            throw new IllegalArgumentException("context timing invalid");
        }
        if (path == null || path.length() == 0) {
            throw new IllegalArgumentException("context path missing");
        }
        this.clipId = clipId;
        this.startSec = startSec;
        this.endSec = endSec;
        this.path = path;
        this.linkedLaughterClipIds = new ArrayList<>();
        if (linkedLaughterClipIds != null) {
            this.linkedLaughterClipIds.addAll(
                    linkedLaughterClipIds);
        }
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("clip_id", clipId);
        json.put("start_sec", startSec);
        json.put("end_sec", endSec);
        json.put("duration_sec", Math.max(0.0, endSec - startSec));
        json.put("path", path);
        json.put(
                "linked_laughter_clip_ids",
                new JSONArray(linkedLaughterClipIds));
        return json;
    }

    static boolean copyMatchingRecord(
            JSONArray records,
            int clipId,
            String path,
            JSONObject target) throws JSONException {
        if (records == null || target == null) {
            return false;
        }
        JSONObject match = null;
        if (path != null && path.length() > 0) {
            for (int i = 0; i < records.length(); i++) {
                JSONObject candidate = records.optJSONObject(i);
                if (candidate != null
                        && path.equals(candidate.optString("path", ""))) {
                    match = candidate;
                    break;
                }
            }
        }
        if (match == null && clipId >= 0) {
            for (int i = 0; i < records.length(); i++) {
                JSONObject candidate = records.optJSONObject(i);
                if (candidate != null
                        && candidate.optInt("clip_id", -1) == clipId) {
                    match = candidate;
                    break;
                }
            }
        }
        if (match == null) {
            return false;
        }
        target.put("clip_id", match.optInt("clip_id", clipId));
        copyNumber(match, target, "start_sec");
        copyNumber(match, target, "end_sec");
        copyNumber(match, target, "duration_sec");
        JSONArray linked =
                match.optJSONArray("linked_laughter_clip_ids");
        if (linked != null) {
            target.put(
                    "linked_laughter_clip_ids",
                    new JSONArray(linked.toString()));
        }
        return true;
    }

    private static void copyNumber(
            JSONObject source,
            JSONObject target,
            String key) throws JSONException {
        if (source.has(key) && !source.isNull(key)) {
            target.put(key, source.getDouble(key));
        }
    }
}
