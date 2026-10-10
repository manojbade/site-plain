package com.siteplain.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.NplBoundaryRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class NplBoundaryLoaderTest {

    @Test
    void loadIntoStaging_storesAbsoluteEpaUrls(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("boundaries.geojson");
        Files.writeString(file, """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"EPA_ID":"COD980499248","SITE_NAME":"LOWRY LANDFILL",
                    "STATE_CODE":"CO","NPL_STATUS_CODE":"F","URL_ALIAS_TXT":"www.epa.gov/superfund/lowry-landfill"},
                   "geometry":{"type":"MultiPolygon","coordinates":[[[[-104.7,39.6],[-104.6,39.6],[-104.6,39.7],[-104.7,39.6]]]]}},
                  {"type":"Feature","properties":{"EPA_ID":"XXD000000001","SITE_NAME":"BAD URL SITE",
                    "STATE_CODE":"CO","NPL_STATUS_CODE":"F","URL_ALIAS_TXT":"https://semspub.epa.src/x"},
                   "geometry":{"type":"MultiPolygon","coordinates":[[[[-104.7,39.6],[-104.6,39.6],[-104.6,39.7],[-104.7,39.6]]]]}}
                ]}
                """);
        NplBoundaryRepository repository = mock(NplBoundaryRepository.class);
        NplBoundaryLoader loader = new NplBoundaryLoader(new ObjectMapper(), repository);

        assertThat(loader.loadIntoStaging(file)).isEqualTo(2);

        verify(repository).upsertStaging(eq("COD980499248"), eq("LOWRY LANDFILL"), eq("CO"), eq("F"),
                eq("https://www.epa.gov/superfund/lowry-landfill"), anyString());
        verify(repository).upsertStaging(eq("XXD000000001"), eq("BAD URL SITE"), eq("CO"), eq("F"),
                isNull(), anyString());
    }

    @Test
    void loadIntoStaging_streamsFeaturesWhateverTheKeyOrder(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("boundaries.geojson");
        Files.writeString(file, """
                {"name":"npl_site_boundaries","crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:OGC:1.3:CRS84"}},
                 "features":[
                  {"geometry":{"coordinates":[[[[-104.7,39.6],[-104.6,39.6],[-104.6,39.7],[-104.7,39.6]]]],"type":"MultiPolygon"},
                   "type":"Feature","properties":{"EPA_ID":"cod980499248","SITE_NAME":"LOWRY LANDFILL","STATE_CODE":"co",
                   "NPL_STATUS_CODE":"f","URL_ALIAS_TXT":null}},
                  null,
                  {"type":"Feature","properties":{"EPA_ID":"COD000000002","SITE_NAME":"NO GEOMETRY"},"geometry":null}
                 ],
                 "type":"FeatureCollection"}
                """);
        NplBoundaryRepository repository = mock(NplBoundaryRepository.class);
        NplBoundaryLoader loader = new NplBoundaryLoader(new ObjectMapper(), repository);

        assertThat(loader.loadIntoStaging(file)).isEqualTo(1);

        ArgumentCaptor<String> geometry = ArgumentCaptor.forClass(String.class);
        verify(repository).upsertStaging(eq("COD980499248"), eq("LOWRY LANDFILL"), eq("CO"), eq("F"), isNull(),
                geometry.capture());
        assertThat(new ObjectMapper().readTree(geometry.getValue())).isEqualTo(new ObjectMapper().readTree(
                "{\"coordinates\":[[[[-104.7,39.6],[-104.6,39.6],[-104.6,39.7],[-104.7,39.6]]]],\"type\":\"MultiPolygon\"}"));
    }
}
