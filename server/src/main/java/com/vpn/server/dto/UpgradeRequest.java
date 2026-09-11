package com.vpn.server.dto;

/** Body for POST /api/v1/auth/upgrade — see AuthService#upgradeGuest. */
public record UpgradeRequest(
        String email,
        String password
) {}
