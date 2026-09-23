package com.vpn.android.vpn.xray;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void tunInboundHasExplicitNameSoXrayNeverEnumeratesInterfacesOnAndroid() {
        // Without a name, Xray-core calls net.Interfaces() (netlink), which Android 11+
        // denies to apps — runXray then fails with "netlinkrib: permission denied".
        VlessUri vless = VlessUri.parse(LINK);
        String json = XrayConfigFactory.build(vless, "firefox", 42, 1400);

        JsonObject settings = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonArray("inbounds").get(0).getAsJsonObject().getAsJsonObject("settings");
        assertTrue(settings.has("name"));
        assertFalse(settings.get("name").getAsString().isEmpty());
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

    @Test
    public void grpcTransportUsesTheFallbackPortAndServiceNameKeepingReality() {
        VlessUri vless = VlessUri.parse(LINK);
        String json = XrayConfigFactory.build(vless, "firefox", 5, 1500, "GRPC", 8443, "vless-grpc");

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject proxyOutbound = root.getAsJsonArray("outbounds").get(0).getAsJsonObject();
        JsonObject streamSettings = proxyOutbound.getAsJsonObject("streamSettings");

        assertEquals("grpc", streamSettings.get("network").getAsString());
        assertEquals("reality", streamSettings.get("security").getAsString());
        assertEquals("vless-grpc", streamSettings.getAsJsonObject("grpcSettings").get("serviceName").getAsString());
        assertFalse(streamSettings.has("xhttpSettings"));

        JsonObject vnext = proxyOutbound.getAsJsonObject("settings").getAsJsonArray("vnext").get(0).getAsJsonObject();
        assertEquals(8443, vnext.get("port").getAsInt());
        // Same node address and Reality keys as the primary transport.
        assertEquals("203.0.113.10", vnext.get("address").getAsString());
        assertEquals("abcDEF123", streamSettings.getAsJsonObject("realitySettings").get("publicKey").getAsString());
    }

    @Test
    public void grpcTransportRequiresAPort() {
        VlessUri vless = VlessUri.parse(LINK);
        assertThrows(IllegalArgumentException.class,
                () -> XrayConfigFactory.build(vless, "firefox", 5, 1500, "GRPC", null, "vless-grpc"));
    }

    @Test
    public void probeInboundAlwaysGoesThroughTheProxyAheadOfEveryOtherRule() {
        // On a P2P path the app is outside its own TUN, so this inbound is the
        // only way the liveness probe crosses the same path as user traffic.
        String p2p = XrayConfigFactory.buildP2pExit(5, 1500, null, "127.0.0.1", 34567);
        JsonObject root = JsonParser.parseString(
                XrayConfigFactory.withProbeInbound(p2p, XrayConfigFactory.PROBE_PORT)).getAsJsonObject();

        JsonObject probe = null;
        for (com.google.gson.JsonElement e : root.getAsJsonArray("inbounds")) {
            if ("probe-in".equals(e.getAsJsonObject().get("tag").getAsString())) probe = e.getAsJsonObject();
        }
        assertTrue("probe inbound present", probe != null);
        assertEquals("127.0.0.1", probe.get("listen").getAsString());
        assertEquals(XrayConfigFactory.PROBE_PORT, probe.get("port").getAsInt());

        JsonObject first = root.getAsJsonObject("routing").getAsJsonArray("rules").get(0).getAsJsonObject();
        assertEquals("probe-in", first.getAsJsonArray("inboundTag").get(0).getAsString());
        assertEquals("proxy", first.get("outboundTag").getAsString());
        // The original rules are all still there, after it.
        assertEquals(
                JsonParser.parseString(p2p).getAsJsonObject().getAsJsonObject("routing").getAsJsonArray("rules").size() + 1,
                root.getAsJsonObject("routing").getAsJsonArray("rules").size());
    }
}
