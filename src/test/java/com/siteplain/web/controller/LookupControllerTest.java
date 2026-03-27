package com.siteplain.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.siteplain.domain.model.NplSite;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LookupControllerTest {

    // -----------------------------------------------------------------------
    // buildFeatureCollection — GeoJSON output
    // -----------------------------------------------------------------------

    @Test
    void emptyGeomMap_returnsNull() {
        NplSite site = site("EPA-001", "Test Site", "red");
        assertThat(LookupController.buildFeatureCollection(List.of(site), Map.of())).isNull();
    }

    @Test
    void nullGeomMap_returnsNull() {
        NplSite site = site("EPA-001", "Test Site", "red");
        assertThat(LookupController.buildFeatureCollection(List.of(site), null)).isNull();
    }

    @Test
    void singleSite_producesValidFeatureCollection() {
        NplSite site = site("EPA-001", "Lipari Landfill", "red");
        Map<String, String> geomMap = Map.of("EPA-001", "{\"type\":\"Point\",\"coordinates\":[-74.0,40.0]}");

        String result = LookupController.buildFeatureCollection(List.of(site), geomMap);

        assertThat(result).startsWith("{\"type\":\"FeatureCollection\"");
        assertThat(result).contains("\"EPA-001\"");
        assertThat(result).contains("\"Lipari Landfill\"");
        assertThat(result).contains("\"color\":\"red\"");
        assertThat(result).contains("\"type\":\"Point\"");
    }

    @Test
    void multipleSites_allFeaturesPresent() {
        NplSite s1 = site("EPA-001", "Site One", "red");
        NplSite s2 = site("EPA-002", "Site Two", "green");
        Map<String, String> geomMap = Map.of(
                "EPA-001", "{\"type\":\"Point\",\"coordinates\":[-74.0,40.0]}",
                "EPA-002", "{\"type\":\"Point\",\"coordinates\":[-75.0,41.0]}"
        );

        String result = LookupController.buildFeatureCollection(List.of(s1, s2), geomMap);

        assertThat(result).contains("\"EPA-001\"");
        assertThat(result).contains("\"EPA-002\"");
        assertThat(result).contains("\"Site One\"");
        assertThat(result).contains("\"Site Two\"");
    }

    @Test
    void siteNotInGeomMap_isSkipped() {
        NplSite s1 = site("EPA-001", "Present", "red");
        NplSite s2 = site("EPA-999", "Missing Geometry", "gray");
        Map<String, String> geomMap = Map.of("EPA-001", "{\"type\":\"Point\",\"coordinates\":[-74.0,40.0]}");

        String result = LookupController.buildFeatureCollection(List.of(s1, s2), geomMap);

        assertThat(result).contains("\"EPA-001\"");
        assertThat(result).doesNotContain("EPA-999");
        assertThat(result).doesNotContain("Missing Geometry");
    }

    @Test
    void nullExposureColor_defaultsToGray() {
        NplSite site = site("EPA-001", "Unknown Status Site", null);
        Map<String, String> geomMap = Map.of("EPA-001", "{\"type\":\"Point\",\"coordinates\":[-74.0,40.0]}");

        String result = LookupController.buildFeatureCollection(List.of(site), geomMap);

        assertThat(result).contains("\"color\":\"gray\"");
    }

    @Test
    void siteNameWithDoubleQuotes_isEscaped() {
        NplSite site = site("EPA-001", "Site \"Alpha\" Plant", "yellow");
        Map<String, String> geomMap = Map.of("EPA-001", "{\"type\":\"Point\",\"coordinates\":[-74.0,40.0]}");

        String result = LookupController.buildFeatureCollection(List.of(site), geomMap);

        assertThat(result).contains("Site \\\"Alpha\\\" Plant");
    }

    @Test
    void siteNameWithBackslash_isEscaped() {
        NplSite site = site("EPA-001", "Site\\Name", "gray");
        Map<String, String> geomMap = Map.of("EPA-001", "{\"type\":\"Point\",\"coordinates\":[-74.0,40.0]}");

        String result = LookupController.buildFeatureCollection(List.of(site), geomMap);

        assertThat(result).contains("Site\\\\Name");
    }

    @Test
    void nullSiteName_treatedAsEmptyString() {
        NplSite site = site("EPA-001", null, "green");
        Map<String, String> geomMap = Map.of("EPA-001", "{\"type\":\"Point\",\"coordinates\":[-74.0,40.0]}");

        String result = LookupController.buildFeatureCollection(List.of(site), geomMap);

        assertThat(result).contains("\"siteName\":\"\"");
    }

    @Test
    void allSitesSkipped_stillReturnsEmptyFeatureCollection() {
        NplSite site = site("EPA-NONE", "No Geometry", "red");
        Map<String, String> geomMap = Map.of("EPA-OTHER", "{\"type\":\"Point\",\"coordinates\":[-74.0,40.0]}");

        String result = LookupController.buildFeatureCollection(List.of(site), geomMap);

        assertThat(result).isEqualTo("{\"type\":\"FeatureCollection\",\"features\":[]}");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static NplSite site(String epaId, String name, String color) {
        NplSite s = new NplSite();
        s.setEpaId(epaId);
        s.setSiteName(name);
        s.setExposureStatusColor(color);
        return s;
    }
}
