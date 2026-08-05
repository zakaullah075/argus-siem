package com.argus.outbox;

import com.argus.apikey.ApiKeyRepository;
import com.argus.apikey.ApiKeyService;
import com.argus.ingest.EventRepository;
import com.argus.support.AbstractIntegrationTest;
import com.argus.tenant.Tenant;
import com.argus.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The outbox exists to close one specific hole: an event committed to the
 * database but never published, because the process died in between. These
 * tests are about that hole, not about the queue working.
 */
class OutboxIntegrationTest extends AbstractIntegrationTest {

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

    @Autowired
    private OutboxRepository outboxRepository;

    private String apiKey;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        eventRepository.deleteAll();
        apiKeyRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = tenantRepository.save(new Tenant("Acme", "free", 600));
        apiKey = apiKeyService.issue(tenant.getId(), "test key");
    }

    @Test
    void ingestWritesAnOutboxRowInTheSameTransaction() throws Exception {
        UUID eventId = UUID.randomUUID();

        mockMvc.perform(ingest(eventId)).andExpect(status().isAccepted());

        // The row exists the moment ingest returns — it is not something the
        // relay creates later.
        assertThat(outboxRepository.findAll())
                .extracting(OutboxMessage::getAggregateId)
                .contains(eventId);
    }

    @Test
    void relayPublishesAndMarksTheRow() throws Exception {
        mockMvc.perform(ingest(UUID.randomUUID())).andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(outboxRepository.countByPublishedAtIsNull()).isZero());

        assertThat(outboxRepository.findAll())
                .allSatisfy(message -> assertThat(message.getPublishedAt()).isNotNull());
    }

    @Test
    void aDuplicateEventWritesNoSecondOutboxRow() throws Exception {
        UUID eventId = UUID.randomUUID();

        mockMvc.perform(ingest(eventId)).andExpect(status().isAccepted());
        mockMvc.perform(ingest(eventId)).andExpect(status().isAccepted());

        // The second delivery is acknowledged without work, so it must not queue
        // a second evaluation either.
        assertThat(outboxRepository.findAll())
                .filteredOn(message -> message.getAggregateId().equals(eventId))
                .hasSize(1);
    }

    @Test
    void outboxRowIsScopedToTheTenantThatOwnsTheEvent() throws Exception {
        Tenant other = tenantRepository.save(new Tenant("Other", "free", 600));
        String otherKey = apiKeyService.issue(other.getId(), "other");

        mockMvc.perform(post("/v1/events")
                .header("X-Api-Key", otherKey)
                .contentType(APPLICATION_JSON)
                .content(eventJson(UUID.randomUUID())));

        assertThat(outboxRepository.findAll())
                .allSatisfy(message -> assertThat(message.getTenantId()).isEqualTo(other.getId()));
    }

    @Test
    void aFailedPublishLeavesTheRowForTheNextRun() {
        Tenant tenant = tenantRepository.findAll().getFirst();
        OutboxMessage message = outboxRepository.save(new OutboxMessage(
                UUID.randomUUID(), tenant.getId(), "event.ingested", "{\"broken\":true}"));

        message.markFailed("broker unavailable");
        outboxRepository.save(message);

        // Unpublished means it will be claimed again. Recording the error matters
        // because a message that keeps failing needs a reason attached, not just
        // a rising counter.
        OutboxMessage reloaded = outboxRepository.findById(message.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isNull();
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getLastError()).isEqualTo("broker unavailable");
    }

    @Test
    void everyIngestedEventEventuallyHasNoUnpublishedMessage() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(ingest(UUID.randomUUID())).andExpect(status().isAccepted());
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(eventRepository.count()).isEqualTo(5);
            assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();
        });
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder ingest(UUID id) {
        return post("/v1/events")
                .header("X-Api-Key", apiKey)
                .contentType(APPLICATION_JSON)
                .content(eventJson(id));
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
                  "payload": {"port": 22},
                  "occurredAt": "2026-08-05T10:00:00Z"
                }
                """.formatted(id);
    }
}
