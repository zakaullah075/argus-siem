package com.argus.alerts;

import com.argus.alerts.dto.AlertResponse;
import com.argus.audit.AuditService;
import com.argus.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Alert triage for humans. Reading alerts is also available to agents through
 * {@code /v1/alerts} with an API key; changing their state is not — a machine
 * should never be able to silence its own alerts.
 */
@RestController
@RequestMapping("/v1/management/alerts")
public class AlertAdminController {

    private final AlertService alertService;
    private final AuditService auditService;

    public AlertAdminController(AlertService alertService, AuditService auditService) {
        this.alertService = alertService;
        this.auditService = auditService;
    }

    @GetMapping
    public Page<AlertResponse> list(
            @PageableDefault(size = 50, sort = "lastSeenAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return alertService.findForTenant(AuthenticatedUser.tenantId(), pageable);
    }

    @PostMapping("/{alertId}/acknowledge")
    public AlertResponse acknowledge(@PathVariable UUID alertId) {
        UUID tenantId = AuthenticatedUser.tenantId();

        AlertResponse alert = alertService.acknowledge(tenantId, alertId);
        auditService.record(tenantId, AuthenticatedUser.userId(),
                "alert.acknowledged", alertId.toString());

        return alert;
    }

    @PostMapping("/{alertId}/resolve")
    public AlertResponse resolve(@PathVariable UUID alertId) {
        UUID tenantId = AuthenticatedUser.tenantId();

        AlertResponse alert = alertService.resolve(tenantId, alertId);
        auditService.record(tenantId, AuthenticatedUser.userId(),
                "alert.resolved", alertId.toString());

        return alert;
    }
}
