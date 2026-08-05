package com.argus.ingest.dto;

import com.argus.ingest.Event;
import com.argus.ingest.Severity;

import java.time.Instant;
import java.util.UUID;

/**
 * The read model. Entities are never returned directly: doing so ties the public
 * API to the database schema, so a column rename becomes a breaking change for
 * every client.
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

    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getSource(),
                event.getEventType(),
                event.getSeverity(),
                event.getActor(),
                event.getTarget(),
                event.getOccurredAt(),
                event.getIngestedAt()
        );
    }
}
