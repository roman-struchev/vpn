package com.vpn.android.api;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TokenStoreTest {

    private FakeSharedPreferences prefs;
    private TokenStore tokenStore;

    @Before
    public void setUp() {
        prefs = new FakeSharedPreferences();
        tokenStore = new TokenStore(prefs);
    }

    @Test
    public void saveAndGetSession() {
        tokenStore.save("jwt-token-123", 42L);
        assertTrue(tokenStore.isLoggedIn());
        assertEquals("jwt-token-123", tokenStore.getToken());
        assertEquals(42L, tokenStore.getUserId());
    }

    @Test
    public void deviceUuidIsGeneratedAndPersisted() {
        String uuid1 = tokenStore.getOrCreateDeviceUuid();
        assertNotNull(uuid1);
        assertFalse(uuid1.isBlank());

        String uuid2 = tokenStore.getOrCreateDeviceUuid();
        assertEquals("Repeated calls must return the same device UUID", uuid1, uuid2);
    }

    @Test
    public void selectedRegionIsPersisted() {
        assertNull(tokenStore.getSelectedRegion());
        tokenStore.saveSelectedRegion("fi");
        assertEquals("fi", tokenStore.getSelectedRegion());
    }

    @Test
    public void clearWipesAuthSessionButPreservesDeviceUuidAndSelectedRegion() {
        tokenStore.save("jwt-token-123", 42L);
        tokenStore.saveDeviceId(99L);
        String uuid = tokenStore.getOrCreateDeviceUuid();
        tokenStore.saveSelectedRegion("nl");

        tokenStore.clear();

        assertFalse(tokenStore.isLoggedIn());
        assertNull(tokenStore.getToken());
        assertEquals(-1L, tokenStore.getUserId());
        assertEquals(-1L, tokenStore.getDeviceId());

        // Crucial security fix from TODO_ANDROID_GUEST_PROFILE.md:
        // deviceUuid and selectedRegion must survive clear() to prevent unlimited trial loophole
        assertEquals("deviceUuid must be preserved across clear()", uuid, tokenStore.getOrCreateDeviceUuid());
        assertEquals("selectedRegion must be preserved across clear()", "nl", tokenStore.getSelectedRegion());
    }
}
