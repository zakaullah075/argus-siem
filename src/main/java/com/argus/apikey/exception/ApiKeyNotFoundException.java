package com.argus.apikey.exception;

import com.argus.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * 404 rather than 403 when the key belongs to another tenant. A 403 would
 * confirm the key exists, letting one tenant probe for another's key ids.
 */
public class ApiKeyNotFoundException extends ApiException {

    public ApiKeyNotFoundException(UUID keyId) {
        super(HttpStatus.NOT_FOUND, "API key %s not found".formatted(keyId));
    }
}
