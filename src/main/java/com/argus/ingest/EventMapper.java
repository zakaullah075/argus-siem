package com.argus.ingest;

import com.argus.ingest.dto.EventResponse;
import com.argus.ingest.dto.IngestEventRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class EventMapper {

    /**
     * The caller supplies the id when it can, so a retry resends the same one and
     * the primary key rejects the duplicate. Agents that cannot generate one get
     * a server-side id and lose that guarantee.
     */
    public Event toEntity(UUID eventId, UUID tenantId, IngestEventRequest request) {
        return new Event(
                eventId,
                tenantId,
                request.source(),
                request.eventType(),
                request.severity(),
                request.actor(),
                request.target(),
                request.payload().toString(),
                request.occurredAt()
        );
    }

    public EventResponse toResponse(Event event) {
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
