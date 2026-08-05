package com.argus.rules;

import com.argus.rules.dto.CreateRuleCommand;
import com.argus.rules.dto.RuleView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Owns rule persistence so controllers never reach a repository directly, and
 * returns views rather than entities so the API contract cannot drift into the
 * database schema by accident.
 */
@Service
public class RuleService {

    private final RuleRepository ruleRepository;

    public RuleService(RuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @Transactional
    public RuleView create(UUID tenantId, CreateRuleCommand command) {
        Rule rule = ruleRepository.save(new Rule(
                tenantId,
                command.name(),
                command.matchSource(),
                command.matchEventType(),
                command.minSeverity(),
                command.thresholdCount(),
                command.windowSeconds(),
                command.alertSeverity()
        ));

        return RuleView.from(rule);
    }

    @Transactional(readOnly = true)
    public List<RuleView> findEnabledForTenant(UUID tenantId) {
        return ruleRepository.findByTenantIdAndEnabledTrue(tenantId)
                .stream()
                .map(RuleView::from)
                .toList();
    }
}
