package com.argus.common;

import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends ApiException {

    public RateLimitExceededException(int limitPerMinute) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit of %d requests per minute exceeded".formatted(limitPerMinute));
    }
}
