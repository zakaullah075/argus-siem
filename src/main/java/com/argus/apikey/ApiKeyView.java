package com.argus.apikey;

import java.time.Instant;
import java.util.UUID;

/**
 * Deliberately omits the hash. It is not the plaintext key, but it is still the
 * verifier — anything that leaks it hands an attacker the ability to confirm a
 * guessed key offline.
 */
public record ApiKeyView(
        UUID id,
        String name,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt,
        boolean revoked
) {

    public static ApiKeyView from(ApiKey key) {
        return new ApiKeyView(
                key.getId(),
                key.getName(),
                key.getCreatedAt(),
                key.getLastUsedAt(),
                key.getRevokedAt(),
                key.isRevoked()
        );
    }
}
