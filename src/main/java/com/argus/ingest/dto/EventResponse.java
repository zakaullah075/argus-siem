package com.argus.ingest.dto;

import com.argus.common.Severity;

import java.time.Instant;
import java.util.UUID;

/**
 * The read model. Entities never leave the service layer: returning one ties the
 * public API to the database schema, so a column rename breaks every client.
 */
public record EventResponse(
        UUID id,
        String source,
        String eventType,
        Severity severity,
        String actor,
        String target,
        Instant occurredAt,
        Instant ingestedAt
) {
}
