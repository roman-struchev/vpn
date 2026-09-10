package com.vpn.android.vpn.xray;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class XrayConfigFactoryTest {

    private static final String LINK =
            "vless://d290f1ee-6c54-4b01-90e6-d701748f0851@203.0.113.10:443"
                    + "?encryption=none&security=reality&type=xhttp&path=%2Fvless-xhttp"
                    + "&sni=dl.google.com&pbk=abcDEF123&sid=0123456789abcdef"
                    + "#eu-west-node1.example.com";

    @Test
    public void tunFdIsWrittenToRootEnvAsPerXrayCoreAndroidContract() {
        VlessUri vless = VlessUri.parse(LINK);
        String json = XrayConfigFactory.build(vless, "firefox", 42, 1500);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("42", root.getAsJsonObject("env").get("xray.tun.fd").getAsString());
    }

    @Test
    public void tunInboundUsesTunProtocolAndConfiguredMtu() {
        VlessUri vless = VlessUri.parse(LINK);
        String json = XrayConfigFactory.build(vless, "firefox", 42, 1400);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray inbounds = root.getAsJsonArray("inbounds");
        assertEquals(1, inbounds.size());
        JsonObject tunInbound = inbounds.get(0).getAsJsonObject();
        assertEquals("tun", tunInbound.get("protocol").getAsString());
        assertEquals(1400, tunInbound.getAsJsonObject("settings").get("mtu").getAsInt());
    }

    @Test
    public void proxyOutboundCarriesRealityFingerprintAndKeys() {
        VlessUri vless = VlessUri.parse(LINK);
        String json = XrayConfigFactory.build(vless, "edge", 5, 1500);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject proxyOutbound = root.getAsJsonArray("outbounds").get(0).getAsJsonObject();
        assertEquals("vless", proxyOutbound.get("protocol").getAsString());

        JsonObject realitySettings = proxyOutbound.getAsJsonObject("streamSettings").getAsJsonObject("realitySettings");
        assertEquals("edge", realitySettings.get("fingerprint").getAsString());
        assertEquals("abcDEF123", realitySettings.get("publicKey").getAsString());

        JsonObject vnext = proxyOutbound.getAsJsonObject("settings").getAsJsonArray("vnext").get(0).getAsJsonObject();
        assertEquals("203.0.113.10", vnext.get("address").getAsString());
        String userId = vnext.getAsJsonArray("users").get(0).getAsJsonObject().get("id").getAsString();
        assertEquals("d290f1ee-6c54-4b01-90e6-d701748f0851", userId);
    }

    @Test
    public void proxyOutboundIsTheDefaultRoute() {
        // Xray/V2Ray semantics: the first declared outbound is the implicit default
        // for traffic no routing rule matches. It must be the proxy, not block/dns.
        VlessUri vless = VlessUri.parse(LINK);
        String json = XrayConfigFactory.build(vless, "firefox", 5, 1500);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("proxy", root.getAsJsonArray("outbounds").get(0).getAsJsonObject().get("tag").getAsString());
    }

    @Test
    public void xmuxIsAlwaysEnabled() {
        VlessUri vless = VlessUri.parse(LINK);
        String json = XrayConfigFactory.build(vless, "firefox", 5, 1500);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject proxyOutbound = root.getAsJsonArray("outbounds").get(0).getAsJsonObject();
        assertTrue(proxyOutbound.getAsJsonObject("streamSettings").has("xmuxSettings"));
    }

    @Test
    public void rejectsRandomOrMissingFingerprint() {
        VlessUri vless = VlessUri.parse(LINK);
        assertThrows(IllegalArgumentException.class, () -> XrayConfigFactory.build(vless, "chrome", 5, 1500));
        assertThrows(IllegalArgumentException.class, () -> XrayConfigFactory.build(vless, null, 5, 1500));
    }
}
