package com.siteplain.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExposureStatusMapperTest {

    @Test
    void knownCodes_mapToExpectedLabelsAndColors() {
        assertThat(ExposureStatusMapper.label("HENC")).isEqualTo("Human exposure is NOT currently under control");
        assertThat(ExposureStatusMapper.color("HENC")).isEqualTo("red");

        assertThat(ExposureStatusMapper.label("HEUC")).isEqualTo("Human exposure is under control");
        assertThat(ExposureStatusMapper.color("HEUC")).isEqualTo("yellow");

        assertThat(ExposureStatusMapper.label("HEPR")).isEqualTo("Human exposure is under control — protective remedies in place");
        assertThat(ExposureStatusMapper.color("HEPR")).isEqualTo("yellow");

        assertThat(ExposureStatusMapper.label("HHPA")).isEqualTo("Long-term health protection has been achieved");
        assertThat(ExposureStatusMapper.color("HHPA")).isEqualTo("green");

        assertThat(ExposureStatusMapper.label("HEID")).isEqualTo("Exposure status: insufficient data");
        assertThat(ExposureStatusMapper.color("HEID")).isEqualTo("gray");
    }

    @Test
    void nullInput_returnsNotReportedAndGray() {
        assertThat(ExposureStatusMapper.label(null)).isEqualTo("Exposure status not reported");
        assertThat(ExposureStatusMapper.color(null)).isEqualTo("gray");
    }

    @Test
    void unknownInput_returnsGrayColor() {
        assertThat(ExposureStatusMapper.color("UNKNOWN")).isEqualTo("gray");
    }
}
