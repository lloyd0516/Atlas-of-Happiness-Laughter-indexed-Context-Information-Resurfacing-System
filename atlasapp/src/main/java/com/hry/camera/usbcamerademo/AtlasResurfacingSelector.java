package com.hry.camera.usbcamerademo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Eligibility and deterministic two-stage ranking for resurfacing candidates. */
final class AtlasResurfacingSelector {
    boolean isPushEligible(AtlasReviewRepository.EventSummary summary) {
        if (summary == null || summary.eventJson == null) {
            return false;
        }
        JSONObject decision = summary.eventJson.optJSONObject("save_decision");
        return decision != null && "save_push".equals(decision.optString("action", ""));
    }

    boolean hasUserSupplement(AtlasReviewRepository.EventSummary summary) {
        JSONObject event = summary == null ? null : summary.eventJson;
        JSONObject user = event == null ? null : event.optJSONObject("user_generated");
        if (user == null) {
            return false;
        }
        if (length(user.optJSONArray("notes")) > 0
                || length(user.optJSONArray("audio_notes")) > 0
                || length(user.optJSONArray("photos")) > 0) {
            return true;
        }
        JSONObject social = user.optJSONObject("social_context");
        return social != null
                && (hasText(social, "with_whom")
                || hasText(social, "doing_what")
                || hasText(social, "mood"));
    }

    int countMedia(AtlasReviewRepository.EventSummary summary) {
        JSONObject event = summary == null ? null : summary.eventJson;
        if (event == null) {
            return 0;
        }
        int total = 0;
        JSONObject auto = event.optJSONObject("auto_captured");
        if (auto != null) {
            total += length(auto.optJSONArray("audio_clips"));
            total += length(auto.optJSONArray("photos"));
            total += length(auto.optJSONArray("videos"));
        }
        JSONObject user = event.optJSONObject("user_generated");
        if (user != null) {
            total += length(user.optJSONArray("audio_notes"));
            total += length(user.optJSONArray("photos"));
        }
        return total;
    }

    AtlasReviewRepository.EventSummary selectForCalendarDay(
            List<AtlasReviewRepository.EventSummary> events,
            long dayStartMs,
            long dayEndMs,
            long preferredTimeMs) {
        AtlasReviewRepository.EventSummary selected = null;
        if (events == null) {
            return null;
        }
        for (AtlasReviewRepository.EventSummary candidate : events) {
            if (!isPushEligible(candidate)
                    || candidate.startTimeMs < dayStartMs
                    || candidate.startTimeMs >= dayEndMs) {
                continue;
            }
            if (selected == null || compare(candidate, selected, preferredTimeMs) < 0) {
                selected = candidate;
            }
        }
        return selected;
    }

    private int compare(
            AtlasReviewRepository.EventSummary left,
            AtlasReviewRepository.EventSummary right,
            long preferredTimeMs) {
        boolean leftSupplement = hasUserSupplement(left);
        boolean rightSupplement = hasUserSupplement(right);
        if (leftSupplement != rightSupplement) {
            return leftSupplement ? -1 : 1;
        }

        int leftMedia = countMedia(left);
        int rightMedia = countMedia(right);
        if (leftMedia != rightMedia) {
            return leftMedia > rightMedia ? -1 : 1;
        }

        long leftDistance = absoluteDistance(left.startTimeMs, preferredTimeMs);
        long rightDistance = absoluteDistance(right.startTimeMs, preferredTimeMs);
        if (leftDistance != rightDistance) {
            return leftDistance < rightDistance ? -1 : 1;
        }

        String leftId = left.eventId == null ? "" : left.eventId;
        String rightId = right.eventId == null ? "" : right.eventId;
        return leftId.compareTo(rightId);
    }

    private long absoluteDistance(long value, long target) {
        if (value >= target) {
            return value - target;
        }
        return target - value;
    }

    private int length(JSONArray array) {
        return array == null ? 0 : array.length();
    }

    private boolean hasText(JSONObject object, String key) {
        String value = object.optString(key, "");
        return value != null && value.trim().length() > 0;
    }
}
