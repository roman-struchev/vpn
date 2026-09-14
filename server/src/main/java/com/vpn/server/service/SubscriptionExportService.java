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
     * never authenticate as this user, so a leaked/shared link is a real risk
     * (docs/PLAN.md §1: "subscription-ссылка vless:// — только платным").
     * Trial stays excluded here. Auto-creates a "Primary Device" placeholder
     * if the user has none — the only way a third-party client can get a
     * working link before ever touching the web dashboard or an app.
     */
    @Transactional
    public List<String> exportVlessLinks(Long userId) {
        return exportVlessLinks(userId, true, true, null).links();
    }

    /**
     * For the *authenticated* path ({@code GET /api/v1/user/subscription/links})
     * — our own first-party apps (Android/Desktop) and the web dashboard's
     * "copy link" button. These aren't the "give away a portable link"
     * scenario PLAN.md's restriction targets: it's a logged-in user fetching
     * their own credentials to connect through the official app, which is
     * exactly what a trial is for. Excluding trial here made the native
     * clients unable to ever connect during a trial at all — reported live:
     * "Failed to load VPN profile ApiError: Subscription link export is
     * available for paid plans only" from the Desktop app on a trial account.
     *
     * Does NOT auto-create a "Primary Device" placeholder (returns an empty
     * list instead if the user has no devices yet) — DeviceManagementService.
     * addDevice and the native clients' auto-register-on-connect
     * (XrayVpnService / VpnController.registerOrTouchDevice) are the real
     * device-creation paths now, and a silent placeholder here would eat a
     * trial account's one-and-only device slot the instant the dashboard
     * loads, before the user ever gets to actually use it — reproduced live
     * via the e2e suite once the trial restriction above was lifted.
     */
    @Transactional
    public List<String> exportVlessLinksForOwnApp(Long userId) {
        return exportVlessLinks(userId, false, false, null).links();
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
        return exportVlessLinks(userId, false, false, region);
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
            boolean accessible
    ) {}

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
        Subscription sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .orElseThrow(() -> new IllegalStateException("Active subscription not found"));
        if (!"ACTIVE".equals(sub.getUser().getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }
        if (sub.getCurrentPeriodEnd().isBefore(Instant.now())) {
            throw new IllegalStateException("Subscription has expired");
        }

        Tariff effectiveTariff = sub.getEffectiveTariff();
        String targetPool = (effectiveTariff != null && effectiveTariff.getServerPool() != null)
                ? effectiveTariff.getServerPool()
                : "paid";

        // Mirrors exportVlessLinks' real selection rule: normally restricted to
        // the caller's own pool, but if that whole pool has no ONLINE node
        // anywhere right now, exportVlessLinks transparently serves any ONLINE
        // node instead — so in that edge case every region is actually reachable
        // too, not just the caller's usual pool.
        boolean ownPoolHasCapacity = !nodeRepository.findByPoolAndStatus(targetPool, "ONLINE").isEmpty();

        List<Node> activeNodes = nodeRepository.findByStatus("ONLINE");

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

            boolean accessible = !ownPoolHasCapacity
                    || nodes.stream().anyMatch(n -> targetPool.equalsIgnoreCase(n.getPool()));

            summaries.add(new RegionSummary(entry.getKey(), nodes.size(), avgCpu, avgConnections,
                    avgBytesPerSec, avgMemoryPercent,
                    loadLevelFor(avgCpu, avgConnections, avgBytesPerSec, avgMemoryPercent),
                    accessible));
        }
        summaries.sort(Comparator.comparing(RegionSummary::region));
        return summaries;
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

    private RegionScopedLinks exportVlessLinks(Long userId, boolean restrictToPaidPlans, boolean autoCreatePrimaryDevice, String region) {
        Subscription sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .orElseThrow(() -> new IllegalStateException("Active subscription not found"));

        if (!"ACTIVE".equals(sub.getUser().getStatus())) {
            throw new IllegalStateException("Account is suspended or blocked");
        }

        Tariff effectiveTariff = sub.getEffectiveTariff();

        if (restrictToPaidPlans && "trial".equalsIgnoreCase(effectiveTariff.getId())) {
            throw new IllegalStateException("Subscription link export is available for paid plans only");
        }

        if (sub.getCurrentPeriodEnd().isBefore(Instant.now())) {
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
        String targetPool = (effectiveTariff != null && effectiveTariff.getServerPool() != null)
                ? effectiveTariff.getServerPool()
                : "paid";
        List<Node> activeNodes = nodeRepository.findByPoolAndStatus(targetPool, "ONLINE");
        if (activeNodes.isEmpty()) {
            activeNodes = nodeRepository.findByStatus("ONLINE");
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
        String remark = URLEncoder.encode(node.getRegion() + "-" + node.getHostname(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        // VLESS + XHTTP + Reality URL
        return String.format(
                "vless://%s@%s:443?encryption=none&security=reality&type=xhttp&path=%%2Fvless-xhttp&sni=%s&pbk=%s&sid=%s#%s",
                uuid.toString(),
                node.getPublicIp(),
                sni,
                pbk,
                sid,
                remark
        );
    }
}
