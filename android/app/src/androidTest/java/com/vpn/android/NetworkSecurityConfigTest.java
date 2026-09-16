package com.vpn.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.security.NetworkSecurityPolicy;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import okhttp3.HttpUrl;

/**
 * Regression guard for a real bug found by manually building this app's
 * release variant and running it in an emulator: the main manifest's
 * android:usesCleartextTraffic="false" silently blocked every plaintext HTTP
 * request to the app's own configured API server (currently the temporary
 * 217.216.79.46 test server — see API_BASE_URL/WEB_BASE_URL in build.gradle),
 * surfacing as "Сервер недоступен"/"Server unavailable" on every real device
 * with no exception ever logged (LoginActivity#attemptDeviceLogin's
 * IOException branch never distinguishes this from a real network failure).
 * Fixed with a narrowly-scoped network_security_config.xml domain-config
 * instead of a blanket cleartext allow — network_security_config_debug.xml
 * (which this androidTest variant actually uses) carries the same exception
 * for the same reason, since API_BASE_URL's default is identical in both
 * build types.
 */
@RunWith(AndroidJUnit4.class)
public class NetworkSecurityConfigTest {

    @Test
    public void configuredApiHostIsNotSilentlyBlockedByCleartextPolicy() {
        String host = HttpUrl.get(BuildConfig.API_BASE_URL).host();

        assertTrue(
                "cleartext HTTP to the app's own configured API host (" + host + ") must be permitted, "
                        + "or every real request fails with an unlogged UnknownServiceException",
                NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host));
    }

    @Test
    public void arbitraryUnrelatedHostsStayBlocked() {
        // Proves the fix is scoped to the one temporary test-server host,
        // not a blanket cleartext allow for the whole app.
        assertFalse(
                "cleartext must stay blocked for hosts outside the narrow test-server exception",
                NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("example.com"));
    }
}
