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
}
