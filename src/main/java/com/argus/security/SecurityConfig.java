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

                        // The dashboard is a static page that authenticates from
                        // the browser; serving the HTML reveals nothing, since
                        // every call it makes still needs a token.
                        .requestMatchers("/", "/index.html", "/app.js", "/style.css",
                                "/favicon.ico").permitAll()

                        // The agent has to be fetchable before anyone has a key —
                        // it is the thing you run to get events flowing. It is
                        // also public source, so there is nothing to protect.
                        .requestMatchers("/agent/**").permitAll()

                        // The API description documents the contract, not the
                        // data. Every endpoint it lists still enforces its own
                        // authentication when called.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()

                        // Machine endpoints authenticate with an API key in a
                        // servlet filter that runs ahead of this chain, so they
                        // are already authenticated by the time they arrive.
                        .requestMatchers("/v1/events/**", "/v1/alerts/**").permitAll()

                        // The demo generator only exists when demo seeding is on,
                        // and writes only to the caller's own tenant. It needs a
                        // token, but not a write role — the point is to let a
                        // read-only visitor watch the pipeline run.
                        .requestMatchers("/v1/demo/**").authenticated()

                        // Alert triage is the analyst's job, so it is carved out
                        // ahead of the admin-only write rule. Matchers are
                        // evaluated in order and the first match wins, so a
                        // narrower rule placed after a broader one never applies.
                        .requestMatchers(HttpMethod.POST, "/v1/management/alerts/*/acknowledge")
                        .hasAnyRole("ADMIN", "ANALYST")
                        .requestMatchers(HttpMethod.POST, "/v1/management/alerts/*/resolve")
                        .hasAnyRole("ADMIN", "ANALYST")

                        // Other writes are admin-only; reads are open to any role,
                        // since VIEWER exists precisely to read without changing
                        // anything.
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
