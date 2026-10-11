package com.siteplain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.config.GeocoderProperties;
import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.support.ExpiringLruCache;
import com.siteplain.support.StateNames;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GeocodingService {

    // Nominatim place_rank 26 and 27 are streets, 28 to 30 are house numbers and buildings.
    private static final int NOMINATIM_STREET_RANK = 26;
    private static final int NOMINATIM_HOUSE_RANK = 28;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COMMA = Pattern.compile(" ?, ?");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GeocoderProperties geocoderProperties;
    /**
     * Normalized address to geocode result, so a repeat lookup or reload of the same address does not
     * call Census or Nominatim again (Nominatim's usage policy requires caching). In memory only, never
     * logged, bounded in size and age.
     */
    private final ExpiringLruCache<String, GeocodedAddress> cache;

    private final Bulkhead geocoding;
    private final NominatimGate nominatimGate;

    @Autowired
    public GeocodingService(HttpClient httpClient,
                            ObjectMapper objectMapper,
                            GeocoderProperties geocoderProperties,
                            WorkLimits workLimits) {
        this(httpClient, objectMapper, geocoderProperties, workLimits, Clock.systemUTC());
    }

    GeocodingService(HttpClient httpClient,
                     ObjectMapper objectMapper,
                     GeocoderProperties geocoderProperties,
                     WorkLimits workLimits,
                     Clock clock) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.geocoderProperties = geocoderProperties;
        this.cache = new ExpiringLruCache<>(geocoderProperties.getCacheMaxEntries(), clock);
        this.geocoding = workLimits.geocoding();
        this.nominatimGate = workLimits.nominatim();
    }

    /** What one geocoder said: a match, a definite "no match", or no usable answer (network, status, body). */
    private enum Outcome { MATCH, NO_MATCH, FAILED }

    private record Attempt(Outcome outcome, GeocodedAddress result) {
        static final Attempt NO_MATCH = new Attempt(Outcome.NO_MATCH, null);
        static final Attempt FAILED = new Attempt(Outcome.FAILED, null);

        static Attempt of(GeocodedAddress result) {
            return result == null ? NO_MATCH : new Attempt(Outcome.MATCH, result);
        }
    }

    /**
     * Census first, then Nominatim. A cached answer makes no outbound call. Outbound calls are capped
     * globally (WorkLimits): past the cap, or when Nominatim's one-per-second slot is too far off,
     * this throws ServiceBusyException rather than reporting the address as not found.
     */
    public GeocodedAddress geocode(String rawAddress) {
        String address = stripCountrySuffix(rawAddress);
        String key = cacheKey(address);
        GeocodedAddress cached = key == null ? null : cache.get(key);
        if (cached != null) {
            return cached.resolved() ? cached : GeocodedAddress.unresolved(rawAddress);
        }
        return geocoding.call(() -> geocodeUncached(rawAddress, address, key));
    }

    private GeocodedAddress geocodeUncached(String rawAddress, String address, String key) {
        Attempt census = geocodeWithCensus(address);
        if (census.outcome() == Outcome.MATCH) {
            return remember(key, census.result(), geocoderProperties.getCacheTtl());
        }
        Attempt nominatim = geocodeWithNominatim(address);
        if (nominatim.outcome() == Outcome.MATCH) {
            return remember(key, nominatim.result(), geocoderProperties.getCacheTtl());
        }
        GeocodedAddress unresolved = GeocodedAddress.unresolved(rawAddress);
        // Only a definite "no match" from both is remembered, and briefly; a failed call is retried next time.
        if (census.outcome() == Outcome.NO_MATCH && nominatim.outcome() == Outcome.NO_MATCH) {
            remember(key, unresolved, geocoderProperties.getCacheUnresolvedTtl());
        }
        return unresolved;
    }

    private GeocodedAddress remember(String key, GeocodedAddress result, Duration ttl) {
        if (key != null && !ttl.isZero() && !ttl.isNegative()) {
            cache.put(key, result, ttl);
        }
        return result;
    }

    /**
     * Cache key for an address: case, runs of whitespace, and spacing around commas do not change what
     * the geocoders return, so "57  Salem St ,Woburn" and "57 salem st, woburn" share an entry.
     */
    static String cacheKey(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String spaced = WHITESPACE.matcher(address.trim()).replaceAll(" ");
        return COMMA.matcher(spaced).replaceAll(", ").trim().toLowerCase(Locale.ROOT);
    }

    private String stripCountrySuffix(String address) {
        if (address == null) return null;
        String trimmed = address.trim();
        for (String suffix : new String[]{", United States", ", United States of America", ", USA", ", US"}) {
            if (trimmed.regionMatches(true, trimmed.length() - suffix.length(), suffix, 0, suffix.length())) {
                return trimmed.substring(0, trimmed.length() - suffix.length()).trim();
            }
        }
        return trimmed;
    }

    private Attempt geocodeWithCensus(String rawAddress) {
        try {
            String url = geocoderProperties.getCensusBaseUrl()
                    + "?benchmark=" + URLEncoder.encode(geocoderProperties.getCensusBenchmark(), StandardCharsets.UTF_8)
                    + "&format=json&address=" + URLEncoder.encode(rawAddress, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(geocoderProperties.getCensusTimeout())
                    .header("User-Agent", geocoderProperties.getUserAgent())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Attempt.FAILED;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode matches = root.path("result").path("addressMatches");
            if (!matches.isArray()) {
                return Attempt.FAILED;
            }
            if (matches.isEmpty()) {
                return Attempt.NO_MATCH;
            }
            JsonNode best = matches.get(0);
            JsonNode components = best.path("addressComponents");
            return Attempt.of(new GeocodedAddress(
                    rawAddress,
                    best.path("matchedAddress").asText(rawAddress),
                    text(components, "city"),
                    uppercase(text(components, "state")),
                    text(components, "zip"),
                    "census",
                    number(best.path("coordinates"), "y"),
                    number(best.path("coordinates"), "x"),
                    true
            ));
        } catch (IOException | InterruptedException | IllegalArgumentException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Attempt.FAILED;
        }
    }

    private Attempt geocodeWithNominatim(String rawAddress) {
        if (!nominatimGate.awaitTurn()) {
            throw new ServiceBusyException(ServiceBusyException.SERVICE_UNAVAILABLE, nominatimGate.secondsUntilFree(),
                    "Nominatim one-per-second slot too far off");
        }
        try {
            String url = geocoderProperties.getNominatimBaseUrl()
                    + "?format=jsonv2&limit=" + geocoderProperties.getNominatimLimit()
                    + "&countrycodes=us&addressdetails=1&q=" + URLEncoder.encode(rawAddress, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(geocoderProperties.getNominatimTimeout())
                    .header("User-Agent", geocoderProperties.getUserAgent())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Attempt.FAILED;
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray()) {
                return Attempt.FAILED;
            }
            return Attempt.of(parseNominatim(root, rawAddress));
        } catch (IOException | InterruptedException | IllegalArgumentException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Attempt.FAILED;
        }
    }

    GeocodedAddress parseNominatim(JsonNode root, String rawAddress) {
        if (!root.isArray() || root.isEmpty()) {
            return null;
        }
        JsonNode best = root.get(0);
        JsonNode address = best.path("address");
        return new GeocodedAddress(
                rawAddress,
                best.path("display_name").asText(rawAddress),
                text(address, "city", "town", "village"),
                nominatimStateCode(address),
                normalizeZip(text(address, "postcode")),
                "nominatim",
                decimal(best, "lat"),
                decimal(best, "lon"),
                true,
                nominatimApproximateMatch(best)
        );
    }

    /**
     * jsonv2 with addressdetails gives the state as an ISO 3166-2 code (ISO3166-2-lvl4, for
     * example US-OH) and as a name (state). There is no state_code field.
     */
    private String nominatimStateCode(JsonNode address) {
        String iso = text(address, "ISO3166-2-lvl4");
        if (iso != null && iso.toUpperCase(Locale.ROOT).startsWith("US-")) {
            String code = iso.substring(3).toUpperCase(Locale.ROOT);
            if (StateNames.isKnown(code)) {
                return code;
            }
        }
        return StateNames.codeForName(text(address, "state"));
    }

    /**
     * Null for a house or building level match. Otherwise a short description of what was matched
     * (street, city, ZIP code, county, ...), so the results page can say the location is approximate.
     * A street match is approximate: the point is somewhere on the road, which can be miles from
     * the typed house number (Nominatim returns the road when the number does not exist).
     */
    private String nominatimApproximateMatch(JsonNode best) {
        JsonNode rank = best.path("place_rank");
        String type = text(best, "addresstype");
        String lowerType = type == null ? null : type.toLowerCase(Locale.ROOT);
        if (rank.isNumber()) {
            if (rank.asInt() >= NOMINATIM_HOUSE_RANK) {
                return null;
            }
            if (rank.asInt() >= NOMINATIM_STREET_RANK) {
                return "street";
            }
        } else if (lowerType != null && (List.of("house", "building").contains(lowerType)
                || ("place".equals(lowerType) && text(best.path("address"), "house_number") != null))) {
            return null;
        }
        if (lowerType == null) {
            return "area";
        }
        return switch (lowerType) {
            case "road" -> "street";
            case "postcode" -> "ZIP code";
            case "neighbourhood" -> "neighborhood";
            default -> lowerType.replace('_', ' ');
        };
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode child = node.path(field);
            if (!child.isMissingNode() && !child.isNull()) {
                String value = child.asText();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private Double number(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            return null;
        }
        return child.asDouble();
    }

    private Double decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeZip(String zip) {
        if (zip == null || zip.isBlank()) {
            return null;
        }
        String trimmed = zip.trim();
        return trimmed.length() > 5 ? trimmed.substring(0, 5) : trimmed;
    }

    private String uppercase(String value) {
        return value == null ? null : value.toUpperCase();
    }
}
