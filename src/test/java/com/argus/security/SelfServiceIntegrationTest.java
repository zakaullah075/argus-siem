package com.argus.security;

import com.argus.apikey.ApiKeyRepository;
import com.argus.rules.RuleRepository;
import com.argus.support.AbstractIntegrationTest;
import com.argus.tenant.TenantRepository;
import com.argus.user.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The path a real user takes: sign up, issue a key, create a rule, send an event
 * with that key, and see it land in their own tenant.
 */
class SelfServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private RuleRepository ruleRepository;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        apiKeyRepository.deleteAll();
        ruleRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();

        token = signUp("owner@acme.test", "203.0.113." + (int) (Math.random() * 200 + 20));
    }

    @Test
    void issuedKeyAuthenticatesIngest() throws Exception {
        String apiKey = issueKey("laptop");

        mockMvc.perform(post("/v1/events")
                        .header("X-Api-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content(eventJson()))
                .andExpect(status().isAccepted());
    }

    @Test
    void revokedKeyStopsWorkingImmediately() throws Exception {
        String apiKey = issueKey("laptop");
        UUID keyId = apiKeyRepository.findAll().getFirst().getId();

        mockMvc.perform(delete("/v1/management/api-keys/{id}", keyId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/events")
                        .header("X-Api-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content(eventJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void keyListingNeverExposesTheHash() throws Exception {
        issueKey("laptop");

        String body = mockMvc.perform(get("/v1/management/api-keys")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("laptop"))
                .andReturn().getResponse().getContentAsString();

        // The hash is not the key, but it is the verifier — leaking it lets an
        // attacker confirm a guess offline.
        String storedHash = apiKeyRepository.findAll().getFirst().getKeyHash();
        assertThat(body).doesNotContain(storedHash).doesNotContain("keyHash");
    }

    @Test
    void anotherTenantCannotRevokeYourKey() throws Exception {
        issueKey("laptop");
        UUID keyId = apiKeyRepository.findAll().getFirst().getId();

        String intruder = signUp("intruder@other.test", "203.0.113.250");

        mockMvc.perform(delete("/v1/management/api-keys/{id}", keyId)
                        .header("Authorization", "Bearer " + intruder))
                .andExpect(status().isNotFound());

        assertThat(apiKeyRepository.findById(keyId).orElseThrow().isRevoked()).isFalse();
    }

    @Test
    void createdRuleAppearsForItsOwnTenantOnly() throws Exception {
        createRule();

        mockMvc.perform(get("/v1/management/rules").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));

        String other = signUp("other@other.test", "203.0.113.251");
        mockMvc.perform(get("/v1/management/rules").header("Authorization", "Bearer " + other))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void removingARuleDisablesItRatherThanDeletingHistory() throws Exception {
        createRule();
        UUID ruleId = ruleRepository.findAll().getFirst().getId();

        mockMvc.perform(delete("/v1/management/rules/{id}", ruleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Alerts carry a foreign key to the rule that raised them, so the row
        // must survive; only the enabled flag changes.
        assertThat(ruleRepository.findById(ruleId)).isPresent();
        assertThat(ruleRepository.findById(ruleId).orElseThrow().isEnabled()).isFalse();

        mockMvc.perform(get("/v1/management/rules").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));
    }

    private String signUp(String email, String address) throws Exception {
        String body = mockMvc.perform(post("/v1/auth/signup")
                        .header("X-Forwarded-For", address)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"organisation":"Acme","email":"%s","password":"supersecret1"}"""
                                .formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("token").asText();
    }

    private String issueKey(String name) throws Exception {
        String body = mockMvc.perform(post("/v1/management/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}""".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("apiKey").asText();
    }

    private void createRule() throws Exception {
        mockMvc.perform(post("/v1/management/rules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name":"brute force","matchSource":"sshd",
                                 "matchEventType":"auth.failed","minSeverity":"MEDIUM",
                                 "thresholdCount":3,"windowSeconds":300,
                                 "alertSeverity":"CRITICAL"}"""))
                .andExpect(status().isCreated());
    }

    private String eventJson() {
        return """
                {
                  "id": "%s",
                  "source": "sshd",
                  "eventType": "auth.failed",
                  "severity": "HIGH",
                  "actor": "root",
                  "target": "10.0.0.1",
                  "payload": {"port": 22},
                  "occurredAt": "2026-08-05T10:00:00Z"
                }
                """.formatted(UUID.randomUUID());
    }
}
