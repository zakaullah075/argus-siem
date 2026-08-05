package com.argus.alerts;

import com.argus.alerts.dto.AlertResponse;
import com.argus.apikey.ApiKeyAuthFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public Page<AlertResponse> list(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE) UUID tenantId,
            @PageableDefault(size = 50, sort = "lastSeenAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return alertService.findForTenant(tenantId, pageable).map(AlertResponse::from);
    }
}
