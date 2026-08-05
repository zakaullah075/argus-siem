package com.argus.ratelimit;

import com.argus.tenant.TenantRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches each tenant's configured limit, because reading it from the database on
 * every request would cost more than the limiting saves.
 * <p>
 * There is no invalidation: changing a tenant's limit takes effect on restart.
 * That is a real limitation, acceptable only because limits change rarely and
 * this runs as a single instance. A shared cache with a TTL is the fix, and it
 * arrives at the same time as the Redis-backed counter.
 */
@Component
public class TenantLimits {

    private static final int DEFAULT_LIMIT = 600;

    private final TenantRepository tenantRepository;
    private final Map<UUID, Integer> cache = new ConcurrentHashMap<>();

    public TenantLimits(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public int perMinuteFor(UUID tenantId) {
        return cache.computeIfAbsent(tenantId, id -> tenantRepository.findById(id)
                .map(tenant -> tenant.getRateLimitPerMinute())
                .orElse(DEFAULT_LIMIT));
    }
}
