package com.vpn.android.api;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JwtExpiryTest {

    @Test
    public void readsTheExpClaim() {
        long exp = JwtExpiry.expiresAtEpochSec(ApiClientSessionTest.jwtExpiringIn(100));
        long now = System.currentTimeMillis() / 1000;
        assertTrue(exp > now + 90 && exp <= now + 101);
    }

    @Test
    public void unreadableTokensGiveMinusOne() {
        assertEquals(-1, JwtExpiry.expiresAtEpochSec(null));
        assertEquals(-1, JwtExpiry.expiresAtEpochSec("not-a-jwt"));
        assertEquals(-1, JwtExpiry.expiresAtEpochSec("a.%%%.c"));
    }
}
