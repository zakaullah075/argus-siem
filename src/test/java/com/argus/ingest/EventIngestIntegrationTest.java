package com.argus.ingest;

import com.argus.apikey.ApiKeyRepository;
import com.argus.apikey.ApiKeyService;
import com.argus.tenant.Tenant;
import com.argus.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs against a real Postgres in Docker rather than an in-memory database.
 * H2 would not enforce the jsonb column, the check constraint, or the unique
 * index — so it would pass while production failed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EventIngestIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private EventRepository eventRepository;

    private String apiKey;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        apiKeyRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = tenantRepository.save(new Tenant("Acme Corp", "free", 600));
        apiKey = apiKeyService.issue(tenant.getId(), "test key");
    }

    @Test
    void rejectsRequestWithoutApiKey() throws Exception {
        mockMvc.perform(post("/v1/events")
                        .contentType(APPLICATION_JSON)
                        .content(eventJson(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnknownApiKey() throws Exception {
        mockMvc.perform(post("/v1/events")
                        .header("X-Api-Key", "argus_not_a_real_key")
                        .contentType(APPLICATION_JSON)
                        .content(eventJson(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsEventWhenApiKeyRevoked() throws Exception {
        apiKeyRepository.findAll().forEach(key -> {
            key.revoke();
            apiKeyRepository.save(key);
        });

        mockMvc.perform(post("/v1/events")
                        .header("X-Api-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content(eventJson(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsEventMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/v1/events")
                        .header("X-Api-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"source\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsValidEvent() throws Exception {
        UUID eventId = UUID.randomUUID();

        mockMvc.perform(post("/v1/events")
                        .header("X-Api-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content(eventJson(eventId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(eventId.toString()))
                .andExpect(jsonPath("$.duplicate").value(false));

        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    void treatsResentEventIdAsDuplicateWithoutWritingSecondRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        String body = eventJson(eventId);

        mockMvc.perform(post("/v1/events")
                        .header("X-Api-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.duplicate").value(false));

        // An agent that timed out and retried sends the identical event again.
        mockMvc.perform(post("/v1/events")
                        .header("X-Api-Key", apiKey)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.duplicate").value(true));

        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    void returnsOnlyEventsBelongingToAuthenticatedTenant() throws Exception {
        mockMvc.perform(post("/v1/events")
                .header("X-Api-Key", apiKey)
                .contentType(APPLICATION_JSON)
                .content(eventJson(UUID.randomUUID())));

        Tenant other = tenantRepository.save(new Tenant("Other Corp", "free", 600));
        String otherKey = apiKeyService.issue(other.getId(), "other key");

        mockMvc.perform(post("/v1/events")
                .header("X-Api-Key", otherKey)
                .contentType(APPLICATION_JSON)
                .content(eventJson(UUID.randomUUID())));

        assertThat(eventRepository.count()).isEqualTo(2);

        // Two events exist, but each tenant must only ever see its own.
        mockMvc.perform(get("/v1/events").header("X-Api-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/v1/events").header("X-Api-Key", otherKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    private String eventJson(UUID id) {
        return """
                {
                  "id": "%s",
                  "source": "sshd",
                  "eventType": "auth.failed",
                  "severity": "HIGH",
                  "actor": "root",
                  "target": "10.0.0.5",
                  "payload": {"attempts": 5, "port": 22},
                  "occurredAt": "2026-08-05T01:30:00Z"
                }
                """.formatted(id);
    }
}
