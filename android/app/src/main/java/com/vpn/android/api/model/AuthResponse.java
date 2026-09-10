package com.vpn.android.api.model;

/** Mirrors server dto.AuthResponse (POST /api/v1/auth/register|login). */
public class AuthResponse {
    public String token;
    public long userId;
    public String email;
    public String role;
    public String referralCode;
}
