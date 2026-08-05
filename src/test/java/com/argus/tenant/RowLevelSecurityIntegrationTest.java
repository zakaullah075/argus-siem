package com.argus.tenant;

import com.argus.common.Severity;
import com.argus.ingest.Event;
import com.argus.ingest.EventRepository;
import com.argus.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Row level security exists for the query someone forgets to scope. These tests
 * run deliberately unscoped SQL — no tenant predicate anywhere — which is
 * exactly the mistake the policy has to survive.
 * <p>
 * They connect as a dedicated non-superuser role, because <em>superusers and
 * BYPASSRLS roles ignore row level security entirely, and that cannot be turned
 * off</em>. FORCE only covers the table owner. Testing as the owner or an admin
 * shows policies that look correct and protect nothing — which is the usual way
 * this feature ships broken.
 */
class RowLevelSecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String APP_ROLE = "argus_app_test";
    private static final String APP_PASSWORD = "app-role-password";

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DataSource dataSource;

    private UUID tenantA;
    private UUID tenantB;
    private UUID eventOfA;

    @BeforeEach
    void setUp() throws SQLException {
        eventRepository.deleteAll();
        tenantRepository.deleteAll();

        tenantA = tenantRepository.save(new Tenant("A", "free", 600)).getId();
        tenantB = tenantRepository.save(new Tenant("B", "free", 600)).getId();

        eventOfA = UUID.randomUUID();
        eventRepository.save(event(eventOfA, tenantA));
        eventRepository.save(event(UUID.randomUUID(), tenantB));

        ensureAppRoleExists();
    }

    @Test
    void unscopedCountSeesOnlyTheCurrentTenant() throws SQLException {
        assertThat(countEventsAs(tenantA)).isEqualTo(1);
        assertThat(countEventsAs(tenantB)).isEqualTo(1);
    }

    @Test
    void unscopedSelectCannotReachAnotherTenantsRow() throws SQLException {
        try (Connection connection = connectAsAppRole(tenantB);
             PreparedStatement statement =
                     connection.prepareStatement("select count(*) from event where id = ?")) {

            // The id is correct and the row exists. It still must not be readable.
            statement.setObject(1, eventOfA);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }

    @Test
    void withNoTenantSetBackgroundWorkStillSeesEverything() throws SQLException {
        // The outbox relay and migrations run with no tenant and must keep
        // working, so the policy is permissive when the setting is absent.
        assertThat(countEventsAs(null)).isEqualTo(2);
    }

    @Test
    void aConnectionDoesNotCarryTheTenantOfThePreviousUser() throws SQLException {
        assertThat(countEventsAs(tenantA)).isEqualTo(1);
        assertThat(countEventsAs(null)).isEqualTo(2);
        assertThat(countEventsAs(tenantB)).isEqualTo(1);
    }

    @Test
    void writingForAnotherTenantIsRejected() throws SQLException {
        try (Connection connection = connectAsAppRole(tenantA)) {
            // with check: you cannot insert a row you would not be allowed to
            // read back. Without it, isolation would be read-only.
            assertThatThrownBy(() -> insertEvent(connection, tenantB))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    void writingForYourOwnTenantIsAllowed() throws SQLException {
        try (Connection connection = connectAsAppRole(tenantA)) {
            insertEvent(connection, tenantA);
        }

        assertThat(countEventsAs(tenantA)).isEqualTo(2);
    }

    @Test
    void everyTenantScopedTableHasAPolicy() throws SQLException {
        try (Connection connection = connectAsAppRole(tenantA);
             Statement statement = connection.createStatement()) {

            // A policy on one table is not isolation; every tenant-scoped table
            // needs one, or the gap is wherever nobody looked.
            for (String table : new String[]{"event", "alert", "rule", "api_key", "audit_log"}) {
                try (ResultSet rs = statement.executeQuery(
                        "select count(*) from pg_policies where tablename = '" + table + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1))
                            .describedAs("policy on %s", table)
                            .isGreaterThan(0);
                }
            }
        }
    }

    private long countEventsAs(UUID tenantId) throws SQLException {
        try (Connection connection = connectAsAppRole(tenantId);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select count(*) from event")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void insertEvent(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into event (id, tenant_id, source, event_type, severity,
                                   actor, target, raw_payload, occurred_at)
                values (?, ?, 'sshd', 'auth.failed', 'HIGH', 'root', '10.0.0.1',
                        '{}'::jsonb, now())
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenantId);
            statement.executeUpdate();
        }
    }

    private Connection connectAsAppRole(UUID tenantId) throws SQLException {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);

        try (PreparedStatement statement = connection.prepareStatement(
                "select set_config('argus.tenant_id', ?, false)")) {
            statement.setString(1, tenantId == null ? "" : tenantId.toString());
            statement.execute();
        }

        return connection;
    }

    /**
     * The migration creates a nologin role, which is right for production where
     * credentials come from the platform. The test needs to connect, so it grants
     * login here rather than weakening the migration.
     */
    private void ensureAppRoleExists() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("""
                    do $$
                    begin
                        if not exists (select 1 from pg_roles where rolname = '%s') then
                            create role %s login password '%s';
                        end if;
                    end $$
                    """.formatted(APP_ROLE, APP_ROLE, APP_PASSWORD));

            statement.execute("grant argus_app to " + APP_ROLE);
            statement.execute("grant usage on schema public to " + APP_ROLE);
            statement.execute(
                    "grant select, insert, update, delete on all tables in schema public to " + APP_ROLE);
        }
    }

    private Event event(UUID id, UUID tenantId) {
        return new Event(id, tenantId, "sshd", "auth.failed", Severity.HIGH,
                "root", "10.0.0.1", "{}", Instant.now());
    }
}
