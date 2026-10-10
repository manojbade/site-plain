package com.siteplain.data.loader;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BoundaryFileStatsTest {

    /** The July 2026 release: 1,908 features, 1,328 Final, nothing missing. */
    private static BoundaryFileStats julyRelease() {
        BoundaryFileStats stats = new BoundaryFileStats();
        stats.collectionType = "FeatureCollection";
        stats.crsName = "urn:ogc:def:crs:OGC:1.3:CRS84";
        stats.featuresArray = true;
        stats.features = 1908;
        stats.staged = 1908;
        stats.finalCount = 1328;
        return stats;
    }

    @Test
    void acceptsTheCurrentRelease() {
        assertThat(julyRelease().problems(1908)).isEmpty();
        assertThat(julyRelease().problems(0)).isEmpty();
    }

    @Test
    void rejectsTheBroken2026_10_10EdgNullRates() {
        BoundaryFileStats stats = julyRelease();
        for (int i = 0; i < 11; i++) {
            stats.missing.get("NPL_STATUS_CODE").add("X" + i);
        }
        for (int i = 0; i < 84; i++) {
            stats.missing.get("STATE_CODE").add("Y" + i);
        }
        assertThat(stats.problems(1908)).containsExactly(
                "STATE_CODE missing or blank on 84 of 1908 features (4.40%, limit 0.50%)",
                "NPL_STATUS_CODE missing or blank on 11 of 1908 features (0.58%, limit 0.50%)");
    }

    @Test
    void allowsNullsUpToTheLimit() {
        BoundaryFileStats stats = julyRelease();
        for (int i = 0; i < 9; i++) {
            stats.missing.get("STATE_CODE").add("Y" + i);
        }
        assertThat(stats.problems(1908)).isEmpty();
    }

    @Test
    void rejectsAWrongCollectionTypeOrCrsOrMissingFeatures() {
        BoundaryFileStats stats = julyRelease();
        stats.collectionType = "Feature";
        stats.crsName = "urn:ogc:def:crs:EPSG::3857";
        assertThat(stats.problems(0)).containsExactly(
                "top-level type is \"Feature\", expected \"FeatureCollection\"",
                "crs is \"urn:ogc:def:crs:EPSG::3857\", expected CRS84 or EPSG:4326 longitude/latitude");

        BoundaryFileStats noFeatures = new BoundaryFileStats();
        noFeatures.collectionType = "FeatureCollection";
        assertThat(noFeatures.problems(1908)).containsExactly("no features array");

        BoundaryFileStats epsg4326 = julyRelease();
        epsg4326.crsName = "urn:ogc:def:crs:EPSG::4326";
        assertThat(epsg4326.problems(0)).isEmpty();
    }

    @Test
    void rejectsADropOfMoreThanTenPercentAgainstLive() {
        BoundaryFileStats stats = julyRelease();
        stats.staged = 1700;
        assertThat(stats.problems(1908)).containsExactly("only 1700 boundaries staged, fewer than 90% of the 1908 live rows");
        stats.staged = 1718;
        assertThat(stats.problems(1908)).isEmpty();
    }

    @Test
    void rejectsAFinalCountOutsideTheConverterBand() {
        BoundaryFileStats stats = julyRelease();
        stats.finalCount = 1199;
        assertThat(stats.problems(1908)).containsExactly("Final (NPL_STATUS_CODE F) count 1199 is outside the expected band 1200..1450");
        stats.finalCount = 1451;
        assertThat(stats.problems(1908)).hasSize(1);
    }

    @Test
    void listsAtMostFiveExamples() {
        BoundaryFileStats stats = julyRelease();
        for (int i = 1; i <= 7; i++) {
            stats.duplicates.add("ID" + i);
        }
        assertThat(stats.problems(1908)).containsExactly("7 duplicate EPA_ID values (first: ID1, ID2, ID3, ID4, ID5, ...)");
    }
}
