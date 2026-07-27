package com.hry.camera.usbcamerademo;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class AtlasLocationClustererTest {
    private AtlasReviewRepository.EventSummary event(
            String id, double lat, double lng, String action) throws Exception {
        AtlasReviewRepository.EventSummary summary = new AtlasReviewRepository.EventSummary();
        summary.eventId = id;
        summary.lat = lat;
        summary.lng = lng;
        summary.eventJson = new JSONObject().put(
                "save_decision", new JSONObject().put("action", action));
        return summary;
    }

    @Test
    public void nearbyEventsFormOneStableCluster() throws Exception {
        AtlasLocationClusterer clusterer = new AtlasLocationClusterer();
        AtlasReviewRepository.EventSummary a = event("a", 39.90420, 116.40740, "save_push");
        AtlasReviewRepository.EventSummary b = event("b", 39.90435, 116.40740, "save_push");
        List<AtlasLocationClusterer.LocationCluster> forward =
                clusterer.cluster(Arrays.asList(a, b));
        List<AtlasLocationClusterer.LocationCluster> reverse =
                clusterer.cluster(Arrays.asList(b, a));
        assertEquals(1, forward.size());
        assertEquals(forward.get(0).clusterKey, reverse.get(0).clusterKey);
        assertEquals(2, forward.get(0).events.size());
    }

    @Test
    public void eventsOutsideRadiusRemainSeparate() throws Exception {
        AtlasLocationClusterer clusterer = new AtlasLocationClusterer();
        List<AtlasLocationClusterer.LocationCluster> clusters = clusterer.cluster(Arrays.asList(
                event("a", 39.90420, 116.40740, "save_push"),
                event("b", 39.90520, 116.40740, "save_push")));
        assertEquals(2, clusters.size());
        assertNotEquals(clusters.get(0).requestCode, clusters.get(1).requestCode);
    }

    @Test
    public void ignoresNoPushAndMissingGps() throws Exception {
        AtlasReviewRepository.EventSummary missing = event("missing", 0, 0, "save_push");
        missing.lat = null;
        missing.lng = null;
        List<AtlasLocationClusterer.LocationCluster> clusters =
                new AtlasLocationClusterer().cluster(Arrays.asList(
                        missing,
                        event("private", 39.90420, 116.40740, "save_no_push"),
                        event("eligible", 39.90420, 116.40740, "save_push")));
        assertEquals(1, clusters.size());
        assertEquals("eligible", clusters.get(0).events.get(0).eventId);
    }
}
