package com.vpn.server;

import com.vpn.server.config.JwtUtil;
import com.vpn.server.dto.AuthResponse;
import com.vpn.server.dto.LoginRequest;
import com.vpn.server.dto.RegisterRequest;
import com.vpn.server.dto.UpgradeRequest;
import com.vpn.server.entity.User;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        jwtUtil = new JwtUtil("404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970", 24);
        authService = new AuthService(userRepository, passwordEncoder, jwtUtil);
    }

    @Test
    void testRegisterSuccess() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
        when(userRepository.existsByReferralCode(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(100L);
            return u;
        });

        AuthResponse resp = authService.register(new RegisterRequest("test@example.com", "securePassword123", null));

        assertNotNull(resp);
        assertEquals(100L, resp.userId());
        assertEquals("test@example.com", resp.email());
        assertNotNull(resp.token());
        assertNotNull(resp.referralCode());
        assertTrue(jwtUtil.validateToken(resp.token()));
    }

    @Test
    void testRegisterDuplicateEmail() {
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () ->
                authService.register(new RegisterRequest("test@example.com", "password", null)));
    }

    @Test
    void testLoginSuccess() {
        User user = new User();
        user.setId(200L);
        user.setEmail("user@test.com");
        user.setPasswordHash(passwordEncoder.encode("correctPassword"));
        user.setRole("USER");
        user.setStatus("ACTIVE");
        user.setReferralCode("REF12345");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        AuthResponse resp = authService.login(new LoginRequest("user@test.com", "correctPassword"));

        assertNotNull(resp);
        assertEquals(200L, resp.userId());
        assertTrue(jwtUtil.validateToken(resp.token()));
    }

    @Test
    void testLoginInvalidPassword() {
        User user = new User();
        user.setPasswordHash(passwordEncoder.encode("correctPassword"));
        user.setStatus("ACTIVE");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class, () ->
                authService.login(new LoginRequest("user@test.com", "wrongPassword")));
    }

    @Test
    void testUpgradeGuestSuccess() {
        User guest = new User();
        guest.setId(300L);
        guest.setDeviceUuid("device-abc");
        guest.setEmail("device_device-abc@device.local");
        guest.setRole("USER");
        guest.setStatus("ACTIVE");
        guest.setBalanceUsdtMicro(0L);
        guest.setReferralCode("REF99999");

        when(userRepository.findById(300L)).thenReturn(Optional.of(guest));
        when(userRepository.existsByEmail("real@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse resp = authService.upgradeGuest(300L, new UpgradeRequest("real@example.com", "securePassword123"));

        assertEquals(300L, resp.userId());
        assertEquals("real@example.com", resp.email());
        assertTrue(jwtUtil.validateToken(resp.token()));
    }

    @Test
    void testUpgradeGuestRejectsAlreadyCredentialedAccount() {
        User user = new User();
        user.setId(301L);
        user.setPasswordHash(passwordEncoder.encode("existingPassword"));

        when(userRepository.findById(301L)).thenReturn(Optional.of(user));

        assertThrows(IllegalStateException.class, () ->
                authService.upgradeGuest(301L, new UpgradeRequest("real@example.com", "securePassword123")));
    }
}
