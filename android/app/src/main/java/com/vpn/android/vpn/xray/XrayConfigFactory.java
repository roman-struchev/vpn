package com.vpn.android.vpn.xray;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Builds the Xray-core JSON config consumed by libXray's {@code runXray}.
 *
 * Mirrors agent/src/xray/config-builder.ts on the node side (same transport:
 * VLESS + XHTTP + Reality, XMUX always on, log/stats/policy shape) but wires
 * a client-side {@code tun} inbound instead of a listening VLESS inbound.
 *
 * TUN wiring follows Xray-core's own Android support contract
 * (proxy/tun/README.md, "ANDROID SUPPORT"): the already-established
 * VpnService TUN fd is not part of the Xray JSON directly — it is written
 * into the config's root {@code env} object as {@code xray.tun.fd}, which
 * Xray-core's config loader turns into a process env var
 * (infra/conf/xray.go: {@code Config.Build} calls {@code os.Setenv} for every
 * entry) before {@code proxy/tun.NewTun} reads it back out
 * (proxy/tun/tun_android.go).
 */
public final class XrayConfigFactory {

    private static final String TUN_INBOUND_TAG = "tun-in";
    private static final String PROXY_OUTBOUND_TAG = "proxy";
    private static final String DNS_OUTBOUND_TAG = "dns-out";
    private static final String BLOCK_OUTBOUND_TAG = "block";

    private XrayConfigFactory() {
    }

    /**
     * @param vless       parsed subscription link for the node to connect to.
     * @param fingerprint session-fixed TLS fingerprint ("firefox" or "edge"); never randomized
     *                    per session, see {@link com.vpn.android.vpn.ReconnectBackoffPolicy}.
     * @param tunFd       fd returned by {@code VpnService.Builder#establish()}.
     * @param mtu         TUN interface MTU, must match what was passed to the VpnService Builder.
     */
    public static String build(VlessUri vless, String fingerprint, int tunFd, int mtu) {
        return build(vless, fingerprint, tunFd, mtu, "XHTTP", null, null);
    }

    /**
     * Same as {@link #build(VlessUri, String, int, int)}, but selects the transport
     * (Phase 9 "transport flexibility"): "GRPC" builds a gRPC+Reality outbound against
     * {@code grpcPort}/{@code grpcServiceName} (from RoutingConfigResponse.NodeInfo)
     * instead of the XHTTP settings embedded in the vless link, reusing the same
     * Reality key material and client UUID either way.
     */
    public static String build(
            VlessUri vless, String fingerprint, int tunFd, int mtu,
            String transport, Integer grpcPort, String grpcServiceName) {
        if (!"firefox".equals(fingerprint) && !"edge".equals(fingerprint)) {
            throw new IllegalArgumentException("fingerprint must be firefox or edge, got: " + fingerprint);
        }
        boolean useGrpc = "GRPC".equals(transport);
        if (useGrpc && grpcPort == null) {
            throw new IllegalArgumentException("grpcPort is required when transport is GRPC");
        }

        JsonObject root = new JsonObject();

        JsonObject log = new JsonObject();
        log.addProperty("loglevel", "warning");
        root.add("log", log);

        // env.xray.tun.fd — see class javadoc.
        JsonObject env = new JsonObject();
        env.addProperty("xray.tun.fd", String.valueOf(tunFd));
        root.add("env", env);

        JsonObject policy = new JsonObject();
        JsonObject levels = new JsonObject();
        JsonObject level0 = new JsonObject();
        level0.addProperty("statsUserUplink", true);
        level0.addProperty("statsUserDownlink", true);
        levels.add("0", level0);
        policy.add("levels", levels);
        root.add("policy", policy);

        // DNS: system-wide queries captured off the TUN are answered here over DoH,
        // so the ISP resolver never sees them (PLAN.md §6, "клиент не зависит от DNS
        // провайдера"). Upstream dials go through the same protected dialer as the
        // proxy outbound (registerDialerController), so they don't loop back into tun.
        JsonObject dns = new JsonObject();
        JsonArray dnsServers = new JsonArray();
        dnsServers.add("https://1.1.1.1/dns-query");
        dnsServers.add("https://1.0.0.1/dns-query");
        dns.add("servers", dnsServers);
        dns.addProperty("queryStrategy", "UseIP");
        root.add("dns", dns);

        JsonArray inbounds = new JsonArray();
        inbounds.add(buildTunInbound(mtu));
        root.add("inbounds", inbounds);

        JsonArray outbounds = new JsonArray();
        // First outbound is Xray's default match for anything not covered by a routing
        // rule below — must be the proxy, not direct/block.
        outbounds.add(buildProxyOutbound(vless, fingerprint, useGrpc, grpcPort, grpcServiceName));
        outbounds.add(buildDnsOutbound());
        outbounds.add(buildBlockOutbound());
        root.add("outbounds", outbounds);

        root.add("routing", buildRouting());

        return root.toString();
    }

