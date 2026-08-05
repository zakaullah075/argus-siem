package com.argus.alerts;

import com.argus.ingest.Severity;
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

    public AlertService(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
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

    @Transactional(readOnly = true)
    public Page<Alert> findForTenant(UUID tenantId, Pageable pageable) {
        return alertRepository.findByTenantId(tenantId, pageable);
    }
}
