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
        boolean resolved
) {

    public static GeocodedAddress unresolved(String rawAddress) {
        return new GeocodedAddress(rawAddress, rawAddress, null, null, null, null, null, null, false);
    }
}
