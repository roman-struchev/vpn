package com.vpn.android.util;

import org.junit.After;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeoLocaleTest {

    private final Locale originalDefault = Locale.getDefault();

    @After
    public void restoreDefaultLocale() {
        Locale.setDefault(originalDefault);
    }

    @Test
    public void detectsRussianDeviceLocale() {
        Locale.setDefault(new Locale("ru", "RU"));
        assertTrue(GeoLocale.isDeviceLocaleRussian());
    }

    @Test
    public void nonRussianLocaleIsNotDetectedAsRussian() {
        Locale.setDefault(Locale.US);
        assertFalse(GeoLocale.isDeviceLocaleRussian());
    }

    /**
     * detectNodeRegion's pure formatting logic (docs/research/
     * P2P_RELAY_FEASIBILITY.md §8.4) — the network-calling half isn't
     * unit-tested here, matching this class's pre-existing
     * lookupBlocking()/fetchCountryCode(), which also have no coverage (no
     * HTTP-mocking dependency exists in this project yet).
     */
    @Test
    public void formatRegion_combinesCountryAndCity() {
        org.junit.Assert.assertEquals("Germany, Berlin", GeoLocale.formatRegion("Germany", "Berlin"));
    }

    @Test
    public void formatRegion_dropsCityWhenAbsentOrBlank() {
        org.junit.Assert.assertEquals("Germany", GeoLocale.formatRegion("Germany", null));
        org.junit.Assert.assertEquals("Germany", GeoLocale.formatRegion("Germany", ""));
    }

    @Test
    public void formatRegion_expandsIsoCountryCodeToEnglishName() {
        // ipinfo.io fallback: must match desktop's/install-node.sh's "Montenegro, Podgorica" exactly.
        Locale.setDefault(new Locale("ru", "RU"));
        org.junit.Assert.assertEquals("Montenegro, Podgorica", GeoLocale.formatRegion("ME", "Podgorica"));
        org.junit.Assert.assertEquals("Germany", GeoLocale.formatRegion("de", null));
        org.junit.Assert.assertEquals("Turkey, Istanbul", GeoLocale.formatRegion("TR", "Istanbul"));
        org.junit.Assert.assertEquals("Netherlands, Amsterdam", GeoLocale.formatRegion("Netherlands", "Amsterdam"));
    }

    @Test
    public void normalizeRegion_fixesLabelsPersistedWithRawCountryCode() {
        org.junit.Assert.assertEquals("Montenegro, Podgorica", GeoLocale.normalizeRegion("ME, Podgorica"));
        org.junit.Assert.assertEquals("Montenegro", GeoLocale.normalizeRegion("ME"));
        org.junit.Assert.assertEquals("Finland, Helsinki", GeoLocale.normalizeRegion("Finland, Helsinki"));
        org.junit.Assert.assertEquals("default", GeoLocale.normalizeRegion("default"));
    }

    @Test
    public void formatRegion_fallsBackToDefault_whenCountryAbsentOrBlank() {
        org.junit.Assert.assertEquals("default", GeoLocale.formatRegion(null, "Berlin"));
        org.junit.Assert.assertEquals("default", GeoLocale.formatRegion("", "Berlin"));
    }
}
