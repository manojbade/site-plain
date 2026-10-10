package com.siteplain.data.loader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the boundary loader saw in one GeoJSON file, and the checks that decide whether it may
 * replace the live boundaries. The null-rate and Final-count limits mirror the converter's gates
 * (scripts/convert_npl_to_geojson.py --max-null-rate, --min-final, --max-final): the broken
 * 2026-10-10 EDG file had 0.6% null NPL_STATUS_CODE and 4.4% null STATE_CODE after dedup.
 */
public class BoundaryFileStats {

    static final double MAX_NULL_RATE = 0.005;
    static final int MIN_FINAL = 1200;
    static final int MAX_FINAL = 1450;
    static final double MIN_LIVE_RATIO = 0.9;
    static final List<String> REQUIRED_PROPERTIES = List.of("EPA_ID", "SITE_NAME", "STATE_CODE", "NPL_STATUS_CODE");

    String collectionType;
    String crsName;
    boolean featuresArray;
    int features;
    int staged;
    int finalCount;
    final Map<String, Tally> missing = new LinkedHashMap<>();
    final Tally duplicates = new Tally();
    final Tally finalWithoutState = new Tally();
    final Tally badGeometry = new Tally();
    final Tally outOfRange = new Tally();

    BoundaryFileStats() {
        REQUIRED_PROPERTIES.forEach(property -> missing.put(property, new Tally()));
    }

    public int staged() {
        return staged;
    }

    public int finalCount() {
        return finalCount;
    }

    /** Every failed check, each saying what failed; empty when the file may be swapped in. */
    public List<String> problems(int liveCount) {
        List<String> problems = new ArrayList<>();
        if (!"FeatureCollection".equals(collectionType)) {
            problems.add("top-level type is " + quoted(collectionType) + ", expected \"FeatureCollection\"");
        }
        if (crsName != null && !isWgs84(crsName)) {
            problems.add("crs is " + quoted(crsName) + ", expected CRS84 or EPSG:4326 longitude/latitude");
        }
        if (!featuresArray) {
            problems.add("no features array");
            return problems;
        }
        if (features == 0) {
            problems.add("features array is empty");
            return problems;
        }
        missing.forEach((property, tally) -> {
            double rate = (double) tally.count() / features;
            if (rate > MAX_NULL_RATE) {
                problems.add(String.format(Locale.ROOT, "%s missing or blank on %d of %d features (%.2f%%, limit %.2f%%)",
                        property, tally.count(), features, rate * 100, MAX_NULL_RATE * 100));
            }
        });
        if (duplicates.count() > 0) {
            problems.add(duplicates.count() + " duplicate EPA_ID values" + duplicates.examples());
        }
        if (badGeometry.count() > 0) {
            problems.add(badGeometry.count() + " features without a valid Polygon or MultiPolygon geometry"
                    + badGeometry.examples());
        }
        if (outOfRange.count() > 0) {
            problems.add(outOfRange.count() + " features with coordinates outside WGS84 longitude -180..180, "
                    + "latitude -90..90" + outOfRange.examples());
        }
        if (finalCount < MIN_FINAL || finalCount > MAX_FINAL) {
            problems.add("Final (NPL_STATUS_CODE F) count " + finalCount + " is outside the expected band "
                    + MIN_FINAL + ".." + MAX_FINAL);
        }
        if (finalWithoutState.count() > 0) {
            problems.add(finalWithoutState.count() + " Final features have no STATE_CODE" + finalWithoutState.examples());
        }
        if (liveCount > 0 && staged < liveCount * MIN_LIVE_RATIO) {
            problems.add("only " + staged + " boundaries staged, fewer than "
                    + Math.round(MIN_LIVE_RATIO * 100) + "% of the " + liveCount + " live rows");
        }
        return problems;
    }

    private static boolean isWgs84(String crs) {
        String upper = crs.toUpperCase(Locale.ROOT);
        return upper.endsWith("CRS84") || upper.endsWith(":4326");
    }

    private static String quoted(String value) {
        return value == null ? "missing" : "\"" + value + "\"";
    }
}
