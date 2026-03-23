package com.siteplain.support;

public final class ExposureStatusMapper {

    private ExposureStatusMapper() {
    }

    public static String label(String code) {
        if (code == null || code.isBlank()) {
            return "Exposure status not reported";
        }
        return switch (code.toUpperCase()) {
            case "HENC" -> "Human exposure is NOT currently under control";
            case "HEUC" -> "Human exposure is under control";
            case "HEPR" -> "Human exposure is under control — protective remedies in place";
            case "HHPA" -> "Long-term health protection has been achieved";
            case "HEID" -> "Exposure status: insufficient data";
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
        return "badge-exposure-" + color(code);
    }
}
