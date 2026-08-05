package com.argus.ingest;

import com.argus.apikey.ApiKeyAuthFilter;
import com.argus.ingest.dto.EventResponse;
import com.argus.ingest.dto.IngestEventRequest;
import com.argus.ingest.dto.IngestEventResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    /**
     * 202 rather than 201: the event is accepted and durable, but rule evaluation
     * happens after the response. Agents shipping thousands of events per second
     * must not wait for it.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestEventResponse ingest(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE) UUID tenantId,
            @Valid @RequestBody IngestEventRequest request) {

        return eventService.ingest(tenantId, request);
    }

    @GetMapping
    public Page<EventResponse> list(
            @RequestAttribute(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE) UUID tenantId,
            @PageableDefault(size = 50, sort = "occurredAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return eventService.findForTenant(tenantId, pageable);
    }
}
