package com.argus.ingest;

import com.argus.ingest.dto.EventResponse;
import com.argus.rules.DetectionService;
import com.argus.ingest.dto.IngestEventRequest;
import com.argus.ingest.dto.IngestEventResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final DetectionService detectionService;

    public EventService(EventRepository eventRepository, DetectionService detectionService) {
        this.eventRepository = eventRepository;
        this.detectionService = detectionService;
    }

    @Transactional
    public IngestEventResponse ingest(UUID tenantId, IngestEventRequest request) {
        UUID eventId = request.id() != null ? request.id() : UUID.randomUUID();

        // Agents retry on timeout, so the same event arrives more than once. The
        // second delivery is acknowledged without writing a second row.
        //
        // This read-then-write is not safe against two concurrent deliveries of
        // the same id — the unique primary key still prevents a duplicate row,
        // but one request would fail instead of being acknowledged. Handling
        // that properly needs an upsert; noted rather than solved for now.
        if (eventRepository.existsById(eventId)) {
            return new IngestEventResponse(eventId, true);
        }

        var event = new Event(
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

        eventRepository.save(event);

        // Synchronous for now, so the caller waits for rule evaluation. That is
        // acceptable while rules are few, and is the first thing to move off the
        // request path when ingest volume grows.
        detectionService.evaluate(event);

        return new IngestEventResponse(eventId, false);
    }

    @Transactional(readOnly = true)
    public Page<EventResponse> findForTenant(UUID tenantId, Pageable pageable) {
        return eventRepository.findByTenantId(tenantId, pageable)
                .map(EventResponse::from);
    }
}
