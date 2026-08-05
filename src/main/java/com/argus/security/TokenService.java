package com.argus.security;

import com.argus.user.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final Duration tokenTtl;

    public TokenService(JwtEncoder jwtEncoder,
                        @Value("${argus.jwt.ttl-seconds}") long ttlSeconds) {
        this.jwtEncoder = jwtEncoder;
        this.tokenTtl = Duration.ofSeconds(ttlSeconds);
    }

    public String issue(AppUser user) {
        Instant now = Instant.now();

        var claims = JwtClaimsSet.builder()
                .issuer("argus")
                .issuedAt(now)
                .expiresAt(now.plus(tokenTtl))
                .subject(user.getId().toString())
                // The tenant travels in the token, so no request can name a
                // tenant it has not authenticated against.
                .claim("tenantId", user.getTenantId().toString())
                .claim("role", user.getRole().name())
                .build();

        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
