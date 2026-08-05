package com.argus.security;

import com.argus.audit.AuditService;
import com.argus.common.ApiException;
import com.argus.ratelimit.RateLimiter;
import com.argus.tenant.Tenant;
import com.argus.tenant.TenantRepository;
import com.argus.user.AppUser;
import com.argus.user.AppUserRepository;
import com.argus.user.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class SignupService {

    private final TenantRepository tenantRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final RateLimiter rateLimiter;
    private final int signupsPerMinutePerIp;
    private final int defaultRateLimit;

    public SignupService(TenantRepository tenantRepository,
                         AppUserRepository userRepository,
                         PasswordEncoder passwordEncoder,
                         TokenService tokenService,
                         AuditService auditService,
                         RateLimiter rateLimiter,
                         @Value("${argus.signup.per-minute-per-ip:3}") int signupsPerMinutePerIp,
                         @Value("${argus.signup.tenant-rate-limit:600}") int defaultRateLimit) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
        this.signupsPerMinutePerIp = signupsPerMinutePerIp;
        this.defaultRateLimit = defaultRateLimit;
    }

    /**
     * Creates a tenant and its first administrator.
     * <p>
     * Signup is public, which makes it an abuse surface: each one costs a row in
     * every table and a share of a free-tier database. It is rate limited per
     * caller address, keyed through the same limiter used for ingest.
     */
    @Transactional
    public AuthenticationService.AuthenticatedSession signUp(String organisation,
                                                             String email,
                                                             String password,
                                                             String callerAddress) {
        throttle(callerAddress);

        String normalised = email.toLowerCase().trim();

        // Email is unique per tenant, not globally, so this cannot rely on the
        // database constraint alone — two organisations may legitimately share
        // an address, but one address must not create two accounts by accident.
        if (userRepository.findByEmail(normalised).isPresent()) {
            throw new SignupConflictException("An account already exists for that email");
        }

        Tenant tenant = tenantRepository.save(
                new Tenant(organisation.trim(), "free", defaultRateLimit));

        AppUser admin = userRepository.save(new AppUser(
                tenant.getId(), normalised, passwordEncoder.encode(password), Role.ADMIN));

        auditService.recordWithCaller(tenant.getId(), admin.getId(), "tenant.created", tenant.getName());

        return new AuthenticationService.AuthenticatedSession(
                tokenService.issue(admin), admin.getRole().name());
    }

    /**
     * Reuses the tenant rate limiter by deriving a stable UUID from the caller
     * address. Signups have no tenant yet, and standing up a second limiter for
     * one endpoint would be more machinery than the problem deserves.
     */
    private void throttle(String callerAddress) {
        UUID key = UUID.nameUUIDFromBytes(
                ("signup:" + callerAddress).getBytes(StandardCharsets.UTF_8));

        if (!rateLimiter.tryAcquire(key, signupsPerMinutePerIp)) {
            throw new SignupThrottledException(signupsPerMinutePerIp);
        }
    }

    public static class SignupConflictException extends ApiException {
        public SignupConflictException(String message) {
            super(HttpStatus.CONFLICT, message);
        }
    }

    public static class SignupThrottledException extends ApiException {
        public SignupThrottledException(int limit) {
            super(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many signups from this address; limit is %d per minute".formatted(limit));
        }
    }
}
