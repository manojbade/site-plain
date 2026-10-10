package com.siteplain.data.loader;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.support.EpaUrls;
import com.siteplain.support.StateNames;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class HumanExposureLoader {

    private final ObjectMapper objectMapper;
    private final HumanExposureRepository humanExposureRepository;

    public HumanExposureLoader(ObjectMapper objectMapper,
                               HumanExposureRepository humanExposureRepository) {
        this.objectMapper = objectMapper;
        this.humanExposureRepository = humanExposureRepository;
    }

    /** Loads a downloaded file (see SourceDownloader) into staging, streaming one row at a time. */
    public int loadIntoStaging(Path file) throws IOException {
        humanExposureRepository.clearStaging();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
             JsonParser parser = objectMapper.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Human exposure JSON is not a JSON object");
            }
            boolean sawData = false;
            int rowCount = 0;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("data".equals(field) && value == JsonToken.START_ARRAY) {
                    sawData = true;
                    for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                        if (token == JsonToken.START_OBJECT && loadRow(parser.readValueAsTree())) {
                            rowCount++;
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (!sawData) {
                throw new IOException("Human exposure JSON missing data array");
            }
            return rowCount;
        }
    }

    private boolean loadRow(JsonNode row) {
        String epaId = uppercase(text(row, "epaid"));
        if (epaId == null) {
            return false;
        }
        // Every feed row is kept, including Final sites with no published boundary.
        humanExposureRepository.upsertStaging(
                epaId,
                uppercase(text(row, "humexposurestscode")),
                text(row, "humanexposurepathdesc"),
                uppercase(text(row, "nplstatus")),
                text(row, "sitename"),
                stateCode(uppercase(text(row, "state_code")), epaId),
                EpaUrls.normalize(text(row, "friendlyurl"))
        );
        return true;
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
