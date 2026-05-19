package io.github.excalibase.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Maps {@link RoleNotAllowedException} to HTTP 403, in a GraphQL-shaped error envelope
 * so existing clients see a consistent body.
 */
@RestControllerAdvice
public class PostgresRoleSwitchingAdvice {

    @ExceptionHandler(RoleNotAllowedException.class)
    public ResponseEntity<Object> handleRoleNotAllowed(RoleNotAllowedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(Map.of("errors", List.of(Map.of("message", ex.getMessage()))));
    }
}
