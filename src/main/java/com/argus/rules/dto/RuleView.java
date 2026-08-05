package com.argus.rules.dto;

import com.argus.common.Severity;
import com.argus.rules.Rule;

import java.time.Instant;
import java.util.UUID;

public record RuleView(
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

    public static RuleView from(Rule rule) {
        return new RuleView(
                rule.getId(),
                rule.getName(),
                rule.isEnabled(),
                rule.getMatchSource(),
                rule.getMatchEventType(),
                rule.getMinSeverity(),
                rule.getThresholdCount(),
                rule.getWindowSeconds(),
                rule.getAlertSeverity(),
                rule.getCreatedAt()
        );
    }
}
