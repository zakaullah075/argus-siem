package com.argus.ingest.dto;

import java.util.UUID;

public record IngestEventResponse(UUID id, boolean duplicate) {
}
