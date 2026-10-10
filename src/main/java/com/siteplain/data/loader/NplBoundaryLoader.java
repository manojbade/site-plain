package com.siteplain.data.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.support.EpaUrls;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import org.springframework.stereotype.Component;

@Component
public class NplBoundaryLoader {

    private final ObjectMapper objectMapper;
    private final NplBoundaryRepository nplBoundaryRepository;

    public NplBoundaryLoader(ObjectMapper objectMapper,
                             NplBoundaryRepository nplBoundaryRepository) {
        this.objectMapper = objectMapper;
        this.nplBoundaryRepository = nplBoundaryRepository;
    }

    /** Loads a downloaded file (see SourceDownloader) into staging. */
    public int loadIntoStaging(Path file) throws IOException {
        nplBoundaryRepository.clearStaging();
        JsonNode root = objectMapper.readTree(file.toFile());
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
                    EpaUrls.normalize(text(properties, "URL_ALIAS_TXT")),
                    geometry.toString()
            );
            rowCount++;
        }
        return rowCount;
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
