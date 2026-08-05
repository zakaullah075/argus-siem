package com.argus.ingest;

import com.argus.ingest.dto.EventResponse;
import com.argus.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Events for humans. The agent-facing {@code /v1/events} authenticates with an
 * API key; a person in a browser has a JWT, and should not need to hold a
 * machine credential to look at their own data.
 */
@RestController
@RequestMapping("/v1/management/events")
public class EventAdminController {

    private final EventService eventService;

    public EventAdminController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public Page<EventResponse> list(
            @PageableDefault(size = 50, sort = "occurredAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return eventService.findForTenant(AuthenticatedUser.tenantId(), pageable);
    }
}
