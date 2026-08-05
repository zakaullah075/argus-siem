package com.argus.security;

import com.argus.security.dto.SessionResponse;
import com.argus.security.exception.InvalidCredentialsException;
import com.argus.user.AppUser;
import com.argus.user.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthenticationService(AppUserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    /**
     * Verifies credentials and issues a token.
     * <p>
     * The same failure is returned whether the account is unknown or the password
     * is wrong, so the endpoint cannot be used to discover which addresses have
     * accounts.
     */
    @Transactional(readOnly = true)
    public SessionResponse login(String email, String password) {
        AppUser user = userRepository.findByEmail(email.toLowerCase()).orElse(null);

        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return new SessionResponse(tokenService.issue(user), user.getRole().name());
    }

}
