package com.siteplain.data.loader;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.support.EpaUrls;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * Loads a downloaded file (see SourceDownloader) into staging, one feature at a time: the parser
     * streams the file and only the current feature is held in memory, so the 57.8 MB release does
     * not need the whole file plus a full Jackson tree on the heap.
     */
    public int loadIntoStaging(Path file) throws IOException {
        nplBoundaryRepository.clearStaging();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
             JsonParser parser = objectMapper.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("GeoJSON is not a JSON object");
            }
            boolean sawFeatures = false;
            int rowCount = 0;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("features".equals(field) && value == JsonToken.START_ARRAY) {
                    sawFeatures = true;
                    for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                        if (token == JsonToken.START_OBJECT && loadFeature(parser)) {
                            rowCount++;
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
            if (!sawFeatures) {
                throw new IOException("GeoJSON missing features array");
            }
            return rowCount;
        }
    }

    /** Reads one feature (the parser is on its START_OBJECT) and stages it; false when it was skipped. */
    private boolean loadFeature(JsonParser parser) throws IOException {
        JsonNode properties = null;
        String geometryJson = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("properties".equals(field) && value == JsonToken.START_OBJECT) {
                properties = parser.readValueAsTree();
            } else if ("geometry".equals(field) && value == JsonToken.START_OBJECT) {
                geometryJson = copyStructure(parser);
            } else {
                parser.skipChildren();
            }
        }
        String epaId = properties == null ? null : uppercase(text(properties, "EPA_ID"));
        if (epaId == null || geometryJson == null) {
            return false;
        }
        nplBoundaryRepository.upsertStaging(
                epaId,
                text(properties, "SITE_NAME"),
                uppercase(text(properties, "STATE_CODE")),
                uppercase(text(properties, "NPL_STATUS_CODE")),
                EpaUrls.normalize(text(properties, "URL_ALIAS_TXT")),
                geometryJson
        );
        return true;
    }

    /** Re-serializes the value the parser is on (a geometry can be 10 MB of text) without building a tree. */
    private String copyStructure(JsonParser parser) throws IOException {
        ByteArrayBuilder buffer = new ByteArrayBuilder();
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(buffer)) {
            generator.copyCurrentStructure(parser);
        }
        byte[] bytes = buffer.toByteArray();
        buffer.release();
        return new String(bytes, StandardCharsets.UTF_8);
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
