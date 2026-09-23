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
        return build(vless, fingerprint, tunFd, mtu, transport, grpcPort, grpcServiceName, null);
    }

    /**
     * Same as {@link #build(VlessUri, String, int, int, String, Integer, String)}, plus
     * {@code xrayAssetDir}: the real filesystem directory (NOT an APK asset path —
     * xray-core's own file I/O cannot read out of an APK) containing a copy of
     * {@code geoip.dat} (see {@code assets/geoip.dat}, extracted once at runtime by
     * XrayVpnService). Without this, the routing rule below that keeps LAN traffic
     * out of the tunnel (geoip:private) fails Xray-core startup entirely on Android
     * with "failed to open geoip.dat: no such file or directory" — confirmed live —
     * because Xray-core has no bundled default asset location on this platform the
     * way desktop/agent's install (which ships geoip.dat/geosite.dat next to the
     * xray binary, see desktop/resources/bin/<platform>/) does. Pass {@code null}
     * only where the geoip:private rule is guaranteed unreachable (i.e. never, in
     * production — kept nullable purely so existing JVM unit tests that don't
     * exercise Xray-core itself don't need a real directory).
     */
    public static String build(
            VlessUri vless, String fingerprint, int tunFd, int mtu,
            String transport, Integer grpcPort, String grpcServiceName, String xrayAssetDir) {
        return build(vless, fingerprint, tunFd, mtu, transport, grpcPort, grpcServiceName, xrayAssetDir, null, 0);
    }

    /**
     * Same again, plus {@code dialHost}/{@code dialPort}: connect there instead
     * of to the node's own address, keeping everything else identical. Used for
     * the P2P relay hop, where a local bridge (p2p/P2pRelayConnector) forwards
     * the connection to the node through somebody else's device.
     *
     * Only the TCP destination changes. The Reality serverName, the fingerprint,
     * the UUID and the transport stay exactly as the node issued them, because
     * the VLESS/Reality session is negotiated end-to-end with that node —
     * rewriting the SNI would break the handshake, and would mean the relay was
     * reading traffic this design deliberately keeps opaque to it.
     */
    public static String build(
            VlessUri vless, String fingerprint, int tunFd, int mtu,
            String transport, Integer grpcPort, String grpcServiceName, String xrayAssetDir,
            String dialHost, int dialPort) {
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

        // env.xray.tun.fd — see class javadoc. XRAY_LOCATION_ASSET is Xray-core's
        // own standard geoip/geosite asset-directory env var (not an app-specific
        // hack like tun.fd) — set here via the same env->os.Setenv plumbing.
        JsonObject env = new JsonObject();
        env.addProperty("xray.tun.fd", String.valueOf(tunFd));
        if (xrayAssetDir != null) {
            env.addProperty("XRAY_LOCATION_ASSET", xrayAssetDir);
        }
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
        outbounds.add(buildProxyOutbound(vless, fingerprint, useGrpc, grpcPort, grpcServiceName, dialHost, dialPort));
        outbounds.add(buildDnsOutbound());
        outbounds.add(buildBlockOutbound());
        root.add("outbounds", outbounds);

        root.add("routing", buildRouting());

        return root.toString();
    }

    /**
     * The config for a session whose exit is another user's device rather
     * than a node of ours (docs/research/P2P_RELAY_FEASIBILITY.md §8.9).
     *
     * Same TUN inbound, same DoH, same private-range rule as {@link #build} —
     * the only difference is where matched traffic goes: a SOCKS5 outbound
     * into the local P2P bridge (p2p/P2pRelayConnector#forExit), which turns
     * every connection into its own WebRTC session to the peer, who dials the
     * site itself. There is no VLESS/Reality outbound here at all, because
     * there is no node of ours in the path to speak it to; the hop to the
     * peer is encrypted by WebRTC's own DTLS.
     *
     * UDP is blocked outright rather than left to fail late. A peer forwards
     * a TCP stream and nothing else, and a SOCKS5 outbound with no UDP
     * ASSOCIATE behind it would let QUIC look available and then black-hole
     * it — the classic "most sites work, some just hang". DNS is unaffected:
     * it is answered locally over DoH, which is TCP.
     */
    public static String buildP2pExit(int tunFd, int mtu, String xrayAssetDir, String bridgeHost, int bridgePort) {
        JsonObject root = new JsonObject();

        JsonObject log = new JsonObject();
        log.addProperty("loglevel", "warning");
        root.add("log", log);

        JsonObject env = new JsonObject();
        env.addProperty("xray.tun.fd", String.valueOf(tunFd));
        if (xrayAssetDir != null) {
            env.addProperty("XRAY_LOCATION_ASSET", xrayAssetDir);
        }
        root.add("env", env);

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

        JsonObject proxy = new JsonObject();
        proxy.addProperty("tag", PROXY_OUTBOUND_TAG);
        proxy.addProperty("protocol", "socks");
        JsonObject server = new JsonObject();
        server.addProperty("address", bridgeHost);
        server.addProperty("port", bridgePort);
        JsonArray servers = new JsonArray();
        servers.add(server);
        JsonObject proxySettings = new JsonObject();
        proxySettings.add("servers", servers);
        proxy.add("settings", proxySettings);

        JsonArray outbounds = new JsonArray();
        // First outbound is Xray's default match for anything no rule covers.
        outbounds.add(proxy);
        outbounds.add(buildDnsOutbound());
        outbounds.add(buildBlockOutbound());
        root.add("outbounds", outbounds);

        JsonObject routing = buildRouting();
        JsonArray rules = routing.getAsJsonArray("rules");
        JsonObject blockUdp = new JsonObject();
        blockUdp.addProperty("type", "field");
        blockUdp.addProperty("network", "udp");
        // Carved around :53 so DNS still reaches the dns outbound above.
        blockUdp.addProperty("port", "1-52,54-65535");
        blockUdp.addProperty("outboundTag", BLOCK_OUTBOUND_TAG);
        JsonArray withUdpBlocked = new JsonArray();
        withUdpBlocked.add(blockUdp);
        rules.forEach(withUdpBlocked::add);
        routing.add("rules", withUdpBlocked);
        root.add("routing", routing);

        return root.toString();
    }

    /** Where {@link #withProbeInbound} listens (loopback only). */
    public static final int PROBE_PORT = 10810;
    public static final String PROBE_INBOUND_TAG = "probe-in";

    /**
     * Adds a loopback HTTP inbound that always routes to the proxy outbound,
     * for the service's own liveness probe.
     *
     * Needed whenever the path runs through another user's device: the app
     * is then kept out of its own TUN (its WebRTC sockets would otherwise be
     * captured by the tunnel they are supposed to carry — see
     * XrayVpnService#ensureTunEstablished), so a probe sent the ordinary way
     * would bypass the tunnel and prove nothing. Through this inbound it
     * crosses exactly the path user traffic takes.
     */
    public static String withProbeInbound(String configJson, int port) {
        JsonObject root = com.google.gson.JsonParser.parseString(configJson).getAsJsonObject();
        JsonObject inbound = new JsonObject();
        inbound.addProperty("tag", PROBE_INBOUND_TAG);
        inbound.addProperty("protocol", "http");
        inbound.addProperty("listen", "127.0.0.1");
        inbound.addProperty("port", port);
        root.getAsJsonArray("inbounds").add(inbound);

        JsonObject routing = root.getAsJsonObject("routing");
        JsonObject rule = new JsonObject();
        rule.addProperty("type", "field");
        JsonArray tags = new JsonArray();
        tags.add(PROBE_INBOUND_TAG);
        rule.add("inboundTag", tags);
        rule.addProperty("outboundTag", PROXY_OUTBOUND_TAG);
        JsonArray rules = new JsonArray();
        rules.add(rule); // ahead of everything, the private-range block included
        routing.getAsJsonArray("rules").forEach(rules::add);
        routing.add("rules", rules);
        return root.toString();
    }

    private static JsonObject buildTunInbound(int mtu) {
        JsonObject inbound = new JsonObject();
        inbound.addProperty("tag", TUN_INBOUND_TAG);
        inbound.addProperty("protocol", "tun");
        JsonObject settings = new JsonObject();
        // An explicit name is required on Android, even though the name itself
        // is never used there (the VpnService fd from env.xray.tun.fd is).
        // Left empty, Xray-core's TunConfig.Build picks a free "utunN" name via
        // net.Interfaces(), which needs a netlink RTM_GETADDR dump that Android
        // 11+ denies to apps — every connect then failed with "fail to get
        // system interface information: route ip+net: netlinkrib: permission
        // denied" (reproduced on an API 34 emulator).
        settings.addProperty("name", "tun0");
        settings.addProperty("mtu", mtu);
        inbound.add("settings", settings);
        return inbound;
    }

    private static JsonObject buildProxyOutbound(
            VlessUri vless, String fingerprint, boolean useGrpc, Integer grpcPort, String grpcServiceName,
            String dialHost, int dialPort) {
        JsonObject outbound = new JsonObject();
        outbound.addProperty("tag", PROXY_OUTBOUND_TAG);
        outbound.addProperty("protocol", "vless");

        JsonObject vnext = new JsonObject();
        vnext.addProperty("address", dialHost != null ? dialHost : vless.getHost());
        // Same node, different port when falling back to gRPC — see
        // NodeManagementService#buildNodeConfigSync on the server (Phase 9).
        vnext.addProperty("port", dialHost != null ? dialPort : (useGrpc ? grpcPort : vless.getPort()));
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
