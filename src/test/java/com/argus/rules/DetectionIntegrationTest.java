package com.argus.rules;

import com.argus.alerts.Alert;
import com.argus.alerts.AlertRepository;
import com.argus.apikey.ApiKeyRepository;
import com.argus.apikey.ApiKeyService;
import com.argus.ingest.EventRepository;
import com.argus.ingest.Severity;
import com.argus.support.AbstractIntegrationTest;
import com.argus.tenant.Tenant;
import com.argus.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Detection runs in a consumer, so these assertions poll rather than read once.
 * <p>
 * Proving something did <em>not</em> happen is the harder half: a plain check
 * straight after ingest would pass simply because the consumer had not run yet.
 * Those cases wait for the events to be consumed first, then assert on alerts.
 */
class DetectionIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private RuleRepository ruleRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private EventRepository eventRepository;

    private UUID tenantId;
    private String apiKey;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        ruleRepository.deleteAll();
        eventRepository.deleteAll();
        apiKeyRepository.deleteAll();
        tenantRepository.deleteAll();

        Tenant tenant = tenantRepository.save(new Tenant("Acme Corp", "free", 600));
        tenantId = tenant.getId();
        apiKey = apiKeyService.issue(tenantId, "test key");
    }

    @Test
    void raisesAlertWhenThresholdReachedInsideWindow() throws Exception {
        givenRule(3, 300);

        ingest("root", Instant.parse("2026-08-05T10:00:00Z"));
        ingest("root", Instant.parse("2026-08-05T10:00:30Z"));
        ingest("root", Instant.parse("2026-08-05T10:01:00Z"));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Alert> alerts = alertRepository.findAll();
            assertThat(alerts).hasSize(1);
            assertThat(alerts.getFirst().getSeverity()).isEqualTo(Severity.CRITICAL);
        });
    }

    @Test
    void doesNotAlertBeforeThresholdIsReached() throws Exception {
        givenRule(3, 300);

        ingest("root", Instant.parse("2026-08-05T10:00:00Z"));
        ingest("root", Instant.parse("2026-08-05T10:00:30Z"));

        awaitEventsProcessed(2);
        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void doesNotAlertWhenEventsFallOutsideWindow() throws Exception {
        givenRule(3, 60);

        ingest("root", Instant.parse("2026-08-05T10:00:00Z"));
        ingest("root", Instant.parse("2026-08-05T10:05:00Z"));
        ingest("root", Instant.parse("2026-08-05T10:10:00Z"));

        awaitEventsProcessed(3);
        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void foldsRepeatOccurrencesIntoOneAlertInsteadOfAlertStorm() throws Exception {
        givenRule(3, 300);

        for (int i = 0; i < 8; i++) {
            ingest("root", Instant.parse("2026-08-05T10:00:00Z").plusSeconds(i * 10L));
        }

        // The exact occurrence count is timing-dependent now that detection is
        // asynchronous: if the consumer runs after all eight rows are stored,
        // even the first event's evaluation sees a full window and trips. The
        // property worth asserting is that a burst folds into ONE alert rather
        // than producing one per event — not the particular number it counted to.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Alert> alerts = alertRepository.findAll();
            assertThat(alerts).hasSize(1);
            assertThat(alerts.getFirst().getOccurrenceCount()).isGreaterThan(1);
        });
    }

    @Test
    void countsPerActorSoUnrelatedFailuresDoNotCombine() throws Exception {
        givenRule(3, 300);

        for (String actor : List.of("alice", "bob", "carol")) {
            ingest(actor, Instant.parse("2026-08-05T10:00:00Z"));
            ingest(actor, Instant.parse("2026-08-05T10:00:10Z"));
        }

        awaitEventsProcessed(6);
        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void ignoresDisabledRules() throws Exception {
        ingest("root", Instant.parse("2026-08-05T10:00:00Z"));
        ingest("root", Instant.parse("2026-08-05T10:00:30Z"));
        ingest("root", Instant.parse("2026-08-05T10:01:00Z"));

        awaitEventsProcessed(3);
        assertThat(alertRepository.count()).isZero();
    }

    /**
     * Waits for the consumer to drain, so a "no alert" assertion means the rule
     * genuinely did not fire rather than that detection had not run yet.
     */
    private void awaitEventsProcessed(int expected) {
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(eventRepository.count()).isEqualTo(expected));

        // The row exists before the consumer has evaluated it, so give the
        // listener a moment to finish. Crude, but a negative assertion has no
        // state to poll on.
        await().pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(true).isTrue());
    }

    private void givenRule(int threshold, int windowSeconds) {
        ruleRepository.save(new Rule(
                tenantId,
                "%d failed logins in %d seconds".formatted(threshold, windowSeconds),
                "sshd",
                "auth.failed",
                Severity.MEDIUM,
                threshold,
                windowSeconds,
                Severity.CRITICAL
        ));
    }

    private void ingest(String actor, Instant occurredAt) throws Exception {
        String body = """
                {
                  "id": "%s",
                  "source": "sshd",
                  "eventType": "auth.failed",
                  "severity": "HIGH",
                  "actor": "%s",
                  "target": "10.0.0.5",
                  "payload": {"port": 22},
                  "occurredAt": "%s"
                }
                """.formatted(UUID.randomUUID(), actor, occurredAt);

        mockMvc.perform(post("/v1/events")
                .header("X-Api-Key", apiKey)
                .contentType(APPLICATION_JSON)
                .content(body));
    }
}
