package com.argus.security;

import com.argus.common.UnauthorizedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Reads the caller's identity from the validated token rather than from request
 * parameters. Nothing a client sends can change which tenant it acts on.
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static UUID tenantId() {
        return UUID.fromString(claim("tenantId"));
    }

    public static UUID userId() {
        return UUID.fromString(jwt().getSubject());
    }

    private static String claim(String name) {
        String value = jwt().getClaimAsString(name);
        if (value == null) {
            throw new UnauthorizedException("Token missing claim: " + name);
        }
        return value;
    }

    private static Jwt jwt() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt token)) {
            throw new UnauthorizedException("No authenticated user");
        }
        return token;
    }
}
