package com.argus.security;

import com.argus.support.AbstractIntegrationTest;
import com.argus.audit.AuditLogRepository;
import com.argus.rules.RuleRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ManagementSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private RuleRepository ruleRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        ruleRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = tenantRepository.save(new Tenant("Acme Corp", "free", 600));
        tenantId = tenant.getId();

        userRepository.save(new AppUser(tenantId, "admin@acme.test",
                passwordEncoder.encode("correct-horse"), Role.ADMIN));
        userRepository.save(new AppUser(tenantId, "viewer@acme.test",
                passwordEncoder.encode("correct-horse"), Role.VIEWER));
    }

    @Test
    void issuesTokenForValidCredentials() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(login("admin@acme.test", "correct-horse")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(login("admin@acme.test", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void givesSameAnswerForUnknownAccountAsForWrongPassword() throws Exception {
        // Both must be 401 with the same body, or the endpoint becomes an
        // oracle for which email addresses have accounts.
        String unknown = mockMvc.perform(post("/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(login("nobody@acme.test", "correct-horse")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String wrongPassword = mockMvc.perform(post("/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(login("admin@acme.test", "wrong")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(wrongPassword);
    }

    @Test
    void rejectsManagementRequestWithoutToken() throws Exception {
        mockMvc.perform(post("/v1/management/rules")
                        .contentType(APPLICATION_JSON)
                        .content(createRuleJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRuleCreationByViewer() throws Exception {
        String token = tokenFor("viewer@acme.test");

        mockMvc.perform(post("/v1/management/rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(createRuleJson()))
                .andExpect(status().isForbidden());

        assertThat(ruleRepository.count()).isZero();
    }

    @Test
    void allowsRuleCreationByAdminAndWritesAuditRecord() throws Exception {
        String token = tokenFor("admin@acme.test");

        mockMvc.perform(post("/v1/management/rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(createRuleJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("brute force"));

        assertThat(ruleRepository.count()).isEqualTo(1);

        var audit = auditLogRepository.findAll();
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().getAction()).isEqualTo("rule.created");
    }

    @Test
    void scopesCreatedRuleToTokenTenantNotRequestBody() throws Exception {
        String token = tokenFor("admin@acme.test");

        mockMvc.perform(post("/v1/management/rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(createRuleJson()))
                .andExpect(status().isCreated());

        assertThat(ruleRepository.findAll().getFirst().getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void issuedApiKeyIsReturnedOnceAndNotStoredInPlaintext() throws Exception {
        String token = tokenFor("admin@acme.test");

        String body = mockMvc.perform(post("/v1/management/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"agent-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String issued = objectMapper.readTree(body).get("apiKey").asText();
        assertThat(issued).startsWith("argus_");

        // The audit trail must record the issuance without recording the key.
        assertThat(auditLogRepository.findAll().getFirst().getAction()).isEqualTo("apikey.issued");
        assertThat(auditLogRepository.findAll().getFirst().getResource()).doesNotContain(issued);
    }

    @Test
    void listingRulesIsAllowedForViewer() throws Exception {
        mockMvc.perform(get("/v1/management/rules")
                        .header("Authorization", "Bearer " + tokenFor("viewer@acme.test")))
                .andExpect(status().isOk());
    }

    private String tokenFor(String email) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(login(email, "correct-horse")))
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("token").asText();
    }

    private String login(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    private String createRuleJson() {
        return """
                {
                  "name": "brute force",
                  "matchSource": "sshd",
                  "matchEventType": "auth.failed",
                  "minSeverity": "MEDIUM",
                  "thresholdCount": 5,
                  "windowSeconds": 300,
                  "alertSeverity": "CRITICAL"
                }
                """;
    }
}
