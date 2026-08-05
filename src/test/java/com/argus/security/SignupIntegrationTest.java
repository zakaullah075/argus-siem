package com.argus.security;

import com.argus.audit.AuditLogRepository;
import com.argus.support.AbstractIntegrationTest;
import com.argus.tenant.TenantRepository;
import com.argus.user.AppUserRepository;
import com.argus.user.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SignupIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    /**
     * The agent must be fetchable before anyone has an account — it is what you
     * run to get events flowing. This shipped broken once: the static allowlist
     * named exact paths and did not include /agent, so the download link on the
     * setup page returned 401.
     */
    @Test
    void agentIsDownloadableWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/agent/argus-agent.py"))
                .andExpect(status().isOk());
    }

    @Test
    void dashboardIsServedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/app.js")).andExpect(status().isOk());
    }

    @Test
    void managementStillRequiresAuthentication() throws Exception {
        // Making static files public must not open the api.
        mockMvc.perform(get("/v1/management/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/management/api-keys")).andExpect(status().isUnauthorized());
    }

    @Test
    void createsTenantAndAdminAndReturnsToken() throws Exception {
        mockMvc.perform(signup("Acme Ltd", "owner@acme.test", "supersecret1", "198.51.100.1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertThat(tenantRepository.count()).isEqualTo(1);
        assertThat(userRepository.findByEmail("owner@acme.test"))
                .isPresent()
                .get()
                .extracting(user -> user.getRole())
                .isEqualTo(Role.ADMIN);
    }

    /**
     * The audit record references the tenant created in the same transaction.
     * Writing it with REQUIRES_NEW fails the foreign key, because a separate
     * transaction cannot see an uncommitted row — this test pins the fix.
     */
    @Test
    void writesAuditRecordForTenantCreation() throws Exception {
        mockMvc.perform(signup("Acme Ltd", "owner@acme.test", "supersecret1", "198.51.100.2"))
                .andExpect(status().isCreated());

        assertThat(auditLogRepository.findAll())
                .extracting(log -> log.getAction())
                .containsExactly("tenant.created");
    }

    @Test
    void neverStoresThePasswordInPlaintext() throws Exception {
        mockMvc.perform(signup("Acme Ltd", "owner@acme.test", "supersecret1", "198.51.100.3"))
                .andExpect(status().isCreated());

        String hash = userRepository.findByEmail("owner@acme.test").orElseThrow().getPasswordHash();
        assertThat(hash).doesNotContain("supersecret1").startsWith("$2");
    }

    @Test
    void rejectsDuplicateEmail() throws Exception {
        mockMvc.perform(signup("Acme Ltd", "owner@acme.test", "supersecret1", "198.51.100.4"))
                .andExpect(status().isCreated());

        mockMvc.perform(signup("Other Ltd", "owner@acme.test", "supersecret1", "198.51.100.4"))
                .andExpect(status().isConflict());

        assertThat(tenantRepository.count()).isEqualTo(1);
    }

    @Test
    void normalisesEmailCaseSoDuplicatesAreStillCaught() throws Exception {
        mockMvc.perform(signup("Acme Ltd", "Owner@Acme.test", "supersecret1", "198.51.100.5"))
                .andExpect(status().isCreated());

        mockMvc.perform(signup("Other Ltd", "owner@acme.TEST", "supersecret1", "198.51.100.5"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsShortPassword() throws Exception {
        mockMvc.perform(signup("Acme Ltd", "owner@acme.test", "short", "198.51.100.6"))
                .andExpect(status().isBadRequest());

        assertThat(tenantRepository.count()).isZero();
    }

    @Test
    void rejectsMalformedEmail() throws Exception {
        mockMvc.perform(signup("Acme Ltd", "not-an-email", "supersecret1", "198.51.100.7"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void throttlesRepeatedSignupsFromOneAddress() throws Exception {
        // Public signup is an abuse surface: each one costs rows in every table
        // and a share of a free-tier database.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(signup("Org " + i, "user" + i + "@acme.test",
                            "supersecret1", "203.0.113.99"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(signup("Org 4", "user4@acme.test", "supersecret1", "203.0.113.99"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void throttleIsPerAddressNotGlobal() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(signup("Org " + i, "user" + i + "@acme.test",
                    "supersecret1", "203.0.113.10"));
        }

        // A different caller must not be blocked by someone else's usage.
        mockMvc.perform(signup("Other", "other@acme.test", "supersecret1", "203.0.113.11"))
                .andExpect(status().isCreated());
    }

    @Test
    void newTenantStartsEmptyAndIsolated() throws Exception {
        String token = objectMapper.readTree(
                mockMvc.perform(signup("Acme Ltd", "owner@acme.test", "supersecret1", "198.51.100.8"))
                        .andReturn().getResponse().getContentAsString())
                .get("token").asText();

        mockMvc.perform(get("/v1/management/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/v1/management/alerts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signup(
            String org, String email, String password, String address) {

        return post("/v1/auth/signup")
                .header("X-Forwarded-For", address)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"organisation":"%s","email":"%s","password":"%s"}"""
                        .formatted(org, email, password));
    }
}
