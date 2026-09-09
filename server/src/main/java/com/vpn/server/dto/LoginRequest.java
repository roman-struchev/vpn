package com.vpn.server.dto;

public record LoginRequest(
        String email,
        String password
) {}
