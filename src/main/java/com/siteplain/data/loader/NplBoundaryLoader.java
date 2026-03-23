package com.siteplain.data.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.NplBoundaryRepository;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import org.springframework.stereotype.Component;

@Component
public class NplBoundaryLoader {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final NplBoundaryRepository nplBoundaryRepository;

    public NplBoundaryLoader(HttpClient httpClient,
                             ObjectMapper objectMapper,
                             NplBoundaryRepository nplBoundaryRepository) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.nplBoundaryRepository = nplBoundaryRepository;
    }

    public int loadIntoStaging(String sourceUrl) throws IOException, InterruptedException {
        nplBoundaryRepository.clearStaging();
        JsonNode root = objectMapper.readTree(readBytes(sourceUrl));
        JsonNode features = root.path("features");
        if (!features.isArray()) {
            throw new IOException("GeoJSON missing features array");
        }
        int rowCount = 0;
        for (Iterator<JsonNode> iterator = features.elements(); iterator.hasNext(); ) {
            JsonNode feature = iterator.next();
            JsonNode properties = feature.path("properties");
            JsonNode geometry = feature.path("geometry");
            String epaId = uppercase(text(properties, "EPA_ID"));
            if (epaId == null || geometry.isMissingNode() || geometry.isNull()) {
                continue;
            }
            nplBoundaryRepository.upsertStaging(
                    epaId,
                    text(properties, "SITE_NAME"),
                    uppercase(text(properties, "STATE_CODE")),
                    uppercase(text(properties, "NPL_STATUS_CODE")),
                    text(properties, "URL_ALIAS_TXT"),
                    geometry.toString()
            );
            rowCount++;
        }
        return rowCount;
    }

    private byte[] readBytes(String sourceUrl) throws IOException, InterruptedException {
        URI uri = URI.create(sourceUrl);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return Files.readAllBytes(Path.of(uri));
        }
        if (uri.getScheme() == null || uri.getScheme().isBlank()) {
            return Files.readAllBytes(Path.of(sourceUrl));
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", "site-plain-loader/0.0.1")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IOException("Failed to download boundaries: status=" + response.statusCode()
                    + " url=" + URLEncoder.encode(sourceUrl, StandardCharsets.UTF_8));
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
