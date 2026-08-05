package com.argus.common;

import org.springframework.http.HttpStatus;

/**
 * Base for errors that map to a specific HTTP status. Carrying the status on the
 * exception keeps that decision next to the thing that failed, instead of in a
 * translation table far away from it.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;

    protected ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
