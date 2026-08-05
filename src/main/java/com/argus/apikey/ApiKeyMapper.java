package com.argus.apikey;

import com.argus.apikey.dto.ApiKeyResponse;
import org.springframework.stereotype.Component;

/**
 * Entity to response. Kept out of the DTO so the outward-facing type does not
 * import the persistence type — a response should be movable without dragging
 * the entity along.
 */
@Component
public class ApiKeyMapper {

    public ApiKeyResponse toResponse(ApiKey key) {
        return new ApiKeyResponse(
                key.getId(),
                key.getName(),
                key.getCreatedAt(),
                key.getLastUsedAt(),
                key.getRevokedAt(),
                key.isRevoked()
        );
    }
}
