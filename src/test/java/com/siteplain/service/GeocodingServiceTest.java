package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.config.GeocoderProperties;
import com.siteplain.domain.model.GeocodedAddress;
import org.junit.jupiter.api.Test;

class GeocodingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeocodingService service = new GeocodingService(null, objectMapper, new GeocoderProperties());

    @Test
    void nominatimHouseMatch_isExactAndTakesStateFromIsoCode() throws Exception {
        GeocodedAddress result = parse("""
                [{"lat":"42.4793","lon":"-71.1523","place_rank":30,"addresstype":"place",
                  "display_name":"57, Salem Street, Woburn, Middlesex County, Massachusetts, 01801, United States",
                  "address":{"house_number":"57","road":"Salem Street","city":"Woburn","county":"Middlesex County",
                             "state":"Massachusetts","ISO3166-2-lvl4":"US-MA","postcode":"01801-2003",
                             "country":"United States","country_code":"us"}}]
                """);

        assertThat(result.resolved()).isTrue();
        assertThat(result.stateCode()).isEqualTo("MA");
        assertThat(result.zipCode()).isEqualTo("01801");
        assertThat(result.city()).isEqualTo("Woburn");
        assertThat(result.approximate()).isFalse();
        assertThat(result.latitude()).isEqualTo(42.4793);
    }

    @Test
    void nominatimStreetOnlyMatch_isApproximateStreet() throws Exception {
        // Real shape of the jsonv2 reply for "99999 Kings Highway, NJ": no such house, so Nominatim
        // returns the road and there is no house_number in the address.
        GeocodedAddress result = parse("""
                [{"lat":"40.4078","lon":"-74.0921","place_rank":26,"addresstype":"road","category":"highway",
                  "type":"secondary","display_name":"Kings Highway, Hendrickson Corners, Middletown Township, Monmouth County, New Jersey, 07748, United States",
                  "address":{"road":"Kings Highway","hamlet":"Hendrickson Corners","town":"Middletown Township",
                             "county":"Monmouth County","state":"New Jersey","ISO3166-2-lvl4":"US-NJ",
                             "postcode":"07748","country":"United States","country_code":"us"}}]
                """);

        assertThat(result.approximate()).isTrue();
        assertThat(result.approximateMatch()).isEqualTo("street");
        assertThat(result.stateCode()).isEqualTo("NJ");
    }

    @Test
    void nominatimRank27_isApproximateStreet() throws Exception {
        assertThat(parse("""
                [{"lat":"39.8","lon":"-84.0","place_rank":27,"addresstype":"road","display_name":"x","address":{}}]
                """).approximateMatch()).isEqualTo("street");
        assertThat(parse("""
                [{"lat":"39.8","lon":"-84.0","place_rank":28,"addresstype":"building","display_name":"x","address":{}}]
                """).approximate()).isFalse();
    }

    @Test
    void nominatimCityMatch_isApproximate() throws Exception {
        GeocodedAddress result = parse("""
                [{"lat":"42.4793","lon":"-71.1523","place_rank":16,"addresstype":"city",
                  "display_name":"Woburn, Middlesex County, Massachusetts, United States",
                  "address":{"city":"Woburn","county":"Middlesex County","state":"Massachusetts",
                             "ISO3166-2-lvl4":"US-MA","postcode":"01801","country":"United States","country_code":"us"}}]
                """);

        assertThat(result.resolved()).isTrue();
        assertThat(result.stateCode()).isEqualTo("MA");
        assertThat(result.approximate()).isTrue();
        assertThat(result.approximateMatch()).isEqualTo("city");
    }

    @Test
    void nominatimPostcodeMatch_isApproximateZipCode() throws Exception {
        GeocodedAddress result = parse("""
                [{"lat":"33.7","lon":"-84.4","place_rank":21,"addresstype":"postcode","display_name":"30303, Atlanta, Georgia",
                  "address":{"postcode":"30303","state":"Georgia","ISO3166-2-lvl4":"US-GA"}}]
                """);

        assertThat(result.approximateMatch()).isEqualTo("ZIP code");
        assertThat(result.stateCode()).isEqualTo("GA");
    }

    @Test
    void nominatimState_fallsBackToStateNameAndIgnoresUnknownCodes() throws Exception {
        assertThat(parse("""
                [{"lat":"39.8","lon":"-84.0","place_rank":30,"display_name":"x","address":{"state":"Ohio"}}]
                """).stateCode()).isEqualTo("OH");
        assertThat(parse("""
                [{"lat":"18.4","lon":"-66.1","place_rank":30,"display_name":"x",
                  "address":{"state":"Puerto Rico","ISO3166-2-lvl4":"US-XX"}}]
                """).stateCode()).isEqualTo("PR");
        assertThat(parse("""
                [{"lat":"39.8","lon":"-84.0","place_rank":30,"display_name":"x","address":{"state":"Atlantis"}}]
                """).stateCode()).isNull();
    }

    @Test
    void nominatimWithoutRank_usesAddressType() throws Exception {
        assertThat(parse("""
                [{"lat":"39.8","lon":"-84.0","addresstype":"building","display_name":"x","address":{}}]
                """).approximate()).isFalse();
        assertThat(parse("""
                [{"lat":"39.8","lon":"-84.0","addresstype":"place","display_name":"x","address":{"house_number":"57"}}]
                """).approximate()).isFalse();
        assertThat(parse("""
                [{"lat":"39.8","lon":"-84.0","addresstype":"place","display_name":"x","address":{}}]
                """).approximateMatch()).isEqualTo("place");
        assertThat(parse("""
                [{"lat":"39.8","lon":"-84.0","addresstype":"road","display_name":"x","address":{}}]
                """).approximateMatch()).isEqualTo("street");
        assertThat(parse("""
                [{"lat":"39.8","lon":"-84.0","display_name":"x","address":{}}]
                """).approximateMatch()).isEqualTo("area");
    }

    @Test
    void nominatimEmptyResult_returnsNull() throws Exception {
        assertThat(parse("[]")).isNull();
    }

    private GeocodedAddress parse(String json) throws Exception {
        return service.parseNominatim(objectMapper.readTree(json), "raw input");
    }
}
