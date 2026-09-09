package com.vpn.server.dto;

public record RegisterRequest(
        String email,
        String password,
        String referralCode
) {}
