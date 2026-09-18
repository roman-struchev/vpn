package com.vpn.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request (IllegalArgumentException): {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Bad request"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        log.warn("Conflict/Bad state (IllegalStateException): {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Invalid state"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.warn("Validation error: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed JSON request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "Malformed JSON request body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String msg = "Invalid value for parameter: " + ex.getName();
        log.warn("Parameter mismatch: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResourceFound(NoResourceFoundException ex) {
        // Thrown for any request path that matches no controller mapping AND no
        // static resource (e.g. a stale/typo'd asset URL) — without this handler
        // it falls through to the generic Exception.class handler below, which
        // was written for genuinely unexpected server errors and turns every
        // missing asset into a misleading 500 instead of a plain, correct 404.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        log.warn("ResponseStatusException ({}): {}", ex.getStatusCode(), reason);
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", reason));
    }

    /**
     * A value the database itself refused: too long for its column, or
     * colliding with a unique constraint. That is bad input, not a server
     * fault, so it must not read as a 500 — which is exactly how an overlong
     * device name, platform, e-mail or device UUID used to surface, complete
     * with the client having no idea what it did wrong.
     *
     * Each of those inputs is also checked up front now (see AuthService#
     * register, DeviceAuthService#authenticateDevice, DeviceManagementService#
     * addDevice) with a message naming the offending field; this is the net
     * underneath, so the next unbounded string to reach a column degrades to a
     * 400 instead of a 500. The detail is logged, never returned: the driver's
     * message quotes the SQL and the value.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Rejected request violating a database constraint: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid input: a value is too long or already in use"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception ex) {
        log.error("Unhandled exception processing request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
    }
}
