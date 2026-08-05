package com.argus.apikey;

import com.argus.apikey.dto.ApiKeyResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyMapperTest {

    private final ApiKeyMapper mapper = new ApiKeyMapper();

    @Test
    void copiesEveryFieldThatIsSafeToExpose() {
        ApiKey key = new ApiKey(UUID.randomUUID(), "somehash", "web-server-1");

        ApiKeyResponse response = mapper.toResponse(key);

        assertThat(response.id()).isEqualTo(key.getId());
        assertThat(response.name()).isEqualTo("web-server-1");
        assertThat(response.createdAt()).isEqualTo(key.getCreatedAt());
        assertThat(response.revoked()).isFalse();
    }

    /**
     * The response has no hash field at all. This is the point of the mapper —
     * the hash is the verifier, and a response that carried it would let anyone
     * who saw the payload confirm a guessed key offline.
     */
    @Test
    void neverExposesTheHash() {
        ApiKey key = new ApiKey(UUID.randomUUID(), "secret-hash-value", "agent");

        assertThat(mapper.toResponse(key).toString()).doesNotContain("secret-hash-value");
    }

    @Test
    void reflectsRevocation() {
        ApiKey key = new ApiKey(UUID.randomUUID(), "hash", "agent");
        key.revoke();

        ApiKeyResponse response = mapper.toResponse(key);

        assertThat(response.revoked()).isTrue();
        assertThat(response.revokedAt()).isNotNull();
    }

    @Test
    void lastUsedIsNullUntilTheKeyAuthenticates() {
        ApiKey key = new ApiKey(UUID.randomUUID(), "hash", "agent");
        assertThat(mapper.toResponse(key).lastUsedAt()).isNull();

        key.markUsed();
        assertThat(mapper.toResponse(key).lastUsedAt()).isNotNull();
    }
}
