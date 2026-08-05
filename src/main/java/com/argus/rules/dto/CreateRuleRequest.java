package com.argus.rules.dto;

import com.argus.common.Severity;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateRuleRequest(
        @NotBlank String name,
        String matchSource,
        String matchEventType,
        Severity minSeverity,
        @Min(1) int thresholdCount,
        @Min(1) int windowSeconds,
        @NotNull Severity alertSeverity
) {
}
