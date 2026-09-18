package com.vpn.android.p2p;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The link opened a blank page: the dashboard is hash-routed, so
 * "<origin>/p2p-terms" is not a page — it fell through to the server's
 * catch-all and came back 403.
 */
public class P2pTermsUrlTest {

    @Test
    public void buildsTheHashRouteTheWebDashboardActuallyServes() {
        assertEquals("https://example.com/#p2p-terms",
                P2pRelaySettingsActivity.termsUrl("https://example.com"));
    }

    @Test
    public void toleratesTheTrailingSlashTheConfiguredBaseUrlCarries() {
        // BuildConfig.WEB_BASE_URL ends with "/" (see android/app/build.gradle),
        // which is exactly how the old concatenation produced its broken path.
        assertEquals("http://217.216.79.46:8080/#p2p-terms",
                P2pRelaySettingsActivity.termsUrl("http://217.216.79.46:8080/"));
        assertEquals("https://example.com/#p2p-terms",
                P2pRelaySettingsActivity.termsUrl("https://example.com///"));
    }

    @Test
    public void survivesAnUnsetBaseUrlWithoutCrashing() {
        assertEquals("/#p2p-terms", P2pRelaySettingsActivity.termsUrl(null));
        assertEquals("/#p2p-terms", P2pRelaySettingsActivity.termsUrl("  "));
    }
}
