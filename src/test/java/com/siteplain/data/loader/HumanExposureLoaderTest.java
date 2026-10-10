package com.siteplain.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.HumanExposureRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HumanExposureLoaderTest {

    @TempDir
    Path dir;

    private final HumanExposureRepository repository = mock(HumanExposureRepository.class);
    private final HumanExposureLoader loader = new HumanExposureLoader(new ObjectMapper(), repository);

    @Test
    void stateCode_usesFeedStateWhenKnown() {
        assertThat(HumanExposureLoader.stateCode("WA", "WA2170023418")).isEqualTo("WA");
        assertThat(HumanExposureLoader.stateCode("AZ", "NNN000920223")).isEqualTo("AZ");
    }

    @Test
    void stateCode_fallsBackToEpaIdPrefixOnlyWhenItIsAState() {
        assertThat(HumanExposureLoader.stateCode(null, "OH7571724312")).isEqualTo("OH");
        assertThat(HumanExposureLoader.stateCode("XX", "OH7571724312")).isEqualTo("OH");
        assertThat(HumanExposureLoader.stateCode(null, "NNN000920223")).isNull();
        assertThat(HumanExposureLoader.stateCode(null, "W")).isNull();
    }

    @Test
    void loadIntoStaging_streamsRowsAndSkipsRowsWithoutEpaId() throws Exception {
        Path file = Files.writeString(dir.resolve("feed.json"), """
                {"success":true,"data":[
                  {"epaid":"wan001003081","nplstatus":"f","humexposurestscode":"heuc","humanexposurepathdesc":"",
                   "sitename":"GRAIN HANDLING FACILITY AT FREEMAN","state_code":"WA","friendlyurl":"www.epa.gov/superfund/freeman"},
                  {"epaid":"","nplstatus":"F","sitename":"NO ID"}
                 ],"meta":{"total_data":2}}
                """);

        ExposureFeedStats stats = loader.loadIntoStaging(file);

        assertThat(stats.staged()).isEqualTo(1);
        assertThat(stats.rows).isEqualTo(2);
        assertThat(stats.metaTotal).isEqualTo(2);
        assertThat(stats.success).isTrue();
        verify(repository).clearStaging();
        verify(repository).insertStaging("WAN001003081", "HEUC", null, "F", "GRAIN HANDLING FACILITY AT FREEMAN", "WA",
                "https://www.epa.gov/superfund/freeman");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void loadIntoStaging_recordsDuplicatesMissingFieldsAndUnknownCodes() throws Exception {
        Path file = Files.writeString(dir.resolve("feed.json"), """
                {"meta":{"total_data":7},"success":false,"data":[
                  {"epaid":"GAD000000001","nplstatus":"F","humexposurestscode":"HENC","sitename":"A","state_code":"GA"},
                  {"epaid":"gad000000001","nplstatus":"D","humexposurestscode":"HEUC","sitename":"A AGAIN","state_code":"GA"},
                  {"epaid":"GAD000000002","nplstatus":"F","humexposurestscode":"HEXX","sitename":"B","state_code":"GA"},
                  {"epaid":"GAD000000003","nplstatus":"F","humexposurestscode":"HEXX","sitename":"C","state_code":"GA"},
                  {"epaid":"NND000000004","humexposurestscode":"","sitename":"D","state_code":"ZZ"},
                  "not a row"
                ]}
                """);

        ExposureFeedStats stats = loader.loadIntoStaging(file);

        assertThat(stats.staged()).isEqualTo(4);
        assertThat(stats.unknownStatusCodes()).isEqualTo(Map.of("HEXX", 2));
        verify(repository, never()).insertStaging(eq("GAD000000001"), eq("HEUC"), any(), any(), any(), any(), any());
        assertThat(stats.problems()).containsExactly(
                "feed says success=false",
                "meta.total_data is 7 but the data array has 6 rows",
                "epaid missing or blank on 1 of 6 rows (16.67%, limit 0.50%)",
                "nplstatus missing or blank on 1 of 6 rows (16.67%, limit 0.50%)",
                "state (state_code or EPA ID prefix) missing or blank on 1 of 6 rows (16.67%, limit 0.50%)",
                "humexposurestscode missing or blank on 1 of 6 rows (16.67%, limit 2.00%)",
                "1 duplicate epaid values (GAD000000001)",
                "only 4 rows with an epaid, expected more than 1500");
    }

    @Test
    void loadIntoStaging_rejectsInvalidJson() throws Exception {
        Path file = Files.writeString(dir.resolve("feed.json"), "{\"success\":true,\"data\":[{\"epaid\":}]}");

        assertThatThrownBy(() -> loader.loadIntoStaging(file))
                .isInstanceOf(SourceRejectedException.class)
                .hasMessageStartingWith("Human exposure feed rejected: not valid JSON: ")
                .hasMessageContaining("line 1, column");
    }
}
