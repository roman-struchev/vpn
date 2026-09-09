package com.vpn.server.dto;

public record AuthResponse(
        String token,
        Long userId,
        String email,
        String role,
        String referralCode
) {}
