package com.siteplain.support;

import java.util.List;

/**
 * Rough boxes around the US states, DC and the territories that have NPL sites, with some margin.
 * Lookups outside them cannot be near any site, so they are rejected instead of answered with a
 * confident "no sites found".
 */
public final class UsCoverageArea {

    private record Box(double minLat, double maxLat, double minLng, double maxLng) {
        boolean contains(double lat, double lng) {
            return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
        }
    }

    private static final List<Box> BOXES = List.of(
            new Box(23.0, 50.0, -126.0, -65.0),    // contiguous states and DC
            new Box(51.0, 72.0, -180.0, -129.0),   // Alaska
            new Box(51.0, 54.0, 172.0, 180.0),     // Aleutians west of 180
            new Box(18.0, 29.0, -179.0, -154.0),   // Hawaii, including the northwestern islands
            new Box(17.5, 18.8, -68.0, -64.4),     // Puerto Rico and the U.S. Virgin Islands
            new Box(13.0, 21.0, 144.0, 146.5),     // Guam and the Northern Mariana Islands
            new Box(-15.0, -11.0, -171.5, -168.0), // American Samoa
            new Box(0.5, 10.5, 137.0, 163.5)       // Federated States of Micronesia
    );

    private UsCoverageArea() {
    }

    public static boolean contains(Double lat, Double lng) {
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)) {
            return false;
        }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            return false;
        }
        return BOXES.stream().anyMatch(box -> box.contains(lat, lng));
    }
}
