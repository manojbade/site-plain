package com.siteplain.data.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.support.StateNames;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import org.springframework.stereotype.Component;

@Component
public class HumanExposureLoader {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final HumanExposureRepository humanExposureRepository;

    public HumanExposureLoader(HttpClient httpClient,
                               ObjectMapper objectMapper,
                               HumanExposureRepository humanExposureRepository) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.humanExposureRepository = humanExposureRepository;
    }

    public int loadIntoStaging(String sourceUrl) throws IOException, InterruptedException {
        humanExposureRepository.clearStaging();
        JsonNode root = objectMapper.readTree(readBytes(sourceUrl));
        JsonNode rows = root.path("data");
        if (!rows.isArray()) {
            throw new IOException("Human exposure JSON missing data array");
        }
        int rowCount = 0;
        for (Iterator<JsonNode> iterator = rows.elements(); iterator.hasNext(); ) {
            JsonNode row = iterator.next();
            String epaId = uppercase(text(row, "epaid"));
            if (epaId == null) {
                continue;
            }
            // Every feed row is kept, including Final sites with no published boundary.
            humanExposureRepository.upsertStaging(
                    epaId,
                    uppercase(text(row, "humexposurestscode")),
                    text(row, "humanexposurepathdesc"),
                    uppercase(text(row, "nplstatus")),
                    text(row, "sitename"),
                    stateCode(uppercase(text(row, "state_code")), epaId),
                    epaUrl(text(row, "friendlyurl"))
            );
            rowCount++;
        }
        return rowCount;
    }

    static String stateCode(String feedStateCode, String epaId) {
        if (StateNames.isKnown(feedStateCode)) {
            return feedStateCode;
        }
        String prefix = epaId != null && epaId.length() >= 2 ? epaId.substring(0, 2) : null;
        return StateNames.isKnown(prefix) ? prefix : null;
    }

    static String epaUrl(String friendlyUrl) {
        if (friendlyUrl == null) {
            return null;
        }
        if (friendlyUrl.regionMatches(true, 0, "http://", 0, 7) || friendlyUrl.regionMatches(true, 0, "https://", 0, 8)) {
            return friendlyUrl;
        }
        return friendlyUrl.regionMatches(true, 0, "www.epa.gov/", 0, 12) ? "https://" + friendlyUrl : null;
    }

    private byte[] readBytes(String sourceUrl) throws IOException, InterruptedException {
        URI uri = URI.create(sourceUrl);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return Files.readAllBytes(Path.of(uri));
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", "site-plain-loader/0.0.1")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IOException("Failed to download human exposure JSON: status=" + response.statusCode());
        }
        return response.body();
    }

    private String text(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            return null;
        }
        String value = child.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String uppercase(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }
}
