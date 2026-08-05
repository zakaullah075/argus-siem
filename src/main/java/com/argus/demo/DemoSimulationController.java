package com.argus.demo;

import com.argus.common.Severity;
import com.argus.ingest.EventService;
import com.argus.ingest.dto.IngestEventRequest;
import com.argus.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates live traffic so the demo shows the pipeline working rather than a
 * table of rows that were seeded once and never move.
 * <p>
 * Exists only when {@code argus.demo.seed} is on, so it cannot appear in a real
 * deployment. It writes events for the caller's own tenant — the same path an
 * agent uses — so what a visitor triggers is genuinely the production flow:
 * persist, publish after commit, consume, evaluate, fold into an alert.
 */
@RestController
@RequestMapping("/v1/demo")
@ConditionalOnProperty(name = "argus.demo.seed", havingValue = "true")
public class DemoSimulationController {

    private static final List<String> ACCOUNTS =
            List.of("root", "admin", "deploy", "postgres", "jenkins", "backup");

    private final EventService eventService;
    private final ObjectMapper objectMapper;

    public DemoSimulationController(EventService eventService, ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    /**
     * Six failed logins against one account inside the rule's window, which is
     * one over the threshold. The first five build toward it and the sixth
     * confirms folding — the alert count rises rather than a second alert
     * appearing.
     */
    @PostMapping("/simulate/brute-force")
    public SimulationResult bruteForce() {
        UUID tenantId = AuthenticatedUser.tenantId();
        String account = ACCOUNTS.get(ThreadLocalRandom.current().nextInt(ACCOUNTS.size()));
        String sourceIp = "203.0.113." + ThreadLocalRandom.current().nextInt(2, 250);

        Instant now = Instant.now();
        for (int i = 0; i < 6; i++) {
            ingest("sshd", "auth.failed", Severity.HIGH, account, sourceIp,
                    now.plusMillis(i * 50L), """
                    {"port":22,"protocol":"ssh2","attempt":%d,"sourceIp":"%s"}"""
                            .formatted(i + 1, sourceIp), tenantId);
        }

        return new SimulationResult(6,
                "6 failed logins for '%s' from %s".formatted(account, sourceIp),
                "Watch the alert count rise rather than a second alert appearing.");
    }

    @PostMapping("/simulate/escalation")
    public SimulationResult escalation() {
        UUID tenantId = AuthenticatedUser.tenantId();
        String account = ACCOUNTS.get(ThreadLocalRandom.current().nextInt(ACCOUNTS.size()));

        ingest("sudo", "privilege.escalation", Severity.CRITICAL, account, "/bin/bash",
                Instant.now(), """
                {"command":"/bin/bash","tty":"pts/0","cwd":"/tmp"}""", tenantId);

        return new SimulationResult(1,
                "Privilege escalation by '%s'".formatted(account),
                "Threshold is 1, so this alerts on the first event.");
    }

    /**
     * Traffic that matches no rule, so the dashboard shows that not every event
     * is an alert — which is the difference between a detection engine and a
     * log viewer.
     */
    @PostMapping("/simulate/noise")
    public SimulationResult noise() {
        UUID tenantId = AuthenticatedUser.tenantId();
        Instant now = Instant.now();

        ingest("nginx", "http.forbidden", Severity.LOW,
                "198.51.100." + ThreadLocalRandom.current().nextInt(2, 250), "/admin",
                now, """
                {"status":403,"path":"/admin","method":"GET"}""", tenantId);

        ingest("auditd", "file.modified", Severity.MEDIUM, "deploy",
                "/etc/nginx/nginx.conf", now.plusMillis(40), """
                {"action":"write","pid":%d}""".formatted(
                        ThreadLocalRandom.current().nextInt(1000, 9999)), tenantId);

        return new SimulationResult(2,
                "Routine activity, matching no rule",
                "Events are recorded; no alert is raised.");
    }

    private void ingest(String source, String type, Severity severity, String actor,
                        String target, Instant at, String payload, UUID tenantId) {
        try {
            eventService.ingest(tenantId, new IngestEventRequest(
                    UUID.randomUUID(), source, type, severity, actor, target,
                    objectMapper.readTree(payload), at));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate demo event", e);
        }
    }

    public record SimulationResult(int eventsSent, String summary, String hint) {
    }
}
