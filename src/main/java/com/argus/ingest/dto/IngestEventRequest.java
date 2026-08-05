package com.argus.ingest.dto;

import com.argus.ingest.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * @param id supplied by the caller to make delivery idempotent. Optional: agents
 *           that cannot generate one get a server-side id and lose the guarantee.
 */
public record IngestEventRequest(

        UUID id,

        @NotBlank @Size(max = 120)
        String source,

        @NotBlank @Size(max = 120)
        String eventType,

        @NotNull
        Severity severity,

        @Size(max = 255)
        String actor,

        @Size(max = 255)
        String target,

        @NotNull
        JsonNode payload,

        @NotNull
        Instant occurredAt
) {
}
