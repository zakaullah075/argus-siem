package com.argus.security.exception;

import com.argus.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * One message for an unknown account and a wrong password. Distinguishing them
 * turns login into an oracle for which addresses have accounts.
 */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
}
