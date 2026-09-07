package ru.murad.myvpn.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.murad.myvpn.application.auth.InvalidAuthenticationException;
import ru.murad.myvpn.application.auth.RefreshAuthenticationException;
import ru.murad.myvpn.dto.AuthErrorResponse;

@RestControllerAdvice
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class AuthExceptionHandler {

    @ExceptionHandler(RefreshAuthenticationException.class)
    public ResponseEntity<AuthErrorResponse> refreshAuthentication(RefreshAuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AuthErrorResponse(exception.getReason().name(), "Refresh session is invalid or revoked"));
    }

    @ExceptionHandler(InvalidAuthenticationException.class)
    public ResponseEntity<AuthErrorResponse> invalidAuthentication() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AuthErrorResponse(
                        "INVALID_AUTHENTICATION",
                        "Authentication data is invalid or expired"));
    }
}
