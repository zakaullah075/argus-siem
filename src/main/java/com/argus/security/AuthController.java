package com.argus.security;

import com.argus.security.AuthenticationService.AuthenticatedSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    public AuthenticatedSession login(@Valid @RequestBody LoginRequest request) {
        return authenticationService.login(request.email(), request.password());
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthenticatedSession signUp(@Valid @RequestBody SignupRequest request,
                                       HttpServletRequest httpRequest) {

        return signupService.signUp(request.organisation(), request.email(),
                request.password(), callerAddress(httpRequest));
    }

    /**
     * Behind Render's proxy the socket address is the load balancer, so every
     * signup would share one rate limit bucket. The forwarded header is the real
     * client — spoofable in general, but the proxy overwrites it, and the cost of
     * being wrong here is a slightly wrong throttle rather than a security hole.
     */
    private String callerAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record SignupRequest(
            @NotBlank @Size(max = 120) String organisation,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 100) String password
    ) {
    }
}
