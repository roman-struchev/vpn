package com.vpn.android.api.model;

/** Mirrors POST /api/v1/auth/web-handoff. */
public class WebHandoffResponse {
    public String code;
    public String webUrl;
    public int expiresInSeconds;
}
