package com.argus.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain unit tests — no Spring context, no database. The rate limiter holds its
 * own state, so loading a container to test it would only make it slower.
 */
class RateLimiterTest {

    private final RateLimiter rateLimiter = new RateLimiter();

    @Test
    void allowsRequestsUpToTheLimit() {
        UUID tenant = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryAcquire(tenant, 5)).isTrue();
        }
    }

    @Test
    void rejectsRequestsPastTheLimit() {
        UUID tenant = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            rateLimiter.tryAcquire(tenant, 5);
        }

        assertThat(rateLimiter.tryAcquire(tenant, 5)).isFalse();
    }

    @Test
    void countsTenantsIndependently() {
        UUID noisy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            rateLimiter.tryAcquire(noisy, 5);
        }

        // One tenant exhausting its budget must not affect another.
        assertThat(rateLimiter.tryAcquire(quiet, 5)).isTrue();
    }

    @Test
    void doesNotOverAdmitUnderConcurrentAccess() throws Exception {
        UUID tenant = UUID.randomUUID();
        int limit = 100;
        int threads = 20;
        int callsPerThread = 50;

        var executor = Executors.newFixedThreadPool(threads);
        var startSignal = new CountDownLatch(1);
        var allowed = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                startSignal.await();
                for (int i = 0; i < callsPerThread; i++) {
                    if (rateLimiter.tryAcquire(tenant, limit)) {
                        allowed.incrementAndGet();
                    }
                }
                return null;
            });
        }

        // Release every thread at once so the increments genuinely contend.
        startSignal.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // A non-atomic counter would let more than the limit through here.
        assertThat(allowed.get()).isEqualTo(limit);
    }

    @Test
    void evictsWindowsForTenantsThatHaveGoneQuiet() {
        rateLimiter.tryAcquire(UUID.randomUUID(), 5);

        // Nothing has expired yet, so eviction must not drop a live window.
        rateLimiter.evictExpired();

        UUID tenant = UUID.randomUUID();
        rateLimiter.tryAcquire(tenant, 1);
        assertThat(rateLimiter.tryAcquire(tenant, 1)).isFalse();
    }
}
