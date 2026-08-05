package com.argus.common;

import java.util.UUID;

/**
 * Carries only the event id, not the event itself.
 * <p>
 * The row is already durably stored by the time this is published, so the
 * consumer reads the canonical record rather than a copy that could disagree
 * with it. It also keeps messages small and avoids versioning a payload schema
 * across the queue. The cost is one extra read in the consumer.
 */
public record EventIngested(UUID eventId, UUID tenantId) {
}
