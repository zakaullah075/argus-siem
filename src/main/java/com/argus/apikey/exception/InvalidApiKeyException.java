package com.argus.apikey.exception;

import com.argus.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * One message for missing, unknown and revoked keys. A distinct message for a
 * revoked key would tell an attacker their guess was once real, which narrows
 * the search.
 */
public class InvalidApiKeyException extends ApiException {

    public InvalidApiKeyException() {
        super(HttpStatus.UNAUTHORIZED, "Invalid API key");
    }
}
