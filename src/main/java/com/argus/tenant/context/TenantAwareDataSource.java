package com.argus.tenant.context;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Applies the request's tenant to every connection handed out, so Postgres row
 * level security can enforce isolation the application would otherwise be
 * trusted to remember.
 * <p>
 * Implemented as a BeanPostProcessor that wraps whatever DataSource Spring Boot
 * built. Declaring a replacement bean instead means re-implementing the auto
 * configuration — property binding, pool settings, the Testcontainers service
 * connection — and getting one of them subtly wrong. That was the first attempt,
 * and Flyway failed to start because the delegate never received the JDBC url.
 * <p>
 * The tenant is set on the connection rather than passed per query, because the
 * policy reads a session setting and because the whole point is to cover queries
 * nobody remembered to scope — including ones written later by someone who has
 * never read this class.
 */
@Configuration
public class TenantAwareDataSource implements BeanPostProcessor {

    private final boolean enabled;

    public TenantAwareDataSource(@Value("${argus.rls.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!enabled || !(bean instanceof DataSource dataSource) || bean instanceof TenantScoped) {
            return bean;
        }
        return new TenantScoped(dataSource);
    }

    /**
     * Marker subclass so the post processor never wraps its own output — a bean
     * can pass through post processing more than once.
     */
    static final class TenantScoped extends DelegatingDataSource {

        TenantScoped(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return applyTenant(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return applyTenant(super.getConnection(username, password));
        }

        /**
         * is_local=false because Hikari hands out connections that may be used
         * outside an explicit transaction. The value is rewritten on every
         * checkout and cleared when no tenant is present, so a pooled connection
         * cannot carry one request's tenant into the next.
         */
        private static Connection applyTenant(Connection connection) throws SQLException {
            String tenantId = TenantContext.get().map(Object::toString).orElse("");

            try (PreparedStatement statement = connection.prepareStatement(
                    "select set_config('argus.tenant_id', ?, false)")) {
                statement.setString(1, tenantId);
                statement.execute();
            }

            return connection;
        }
    }
}
