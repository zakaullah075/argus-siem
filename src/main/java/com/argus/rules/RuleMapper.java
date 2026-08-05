package com.argus.rules;

import com.argus.rules.dto.CreateRuleRequest;
import com.argus.rules.dto.RuleResponse;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RuleMapper {

    public Rule toEntity(UUID tenantId, CreateRuleRequest request) {
        return new Rule(
                tenantId,
                request.name(),
                request.matchSource(),
                request.matchEventType(),
                request.minSeverity(),
                request.thresholdCount(),
                request.windowSeconds(),
                request.alertSeverity()
        );
    }

    public RuleResponse toResponse(Rule rule) {
        return new RuleResponse(
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
