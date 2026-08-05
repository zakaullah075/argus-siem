package com.argus.security;

import com.argus.security.dto.LoginRequest;
import com.argus.security.dto.SessionResponse;
import com.argus.security.dto.SignupRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final SignupService signupService;

    public AuthController(AuthenticationService authenticationService,
                          SignupService signupService) {
        this.authenticationService = authenticationService;
        this.signupService = signupService;
    }

    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest request) {
        return authenticationService.login(request.email(), request.password());
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse signUp(@Valid @RequestBody SignupRequest request,
                                  HttpServletRequest httpRequest) {

        return signupService.signUp(request.organisation(), request.email(),
                request.password(), callerAddress(httpRequest));
    }

    /**
     * Behind a proxy the socket address is the load balancer, so every signup
     * would share one rate limit bucket. The forwarded header is the real client
     * — spoofable in general, but the proxy overwrites it, and being wrong here
     * costs a slightly wrong throttle rather than a security hole.
     */
    private String callerAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
