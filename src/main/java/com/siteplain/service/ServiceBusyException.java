package com.siteplain.service;

/**
 * The request was refused to protect shared capacity: a per-client rate limit (429) or a full
 * concurrency cap or outbound quota (503). Answered fast with Retry-After and the plain busy page.
 */
public class ServiceBusyException extends RuntimeException {

    public static final int TOO_MANY_REQUESTS = 429;
    public static final int SERVICE_UNAVAILABLE = 503;

    private final int status;
    private final long retryAfterSeconds;

    public ServiceBusyException(int status, long retryAfterSeconds, String reason) {
        super(reason, null, false, false);
        this.status = status;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public int status() {
        return status;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
