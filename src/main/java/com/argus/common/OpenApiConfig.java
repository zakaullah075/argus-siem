package com.argus.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the two authentication schemes explicitly, because the generated
 * document cannot infer them: API keys are checked in a servlet filter that runs
 * ahead of the security chain, so nothing in the annotations reveals that
 * {@code /v1/events} needs a header rather than a bearer token.
 */
@Configuration
public class OpenApiConfig {

    private static final String API_KEY = "apiKey";
    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI argusOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Argus")
                        .version("0.1.0")
                        .description("""
                                Multi-tenant security event platform. Agents push events to \
                                an authenticated ingest API; Argus normalises them, evaluates \
                                detection rules against the stream, and raises deduplicated \
                                alerts.

                                Two authentication schemes. Machines use an API key \
                                (`X-Api-Key`) against `/v1/events` and `/v1/alerts`. People use \
                                a JWT from `/v1/auth/login` against `/v1/management/**`, where \
                                the role decides what is permitted: ADMIN manages everything, \
                                ANALYST works alerts, VIEWER reads."""))
                .components(new Components()
                        .addSecuritySchemes(API_KEY, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")
                                .description("Issued once from /v1/management/api-keys. "
                                        + "Only its hash is stored, so it cannot be recovered."))
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Returned by /v1/auth/login and /v1/auth/signup. "
                                        + "Valid for one hour and cannot be revoked early.")));
    }
}
