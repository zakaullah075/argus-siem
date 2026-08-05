package com.argus.alerts.dto;

import com.argus.alerts.Alert;
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

    public static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getRuleId(),
                alert.getStatus(),
                alert.getSeverity(),
                alert.getDedupeKey(),
                alert.getOccurrenceCount(),
                alert.getFirstSeenAt(),
                alert.getLastSeenAt()
        );
    }
}
