package com.vpn.android.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Reads the {@code exp} claim out of a JWT without verifying it — the app only
 * needs to know when to renew its own token, and the server re-checks the
 * signature on every request anyway.
 */
public final class JwtExpiry {

    private JwtExpiry() {
    }

    /** Epoch seconds, or -1 if the token has no readable exp. */
    public static long expiresAtEpochSec(String token) {
        if (token == null) return -1;
        String[] parts = token.split("\\.");
        if (parts.length < 2) return -1;
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonObject claims = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
            return claims.has("exp") ? claims.get("exp").getAsLong() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
