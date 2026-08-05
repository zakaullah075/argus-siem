package com.argus.apikey;

import com.argus.common.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "argus_";

    private final ApiKeyRepository apiKeyRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Returns the plaintext key. It is never recoverable afterwards — only the
     * hash is stored — so the caller must show it to the user exactly once.
     */
    @Transactional
    public String issue(UUID tenantId, String name) {
        var randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String plaintext = KEY_PREFIX + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        apiKeyRepository.save(new ApiKey(tenantId, hash(plaintext), name));
        return plaintext;
    }

    @Transactional
    public UUID authenticate(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isBlank()) {
            throw new UnauthorizedException("Missing API key");
        }

        ApiKey key = apiKeyRepository.findByKeyHash(hash(plaintextKey))
                .orElseThrow(() -> new UnauthorizedException("Invalid API key"));

        if (key.isRevoked()) {
            throw new UnauthorizedException("Invalid API key");
        }

        key.markUsed();
        return key.getTenantId();
    }

    /**
     * Plain SHA-256 rather than BCrypt. API keys are 256 bits of random data, so
     * there is no dictionary to attack and no need for a slow hash — and this
     * runs on every request, where BCrypt's cost would be the bottleneck.
     * Passwords are the opposite case and must use BCrypt.
     */
    private String hash(String plaintext) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
