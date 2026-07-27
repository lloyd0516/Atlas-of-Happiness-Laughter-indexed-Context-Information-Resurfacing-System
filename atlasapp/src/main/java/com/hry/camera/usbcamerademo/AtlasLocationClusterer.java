package com.hry.camera.usbcamerademo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Groups eligible historical GPS points into stable same-place proximity alerts. */
final class AtlasLocationClusterer {
    private static final int REQUEST_CODE_PREFIX = 0x43000000;

    static final class LocationCluster {
        String clusterKey;
        double lat;
        double lng;
        int requestCode;
        final List<AtlasReviewRepository.EventSummary> events = new ArrayList<>();
    }

    List<LocationCluster> cluster(List<AtlasReviewRepository.EventSummary> source) {
        List<AtlasReviewRepository.EventSummary> eligible = new ArrayList<>();
        AtlasResurfacingSelector selector = new AtlasResurfacingSelector();
        if (source != null) {
            for (AtlasReviewRepository.EventSummary event : source) {
                if (selector.isPushEligible(event)
                        && event.lat != null && event.lng != null
                        && isCoordinate(event.lat, event.lng)) {
                    eligible.add(event);
                }
            }
        }
        Collections.sort(eligible, new Comparator<AtlasReviewRepository.EventSummary>() {
            @Override
            public int compare(
                    AtlasReviewRepository.EventSummary left,
                    AtlasReviewRepository.EventSummary right) {
                String leftKey = stableEventKey(left);
                String rightKey = stableEventKey(right);
                return leftKey.compareTo(rightKey);
            }
        });

        List<LocationCluster> result = new ArrayList<>();
        for (AtlasReviewRepository.EventSummary event : eligible) {
            LocationCluster match = null;
            for (LocationCluster cluster : result) {
                if (canJoinWithoutExceedingRadius(cluster, event)) {
                    match = cluster;
                    break;
                }
            }
            if (match == null) {
                match = new LocationCluster();
                match.lat = event.lat;
                match.lng = event.lng;
                result.add(match);
            }
            match.events.add(event);
            recomputeCenter(match);
        }

        Set<Integer> usedCodes = new HashSet<>();
        for (LocationCluster cluster : result) {
            cluster.clusterKey = "place:" + stableEventKey(cluster.events.get(0));
            int requestCode = REQUEST_CODE_PREFIX
                    | (cluster.clusterKey.hashCode() & 0x00ffffff);
            while (usedCodes.contains(requestCode)) {
                requestCode++;
            }
            cluster.requestCode = requestCode;
            usedCodes.add(requestCode);
        }
        return result;
    }

    static double distanceMeters(
            double lat1, double lng1, double lat2, double lng2) {
        double earthRadiusMeters = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2.0) * Math.sin(dLng / 2.0);
        return earthRadiusMeters * 2.0
                * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private static boolean isCoordinate(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng)
                && lat >= -90.0 && lat <= 90.0
                && lng >= -180.0 && lng <= 180.0;
    }

    private static void recomputeCenter(LocationCluster cluster) {
        double lat = 0.0;
        double lng = 0.0;
        for (AtlasReviewRepository.EventSummary event : cluster.events) {
            lat += event.lat;
            lng += event.lng;
        }
        cluster.lat = lat / cluster.events.size();
        cluster.lng = lng / cluster.events.size();
    }

    private static boolean canJoinWithoutExceedingRadius(
            LocationCluster cluster,
            AtlasReviewRepository.EventSummary candidate) {
        double lat = candidate.lat;
        double lng = candidate.lng;
        for (AtlasReviewRepository.EventSummary event : cluster.events) {
            lat += event.lat;
            lng += event.lng;
        }
        double prospectiveLat = lat / (cluster.events.size() + 1);
        double prospectiveLng = lng / (cluster.events.size() + 1);
        if (distanceMeters(
                prospectiveLat, prospectiveLng, candidate.lat, candidate.lng)
                > AppConfig.SPECIAL_LOCATION_RADIUS_METERS) {
            return false;
        }
        for (AtlasReviewRepository.EventSummary event : cluster.events) {
            if (distanceMeters(
                    prospectiveLat, prospectiveLng, event.lat, event.lng)
                    > AppConfig.SPECIAL_LOCATION_RADIUS_METERS) {
                return false;
            }
        }
        return true;
    }

    private static String stableEventKey(AtlasReviewRepository.EventSummary event) {
        String session = event.sessionId == null ? "" : event.sessionId;
        String id = event.eventId == null ? "" : event.eventId;
        return session + "/" + id;
    }
}
