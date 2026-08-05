package com.argus.alerts.dto;

import com.argus.alerts.AlertStatus;
import com.argus.common.Severity;

import java.time.Instant;
import java.util.UUID;

public record AlertResponse(
        UUID id,
        UUID ruleId,
        AlertStatus status,
        Severity severity,
        String dedupeKey,
        int occurrenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt
) {
}
