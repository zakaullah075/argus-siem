package com.argus.tenant.context;

import java.util.Optional;
import java.util.UUID;

/**
 * The tenant for the current request, held for the life of the thread.
 * <p>
 * A ThreadLocal because the value has to reach the JDBC layer without being
 * threaded through every method signature between the filter and the query.
 * The cost is that it must be cleared in a finally block: a pooled thread that
 * keeps a stale tenant would serve one customer's data to the next request.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static Optional<UUID> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }
}
