package com.siteplain.data.loader;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
