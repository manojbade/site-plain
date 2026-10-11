package com.siteplain.support;

import java.util.Set;

public final class ExposureStatusMapper {

    /** Codes with a label of their own; any other code is shown as "Exposure status: CODE". */
    private static final Set<String> KNOWN_CODES = Set.of("HENC", "HEUC", "HEPR", "HHPA", "HEID", "HEIC");

    private ExposureStatusMapper() {
    }

    public static boolean isKnownCode(String code) {
        return code != null && KNOWN_CODES.contains(code.toUpperCase());
    }

    public static String label(String code) {
        if (code == null || code.isBlank()) {
            return "Exposure status not reported";
        }
        return switch (code.toUpperCase()) {
            case "HENC" -> "Human exposure is NOT currently under control";
            case "HEUC" -> "Human exposure is under control";
            case "HEPR" -> "Human exposure is under control, protective remedies in place";
            case "HHPA" -> "Long-term health protection has been achieved";
            // EPA publishes no definition for HEIC (it appears on two non-Final sites), so it is
            // shown the same way as HEID rather than as a raw code.
            case "HEID", "HEIC" -> "Exposure status: insufficient data";
            default -> "Exposure status: " + code;
        };
    }

    public static String color(String code) {
        if (code == null || code.isBlank()) {
            return "gray";
        }
        return switch (code.toUpperCase()) {
            case "HENC" -> "red";
            case "HEUC", "HEPR" -> "yellow";
            case "HHPA" -> "green";
            default -> "gray";
        };
    }

    public static String badgeCssClass(String code) {
        return "sp-status-" + color(code);
    }
}
