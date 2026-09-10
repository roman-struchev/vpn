package com.vpn.android.vpn.xray;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * The fixture link is byte-for-byte what
 * server/.../SubscriptionExportService#buildVlessUrl produces, so this test
 * doubles as a contract check between server export and Android import.
 */
public class VlessUriTest {

    private static final String SAMPLE =
            "vless://d290f1ee-6c54-4b01-90e6-d701748f0851@203.0.113.10:443"
                    + "?encryption=none&security=reality&type=xhttp&path=%2Fvless-xhttp"
                    + "&sni=dl.google.com&pbk=abcDEF123&sid=0123456789abcdef"
                    + "#eu-west-node1.example.com";

    @Test
    public void parsesAllFieldsFromTheServerGeneratedLink() {
        VlessUri uri = VlessUri.parse(SAMPLE);

        assertEquals("d290f1ee-6c54-4b01-90e6-d701748f0851", uri.getUuid());
        assertEquals("203.0.113.10", uri.getHost());
        assertEquals(443, uri.getPort());
        assertEquals("none", uri.getParam("encryption", null));
        assertEquals("reality", uri.getParam("security", null));
        assertEquals("xhttp", uri.getParam("type", null));
        assertEquals("/vless-xhttp", uri.getParam("path", null));
        assertEquals("dl.google.com", uri.getParam("sni", null));
        assertEquals("abcDEF123", uri.getParam("pbk", null));
        assertEquals("0123456789abcdef", uri.getParam("sid", null));
        assertEquals("eu-west-node1.example.com", uri.getRemark());
    }

    @Test
    public void missingParamFallsBackToProvidedDefault() {
        VlessUri uri = VlessUri.parse(SAMPLE);
        assertEquals("fallback", uri.getParam("doesNotExist", "fallback"));
    }

    @Test
    public void rejectsNonVlessLinks() {
        assertThrows(IllegalArgumentException.class, () -> VlessUri.parse("https://example.com"));
    }

    @Test
    public void rejectsLinkWithoutUuidOrHost() {
        assertThrows(IllegalArgumentException.class, () -> VlessUri.parse("vless://@:443?foo=bar"));
    }
}
