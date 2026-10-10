package com.siteplain.domain.model;

public record GeocodedAddress(
        String rawAddress,
        String normalizedAddress,
        String city,
        String stateCode,
        String zipCode,
        String geocoderUsed,
        Double latitude,
        Double longitude,
        boolean resolved,
        String approximateMatch
) {

    public GeocodedAddress(String rawAddress, String normalizedAddress, String city, String stateCode, String zipCode,
                           String geocoderUsed, Double latitude, Double longitude, boolean resolved) {
        this(rawAddress, normalizedAddress, city, stateCode, zipCode, geocoderUsed, latitude, longitude, resolved, null);
    }

    public static GeocodedAddress unresolved(String rawAddress) {
        return new GeocodedAddress(rawAddress, rawAddress, null, null, null, null, null, null, false);
    }

    /** True when the geocoder matched only a coarse place (a city or ZIP code, say), not a street or house. */
    public boolean approximate() {
        return approximateMatch != null;
    }
}
