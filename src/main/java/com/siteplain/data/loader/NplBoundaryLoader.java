package com.siteplain.data.loader;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.support.EpaUrls;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class NplBoundaryLoader {

    static final String SOURCE = "NPL boundary file";

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
     * not need the whole file plus a full Jackson tree on the heap. Returns what it saw; the caller
     * decides from BoundaryFileStats.problems() whether staging may be swapped in.
     */
    public BoundaryFileStats loadIntoStaging(Path file) throws IOException {
        nplBoundaryRepository.clearStaging();
        BoundaryFileStats stats = new BoundaryFileStats();
        Set<String> seenIds = new HashSet<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
             JsonParser parser = objectMapper.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new SourceRejectedException(SOURCE, List.of("file is not a JSON object"));
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field) && value == JsonToken.VALUE_STRING) {
                    stats.collectionType = parser.getText();
                } else if ("crs".equals(field) && value == JsonToken.START_OBJECT) {
                    JsonNode crs = parser.readValueAsTree();
                    stats.crsName = text(crs.path("properties"), "name");
                } else if ("features".equals(field) && value == JsonToken.START_ARRAY) {
                    stats.featuresArray = true;
                    for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                        stats.features++;
                        if (token == JsonToken.START_OBJECT) {
                            loadFeature(parser, stats, seenIds);
                        } else {
                            parser.skipChildren();
                            stats.badGeometry.add("feature " + stats.features + ": not an object");
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

    /** Reads one feature (the parser is on its START_OBJECT), records what it found and stages it if usable. */
    private void loadFeature(JsonParser parser, BoundaryFileStats stats, Set<String> seenIds) throws IOException {
        JsonNode properties = null;
        String geometryJson = null;
        GeometryCheck geometry = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("properties".equals(field) && value == JsonToken.START_OBJECT) {
                properties = parser.readValueAsTree();
            } else if ("geometry".equals(field) && value == JsonToken.START_OBJECT) {
                ByteArrayBuilder buffer = new ByteArrayBuilder();
                try (JsonGenerator generator = objectMapper.getFactory().createGenerator(buffer)) {
                    geometry = copyGeometry(parser, generator);
                }
                // A geometry can be 10 MB of text; it is re-serialized from the parser, never held as a tree.
                geometryJson = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                buffer.release();
            } else {
                parser.skipChildren();
            }
        }
        JsonNode props = properties == null ? MissingNode.getInstance() : properties;
        String epaId = uppercase(text(props, "EPA_ID"));
        String siteName = text(props, "SITE_NAME");
        String stateCode = uppercase(text(props, "STATE_CODE"));
        String status = uppercase(text(props, "NPL_STATUS_CODE"));
        String label = epaId != null ? epaId : "feature " + stats.features;
        Map<String, String> required = Map.of("EPA_ID", nullToEmpty(epaId), "SITE_NAME", nullToEmpty(siteName),
                "STATE_CODE", nullToEmpty(stateCode), "NPL_STATUS_CODE", nullToEmpty(status));
        required.forEach((property, value) -> {
            if (value.isEmpty()) {
                stats.missing.get(property).add(label);
            }
        });
        if (epaId == null) {
            return;
        }
        if (!seenIds.add(epaId)) {
            stats.duplicates.add(epaId);
            return;
        }
        if ("F".equals(status)) {
            stats.finalCount++;
            if (stateCode == null) {
                stats.finalWithoutState.add(epaId);
            }
        }
        String geometryProblem = geometry == null ? "no geometry" : geometry.problem();
        if (geometryProblem != null) {
            stats.badGeometry.add(epaId + ": " + geometryProblem);
            return;
        }
        if (geometry.outOfRange != null) {
            stats.outOfRange.add(epaId + ": " + geometry.outOfRange);
            return;
        }
        try {
            nplBoundaryRepository.insertStaging(
                    epaId,
                    // site_name is NOT NULL; a missing name (under the null-rate limit) shows the EPA ID.
                    siteName == null ? epaId : siteName,
                    stateCode,
                    status,
                    EpaUrls.normalize(text(props, "URL_ALIAS_TXT")),
                    geometryJson
            );
        } catch (DataAccessException e) {
            throw new SourceRejectedException(SOURCE, List.of("could not stage " + epaId + ": "
                    + e.getMostSpecificCause().getMessage()));
        }
        stats.staged++;
    }

    /** Copies a geometry object to the generator while checking its type, nesting and coordinate ranges. */
    private static GeometryCheck copyGeometry(JsonParser parser, JsonGenerator generator) throws IOException {
        GeometryCheck check = new GeometryCheck();
        generator.writeStartObject();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            generator.writeFieldName(field);
            if ("type".equals(field) && value == JsonToken.VALUE_STRING) {
                check.type = parser.getText();
                generator.writeString(check.type);
            } else if ("coordinates".equals(field) && value == JsonToken.START_ARRAY) {
                check.coordinates = true;
                copyCoordinates(parser, generator, 1, check);
            } else {
                generator.copyCurrentStructure(parser);
            }
        }
        generator.writeEndObject();
        return check;
    }

    /** The parser is on a START_ARRAY at the given nesting depth inside "coordinates". */
    private static void copyCoordinates(JsonParser parser, JsonGenerator generator, int depth, GeometryCheck check)
            throws IOException {
        generator.writeStartArray();
        JsonToken token = parser.nextToken();
        if (token.isNumeric()) {
            // A position: longitude, latitude, then any extra ordinates. Numbers are copied as written.
            int index = 0;
            double longitude = Double.NaN;
            for (; token != JsonToken.END_ARRAY; token = parser.nextToken(), index++) {
                if (!token.isNumeric()) {
                    check.structure("a position holds a non-numeric value");
                    generator.copyCurrentStructure(parser);
                    continue;
                }
                double value = parser.getDoubleValue();
                if (index == 0) {
                    longitude = value;
                } else if (index == 1 && check.outOfRange == null
                        && !(inRange(longitude, 180) && inRange(value, 90))) {
                    check.outOfRange = longitude + ", " + value;
                }
                generator.writeNumber(parser.getText());
            }
            if (index < 2) {
                check.structure("a position has fewer than 2 numbers");
            }
            check.position(depth);
        } else {
            for (; token != JsonToken.END_ARRAY; token = parser.nextToken()) {
                if (token == JsonToken.START_ARRAY) {
                    copyCoordinates(parser, generator, depth + 1, check);
                } else {
                    check.structure("coordinates hold a non-array value");
                    generator.copyCurrentStructure(parser);
                }
            }
        }
        generator.writeEndArray();
    }

    private static boolean inRange(double value, double limit) {
        return Double.isFinite(value) && value >= -limit && value <= limit;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Geometry facts gathered while copying: GeoJSON nests MultiPolygon positions 4 deep, Polygon 3. */
    private static final class GeometryCheck {
        String type;
        boolean coordinates;
        int positionDepth = -1;
        int positions;
        String structureProblem;
        String outOfRange;

        void structure(String problem) {
            if (structureProblem == null) {
                structureProblem = problem;
            }
        }

        void position(int depth) {
            positions++;
            if (positionDepth == -1) {
                positionDepth = depth;
            } else if (positionDepth != depth) {
                structure("positions nested at mixed depths");
            }
        }

        String problem() {
            if (!"MultiPolygon".equals(type) && !"Polygon".equals(type)) {
                return "type " + (type == null ? "missing" : type);
            }
            if (structureProblem != null) {
                return structureProblem;
            }
            if (!coordinates || positions == 0) {
                return "no coordinates";
            }
            int expected = "MultiPolygon".equals(type) ? 4 : 3;
            if (positionDepth != expected) {
                return type + " positions nested " + positionDepth + " deep, expected " + expected;
            }
            return null;
        }
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
