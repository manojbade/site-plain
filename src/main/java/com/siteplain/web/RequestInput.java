package com.siteplain.web;

/**
 * Length caps for user-supplied request values. A value over its cap is a client error (400), so
 * nothing oversized reaches a query, a PDF or a log line.
 */
public final class RequestInput {

    /** Same cap as the address form field (AddressLookupForm). */
    public static final int MAX_ADDRESS = 200;
    public static final int MAX_SEARCH_QUERY = 100;
    public static final int MAX_STATE = 2;
    public static final int MAX_GEOCODER = 20;
    public static final int MAX_APPROX = 30;
    public static final int MAX_SHORT_PARAM = 20;
    public static final int MAX_FEEDBACK_COMMENTS = 500;

    private RequestInput() {
    }

    /** The value unchanged, or a BadRequestException naming the parameter (never the value) when it is too long. */
    public static String maxLength(String value, int max, String name) {
        if (value != null && value.length() > max) {
            throw new BadRequestException(name + " longer than " + max + " characters");
        }
        return value;
    }
}
