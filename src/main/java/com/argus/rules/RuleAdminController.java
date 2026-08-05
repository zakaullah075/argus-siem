package com.argus.rules;

import com.argus.audit.AuditService;
import com.argus.rules.dto.CreateRuleRequest;
import com.argus.rules.dto.RuleResponse;
import com.argus.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/management/rules")
public class RuleAdminController {

    private final RuleService ruleService;
    private final AuditService auditService;

    public RuleAdminController(RuleService ruleService, AuditService auditService) {
        this.ruleService = ruleService;
        this.auditService = auditService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse create(@Valid @RequestBody CreateRuleRequest command) {
        UUID tenantId = AuthenticatedUser.tenantId();

        RuleResponse rule = ruleService.create(tenantId, command);
        auditService.record(tenantId, AuthenticatedUser.userId(), "rule.created", rule.id().toString());

        return rule;
    }

    @DeleteMapping("/{ruleId}")
    public void disable(@PathVariable UUID ruleId) {
        UUID tenantId = AuthenticatedUser.tenantId();
        ruleService.disable(tenantId, ruleId);
        auditService.record(tenantId, AuthenticatedUser.userId(), "rule.disabled", ruleId.toString());
    }

    @GetMapping
    public List<RuleResponse> list() {
        return ruleService.findEnabledForTenant(AuthenticatedUser.tenantId());
    }
}
