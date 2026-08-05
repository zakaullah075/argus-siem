package com.argus.alerts;

import com.argus.alerts.dto.AlertResponse;
import com.argus.alerts.exception.AlertNotFoundException;
import com.argus.common.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alertRepository;
    private final AlertMapper alertMapper;

    public AlertService(AlertRepository alertRepository, AlertMapper alertMapper) {
        this.alertRepository = alertRepository;
        this.alertMapper = alertMapper;
    }

    /**
     * Raises an alert, or folds the occurrence into the existing open one.
     * <p>
     * Without this, a single brute-force burst that trips a rule on every event
     * would produce hundreds of identical alerts and bury the signal. Analysts
     * ignore noisy alerting, which makes a noisy system worse than none.
     */
    @Transactional
    public Alert raise(UUID tenantId, UUID ruleId, Severity severity, String dedupeKey) {
        return alertRepository
                .findByTenantIdAndDedupeKeyAndStatusNot(tenantId, dedupeKey, AlertStatus.RESOLVED)
                .map(existing -> {
                    existing.recordOccurrence();
                    return existing;
                })
                .orElseGet(() -> {
                    log.info("Raising alert tenant={} rule={} severity={} dedupeKey={}",
                            tenantId, ruleId, severity, dedupeKey);
                    return alertRepository.save(new Alert(tenantId, ruleId, severity, dedupeKey));
                });
    }

    /**
     * Looks up by id AND tenant, never by id alone. Fetching by id and checking
     * the tenant afterwards leaks existence: a wrong-tenant request would get a
     * different error than a genuinely missing one.
     */
    @Transactional
    public AlertResponse acknowledge(UUID tenantId, UUID alertId) {
        Alert alert = requireOwnedAlert(tenantId, alertId);
        alert.acknowledge();
        return alertMapper.toResponse(alert);
    }

    @Transactional
    public AlertResponse resolve(UUID tenantId, UUID alertId) {
        Alert alert = requireOwnedAlert(tenantId, alertId);
        alert.resolve();
        return alertMapper.toResponse(alert);
    }

    private Alert requireOwnedAlert(UUID tenantId, UUID alertId) {
        return alertRepository.findByIdAndTenantId(alertId, tenantId)
                .orElseThrow(() -> new AlertNotFoundException(alertId));
    }

    @Transactional(readOnly = true)
    public Page<AlertResponse> findForTenant(UUID tenantId, Pageable pageable) {
        return alertRepository.findByTenantId(tenantId, pageable).map(alertMapper::toResponse);
    }
}
