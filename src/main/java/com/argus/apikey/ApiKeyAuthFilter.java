package com.argus.apikey;

import com.argus.apikey.exception.InvalidApiKeyException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Authenticates ingest requests and puts the resolved tenant on the request.
 * <p>
 * Downstream code reads the tenant id from the request attribute rather than
 * trusting anything in the body — a caller must never be able to write events
 * into another tenant by naming it.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String TENANT_ID_ATTRIBUTE = "argus.tenantId";

    private static final String HEADER = "X-Api-Key";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * Only the machine-facing endpoints authenticate with an API key. Human
     * endpoints (/v1/auth, /v1/management) use JWTs and must not be intercepted
     * here — an earlier version matched all of /v1/ and made login unreachable.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/v1/events") || path.startsWith("/v1/alerts"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            UUID tenantId = apiKeyService.authenticate(request.getHeader(HEADER));
            request.setAttribute(TENANT_ID_ATTRIBUTE, tenantId);
        } catch (InvalidApiKeyException e) {
            // Filters run before @RestControllerAdvice, so this one writes its
            // own response rather than letting the exception escape the chain.
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Unauthorized","status":401,"detail":"%s"}"""
                    .formatted(e.getMessage()));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
