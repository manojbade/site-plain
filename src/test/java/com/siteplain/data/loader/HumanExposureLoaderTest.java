package com.siteplain.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.HumanExposureRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HumanExposureLoaderTest {

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
    void loadIntoStaging_streamsRowsAndSkipsRowsWithoutEpaId(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("feed.json");
        Files.writeString(file, """
                {"success":true,"data":[
                  {"epaid":"wan001003081","nplstatus":"f","humexposurestscode":"heuc","humanexposurepathdesc":"",
                   "sitename":"GRAIN HANDLING FACILITY AT FREEMAN","state_code":"WA","friendlyurl":"www.epa.gov/superfund/freeman"},
                  {"epaid":"","nplstatus":"F","sitename":"NO ID"}
                 ],"meta":{"total_data":2}}
                """);
        HumanExposureRepository repository = mock(HumanExposureRepository.class);

        assertThat(new HumanExposureLoader(new ObjectMapper(), repository).loadIntoStaging(file)).isEqualTo(1);

        verify(repository).clearStaging();
        verify(repository).upsertStaging("WAN001003081", "HEUC", null, "F", "GRAIN HANDLING FACILITY AT FREEMAN", "WA",
                "https://www.epa.gov/superfund/freeman");
        verifyNoMoreInteractions(repository);
    }
}
