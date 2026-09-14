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

    @Test
    public void bypassRussianTrafficSetting() {
        assertFalse(tokenStore.isBypassRussianTraffic());
        tokenStore.setBypassRussianTraffic(true);
        assertTrue(tokenStore.isBypassRussianTraffic());
        tokenStore.setBypassRussianTraffic(false);
        assertFalse(tokenStore.isBypassRussianTraffic());
    }

    @Test
    public void russianRoutingModeDefaultsToOffOnAFreshInstall() {
        assertEquals(TokenStore.RUSSIAN_ROUTING_OFF, tokenStore.getRussianRoutingMode());
    }

    @Test
    public void russianRoutingModeIsPersisted() {
        tokenStore.setRussianRoutingMode(TokenStore.RUSSIAN_ROUTING_ONLY_RU);
        assertEquals(TokenStore.RUSSIAN_ROUTING_ONLY_RU, tokenStore.getRussianRoutingMode());
    }

    @Test
    public void russianRoutingModeMigratesPreExistingBypassBooleanOnFirstRead() {
        // Simulates an app update: the old boolean pref exists (set true, pre-3-way-mode),
        // the new string pref doesn't exist yet.
        tokenStore.setBypassRussianTraffic(true);

        assertEquals(TokenStore.RUSSIAN_ROUTING_BYPASS, tokenStore.getRussianRoutingMode());
        // Migration must stick (not re-derive from the boolean every time) so an explicit
        // later choice of "off" isn't clobbered back to "bypassRu" on the next read.
        tokenStore.setRussianRoutingMode(TokenStore.RUSSIAN_ROUTING_OFF);
        assertEquals(TokenStore.RUSSIAN_ROUTING_OFF, tokenStore.getRussianRoutingMode());
    }

    @Test
    public void originalIpIsRussiaIsNullUntilLookedUp() {
        assertNull(tokenStore.getOriginalIpIsRussia());
        tokenStore.saveOriginalIpIsRussia(true);
        assertTrue(tokenStore.getOriginalIpIsRussia());
    }

    @Test
    public void clearPreservesOriginalIpIsRussia() {
        tokenStore.saveOriginalIpIsRussia(true);
        tokenStore.save("jwt-token-123", 42L);

        tokenStore.clear();

        assertFalse(tokenStore.isLoggedIn());
        assertTrue("originalIpIsRussia is a device property, not session state", tokenStore.getOriginalIpIsRussia());
    }

    @Test
    public void autoConnectOnBootSetting() {
        assertFalse(tokenStore.isAutoConnectOnBoot());
        tokenStore.setAutoConnectOnBoot(true);
        assertTrue(tokenStore.isAutoConnectOnBoot());
    }

    @Test
    public void disallowedAppsSetting() {
        assertTrue(tokenStore.getDisallowedApps().isEmpty());
        java.util.Set<String> apps = java.util.Set.of("com.example.bank", "com.example.maps");
        tokenStore.setDisallowedApps(apps);
        assertEquals(apps, tokenStore.getDisallowedApps());
    }
}

