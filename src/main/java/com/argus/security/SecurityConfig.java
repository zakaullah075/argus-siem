package com.argus.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final String jwtSecret;

    public SecurityConfig(@Value("${argus.jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // No cookies and no server-side session, so there is no ambient
                // authority for a forged cross-site request to ride on. CSRF
                // protection defends against exactly that, and disabling it here
                // is correct rather than a shortcut — a bearer token must be
                // attached deliberately by the caller.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()

                        // Machine endpoints authenticate with an API key in a
                        // servlet filter that runs ahead of this chain, so they
                        // are already authenticated by the time they arrive.
                        .requestMatchers("/v1/events/**", "/v1/alerts/**").permitAll()

                        // Writes are admin-only; reads are open to any role, since
                        // VIEWER exists precisely to read without changing anything.
                        // Matchers are evaluated in order, so the write rules must
                        // come first or the read rule would swallow them.
                        .requestMatchers(HttpMethod.POST, "/v1/management/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/v1/management/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/v1/management/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/v1/management/**")
                        .hasAnyRole("ADMIN", "ANALYST", "VIEWER")

                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(authoritiesConverter())));

        return http.build();
    }

    /**
     * Maps the token's {@code role} claim onto a Spring Security authority.
     * The ROLE_ prefix is what hasRole("ADMIN") looks for.
     */
    private JwtAuthenticationConverter authoritiesConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("ROLE_");
        authorities.setAuthoritiesClaimName("role");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private SecretKeySpec secretKey() {
        return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey()).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
