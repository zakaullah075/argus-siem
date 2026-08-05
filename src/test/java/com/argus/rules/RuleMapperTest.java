package com.argus.rules;

import com.argus.common.Severity;
import com.argus.rules.dto.CreateRuleRequest;
import com.argus.rules.dto.RuleResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleMapperTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final RuleMapper mapper = new RuleMapper();

    @Test
    void buildsEntityFromRequestAndScopesItToTheTenant() {
        var request = new CreateRuleRequest("brute force", "sshd", "auth.failed",
                Severity.MEDIUM, 5, 300, Severity.CRITICAL);

        Rule rule = mapper.toEntity(TENANT, request);

        assertThat(rule.getTenantId()).isEqualTo(TENANT);
        assertThat(rule.getName()).isEqualTo("brute force");
        assertThat(rule.getThresholdCount()).isEqualTo(5);
        assertThat(rule.isEnabled()).isTrue();
    }

    @Test
    void preservesNullConditionsWhichMeanMatchAnything() {
        var request = new CreateRuleRequest("catch all", null, null, null,
                1, 60, Severity.LOW);

        Rule rule = mapper.toEntity(TENANT, request);

        assertThat(rule.getMatchSource()).isNull();
        assertThat(rule.getMatchEventType()).isNull();
        assertThat(rule.getMinSeverity()).isNull();
    }

    @Test
    void roundTripsThroughResponseWithoutLosingFields() {
        var request = new CreateRuleRequest("brute force", "sshd", "auth.failed",
                Severity.HIGH, 3, 120, Severity.CRITICAL);

        RuleResponse response = mapper.toResponse(mapper.toEntity(TENANT, request));

        assertThat(response.name()).isEqualTo(request.name());
        assertThat(response.matchSource()).isEqualTo(request.matchSource());
        assertThat(response.matchEventType()).isEqualTo(request.matchEventType());
        assertThat(response.minSeverity()).isEqualTo(request.minSeverity());
        assertThat(response.thresholdCount()).isEqualTo(request.thresholdCount());
        assertThat(response.windowSeconds()).isEqualTo(request.windowSeconds());
        assertThat(response.alertSeverity()).isEqualTo(request.alertSeverity());
    }

    @Test
    void responseReflectsDisabling() {
        Rule rule = mapper.toEntity(TENANT, new CreateRuleRequest("r", null, null,
                null, 1, 60, Severity.LOW));
        rule.disable();

        assertThat(mapper.toResponse(rule).enabled()).isFalse();
    }
}
