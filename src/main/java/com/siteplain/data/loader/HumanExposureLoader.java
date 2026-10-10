package com.siteplain.data.loader;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.support.EpaUrls;
import com.siteplain.support.ExposureStatusMapper;
import com.siteplain.support.StateNames;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class HumanExposureLoader {

    static final String SOURCE = "Human exposure feed";

    private final ObjectMapper objectMapper;
    private final HumanExposureRepository humanExposureRepository;

    public HumanExposureLoader(ObjectMapper objectMapper,
                               HumanExposureRepository humanExposureRepository) {
        this.objectMapper = objectMapper;
        this.humanExposureRepository = humanExposureRepository;
    }

    /**
     * Loads a downloaded file (see SourceDownloader) into staging, streaming one row at a time, and
     * returns what it saw; the caller decides from ExposureFeedStats.problems() whether to swap it in.
     */
    public ExposureFeedStats loadIntoStaging(Path file) throws IOException {
        humanExposureRepository.clearStaging();
        ExposureFeedStats stats = new ExposureFeedStats();
        Set<String> seenIds = new HashSet<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
             JsonParser parser = objectMapper.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new SourceRejectedException(SOURCE, List.of("file is not a JSON object"));
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("success".equals(field) && value.isBoolean()) {
                    stats.success = parser.getBooleanValue();
                } else if ("meta".equals(field) && value == JsonToken.START_OBJECT) {
                    JsonNode total = ((JsonNode) parser.readValueAsTree()).path("total_data");
                    stats.metaTotal = total.canConvertToInt() ? total.asInt() : null;
                } else if ("data".equals(field) && value == JsonToken.START_ARRAY) {
                    stats.dataArray = true;
                    for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                        stats.rows++;
                        if (token == JsonToken.START_OBJECT) {
                            loadRow(parser.readValueAsTree(), stats, seenIds);
                        } else {
                            parser.skipChildren();
                            stats.missing.get("epaid").add("row " + stats.rows);
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
        } catch (JsonProcessingException e) {
            JsonLocation location = e.getLocation();
            throw new SourceRejectedException(SOURCE, List.of("not valid JSON: " + e.getOriginalMessage()
                    + (location == null ? "" : " at line " + location.getLineNr() + ", column " + location.getColumnNr())));
        }
        return stats;
    }

    private void loadRow(JsonNode row, ExposureFeedStats stats, Set<String> seenIds) {
        String epaId = uppercase(text(row, "epaid"));
        String label = epaId != null ? epaId : "row " + stats.rows;
        String statusCode = uppercase(text(row, "humexposurestscode"));
        String nplStatus = uppercase(text(row, "nplstatus"));
        String siteName = text(row, "sitename");
        String stateCode = stateCode(uppercase(text(row, "state_code")), epaId);
        recordMissing(stats, "epaid", epaId, label);
        recordMissing(stats, "nplstatus", nplStatus, label);
        recordMissing(stats, "sitename", siteName, label);
        recordMissing(stats, ExposureFeedStats.STATE_FIELD, stateCode, label);
        recordMissing(stats, "humexposurestscode", statusCode, label);
        if (epaId == null) {
            return;
        }
        if (!seenIds.add(epaId)) {
            stats.duplicates.add(epaId);
            return;
        }
        if (statusCode != null && !ExposureStatusMapper.isKnownCode(statusCode)) {
            stats.unknownStatusCodes.merge(statusCode, 1, Integer::sum);
        }
        // Every feed row is kept, including Final sites with no published boundary.
        humanExposureRepository.insertStaging(
                epaId,
                statusCode,
                text(row, "humanexposurepathdesc"),
                nplStatus,
                siteName,
                stateCode,
                EpaUrls.normalize(text(row, "friendlyurl"))
        );
        stats.staged++;
    }

    private static void recordMissing(ExposureFeedStats stats, String field, String value, String label) {
        if (value == null) {
            stats.missing.get(field).add(label);
        }
    }

    static String stateCode(String feedStateCode, String epaId) {
        if (StateNames.isKnown(feedStateCode)) {
            return feedStateCode;
        }
        String prefix = epaId != null && epaId.length() >= 2 ? epaId.substring(0, 2) : null;
        return StateNames.isKnown(prefix) ? prefix : null;
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
