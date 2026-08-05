package com.argus.alerts;

import com.argus.audit.AuditLogRepository;
import com.argus.common.Severity;
import com.argus.rules.Rule;
import com.argus.rules.RuleRepository;
import com.argus.support.AbstractIntegrationTest;
import com.argus.tenant.Tenant;
import com.argus.tenant.TenantRepository;
import com.argus.user.AppUser;
import com.argus.user.AppUserRepository;
import com.argus.user.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AlertLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private RuleRepository ruleRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID tenantId;
    private UUID otherTenantId;
    private UUID ruleId;
    private Alert alert;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        alertRepository.deleteAll();
        ruleRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        tenantId = tenantRepository.save(new Tenant("Acme", "free", 600)).getId();
        otherTenantId = tenantRepository.save(new Tenant("Other", "free", 600)).getId();

        userRepository.save(new AppUser(tenantId, "analyst@acme.test",
                passwordEncoder.encode("correct-horse"), Role.ANALYST));
        userRepository.save(new AppUser(tenantId, "viewer@acme.test",
                passwordEncoder.encode("correct-horse"), Role.VIEWER));
        userRepository.save(new AppUser(otherTenantId, "intruder@other.test",
                passwordEncoder.encode("correct-horse"), Role.ADMIN));

        // alert.rule_id is a foreign key, so the rule has to exist first.
        ruleId = ruleRepository.save(new Rule(tenantId, "brute force", "sshd",
                "auth.failed", Severity.MEDIUM, 3, 300, Severity.CRITICAL)).getId();

        alert = alertRepository.save(
                new Alert(tenantId, ruleId, Severity.HIGH, "rule:root"));
    }

    @Test
    void analystCanAcknowledgeAnAlert() throws Exception {
        mockMvc.perform(post("/v1/management/alerts/{id}/acknowledge", alert.getId())
                        .header("Authorization", "Bearer " + tokenFor("analyst@acme.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getStatus())
                .isEqualTo(AlertStatus.ACKNOWLEDGED);
    }

    @Test
    void analystCanResolveAnAlert() throws Exception {
        mockMvc.perform(post("/v1/management/alerts/{id}/resolve", alert.getId())
                        .header("Authorization", "Bearer " + tokenFor("analyst@acme.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    void viewerCannotChangeAlertState() throws Exception {
        mockMvc.perform(post("/v1/management/alerts/{id}/acknowledge", alert.getId())
                        .header("Authorization", "Bearer " + tokenFor("viewer@acme.test")))
                .andExpect(status().isForbidden());

        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getStatus())
                .isEqualTo(AlertStatus.OPEN);
    }

    @Test
    void anonymousCannotChangeAlertState() throws Exception {
        mockMvc.perform(post("/v1/management/alerts/{id}/acknowledge", alert.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherTenantGetsNotFoundRatherThanForbidden() throws Exception {
        // 403 would confirm the alert exists. A tenant must not be able to probe
        // for another tenant's alert ids.
        mockMvc.perform(post("/v1/management/alerts/{id}/acknowledge", alert.getId())
                        .header("Authorization", "Bearer " + tokenFor("intruder@other.test")))
                .andExpect(status().isNotFound());

        assertThat(alertRepository.findById(alert.getId()).orElseThrow().getStatus())
                .isEqualTo(AlertStatus.OPEN);
    }

    @Test
    void unknownAlertIdIsNotFound() throws Exception {
        mockMvc.perform(post("/v1/management/alerts/{id}/resolve", UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenFor("analyst@acme.test")))
                .andExpect(status().isNotFound());
    }

    @Test
    void stateChangesAreAudited() throws Exception {
        mockMvc.perform(post("/v1/management/alerts/{id}/acknowledge", alert.getId())
                .header("Authorization", "Bearer " + tokenFor("analyst@acme.test")));
        mockMvc.perform(post("/v1/management/alerts/{id}/resolve", alert.getId())
                .header("Authorization", "Bearer " + tokenFor("analyst@acme.test")));

        assertThat(auditLogRepository.findAll())
                .extracting(log -> log.getAction())
                .containsExactlyInAnyOrder("alert.acknowledged", "alert.resolved");
    }

    @Test
    void resolvingLetsARecurrenceOpenAFreshAlert() {
        // The unique index is partial on status <> RESOLVED, so a resolved alert
        // and a new open one can coexist. A recurrence after resolution should
        // start fresh history rather than reopening the old one.
        alert.resolve();
        alertRepository.save(alert);

        Alert recurrence = alertRepository.save(
                new Alert(tenantId, ruleId, Severity.HIGH, "rule:root"));

        assertThat(recurrence.getId()).isNotEqualTo(alert.getId());
        assertThat(alertRepository.count()).isEqualTo(2);
    }

    private String tokenFor(String email) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse"}""".formatted(email)))
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("token").asText();
    }
}
