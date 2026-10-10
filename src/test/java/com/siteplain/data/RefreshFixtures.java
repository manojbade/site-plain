package com.siteplain.data;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes boundary GeoJSON and exposure feed files big enough to pass the refresh gates (more than
 * 1,500 feed rows, a Final count inside 1,200..1,450), with every site in Georgia.
 */
public final class RefreshFixtures {

    public static final int SITES = 1600;
    public static final int FINAL_SITES = 1300;
    private static final String[] CODES = {"HENC", "HEUC", "HEPR", "HHPA", "HEID"};

    private RefreshFixtures() {
    }

    public static String epaId(int index) {
        return String.format(Locale.ROOT, "GAR%09d", index);
    }

    public static String status(int index) {
        return index < FINAL_SITES ? "F" : "D";
    }

    /** One small square per site; namePrefix lets a test tell two loads apart. */
    public static Path writeBoundaries(Path file, String namePrefix) throws IOException {
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("{\"type\":\"FeatureCollection\",\"name\":\"npl_site_boundaries\","
                    + "\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:OGC:1.3:CRS84\"}},"
                    + "\"features\":[\n");
            for (int i = 0; i < SITES; i++) {
                if (i > 0) {
                    out.write(",\n");
                }
                double lng = -84.0 - (i % 40) * 0.01;
                double lat = 33.0 + (i / 40) * 0.01;
                out.write(String.format(Locale.ROOT,
                        "{\"type\":\"Feature\",\"properties\":{\"EPA_ID\":\"%s\",\"SITE_NAME\":\"%s SITE %d\","
                                + "\"STATE_CODE\":\"GA\",\"NPL_STATUS_CODE\":\"%s\","
                                + "\"URL_ALIAS_TXT\":\"www.epa.gov/superfund/fixture-%d\"},"
                                + "\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[[[[%s,%s],[%s,%s],[%s,%s],[%s,%s]]]]}}",
                        epaId(i), namePrefix, i, status(i), i,
                        lng, lat, lng + 0.005, lat, lng + 0.005, lat + 0.005, lng, lat));
            }
            out.write("\n]}\n");
        }
        return file;
    }

    /** One feed row per site, with the same status as its boundary. */
    public static Path writeExposure(Path file, String namePrefix) throws IOException {
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("{\"success\":true,\"data\":[\n");
            for (int i = 0; i < SITES; i++) {
                if (i > 0) {
                    out.write(",\n");
                }
                out.write(String.format(Locale.ROOT,
                        "{\"epaid\":\"%s\",\"nplstatus\":\"%s\",\"humexposurestscode\":\"%s\","
                                + "\"humanexposurepathdesc\":\"\",\"sitename\":\"%s SITE %d\",\"state_code\":\"GA\","
                                + "\"friendlyurl\":\"www.epa.gov\\/superfund\\/fixture-%d\"}",
                        epaId(i), status(i), CODES[i % CODES.length], namePrefix, i, i));
            }
            out.write(String.format(Locale.ROOT,
                    "\n],\"meta\":{\"total_data\":%d,\"limit\":500000,\"offset\":0,\"timestamp\":\"10-10-2026 14:01\"}}\n",
                    SITES));
        }
        return file;
    }
}
