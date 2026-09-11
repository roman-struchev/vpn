package com.vpn.server;

import com.vpn.server.controller.WebHandoffController;
import com.vpn.server.dto.WebHandoffExchangeRequest;
import com.vpn.server.entity.User;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.WebHandoffService;
import com.vpn.server.config.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebHandoffControllerTest {

    @Mock
    private WebHandoffService webHandoffService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtUtil jwtUtil;

    private WebHandoffController controller;

    @BeforeEach
    void setUp() {
        controller = new WebHandoffController(webHandoffService, userRepository, jwtUtil);
    }

    @Test
    void testExchangeHandoffBlockedUserReturnsForbidden() {
        String code = "handoff-code-123";
        when(webHandoffService.redeem(code)).thenReturn(42L);

        User blockedUser = new User();
        blockedUser.setId(42L);
        blockedUser.setEmail("blocked@example.com");
        blockedUser.setStatus("BLOCKED");

        when(userRepository.findById(42L)).thenReturn(Optional.of(blockedUser));

        ResponseEntity<?> response = controller.exchangeHandoff(new WebHandoffExchangeRequest(code));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("Account is BLOCKED"));
        verify(jwtUtil, never()).generateToken(any(), any(), any());
    }

    @Test
    void testExchangeHandoffActiveUserReturnsToken() {
        String code = "valid-code-456";
        when(webHandoffService.redeem(code)).thenReturn(10L);

        User activeUser = new User();
        activeUser.setId(10L);
        activeUser.setEmail("active@example.com");
        activeUser.setRole("USER");
        activeUser.setStatus("ACTIVE");

        when(userRepository.findById(10L)).thenReturn(Optional.of(activeUser));
        when(jwtUtil.generateToken(10L, "active@example.com", "USER")).thenReturn("jwt-token-xyz");

        ResponseEntity<?> response = controller.exchangeHandoff(new WebHandoffExchangeRequest(code));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jwtUtil).generateToken(10L, "active@example.com", "USER");
    }
}
