package com.argus.rules;

import com.argus.alerts.Alert;
import com.argus.alerts.AlertRepository;
import com.argus.apikey.ApiKeyRepository;
import com.argus.apikey.ApiKeyService;
import com.argus.ingest.EventRepository;
import com.argus.ingest.Severity;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DetectionIntegrationTest {

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
    void doesNotAlertBeforeThresholdIsReached() throws Exception {
        givenRule("3 failed logins in 5 minutes", 3, 300);

        ingest("root", Instant.parse("2026-08-05T10:00:00Z"));
        ingest("root", Instant.parse("2026-08-05T10:00:30Z"));

        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void raisesAlertWhenThresholdReachedInsideWindow() throws Exception {
        givenRule("3 failed logins in 5 minutes", 3, 300);

        ingest("root", Instant.parse("2026-08-05T10:00:00Z"));
        ingest("root", Instant.parse("2026-08-05T10:00:30Z"));
        ingest("root", Instant.parse("2026-08-05T10:01:00Z"));

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(alerts.getFirst().getOccurrenceCount()).isEqualTo(1);
    }

    @Test
    void doesNotAlertWhenEventsFallOutsideWindow() throws Exception {
        givenRule("3 failed logins in 60 seconds", 3, 60);

        ingest("root", Instant.parse("2026-08-05T10:00:00Z"));
        ingest("root", Instant.parse("2026-08-05T10:05:00Z"));
        ingest("root", Instant.parse("2026-08-05T10:10:00Z"));

        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void foldsRepeatOccurrencesIntoOneAlertInsteadOfAlertStorm() throws Exception {
        givenRule("3 failed logins in 5 minutes", 3, 300);

        // Every event past the third also trips the rule. Without deduplication
        // this burst would produce one alert per event.
        for (int i = 0; i < 8; i++) {
            ingest("root", Instant.parse("2026-08-05T10:00:00Z").plusSeconds(i * 10L));
        }

        List<Alert> alerts = alertRepository.findAll();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst().getOccurrenceCount()).isEqualTo(6);
    }

    @Test
    void countsPerActorSoUnrelatedFailuresDoNotCombine() throws Exception {
        givenRule("3 failed logins in 5 minutes", 3, 300);

        // Three different accounts failing twice each is not a brute-force attack
        // on any one of them.
        for (String actor : List.of("alice", "bob", "carol")) {
            ingest(actor, Instant.parse("2026-08-05T10:00:00Z"));
            ingest(actor, Instant.parse("2026-08-05T10:00:10Z"));
        }

        assertThat(eventRepository.count()).isEqualTo(6);
        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void ignoresDisabledRules() throws Exception {
        Rule rule = givenRule("3 failed logins in 5 minutes", 3, 300);
        ruleRepository.findById(rule.getId()).ifPresent(r -> {
            ruleRepository.delete(r);
            ruleRepository.flush();
        });

        ingest("root", Instant.parse("2026-08-05T10:00:00Z"));
        ingest("root", Instant.parse("2026-08-05T10:00:30Z"));
        ingest("root", Instant.parse("2026-08-05T10:01:00Z"));

        assertThat(alertRepository.count()).isZero();
    }

    private Rule givenRule(String name, int threshold, int windowSeconds) {
        return ruleRepository.save(new Rule(
                tenantId,
                name,
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
