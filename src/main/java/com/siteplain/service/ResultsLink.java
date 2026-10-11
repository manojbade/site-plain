package com.siteplain.service;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What a results page needs, carried encrypted in its link (ResultsTokens), so the address and
 * coordinates never appear in the URL. Field names are short to keep links short.
 *
 * @param label     the geocoder's address label (the page title); null for an unresolved lookup
 * @param raw       the address as the visitor typed it
 * @param lat       latitude, null for an unresolved lookup
 * @param lng       longitude, null for an unresolved lookup
 * @param state     two-letter state code from the geocoder
 * @param geocoder  census or nominatim
 * @param approx    what a coarse match matched (street, city, ZIP code), null for a house match
 * @param issuedAt  when POST /lookup made the link, in epoch seconds
 */
public record ResultsLink(
        @JsonProperty("a") String label,
        @JsonProperty("r") String raw,
        @JsonProperty("y") Double lat,
        @JsonProperty("x") Double lng,
        @JsonProperty("s") String state,
        @JsonProperty("g") String geocoder,
        @JsonProperty("m") String approx,
        @JsonProperty("t") long issuedAt
) {

    /** A lookup the geocoders could not place: the page says so and shows what was typed. */
    public boolean unresolved() {
        return lat == null || lng == null;
    }
}
