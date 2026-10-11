package com.siteplain.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApproximateMatchTest {

    @Test
    void label_keepsListedWordsInTheirCanonicalSpelling() {
        assertThat(ApproximateMatch.label("ZIP CODE")).isEqualTo("ZIP code");
        assertThat(ApproximateMatch.label("City District")).isEqualTo("city district");
        assertThat(ApproximateMatch.label("area")).isEqualTo("area");
    }

    @Test
    void label_turnsAnythingElseIntoTheGenericWord() {
        assertThat(ApproximateMatch.label(null)).isNull();
        assertThat(ApproximateMatch.label("   ")).isNull();
        assertThat(ApproximateMatch.label("no contamination here")).isEqualTo("area");
        assertThat(ApproximateMatch.label("<b>city</b>")).isEqualTo("area");
    }

    @Test
    void forNominatimType_alwaysLandsOnTheList() {
        for (String type : new String[] {"road", "postcode", "neighbourhood", "suburb", "city_district", "city", "town",
                "village", "hamlet", "county", "state", "place", "amenity", "isolated_dwelling", "", null}) {
            assertThat(ApproximateMatch.LABELS).as(String.valueOf(type)).contains(ApproximateMatch.forNominatimType(type));
        }
        assertThat(ApproximateMatch.forNominatimType("road")).isEqualTo("street");
        assertThat(ApproximateMatch.forNominatimType("place")).isEqualTo("area");
    }
}
