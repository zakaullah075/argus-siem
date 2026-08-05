package com.argus.rules;

import com.argus.rules.dto.CreateRuleRequest;
import com.argus.rules.dto.RuleResponse;
import com.argus.rules.exception.RuleNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Owns rule persistence so controllers never reach a repository directly, and
 * returns responses rather than entities so the API contract cannot drift into
 * the database schema by accident.
 */
@Service
public class RuleService {

    private final RuleRepository ruleRepository;
    private final RuleMapper ruleMapper;

    public RuleService(RuleRepository ruleRepository, RuleMapper ruleMapper) {
        this.ruleRepository = ruleRepository;
        this.ruleMapper = ruleMapper;
    }

    @Transactional
    public RuleResponse create(UUID tenantId, CreateRuleRequest request) {
        return ruleMapper.toResponse(ruleRepository.save(ruleMapper.toEntity(tenantId, request)));
    }

    /**
     * Soft delete by disabling. Alerts carry a foreign key to the rule that
     * raised them, so removing the row would either cascade away history or
     * fail — and an analyst still needs to know what fired last week.
     */
    @Transactional
    public void disable(UUID tenantId, UUID ruleId) {
        Rule rule = ruleRepository.findByIdAndTenantId(ruleId, tenantId)
                .orElseThrow(() -> new RuleNotFoundException(ruleId));
        rule.disable();
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> findEnabledForTenant(UUID tenantId) {
        return ruleRepository.findByTenantIdAndEnabledTrue(tenantId)
                .stream()
                .map(ruleMapper::toResponse)
                .toList();
    }
}
