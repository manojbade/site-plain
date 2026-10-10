package com.siteplain.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.NplBoundaryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class NplBoundaryLoaderTest {

    private static final String SQUARE = "[[[-104.7,39.6],[-104.6,39.6],[-104.6,39.7],[-104.7,39.6]]]";

    @TempDir
    Path dir;

    private final NplBoundaryRepository repository = mock(NplBoundaryRepository.class);
    private final NplBoundaryLoader loader = new NplBoundaryLoader(new ObjectMapper(), repository);

    @Test
    void loadIntoStaging_storesAbsoluteEpaUrls() throws Exception {
        Path file = write("""
                {"type":"FeatureCollection","features":[
                  %s,
                  %s
                ]}
                """.formatted(
                feature("COD980499248", "LOWRY LANDFILL", "CO", "F", "\"www.epa.gov/superfund/lowry-landfill\"", multi()),
                feature("XXD000000001", "BAD URL SITE", "CO", "F", "\"https://semspub.epa.src/x\"", multi())));

        BoundaryFileStats stats = loader.loadIntoStaging(file);

        assertThat(stats.staged()).isEqualTo(2);
        assertThat(stats.finalCount()).isEqualTo(2);
        verify(repository).insertStaging(eq("COD980499248"), eq("LOWRY LANDFILL"), eq("CO"), eq("F"),
                eq("https://www.epa.gov/superfund/lowry-landfill"), anyString());
        verify(repository).insertStaging(eq("XXD000000001"), eq("BAD URL SITE"), eq("CO"), eq("F"),
                isNull(), anyString());
    }

    @Test
    void loadIntoStaging_streamsFeaturesWhateverTheKeyOrder_andCopiesNumbersAsWritten() throws Exception {
        Path file = write("""
                {"name":"npl_site_boundaries","crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:OGC:1.3:CRS84"}},
                 "features":[
                  {"geometry":{"coordinates":[[[[-93.337219436999987,45.3125],[-93.3,45.3],[-93.3,45.4],[-93.337219436999987,45.3125]]]],
                   "type":"MultiPolygon"},
                   "type":"Feature","properties":{"EPA_ID":"cod980499248","SITE_NAME":"LOWRY LANDFILL","STATE_CODE":"co",
                   "NPL_STATUS_CODE":"f","URL_ALIAS_TXT":null}}
                 ],
                 "type":"FeatureCollection"}
                """);

        BoundaryFileStats stats = loader.loadIntoStaging(file);

        assertThat(stats.collectionType).isEqualTo("FeatureCollection");
        assertThat(stats.crsName).isEqualTo("urn:ogc:def:crs:OGC:1.3:CRS84");
        ArgumentCaptor<String> geometry = ArgumentCaptor.forClass(String.class);
        verify(repository).insertStaging(eq("COD980499248"), eq("LOWRY LANDFILL"), eq("CO"), eq("F"), isNull(),
                geometry.capture());
        assertThat(geometry.getValue()).isEqualTo("{\"coordinates\":[[[[-93.337219436999987,45.3125],[-93.3,45.3],"
                + "[-93.3,45.4],[-93.337219436999987,45.3125]]]],\"type\":\"MultiPolygon\"}");
    }

    @Test
    void loadIntoStaging_acceptsPolygonsAndRecordsEveryGeometryProblem() throws Exception {
        Path file = write("""
                {"type":"FeatureCollection","features":[
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  null
                ]}
                """.formatted(
                feature("COD000000001", "POLYGON", "CO", "F", "null", "{\"type\":\"Polygon\",\"coordinates\":" + SQUARE + "}"),
                feature("COD000000002", "POINT", "CO", "F", "null", "{\"type\":\"Point\",\"coordinates\":[-104.7,39.6]}"),
                feature("COD000000003", "NO GEOMETRY", "CO", "F", "null", "null"),
                feature("COD000000004", "FLAT", "CO", "F", "null", "{\"type\":\"MultiPolygon\",\"coordinates\":" + SQUARE + "}"),
                feature("COD000000005", "PROJECTED", "CO", "F", "null",
                        "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-11655000.5,4810000.1],[-11654000,4810000],[-11655000.5,4810000.1]]]]}"),
                feature("COD000000006", "TEXT", "CO", "F", "null",
                        "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-104.7,\"39.6\"]]]]}")));

        BoundaryFileStats stats = loader.loadIntoStaging(file);

        assertThat(stats.features).isEqualTo(7);
        assertThat(stats.staged()).isEqualTo(1);
        verify(repository).insertStaging(eq("COD000000001"), anyString(), anyString(), anyString(), isNull(), anyString());
        assertThat(String.join("; ", stats.problems(0)))
                .contains("5 features without a valid Polygon or MultiPolygon geometry (COD000000002: type Point, "
                        + "COD000000003: no geometry, COD000000004: MultiPolygon positions nested 3 deep, expected 4, "
                        + "COD000000006: a position holds a non-numeric value, feature 7: not an object)")
                .contains("1 features with coordinates outside WGS84 longitude -180..180, latitude -90..90 "
                        + "(COD000000005: -1.16550005E7, 4810000.1)");
    }

    @Test
    void loadIntoStaging_recordsDuplicatesMissingPropertiesAndFinalWithoutState() throws Exception {
        Path file = write("""
                {"type":"FeatureCollection","features":[%s,%s,%s,%s]}
                """.formatted(
                feature("COD000000001", "ONE", "CO", "F", "null", multi()),
                feature("cod000000001", "ONE AGAIN", "CO", "D", "null", multi()),
                feature("COD000000002", null, null, "F", "null", multi()),
                feature(null, "NO ID", "CO", null, "null", multi())));

        BoundaryFileStats stats = loader.loadIntoStaging(file);

        assertThat(stats.staged()).isEqualTo(2);
        assertThat(stats.finalCount()).isEqualTo(2);
        // A missing SITE_NAME falls back to the EPA ID (site_name is NOT NULL).
        verify(repository).insertStaging(eq("COD000000002"), eq("COD000000002"), isNull(), eq("F"), isNull(), anyString());
        verify(repository, never()).insertStaging(eq("COD000000001"), eq("ONE AGAIN"), anyString(), anyString(), isNull(), anyString());
        assertThat(String.join("; ", stats.problems(0)))
                .contains("EPA_ID missing or blank on 1 of 4 features (25.00%, limit 0.50%)")
                .contains("SITE_NAME missing or blank on 1 of 4 features")
                .contains("STATE_CODE missing or blank on 1 of 4 features")
                .contains("NPL_STATUS_CODE missing or blank on 1 of 4 features")
                .contains("1 duplicate EPA_ID values (COD000000001)")
                .contains("1 Final features have no STATE_CODE (COD000000002)")
                .contains("Final (NPL_STATUS_CODE F) count 2 is outside the expected band 1200..1450");
    }

    @Test
    void loadIntoStaging_rejectsInvalidJsonWithItsLocation() throws Exception {
        Path file = write("{\"type\":\"FeatureCollection\",\"features\":[\n{\"type\":\"Feature\",,}]}");

        assertThatThrownBy(() -> loader.loadIntoStaging(file))
                .isInstanceOf(SourceRejectedException.class)
                .hasMessageStartingWith("NPL boundary file rejected: not valid JSON: ")
                .hasMessageContaining("at line 2, column");
    }

    private Path write(String content) throws Exception {
        return Files.writeString(dir.resolve("boundaries.geojson"), content);
    }

    private static String multi() {
        return "{\"type\":\"MultiPolygon\",\"coordinates\":[" + SQUARE + "]}";
    }

    private static String feature(String epaId, String name, String state, String status, String url, String geometry) {
        return """
                {"type":"Feature","properties":{"EPA_ID":%s,"SITE_NAME":%s,"STATE_CODE":%s,"NPL_STATUS_CODE":%s,
                 "URL_ALIAS_TXT":%s},"geometry":%s}""".formatted(json(epaId), json(name), json(state), json(status), url, geometry);
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
