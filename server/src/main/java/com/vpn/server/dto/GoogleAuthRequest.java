package com.vpn.server.dto;

public record GoogleAuthRequest(
        String idToken,
        String referralCode
) {}
