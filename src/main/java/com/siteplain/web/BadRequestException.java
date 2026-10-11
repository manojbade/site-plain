package com.siteplain.web;

/**
 * A request the app will not act on: a value too long, malformed or out of range. Answered with
 * 400 and the plain error page, logged as one WARN line without a stack trace and without the value.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String reason) {
        super(reason);
    }
}
