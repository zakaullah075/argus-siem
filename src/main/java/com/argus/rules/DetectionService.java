package com.argus.rules;

import com.argus.alerts.AlertService;
import com.argus.ingest.Event;
import com.argus.ingest.EventRepository;
import com.argus.common.Severity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Evaluates a tenant's enabled rules against a newly ingested event.
 * <p>
 * A rule fires when the number of matching events inside its rolling window
 * reaches its threshold — so "five failed logins in five minutes" is one rule,
 * not five separate alerts.
 */
@Service
public class DetectionService {

    private final RuleRepository ruleRepository;
    private final EventRepository eventRepository;
    private final AlertService alertService;

    public DetectionService(RuleRepository ruleRepository,
                            EventRepository eventRepository,
                            AlertService alertService) {
        this.ruleRepository = ruleRepository;
        this.eventRepository = eventRepository;
        this.alertService = alertService;
    }

    @Transactional
    public void evaluate(Event event) {
        List<Rule> rules = ruleRepository.findByTenantIdAndEnabledTrue(event.getTenantId());

        for (Rule rule : rules) {
            if (!rule.matches(event.getSource(), event.getEventType(), event.getSeverity())) {
                continue;
            }

            // One count query per matching rule. Fine while tenants have a handful
            // of rules; at hundreds this becomes the bottleneck and the counting
            // should move to a rolling counter in Redis rather than a scan.
            // Closed at both ends: [occurredAt - window, occurredAt]. An open
            // upper bound would let events that happened after this one count
            // towards its threshold, which is only invisible when evaluation is
            // immediate.
            Instant since = event.getOccurredAt().minusSeconds(rule.getWindowSeconds());

            long matches = eventRepository.countMatching(
                    event.getTenantId(),
                    rule.getMatchSource(),
                    rule.getMatchEventType(),
                    severitiesAtLeast(rule.getMinSeverity()),
                    since,
                    event.getOccurredAt(),
                    // Counting per actor, so ten accounts each failing twice does
                    // not add up to a brute-force alert against nobody.
                    event.getActor()
            );

            if (matches >= rule.getThresholdCount()) {
                alertService.raise(
                        event.getTenantId(),
                        rule.getId(),
                        rule.getAlertSeverity(),
                        dedupeKey(rule.getId(), event.getActor())
                );
            }
        }
    }

    private List<Severity> severitiesAtLeast(Severity minimum) {
        if (minimum == null) {
            return Arrays.asList(Severity.values());
        }
        return Arrays.stream(Severity.values())
                .filter(severity -> severity.ordinal() >= minimum.ordinal())
                .toList();
    }

    /**
     * Groups repeat occurrences of the same problem. Rule plus actor means one
     * ongoing alert per account under attack, rather than one per event.
     */
    private String dedupeKey(UUID ruleId, String actor) {
        return ruleId + ":" + (actor != null ? actor : "-");
    }
}
