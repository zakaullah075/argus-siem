package com.argus.alerts.exception;

import com.argus.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * 404 rather than 403 for another tenant's alert. A 403 would confirm it exists.
 */
public class AlertNotFoundException extends ApiException {

    public AlertNotFoundException(UUID alertId) {
        super(HttpStatus.NOT_FOUND, "Alert %s not found".formatted(alertId));
    }
}
