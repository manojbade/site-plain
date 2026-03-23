package com.siteplain.data.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.NplBoundaryRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class HumanExposureLoader {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final HumanExposureRepository humanExposureRepository;
    private final NplBoundaryRepository nplBoundaryRepository;

    public HumanExposureLoader(HttpClient httpClient,
                               ObjectMapper objectMapper,
                               HumanExposureRepository humanExposureRepository,
                               NplBoundaryRepository nplBoundaryRepository) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.humanExposureRepository = humanExposureRepository;
        this.nplBoundaryRepository = nplBoundaryRepository;
    }

    public int loadIntoStaging(String sourceUrl) throws IOException, InterruptedException {
        humanExposureRepository.clearStaging();
        Set<String> liveIds = new HashSet<>(nplBoundaryRepository.findAllLiveIds());
        JsonNode root = objectMapper.readTree(readBytes(sourceUrl));
        JsonNode rows = root.path("data");
        if (!rows.isArray()) {
            throw new IOException("Human exposure JSON missing data array");
        }
        int rowCount = 0;
        for (Iterator<JsonNode> iterator = rows.elements(); iterator.hasNext(); ) {
            JsonNode row = iterator.next();
            String epaId = uppercase(text(row, "epaid"));
            if (epaId == null || !liveIds.contains(epaId)) {
                continue;
            }
            humanExposureRepository.upsertStaging(
                    epaId,
                    uppercase(text(row, "humexposurestscode")),
                    text(row, "humanexposurepathdesc"),
                    uppercase(text(row, "nplstatus")),
                    text(row, "sitename")
            );
            rowCount++;
        }
        return rowCount;
    }

    private byte[] readBytes(String sourceUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUrl))
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
