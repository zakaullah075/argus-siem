package com.argus.rules.exception;

import com.argus.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

public class RuleNotFoundException extends ApiException {

    public RuleNotFoundException(UUID ruleId) {
        super(HttpStatus.NOT_FOUND, "Rule %s not found".formatted(ruleId));
    }
}
