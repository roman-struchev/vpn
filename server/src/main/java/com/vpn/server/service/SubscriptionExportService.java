package com.vpn.server.service;

import com.vpn.server.entity.*;
import com.vpn.server.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SubscriptionExportService {

    private final SubscriptionRepository subscriptionRepository;
    private final DeviceRepository deviceRepository;
    private final NodeRepository nodeRepository;
    private final DeviceNodeKeyRepository deviceNodeKeyRepository;
    private final NodeManagementService nodeManagementService;

    /** Null in tests that don't care; see P2pReachabilityService. */
    private P2pReachabilityService reachability;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReachability(P2pReachabilityService reachability) {
        this.reachability = reachability;
    }

    public SubscriptionExportService(
            SubscriptionRepository subscriptionRepository,
            DeviceRepository deviceRepository,
            NodeRepository nodeRepository,
            DeviceNodeKeyRepository deviceNodeKeyRepository,
            NodeManagementService nodeManagementService) {
        this.subscriptionRepository = subscriptionRepository;
        this.deviceRepository = deviceRepository;
        this.nodeRepository = nodeRepository;
        this.deviceNodeKeyRepository = deviceNodeKeyRepository;
        this.nodeManagementService = nodeManagementService;
    }

    /**
     * For the *public*, token-based export ({@code /api/v1/subscription/export/{token}}) —
     * consumed by arbitrary third-party clients (v2rayTun, Clash, ...) that
     * never authenticate as this user. Open to the trial too (it used to be
     * paid-only, while the dashboard and the bot offered the link to everyone
     * — and on iOS it is the only way in); the device limit and the trial's
     * traffic quota are what bound a shared link. Auto-creates a "Primary Device" placeholder
     * if the user has none — the only way a third-party client can get a
     * working link before ever touching the web dashboard or an app.
     */
    @Transactional
    public List<String> exportVlessLinks(Long userId) {
        return exportVlessLinks(userId, true, null).links();
    }

    /**
     * For the *authenticated* path ({@code GET /api/v1/user/subscription/links})
     * — our own first-party apps (Android/Desktop) and the web dashboard's
     * "copy link" button: a logged-in user fetching their own credentials.
     *
     * Does NOT auto-create a "Primary Device" placeholder (returns an empty
     * list instead if the user has no devices yet) — DeviceManagementService.
     * addDevice and the native clients' auto-register-on-connect
     * (XrayVpnService / VpnController.registerOrTouchDevice) are the real
     * device-creation paths now, and a silent placeholder here would eat a
     * trial account's one-and-only device slot the instant the dashboard
     * loads, before the user ever gets to actually use it — reproduced live
     * via the e2e suite.
     */
    @Transactional
    public List<String> exportVlessLinksForOwnApp(Long userId) {
        return exportVlessLinks(userId, false, null).links();
    }

    /**
     * Region-scoped variant of {@link #exportVlessLinksForOwnApp(Long)} — lets a
     * native client (desktop/Android region picker) restrict its subscription
     * links to nodes in one region instead of getting every online "paid" node.
     * {@code region} is matched case-insensitively against {@link Node#getRegion()};
     * when the requested region currently has no ONLINE node, this falls back to
     * the same full node list the unscoped export would return (today's implicit
     * "auto" behavior) rather than returning nothing — {@link RegionScopedLinks
     * #requestedRegionAvailable()} tells the caller whether that fallback kicked in,
     * so the client can restrict/prefer-first accordingly and still surface it to
     * the user (see docs on the desktop/Android region pickers).
     */
    @Transactional
    public RegionScopedLinks exportVlessLinksForOwnApp(Long userId, String region) {
        return exportVlessLinks(userId, false, region);
    }

    public record RegionScopedLinks(List<String> links, boolean requestedRegionAvailable) {}

    /** One region's aggregate load, cheap to compute from already-loaded heartbeat fields — see {@link #getAvailableRegions}. */
    public record RegionSummary(
            String region,
            int nodeCount,
            Double avgCpuPercent,
            long avgActiveConnections,
            Double avgBytesPerSec,
            Double avgMemoryPercent,
            String loadLevel,
            // Whether the caller's own subscription can actually connect through this
            // region right now — see getAvailableRegions. false does NOT mean "hide
            // this row": paid regions are deliberately still listed to a trial user
            // (so a lower tier can see, and be upsold on, what a higher plan
            // unlocks) — a client greys these out / disables picking them instead of
            // removing them, rather than reporting a misleading "temporarily
            // unavailable" the way silently connecting elsewhere used to.
            boolean accessible,
            // Stable identity of this row, and what a client persists as "the
            // region the user picked" — NOT {@link #region}, which is only a
            // display label now. A region can appear twice: once as regular VPS
            // capacity and once as P2P exit capacity (peers happen to live in
            // countries we also rent servers in), and those are two different
            // things to connect to. See {@link #keyFor}.
            String key,
            // A P2P exit row: the traffic leaves for the internet from another
            // user's own device, not from our infrastructure (docs/research/
            // P2P_RELAY_FEASIBILITY.md §8.9). Clients label these distinctly —
            // the exit IP is residential, the throughput is whatever that
            // person's uplink is, and it is a paid-plan feature.
            boolean p2p
    ) {}

    /** Prefix that makes a P2P exit row's {@link RegionSummary#key} distinct from the same region's VPS row. */
    public static final String P2P_KEY_PREFIX = "p2p:";

    /** The selection key a client stores and sends back — see {@link RegionSummary#key}. */
    public static String keyFor(String region, boolean p2p) {
        return p2p ? P2P_KEY_PREFIX + region : region;
    }

    /** Whether a client-supplied selection key names a P2P exit rather than a region of ours. */
    public static boolean isP2pKey(String key) {
        return key != null && key.startsWith(P2P_KEY_PREFIX);
    }

    /** The bare region inside a selection key, P2P-prefixed or not. */
    public static String regionFromKey(String key) {
        return isP2pKey(key) ? key.substring(P2P_KEY_PREFIX.length()) : key;
    }

    /**
     * Lists every region that currently has at least one ONLINE node, regardless
     * of pool — including ones the caller's own plan can't reach, so a trial
     * user still sees paid regions exist (see {@link RegionSummary#accessible}
     * doc) — with a rough per-region load indicator so a client can show "pick a
     * less-congested region" without exposing any node-level identity (hostname/
     * IP already only ever appears inside a vless link, never here). Not a
     * precise capacity/load-balancing model — see {@link #loadLevelFor} — just
     * enough for a user to glance and compare.
     */
    @Transactional(readOnly = true)
    public List<RegionSummary> getAvailableRegions(Long userId) {
        return getAvailableRegions(userId, null);
    }

    /**
     * As above, counting a P2P peer only if this client could actually go out
     * through it — the same peers GET /p2p/exits would hand this caller. A row
     * that said "1 peer" while the exit list came back empty made the app
     * quietly connect to another country instead.
     */
    @Transactional(readOnly = true)
    public List<RegionSummary> getAvailableRegions(Long userId, String clientIp) {
        Subscription sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .orElseThrow(() -> new IllegalStateException("Active subscription not found"));
        if (!"ACTIVE".equals(sub.getUser().getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }
        if (sub.isExpired()) {
            throw new IllegalStateException("Subscription has expired");
        }

        Tariff effectiveTariff = sub.getEffectiveTariff();

        // Mirrors exportVlessLinks' real selection rule: normally restricted to
        // the caller's own accessible nodes, but if none of them is ONLINE
        // anywhere right now, exportVlessLinks transparently serves any
        // ONLINE node instead — so in that edge case every region is
        // actually reachable too, not just the caller's usual access flag.
        // Excludes p2p nodes for the same reason exportVlessLinks does (see
        // findAccessibleOnlineNodesForVless) — no client can consume a p2p
        // node via this path yet, so it shouldn't count as real capacity here.
        boolean ownPoolHasCapacity = !findAccessibleOnlineNodesForVless(userId, effectiveTariff).isEmpty();

        // Never the caller's own relay device, whatever else it is (see
        // Node#isOwnRelayDeviceOf) — without that filter, turning P2P mode on
        // made your own laptop show up as a connection point in your own app.
        List<Node> onlineNodes = nodeRepository.findByStatus("ONLINE").stream()
                .filter(n -> !n.isOwnRelayDeviceOf(userId))
                .toList();

        // The VPS rows. P2P nodes are summarised separately below rather than
        // folded in here: their load figures are meaningless (a phone reports
        // no CPU/memory), their access rule is the tariff's paid/trial split
        // rather than per-node pool flags, and — the point of the split — a
        // region can now carry both kinds at once, which is two different
        // things to connect to under one label.
        List<Node> activeNodes = onlineNodes.stream()
                .filter(n -> !n.isP2p())
                .toList();

        Map<String, List<Node>> byRegion = activeNodes.stream()
                .filter(n -> n.getRegion() != null && !n.getRegion().isBlank())
                .collect(Collectors.groupingBy(Node::getRegion, LinkedHashMap::new, Collectors.toList()));

        List<RegionSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<Node>> entry : byRegion.entrySet()) {
            List<Node> nodes = entry.getValue();
            OptionalDouble avgCpuOpt = nodes.stream()
                    .map(Node::getCpuPercent)
                    .filter(Objects::nonNull)
                    .mapToDouble(BigDecimal::doubleValue)
                    .average();
            Double avgCpu = avgCpuOpt.isPresent() ? round1(avgCpuOpt.getAsDouble()) : null;
            long avgConnections = Math.round(nodes.stream()
                    .mapToInt(n -> n.getActiveConnections() != null ? n.getActiveConnections() : 0)
                    .average()
                    .orElse(0.0));

            OptionalDouble avgBytesPerSecOpt = nodes.stream()
                    .map(Node::getRecentBytesPerSec)
                    .filter(Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .average();
            Double avgBytesPerSec = avgBytesPerSecOpt.isPresent() ? round1(avgBytesPerSecOpt.getAsDouble()) : null;

            OptionalDouble avgMemoryPercentOpt = nodes.stream()
                    .filter(n -> n.getMemoryUsedBytes() != null && n.getMemoryTotalBytes() != null && n.getMemoryTotalBytes() > 0)
                    .mapToDouble(n -> 100.0 * n.getMemoryUsedBytes() / n.getMemoryTotalBytes())
                    .average();
            Double avgMemoryPercent = avgMemoryPercentOpt.isPresent() ? round1(avgMemoryPercentOpt.getAsDouble()) : null;

            // With p2p nodes already filtered out above, a false here means
            // exactly one thing — this region's nodes are not in the caller's
            // pool — which is what the clients' "requires a paid plan" label
            // actually claims.
            boolean accessible = !ownPoolHasCapacity
                    || nodes.stream().anyMatch(n -> accessibleViaFlags(n, effectiveTariff));

            summaries.add(new RegionSummary(entry.getKey(), nodes.size(), avgCpu, avgConnections,
                    avgBytesPerSec, avgMemoryPercent,
                    loadLevelFor(avgCpu, avgConnections, avgBytesPerSec, avgMemoryPercent),
                    accessible, keyFor(entry.getKey(), false), false));
        }

        summaries.addAll(p2pExitSummaries(onlineNodes, effectiveTariff, clientIp));

        // Region first (so the two kinds of a given country sit together),
        // then key — which puts the VPS row ahead of the "p2p:"-prefixed one.
        summaries.sort(Comparator.comparing(RegionSummary::region).thenComparing(RegionSummary::key));
        return summaries;
    }

    /**
     * The P2P exit rows: one per region that currently has at least one peer
     * willing to carry traffic out to the internet for somebody else
     * (docs/research/P2P_RELAY_FEASIBILITY.md §8.9).
     *
     * A P2P exit is a different product from a VPS region and is summarised
     * differently on purpose:
     *
     * <ul>
     *   <li><b>Access is the tariff's paid/trial split, not the node's pool
     *       flags.</b> A peer does not belong to a pool anyone provisioned —
     *       it is somebody's phone. Trial accounts see the row locked (the
     *       clients' padlock) rather than hidden, which is the same upsell
     *       treatment paid VPS regions already get.</li>
     *   <li><b>No CPU/memory figures.</b> A relay agent reports neither (see
     *       the admin node table, where a p2p row shows "CPU: 0% / Mem: —"),
     *       so publishing an averaged 0% here would read as "idle and fast"
     *       when it actually means "not measured". The load hint is derived
     *       from how many sessions the peers are already carrying, which is
     *       the one signal that is real.</li>
     *   <li><b>Grouped by region, not listed per device.</b> Nobody picks a
     *       stranger's particular phone; they pick "somewhere in Montenegro,
     *       through a person rather than a server". The client asks
     *       {@code GET /p2p/exits?region=...} for the actual peers and works
     *       down that list, so one peer going offline mid-session is a
     *       reconnect rather than a dead row in the picker.</li>
     * </ul>
     */
    private List<RegionSummary> p2pExitSummaries(List<Node> onlineNodes, Tariff effectiveTariff, String clientIp) {
        String pool = (effectiveTariff != null && effectiveTariff.getServerPool() != null)
                ? effectiveTariff.getServerPool() : "paid";
        boolean trial = "trial".equalsIgnoreCase(pool);

        Map<String, List<Node>> byRegion = onlineNodes.stream()
                .filter(Node::isP2p)
                // A lapsed TIMED window means this peer is not offering itself
                // any more, even though its last heartbeat left it ONLINE —
                // same gate P2pRelayDirectory applies before handing one out.
                .filter(Node::isEligibleForRelay)
                // Still being checked, failed the check, or behind this
                // client's own carrier NAT — P2pRelayDirectory leaves these
                // out of the exit list, so they are not capacity either.
                .filter(n -> reachability == null
                        || (reachability.isOffered(n.getId()) && !reachability.sameCarrierNetwork(n.getId(), clientIp)))
                .filter(n -> n.getRegion() != null && !n.getRegion().isBlank())
                .collect(Collectors.groupingBy(Node::getRegion, LinkedHashMap::new, Collectors.toList()));

        List<RegionSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<Node>> entry : byRegion.entrySet()) {
            List<Node> peers = entry.getValue();
            long avgConnections = Math.round(peers.stream()
                    .mapToInt(n -> n.getActiveConnections() != null ? n.getActiveConnections() : 0)
                    .average()
                    .orElse(0.0));
            summaries.add(new RegionSummary(
                    entry.getKey(), peers.size(), null, avgConnections, null, null,
                    loadLevelFor(null, avgConnections, null, null),
                    !trial, keyFor(entry.getKey(), true), true));
        }
        return summaries;
    }

    /**
     * Whether a tariff's own access flag is set on this node (docs/research/
     * P2P_RELAY_FEASIBILITY.md §8.3) — availableToTrial/availableToPaid are
     * independent per-node booleans now, not a single exclusive pool string;
     * a P2P node can have both set. See V11 migration's backfill for how
     * existing nodes were mapped so this reproduces prior pool-based access
     * exactly (paid tariffs already reached "trial"-pool nodes too, so those
     * nodes got availableToPaid=true as well at backfill time — no runtime
     * hierarchy logic needed here).
     */
    private static boolean accessibleViaFlags(Node node, Tariff tariff) {
        String pool = (tariff != null && tariff.getServerPool() != null) ? tariff.getServerPool() : "paid";
        return "trial".equalsIgnoreCase(pool)
                ? Boolean.TRUE.equals(node.getAvailableToTrial())
                : Boolean.TRUE.equals(node.getAvailableToPaid());
    }

    /**
     * Online nodes the given tariff can actually connect through right now,
     * VLESS-dialable p2p nodes excluded (see findAccessibleOnlineNodesForVless).
     *
     * The caller's own relay device is dropped here rather than only at the
     * call sites, so the "never route a user through their own phone/laptop"
     * rule (Node#isOwnRelayDeviceOf) holds for whatever consumes this next —
     * in particular once p2p nodes do become dialable (docs §8.1 phase 2/3)
     * and the blanket p2p exclusion below goes away.
     */
    private List<Node> findAccessibleOnlineNodes(Long userId, Tariff tariff) {
        String pool = (tariff != null && tariff.getServerPool() != null) ? tariff.getServerPool() : "paid";
        List<Node> nodes = "trial".equalsIgnoreCase(pool)
                ? nodeRepository.findByAvailableToTrialTrueAndStatus("ONLINE")
                : nodeRepository.findByAvailableToPaidTrueAndStatus("ONLINE");
        return nodes.stream().filter(n -> !n.isOwnRelayDeviceOf(userId)).toList();
    }

    /**
     * Same as {@link #findAccessibleOnlineNodes} but excludes type="p2p"
     * nodes — this is the path that actually calls {@link #buildVlessUrl},
     * which embeds node.getPublicIp() into a direct vless://ip:443 link a
     * client would try to dial straight at. A P2P node's publicIp isn't a
     * real reachable address (docs §8.4) — it needs the WebRTC signaling
     * flow instead (see AgentStreamServiceImpl's p2p_signal routing), which
     * has no client-side consumer yet (phase 2/3). Until that exists, a p2p
     * node must never end up producing a direct-dial link here.
     */
    private List<Node> findAccessibleOnlineNodesForVless(Long userId, Tariff tariff) {
        return findAccessibleOnlineNodes(userId, tariff).stream().filter(n -> !n.isP2p()).toList();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // Relative weights for the signals blended in loadLevelFor, in "how
    // directly does this reflect actual proxy load" order: throughput is what
    // users are actually here to consume, CPU is the (now accurately sampled)
    // secondary signal, memory the lightest one. Judgment calls, not measured
    // figures — see loadLevelFor's doc comment.
    private static final double THROUGHPUT_WEIGHT = 0.5;
    private static final double CPU_WEIGHT = 0.35;
    private static final double MEMORY_WEIGHT = 0.15;

    // Below this, treat a node as "not really pushing traffic" for the
    // zero-connections cap — a few stray bytes/sec of TCP keepalive noise
    // shouldn't prevent the cap from kicking in.
    private static final double NEGLIGIBLE_BYTES_PER_SEC = 1024.0;

    /**
     * Simple LOW/MEDIUM/HIGH bucket blended from several already-reported
     * heartbeat/traffic-stats signals, weighted by how directly each one
     * reflects "is this node actually busy right now" for a proxy workload:
     *
     * <ul>
     *   <li><b>Throughput</b> — {@code avgBytesPerSec}, a rolling bytes/sec
     *       figure computed in NodeManagementService#processTrafficStats. The
     *       most honest signal for a proxy: it's literally the thing users
     *       are here to consume. Mapped onto the 0-100 scale via
     *       {@link #throughputScore}.</li>
     *   <li><b>CPU</b> — {@code avgCpuPercent}, sampled directly over the
     *       heartbeat interval (NodeManagementService#processHeartbeat, fed by
     *       the agent's os.cpus()-diff in AgentGrpcClient#computeCpuPercent).
     *       When absent (a node that hasn't reported CPU), falls back to the
     *       old connections-based estimate — this fallback always applies, so
     *       CPU (or its stand-in) never drops out of the blend entirely.</li>
     *   <li><b>Memory</b> — {@code avgMemoryPercent}, used-of-total from the
     *       same heartbeat. A lighter signal on its own (high memory usage
     *       alone doesn't mean the node can't take more traffic), but a real
     *       "avoid this one" nudge when combined with the others.</li>
     * </ul>
     *
     * Throughput and memory readings aren't always available (a node that
     * hasn't sent a traffic-stats report yet, one running an agent build
     * that predates this signal, etc.), so the weights above (THROUGHPUT_
     * WEIGHT / CPU_WEIGHT / MEMORY_WEIGHT) are renormalized over whichever
     * signals are actually present rather than treating a missing one as 0 —
     * otherwise a node with only CPU data would always look artificially
     * under-loaded just because throughput/memory weren't reported.
     *
     * Deliberately not a real capacity model (no per-node bandwidth/connection
     * ceiling is tracked, and the weights above are a judgment call, not a
     * measured formula) — just enough for a user to pick a less-congested
     * region at a glance, per the product ask.
     *
     * When avgActiveConnections is 0 and there's no meaningful recent
     * throughput either, the blended score is capped from pushing the result
     * past MEDIUM: zero connected users with zero bytes moving is an
     * unambiguous "nobody is using this region right now" signal, and host
     * CPU/memory can still read nonzero from things with nothing to do with
     * VPN traffic (OS housekeeping, monitoring agents, a residual reading
     * right after a burst of unrelated activity, etc.) — an idle-of-traffic
     * region should never look like the busiest choice.
     */
    private static String loadLevelFor(Double avgCpuPercent, long avgActiveConnections,
                                        Double avgBytesPerSec, Double avgMemoryPercent) {
        double cpu = avgCpuPercent != null
                ? avgCpuPercent
                : Math.min(100.0, avgActiveConnections / 2.0);

        double weightedSum = cpu * CPU_WEIGHT;
        double totalWeight = CPU_WEIGHT;

        if (avgBytesPerSec != null) {
            weightedSum += throughputScore(avgBytesPerSec) * THROUGHPUT_WEIGHT;
            totalWeight += THROUGHPUT_WEIGHT;
        }
        if (avgMemoryPercent != null) {
            weightedSum += avgMemoryPercent * MEMORY_WEIGHT;
            totalWeight += MEMORY_WEIGHT;
        }

        double score = weightedSum / totalWeight;

        boolean noRecentTraffic = avgBytesPerSec == null || avgBytesPerSec < NEGLIGIBLE_BYTES_PER_SEC;
        if (avgActiveConnections == 0 && noRecentTraffic) {
            score = Math.min(score, 74.0);
        }

        if (score < 40) return "LOW";
        if (score < 75) return "MEDIUM";
        return "HIGH";
    }

    /**
     * Maps a bytes/sec figure onto the same 0-100 scale as the other signals.
     * No per-node bandwidth ceiling is tracked (see {@link #loadLevelFor}), so
     * this uses a round, deliberately conservative reference point of 200
     * Mbps sustained as "fully loaded" for a single node's share of the
     * indicator — good enough for a glance-able comparison between regions,
     * not a claim about actual node capacity.
     */
    private static double throughputScore(double bytesPerSec) {
        double mbps = (bytesPerSec * 8.0) / 1_000_000.0;
        double referenceMbps = 200.0;
        return Math.min(100.0, (mbps / referenceMbps) * 100.0);
    }

    private RegionScopedLinks exportVlessLinks(Long userId, boolean autoCreatePrimaryDevice, String region) {
        Subscription sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .orElseThrow(() -> new IllegalStateException("Active subscription not found"));

        if (!"ACTIVE".equals(sub.getUser().getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }

        Tariff effectiveTariff = sub.getEffectiveTariff();

        if (sub.isExpired()) {
            throw new IllegalStateException("Subscription has expired");
        }

        List<Device> devices = deviceRepository.findByUserIdAndIsActiveTrue(userId);
        if (devices.isEmpty()) {
            if (!autoCreatePrimaryDevice) {
                return new RegionScopedLinks(List.of(), true);
            }
            Device defaultDev = new Device();
            defaultDev.setUser(sub.getUser());
            defaultDev.setDeviceName("Primary Device");
            defaultDev.setPlatform("THIRD_PARTY");
            defaultDev = deviceRepository.save(defaultDev);
            devices = List.of(defaultDev);
        }

        Device primaryDevice = devices.get(0);
        List<Node> activeNodes = findAccessibleOnlineNodesForVless(userId, effectiveTariff);
        if (activeNodes.isEmpty()) {
            // Same p2p exclusion as findAccessibleOnlineNodesForVless — this
            // fallback must never hand out a p2p node's direct-dial link either.
            activeNodes = nodeRepository.findByStatus("ONLINE").stream().filter(n -> !n.isP2p()).toList();
        }

        boolean requestedRegionAvailable = true;
        if (region != null && !region.isBlank()) {
            List<Node> inRegion = activeNodes.stream()
                    .filter(n -> region.equalsIgnoreCase(n.getRegion()))
                    .toList();
            requestedRegionAvailable = !inRegion.isEmpty();
            if (requestedRegionAvailable) {
                // Sane fallback per the product spec: an unavailable region silently
                // falls back to the full (today's "auto") node list below rather than
                // leaving the client with zero links to connect through.
                activeNodes = inRegion;
            }
        }

        List<String> links = new ArrayList<>();

        for (Node node : activeNodes) {
            final Node currentNode = node;
            DeviceNodeKey key = deviceNodeKeyRepository.findByDeviceIdAndNodeId(primaryDevice.getId(), node.getId())
                    .orElseGet(() -> {
                        DeviceNodeKey newKey = new DeviceNodeKey(primaryDevice, currentNode, UUID.randomUUID());
                        return deviceNodeKeyRepository.save(newKey);
                    });

            // Backfills REALITY keys for a node whose row predates key generation
            // (or whose agent never re-registered to pick it up) — otherwise this
            // node hands out a VLESS link with an empty "pbk" forever, which fails
            // client-side with "infra/conf: empty publicKey" at xray startup.
            nodeManagementService.ensureRealityKeyMaterial(node);

            String vlessLink = buildVlessUrl(node, key.getUuid());
            links.add(vlessLink);
        }

        return new RegionScopedLinks(links, requestedRegionAvailable);
    }

    @Transactional
    public String exportVlessSubscription(Long userId) {
        List<String> links = exportVlessLinks(userId);
        String rawContent = String.join("\n", links);
        return Base64.getEncoder().encodeToString(rawContent.getBytes(StandardCharsets.UTF_8));
    }

    /** Separates the user-facing region from the operator-facing node hostname in a vless link's remark. */
    public static final String REMARK_SEPARATOR = " \u00b7 ";

    private String buildVlessUrl(Node node, UUID uuid) {
        String sni = "dl.google.com";
        String pbk = node.getRealityPublicKey() != null ? node.getRealityPublicKey() : "";
        String sid = (node.getRealityShortIds() != null && node.getRealityShortIds().length > 0)
                ? node.getRealityShortIds()[0]
                : "0123456789abcdef";
        // URLEncoder.encode is form encoding (space -> "+"), not RFC 3986 percent-encoding —
        // clients decode this URI fragment with decodeURIComponent, which leaves a literal
        // "+" alone instead of turning it back into a space, so a form-encoded remark showed
        // up as garbled text like "India,+Mumbai-vmi3163824" instead of "India, Mumbai-vmi3163824".
        // " \u00b7 " (and not "-"): our own clients show only the region part of this
        // label to the user, and a hostname like "centos-4gb-hel1-2" makes a "-"
        // separator impossible to split on — the desktop/Android UI ended up
        // showing "India, Mumbai-vmi3163824" as the region name.
        String remark = URLEncoder.encode(node.getRegion() + REMARK_SEPARATOR + node.getHostname(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        // VLESS + XHTTP + Reality URL. fp is explicit: Xray itself falls back to
        // chrome without it, but third-party clients (Happ, v2rayTun, Hiddify)
        // each read a missing fp their own way. firefox = TransportPolicy's
        // default, what our own apps present too (they pass it separately and
        // ignore this parameter).
        return String.format(
                "vless://%s@%s:443?encryption=none&security=reality&type=xhttp&path=%%2Fvless-xhttp&sni=%s&fp=firefox&pbk=%s&sid=%s#%s",
                uuid.toString(),
                node.getPublicIp(),
                sni,
                pbk,
                sid,
                remark
        );
    }
}
