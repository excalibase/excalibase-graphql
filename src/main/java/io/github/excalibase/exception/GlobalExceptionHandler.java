package io.github.excalibase.exception;

import io.github.excalibase.exception.model.ApplicationExceptionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Global exception handler for REST API error responses.
 */
@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({NotImplementedException.class})
    ResponseEntity<ApplicationExceptionResponse> handleBadCredentialsException(Exception exception) {
        log.error("BadCredentialsException: ", exception);
        final ApplicationExceptionResponse response = ApplicationExceptionResponse.builder()
                .code(HttpStatus.NOT_IMPLEMENTED.toString())
                .key("Not Implemented")
                .details(new String[] {exception.getMessage()})
                .build();
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(response);
    }
}
