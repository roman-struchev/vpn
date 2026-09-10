package com.vpn.server.controller;

import com.vpn.server.dto.AuthResponse;
import com.vpn.server.dto.LoginRequest;
import com.vpn.server.dto.RegisterRequest;
import com.vpn.server.dto.TelegramAuthRequest;
import com.vpn.server.service.AuthService;
import com.vpn.server.service.TelegramAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final TelegramAuthService telegramAuthService;

    public AuthController(AuthService authService, TelegramAuthService telegramAuthService) {
        this.authService = authService;
        this.telegramAuthService = telegramAuthService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/telegram")
    public ResponseEntity<AuthResponse> telegramAuth(@RequestBody TelegramAuthRequest request) {
        return ResponseEntity.ok(telegramAuthService.authenticateTelegram(request.initData(), request.referralCode()));
    }
}
