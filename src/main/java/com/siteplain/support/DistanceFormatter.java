package com.siteplain.support;

public final class DistanceFormatter {

    private DistanceFormatter() {
    }

    public static String format(double meters) {
        double feet = meters * 3.28084;
        double miles = meters / 1609.344;
        // PostGIS returns exactly 0 for a point inside (or on) the boundary polygon.
        if (meters <= 0) {
            return "Inside the EPA site boundary";
        }
        if (feet < 528) {
            if (feet < 10) {
                return "Less than 10 feet (immediately adjacent to site boundary)";
            }
            return String.format("%.0f feet (less than 0.1 miles)", feet);
        }
        if (miles < 0.5) {
            return String.format("%.0f feet (%.1f miles)", feet, miles);
        }
        return String.format("%.1f miles", miles);
    }

    /**
     * Short form for the results summary row, where the full sentence does not fit.
     * "Inside", "Under 10 ft", "420 ft" (under a tenth of a mile), otherwise "0.8 mi".
     */
    public static String shortFormat(double meters) {
        if (meters <= 0) {
            return "Inside";
        }
        double feet = meters * 3.28084;
        if (feet < 10) {
            return "Under 10 ft";
        }
        if (feet < 528) {
            return String.format("%.0f ft", feet);
        }
        return String.format("%.1f mi", meters / 1609.344);
    }
}
