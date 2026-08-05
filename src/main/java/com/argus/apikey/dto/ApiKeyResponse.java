package com.argus.apikey.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Deliberately omits the hash. It is not the plaintext key, but it is the
 * verifier — anything that leaks it lets an attacker confirm a guess offline.
 */
public record ApiKeyResponse(
        UUID id,
        String name,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt,
        boolean revoked
) {
}
