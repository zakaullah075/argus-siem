package com.argus.security.dto;

/**
 * Returned by both login and signup — a caller ends up authenticated either way,
 * so the shape should not differ.
 */
public record SessionResponse(String token, String role) {
}
