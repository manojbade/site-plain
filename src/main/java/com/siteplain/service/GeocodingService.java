package com.siteplain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.config.GeocoderProperties;
import com.siteplain.domain.model.GeocodedAddress;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

@Service
public class GeocodingService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GeocoderProperties geocoderProperties;

    public GeocodingService(HttpClient httpClient,
                            ObjectMapper objectMapper,
                            GeocoderProperties geocoderProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.geocoderProperties = geocoderProperties;
    }

    public GeocodedAddress geocode(String rawAddress) {
        GeocodedAddress census = geocodeWithCensus(rawAddress);
        if (census != null) {
            return census;
        }
        GeocodedAddress nominatim = geocodeWithNominatim(rawAddress);
        if (nominatim != null) {
            return nominatim;
        }
        return GeocodedAddress.unresolved(rawAddress);
    }

    private GeocodedAddress geocodeWithCensus(String rawAddress) {
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
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode matches = root.path("result").path("addressMatches");
            if (!matches.isArray() || matches.isEmpty()) {
                return null;
            }
            JsonNode best = matches.get(0);
            JsonNode components = best.path("addressComponents");
            return new GeocodedAddress(
                    rawAddress,
                    best.path("matchedAddress").asText(rawAddress),
                    text(components, "city"),
                    uppercase(text(components, "state")),
                    text(components, "zip"),
                    "census",
                    number(best.path("coordinates"), "y"),
                    number(best.path("coordinates"), "x"),
                    true
            );
        } catch (IOException | InterruptedException ex) {
            return null;
        }
    }

    private GeocodedAddress geocodeWithNominatim(String rawAddress) {
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
            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray() || root.isEmpty()) {
                return null;
            }
            JsonNode best = root.get(0);
            JsonNode address = best.path("address");
            return new GeocodedAddress(
                    rawAddress,
                    best.path("display_name").asText(rawAddress),
                    text(address, "city", "town", "village"),
                    uppercase(text(address, "state_code")),
                    normalizeZip(text(address, "postcode")),
                    "nominatim",
                    decimal(best, "lat"),
                    decimal(best, "lon"),
                    true
            );
        } catch (IOException | InterruptedException ex) {
            return null;
        }
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
