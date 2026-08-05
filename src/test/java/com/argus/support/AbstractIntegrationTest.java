package com.argus.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Shared infrastructure for integration tests, using the singleton container
 * pattern: the containers are started once in a static initialiser and are
 * deliberately never stopped.
 * <p>
 * The obvious alternative — {@code @Testcontainers} with {@code @Container} —
 * ties container lifecycle to the test <em>class</em>, while Spring caches the
 * application context across classes. The second test class then reuses a
 * context pointing at a container the first class already shut down, and every
 * test in it fails with an empty connection pool. Ryuk still reaps these
 * containers when the JVM exits, so nothing leaks.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Plain image rather than -management: the management plugin loads five more
     * plugins and roughly doubles startup, and no test drives the admin UI. The
     * generous timeout is because RabbitMQ takes around 50 seconds to boot on a
     * constrained machine, which sits on the 60 second default and fails
     * intermittently rather than cleanly.
     */
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-alpine")
                    .asCompatibleSubstituteFor("rabbitmq"))
                    .withStartupTimeout(Duration.ofMinutes(4));

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
}
