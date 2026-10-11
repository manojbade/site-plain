package com.siteplain.web;

/**
 * A request the app refuses on principle rather than for its content, such as a lookup form posted
 * from another site. Answered with 403 and the plain error page, logged like a bad request.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String reason) {
        super(reason);
    }
}
