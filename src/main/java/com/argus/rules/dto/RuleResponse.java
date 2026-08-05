package com.argus.rules.dto;

import com.argus.common.Severity;

import java.time.Instant;
import java.util.UUID;

public record RuleResponse(
        UUID id,
        String name,
        boolean enabled,
        String matchSource,
        String matchEventType,
        Severity minSeverity,
        int thresholdCount,
        int windowSeconds,
        Severity alertSeverity,
        Instant createdAt
) {
}
