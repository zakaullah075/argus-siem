package com.argus.demo;

import com.argus.apikey.ApiKeyService;
import com.argus.common.Severity;
import com.argus.ingest.EventService;
import com.argus.ingest.dto.IngestEventRequest;
import com.argus.rules.Rule;
import com.argus.rules.RuleRepository;
import com.argus.tenant.Tenant;
import com.argus.tenant.TenantRepository;
import com.argus.user.AppUser;
import com.argus.user.AppUserRepository;
import com.argus.user.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Populates a demo tenant so the deployed instance shows something real.
 * <p>
 * Gated on {@code argus.demo.seed} and off by default, so it can never run
 * anywhere it is not explicitly asked for. The account it creates is read-only
 * by design: a public demo hands its credentials to strangers, so the worst a
 * visitor can do is look.
 */
@Component
@ConditionalOnProperty(name = "argus.demo.seed", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String DEMO_EMAIL = "demo@argus.dev";
    private static final String DEMO_PASSWORD = "demo1234";

    private final TenantRepository tenantRepository;
    private final AppUserRepository userRepository;
    private final RuleRepository ruleRepository;
    private final ApiKeyService apiKeyService;
    private final EventService eventService;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public DemoDataSeeder(TenantRepository tenantRepository,
                          AppUserRepository userRepository,
                          RuleRepository ruleRepository,
                          ApiKeyService apiKeyService,
                          EventService eventService,
                          PasswordEncoder passwordEncoder,
                          ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.ruleRepository = ruleRepository;
        this.apiKeyService = apiKeyService;
        this.eventService = eventService;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Idempotent: the seeder runs on every boot, and a restart must not
        // duplicate the tenant or fail on the unique email index.
        if (userRepository.findByEmail(DEMO_EMAIL).isPresent()) {
            log.info("Demo data already present, skipping seed");
            return;
        }

        Tenant tenant = tenantRepository.save(new Tenant("Acme Corp", "demo", 600));

        userRepository.save(new AppUser(tenant.getId(), DEMO_EMAIL,
                passwordEncoder.encode(DEMO_PASSWORD), Role.VIEWER));

        ruleRepository.save(new Rule(tenant.getId(),
                "SSH brute force: 5 failures in 5 minutes",
                "sshd", "auth.failed", Severity.MEDIUM, 5, 300, Severity.CRITICAL));

        ruleRepository.save(new Rule(tenant.getId(),
                "Privilege escalation attempt",
                "sudo", "privilege.escalation", Severity.HIGH, 1, 60, Severity.CRITICAL));

        seedEvents(tenant.getId());

        log.info("Seeded demo tenant {} with login {}", tenant.getId(), DEMO_EMAIL);
    }

    private void seedEvents(UUID tenantId) {
        Instant base = Instant.now().minusSeconds(600);

        // Six failures against one account, inside the window, so the brute
        // force rule fires and the alert shows a real occurrence count.
        for (int i = 0; i < 6; i++) {
            ingest(tenantId, "sshd", "auth.failed", Severity.HIGH, "root",
                    "10.0.0.5", base.plusSeconds(i * 20L),
                    """
                    {"port":22,"protocol":"ssh2","attempt":%d}""".formatted(i + 1));
        }

        // A single escalation, which trips the threshold-1 rule immediately.
        ingest(tenantId, "sudo", "privilege.escalation", Severity.CRITICAL, "www-data",
                "/bin/bash", base.plusSeconds(200),
                """
                {"command":"/bin/bash","tty":"pts/0"}""");

        // Unrelated noise, so the dashboard is not only alerting events.
        ingest(tenantId, "nginx", "http.forbidden", Severity.LOW, "203.0.113.44",
                "/admin", base.plusSeconds(60), """
                {"status":403,"path":"/admin"}""");

        ingest(tenantId, "auditd", "file.modified", Severity.MEDIUM, "deploy",
                "/etc/ssh/sshd_config", base.plusSeconds(120), """
                {"action":"write","pid":4412}""");
    }

    private void ingest(UUID tenantId, String source, String type, Severity severity,
                        String actor, String target, Instant at, String payload) {
        try {
            eventService.ingest(tenantId, new IngestEventRequest(
                    UUID.randomUUID(), source, type, severity, actor, target,
                    objectMapper.readTree(payload), at));
        } catch (Exception e) {
            // Seeding must never stop the application from starting.
            log.warn("Failed to seed demo event {}/{}", source, type, e);
        }
    }
}