    private static JsonObject buildTunInbound(int mtu) {
        JsonObject inbound = new JsonObject();
        inbound.addProperty("tag", TUN_INBOUND_TAG);
        inbound.addProperty("protocol", "tun");
        JsonObject settings = new JsonObject();
        settings.addProperty("mtu", mtu);
        inbound.add("settings", settings);
        return inbound;
    }

    private static JsonObject buildProxyOutbound(
            VlessUri vless, String fingerprint, boolean useGrpc, Integer grpcPort, String grpcServiceName) {
        JsonObject outbound = new JsonObject();
        outbound.addProperty("tag", PROXY_OUTBOUND_TAG);
        outbound.addProperty("protocol", "vless");

        JsonObject vnext = new JsonObject();
        vnext.addProperty("address", vless.getHost());
        // Same node, different port when falling back to gRPC — see
        // NodeManagementService#buildNodeConfigSync on the server (Phase 9).
        vnext.addProperty("port", useGrpc ? grpcPort : vless.getPort());
        JsonArray users = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("id", vless.getUuid());
        user.addProperty("encryption", "none");
        users.add(user);
        vnext.add("users", users);
        JsonArray vnextArr = new JsonArray();
        vnextArr.add(vnext);

        JsonObject settings = new JsonObject();
        settings.add("vnext", vnextArr);
        outbound.add("settings", settings);

        boolean reality = "reality".equalsIgnoreCase(vless.getParam("security", "none"));
        JsonObject streamSettings = new JsonObject();
        streamSettings.addProperty("network", useGrpc ? "grpc" : "xhttp");
        streamSettings.addProperty("security", reality ? "reality" : "none");

        if (reality) {
            JsonObject realitySettings = new JsonObject();
            realitySettings.addProperty("show", false);
            realitySettings.addProperty("serverName", vless.getParam("sni", "dl.google.com"));
            realitySettings.addProperty("publicKey", vless.getParam("pbk", ""));
            realitySettings.addProperty("shortId", vless.getParam("sid", ""));
            // Architecture invariant: real browser fingerprint only, never random,
            // and fixed for the whole session (docs/ROADMAP_PROGRESS.md §1.5).
            realitySettings.addProperty("fingerprint", fingerprint);
            streamSettings.add("realitySettings", realitySettings);
        }

        if (useGrpc) {
            JsonObject grpcSettings = new JsonObject();
            grpcSettings.addProperty("serviceName", grpcServiceName != null ? grpcServiceName : "vless-grpc");
            streamSettings.add("grpcSettings", grpcSettings);
        } else {
            JsonObject xhttpSettings = new JsonObject();
            xhttpSettings.addProperty("path", vless.getParam("path", "/vless-xhttp"));
            xhttpSettings.addProperty("mode", vless.getParam("mode", "auto"));
            streamSettings.add("xhttpSettings", xhttpSettings);

            // XMUX applies to the XHTTP transport only (docs/ROADMAP_PROGRESS.md §1.5).
            JsonObject xmuxSettings = new JsonObject();
            xmuxSettings.addProperty("maxConcurrency", 16);
            streamSettings.add("xmuxSettings", xmuxSettings);
        }

        outbound.add("streamSettings", streamSettings);
        return outbound;
    }

    private static JsonObject buildDnsOutbound() {
        JsonObject outbound = new JsonObject();
        outbound.addProperty("tag", DNS_OUTBOUND_TAG);
        outbound.addProperty("protocol", "dns");
        return outbound;
    }

    private static JsonObject buildBlockOutbound() {
        JsonObject outbound = new JsonObject();
        outbound.addProperty("tag", BLOCK_OUTBOUND_TAG);
        outbound.addProperty("protocol", "blackhole");
        return outbound;
    }

    private static JsonObject buildRouting() {
        JsonObject routing = new JsonObject();
        routing.addProperty("domainStrategy", "IPIfNonMatch");

        JsonArray rules = new JsonArray();

        JsonObject dnsRule = new JsonObject();
        dnsRule.addProperty("type", "field");
        JsonArray dnsInboundTag = new JsonArray();
        dnsInboundTag.add(TUN_INBOUND_TAG);
        dnsRule.add("inboundTag", dnsInboundTag);
        dnsRule.addProperty("port", "53");
        dnsRule.addProperty("network", "udp");
        dnsRule.addProperty("outboundTag", DNS_OUTBOUND_TAG);
        rules.add(dnsRule);

        // Keep LAN/private-range traffic out of the tunnel so local network access
        // (routers, printers, Chromecast, ...) keeps working while connected.
        JsonObject privateRule = new JsonObject();
        privateRule.addProperty("type", "field");
        JsonArray privateIp = new JsonArray();
        privateIp.add("geoip:private");
        privateRule.add("ip", privateIp);
        privateRule.addProperty("outboundTag", BLOCK_OUTBOUND_TAG);
        rules.add(privateRule);

        routing.add("rules", rules);
        return routing;
    }
}
