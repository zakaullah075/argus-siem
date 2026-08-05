package com.argus.tenant.context;

import com.argus.apikey.ApiKeyAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Publishes the authenticated tenant so the database layer can enforce isolation.
 * <p>
 * The value always comes from a verified credential — the API key filter's
 * resolved tenant, or the JWT claim — never from anything the caller can choose.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        try {
            resolveTenant(request).ifPresent(TenantContext::set);
            filterChain.doFilter(request, response);
        } finally {
            // Threads are pooled. Leaving a tenant behind would hand this
            // customer's scope to whoever the thread serves next.
            TenantContext.clear();
        }
    }

    private java.util.Optional<UUID> resolveTenant(HttpServletRequest request) {
        Object fromApiKey = request.getAttribute(ApiKeyAuthFilter.TENANT_ID_ATTRIBUTE);
        if (fromApiKey instanceof UUID tenantId) {
            return java.util.Optional.of(tenantId);
        }

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt token) {
            String claim = token.getClaimAsString("tenantId");
            if (claim != null) {
                return java.util.Optional.of(UUID.fromString(claim));
            }
        }

        return java.util.Optional.empty();
    }
}
