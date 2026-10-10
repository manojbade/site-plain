package com.siteplain.data.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.support.EpaUrls;
import com.siteplain.support.StateNames;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
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

    /** Loads a downloaded file (see SourceDownloader) into staging. */
    public int loadIntoStaging(Path file) throws IOException {
        humanExposureRepository.clearStaging();
        JsonNode root = objectMapper.readTree(file.toFile());
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
                    EpaUrls.normalize(text(row, "friendlyurl"))
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
