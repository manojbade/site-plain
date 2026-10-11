package com.siteplain.support;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The words that can describe a coarse geocoder match on the results page ("only to the following
 * city: ..."). One list for both ends: GeocodingService maps Nominatim's addresstype onto it, and
 * results links read back from a URL (the old approx= parameter) are held to it, so nothing else
 * from a URL reaches the page. Anything outside the list becomes the generic word "area".
 */
public final class ApproximateMatch {

    public static final String GENERIC = "area";

    /** Canonical spellings, as shown on the page. */
    public static final List<String> LABELS = List.of(
            "street", "ZIP code", "neighborhood", "suburb", "quarter", "city district", "district", "borough",
            "hamlet", "village", "town", "city", "municipality", "county", "region", "state", GENERIC);

    private static final Map<String, String> BY_LOWER_CASE = LABELS.stream()
            .collect(Collectors.toUnmodifiableMap(label -> label.toLowerCase(Locale.ROOT), Function.identity()));

    private ApproximateMatch() {
    }

    /** Null for no label (blank); a label from the list, matched without regard to case; otherwise "area". */
    public static String label(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return BY_LOWER_CASE.getOrDefault(value.trim().toLowerCase(Locale.ROOT), GENERIC);
    }

    /** The label for a Nominatim addresstype (road, postcode, neighbourhood, city_district, ...). */
    public static String forNominatimType(String addressType) {
        if (addressType == null || addressType.isBlank()) {
            return GENERIC;
        }
        String type = addressType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "road" -> "street";
            case "postcode" -> "ZIP code";
            case "neighbourhood" -> "neighborhood";
            default -> BY_LOWER_CASE.getOrDefault(type.replace('_', ' '), GENERIC);
        };
    }
}
