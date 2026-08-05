package com.argus.apikey.dto;

/**
 * The only time the plaintext key exists outside the caller's own storage. Only
 * its hash is kept, so it can never be shown again.
 */
public record IssuedApiKeyResponse(String apiKey, String warning) {
}
