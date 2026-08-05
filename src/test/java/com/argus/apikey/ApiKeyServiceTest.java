package com.argus.apikey;

import com.argus.common.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @InjectMocks
    private ApiKeyService apiKeyService;

    @Test
    void issuedKeyIsPrefixedAndLongEnoughToResistGuessing() {
        String key = apiKeyService.issue(TENANT, "agent");

        assertThat(key).startsWith("argus_");
        // 32 random bytes, base64url encoded without padding.
        assertThat(key).hasSizeGreaterThan(40);
    }

    @Test
    void issuedKeysAreUnique() {
        String first = apiKeyService.issue(TENANT, "agent-1");
        String second = apiKeyService.issue(TENANT, "agent-2");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void storesOnlyTheHashNeverThePlaintext() {
        String plaintext = apiKeyService.issue(TENANT, "agent");

        var saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(saved.capture());

        assertThat(saved.getValue().getKeyHash()).isNotEqualTo(plaintext);
        assertThat(saved.getValue().getKeyHash()).doesNotContain(plaintext);
        // Hex-encoded SHA-256.
        assertThat(saved.getValue().getKeyHash()).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void rejectsNullKey() {
        assertThatThrownBy(() -> apiKeyService.authenticate(null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> apiKeyService.authenticate("   "))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsUnknownKey() {
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.authenticate("argus_nope"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid API key");
    }

    @Test
    void rejectsRevokedKeyWithTheSameMessageAsAnUnknownOne() {
        ApiKey revoked = new ApiKey(TENANT, "hash", "old agent");
        revoked.revoke();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(revoked));

        // A distinct message would tell an attacker their guessed key was once
        // real, which narrows the search.
        assertThatThrownBy(() -> apiKeyService.authenticate("argus_revoked"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid API key");
    }

    @Test
    void returnsTenantAndRecordsUsageForValidKey() {
        ApiKey key = new ApiKey(TENANT, "hash", "agent");
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(key));

        UUID tenantId = apiKeyService.authenticate("argus_valid");

        assertThat(tenantId).isEqualTo(TENANT);
        assertThat(key.getLastUsedAt()).isNotNull();
    }

    @Test
    void hashesDeterministicallySoTheSameKeyResolvesEveryTime() {
        ApiKey key = new ApiKey(TENANT, "hash", "agent");
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.of(key));

        apiKeyService.authenticate("argus_same");
        apiKeyService.authenticate("argus_same");

        var hashes = ArgumentCaptor.forClass(String.class);
        verify(apiKeyRepository, org.mockito.Mockito.times(2))
                .findByKeyHash(hashes.capture());

        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
    }

    @Test
    void differentKeysHashDifferently() {
        when(apiKeyRepository.findByKeyHash(anyString()))
                .thenReturn(Optional.of(new ApiKey(TENANT, "hash", "agent")));

        apiKeyService.authenticate("argus_one");
        apiKeyService.authenticate("argus_two");

        var hashes = ArgumentCaptor.forClass(String.class);
        verify(apiKeyRepository, org.mockito.Mockito.times(2))
                .findByKeyHash(hashes.capture());

        assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
    }

    @Test
    void issuedKeyIsScopedToTheRequestedTenant() {
        apiKeyService.issue(TENANT, "agent");

        var saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void newKeyIsNotRevoked() {
        assertThat(new ApiKey(TENANT, "hash", "agent").isRevoked()).isFalse();
    }
}
