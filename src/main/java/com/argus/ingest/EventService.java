package com.argus.ingest;

import com.argus.common.RateLimitExceededException;
import com.argus.ingest.dto.EventResponse;
import com.argus.common.EventIngested;
import com.argus.ratelimit.RateLimiter;
import com.argus.ratelimit.TenantLimits;
import org.springframework.context.ApplicationEventPublisher;
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
    private final EventMapper eventMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final RateLimiter rateLimiter;
    private final TenantLimits tenantLimits;

    public EventService(EventRepository eventRepository,
                        EventMapper eventMapper,
                        ApplicationEventPublisher applicationEventPublisher,
                        RateLimiter rateLimiter,
                        TenantLimits tenantLimits) {
        this.eventRepository = eventRepository;
        this.eventMapper = eventMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.rateLimiter = rateLimiter;
        this.tenantLimits = tenantLimits;
    }

    @Transactional
    public IngestEventResponse ingest(UUID tenantId, IngestEventRequest request) {
        int limit = tenantLimits.perMinuteFor(tenantId);
        if (!rateLimiter.tryAcquire(tenantId, limit)) {
            throw new RateLimitExceededException(limit);
        }

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

        var event = eventMapper.toEntity(eventId, tenantId, request);

        eventRepository.save(event);

        // Detection now happens in a consumer. The listener publishes only after
        // this transaction commits, so a rollback cannot announce an event that
        // was never stored.
        applicationEventPublisher.publishEvent(new EventIngested(eventId, tenantId));

        return new IngestEventResponse(eventId, false);
    }

    @Transactional(readOnly = true)
    public Page<EventResponse> findForTenant(UUID tenantId, Pageable pageable) {
        return eventRepository.findByTenantId(tenantId, pageable)
                .map(eventMapper::toResponse);
    }
}
