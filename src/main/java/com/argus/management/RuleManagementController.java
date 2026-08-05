package com.argus.management;

import com.argus.audit.AuditService;
import com.argus.ingest.Severity;
import com.argus.rules.Rule;
import com.argus.rules.RuleRepository;
import com.argus.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/management/rules")
public class RuleManagementController {

    private final RuleRepository ruleRepository;
    private final AuditService auditService;

    public RuleManagementController(RuleRepository ruleRepository, AuditService auditService) {
        this.ruleRepository = ruleRepository;
        this.auditService = auditService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse create(@Valid @RequestBody CreateRuleRequest request) {
        UUID tenantId = AuthenticatedUser.tenantId();

        Rule rule = ruleRepository.save(new Rule(
                tenantId,
                request.name(),
                request.matchSource(),
                request.matchEventType(),
                request.minSeverity(),
                request.thresholdCount(),
                request.windowSeconds(),
                request.alertSeverity()
        ));

        auditService.record(tenantId, AuthenticatedUser.userId(), "rule.created", rule.getId().toString());

        return RuleResponse.from(rule);
    }

    @GetMapping
    public List<RuleResponse> list() {
        return ruleRepository.findByTenantIdAndEnabledTrue(AuthenticatedUser.tenantId())
                .stream()
                .map(RuleResponse::from)
                .toList();
    }

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

        static RuleResponse from(Rule rule) {
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
}
