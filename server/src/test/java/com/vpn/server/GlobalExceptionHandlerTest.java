package com.vpn.server;

import com.vpn.server.config.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void testHandleIllegalArgument() {
        ResponseEntity<Map<String, String>> response =
                handler.handleIllegalArgument(new IllegalArgumentException("Invalid amount"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid amount", response.getBody().get("error"));
    }

    @Test
    void testHandleIllegalState() {
        ResponseEntity<Map<String, String>> response =
                handler.handleIllegalState(new IllegalStateException("Account suspended"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Account suspended", response.getBody().get("error"));
    }

    @Test
    void testHandleHttpMessageNotReadable() {
        ResponseEntity<Map<String, String>> response =
                handler.handleMessageNotReadable(new HttpMessageNotReadableException("JSON parse error", (org.springframework.http.HttpInputMessage) null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("error").contains("Malformed JSON"));
    }

    @Test
    void testHandleResponseStatus() {
        ResponseEntity<Map<String, String>> response =
                handler.handleResponseStatus(new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("Access denied", response.getBody().get("error"));
    }

    @Test
    void testHandleGenericException() {
        ResponseEntity<Map<String, String>> response =
                handler.handleGenericException(new RuntimeException("Unexpected db crash"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Internal server error", response.getBody().get("error"));
    }

    @Test
    void testDatabaseConstraintViolationIsABadRequestAndLeaksNothing() {
        // The safety net under the explicit length checks: whatever slips
        // through to a column must read as bad input, not as a server fault,
        // and the driver's message (which quotes the SQL and the offending
        // value) must stay in the log.
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: value too long for type character varying(128); INSERT INTO devices ..."));

        ResponseEntity<Map<String, String>> response = handler.handleDataIntegrityViolation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        String error = response.getBody().get("error");
        assertFalse(error.contains("INSERT INTO"), "must not echo the statement back: " + error);
        assertFalse(error.contains("character varying"), "must not echo the column type back: " + error);
        assertTrue(error.toLowerCase().contains("too long") || error.toLowerCase().contains("invalid"));
    }
}
