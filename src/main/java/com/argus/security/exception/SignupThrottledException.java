package com.argus.security.exception;

import com.argus.common.ApiException;
import org.springframework.http.HttpStatus;

public class SignupThrottledException extends ApiException {

    public SignupThrottledException(int limit) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Too many signups from this address; limit is %d per minute".formatted(limit));
    }
}
