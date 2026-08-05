package com.argus.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-tenant request limiting using a fixed window.
 * <p>
 * State is in memory, so the limit applies per instance: two instances behind a
 * load balancer allow roughly twice the configured rate. That is acceptable
 * while this runs as a single instance, and the interface is deliberately narrow
 * so the counter can move to Redis without touching callers.
 * <p>
 * A fixed window also allows a burst across a boundary — the last second of one
 * window plus the first of the next can pass 2x the limit. A sliding window or
 * token bucket fixes that at the cost of more state per tenant.
 */
@Component
public class RateLimiter {

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(UUID tenantId, int limitPerMinute) {
        Instant now = Instant.now();

        Window window = windows.compute(tenantId, (id, existing) -> {
            if (existing == null || existing.hasExpired(now)) {
                return new Window(now);
            }
            return existing;
        });

        return window.count.incrementAndGet() <= limitPerMinute;
    }

    /**
     * Drops windows for tenants that have gone quiet. Without this the map grows
     * with every tenant ever seen and never shrinks — a slow leak that only
     * shows up in long-running processes.
     */
    public void evictExpired() {
        Instant now = Instant.now();
        windows.entrySet().removeIf(entry -> entry.getValue().hasExpired(now));
    }

    private static final class Window {

        private static final Duration LENGTH = Duration.ofMinutes(1);

        private final Instant startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private Window(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private boolean hasExpired(Instant now) {
            return now.isAfter(startedAt.plus(LENGTH));
        }
    }
}
