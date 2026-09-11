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
            String loadLevel
    ) {}

    /**
     * Lists every region that currently has at least one ONLINE node reachable
     * by this user's subscription (same pool-selection rule as
     * {@link #exportVlessLinksForOwnApp(Long)}: "paid" pool, falling back to any
     * ONLINE node if that pool is empty), with a rough per-region load indicator
     * so a client can show "pick a less-congested region" without exposing any
     * node-level identity (hostname/IP already only ever appears inside a vless
     * link, never here). Not a precise capacity/load-balancing model — see
     * {@link #loadLevelFor} — just enough for a user to glance and compare.
     */
    @Transactional(readOnly = true)
    public List<RegionSummary> getAvailableRegions(Long userId) {
        Subscription sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .orElseThrow(() -> new IllegalStateException("Active subscription not found"));
        if (sub.getCurrentPeriodEnd().isBefore(Instant.now())) {
            throw new IllegalStateException("Subscription has expired");
        }

        List<Node> activeNodes = nodeRepository.findByPoolAndStatus("paid", "ONLINE");
        if (activeNodes.isEmpty()) {
            activeNodes = nodeRepository.findByStatus("ONLINE");
        }

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

            summaries.add(new RegionSummary(entry.getKey(), nodes.size(), avgCpu, avgConnections,
                    loadLevelFor(avgCpu, avgConnections)));
        }
        summaries.sort(Comparator.comparing(RegionSummary::region));
        return summaries;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Simple LOW/MEDIUM/HIGH bucket from average CPU% (primary signal — directly
     * reported by the agent heartbeat, NodeManagementService#processHeartbeat)
     * blended with active-connections-per-node as a fallback/secondary signal
     * for nodes that don't report CPU. Deliberately not a real capacity model
     * (no per-node bandwidth/connection ceiling is tracked) — just enough for a
     * user to pick a less-congested region at a glance, per the product ask.
     *
     * When avgActiveConnections is 0, CPU is capped from pushing the result past
     * MEDIUM: zero connected users is an unambiguous "nobody is using this
     * region right now" signal, and host CPU can still read nonzero from things
     * with nothing to do with VPN traffic (OS housekeeping, monitoring agents,
     * a residual reading right after a burst of unrelated activity, etc.) — an
     * idle-of-traffic region should never look like the busiest choice.
     */
    private static String loadLevelFor(Double avgCpuPercent, long avgActiveConnections) {
        double score = avgCpuPercent != null
                ? avgCpuPercent
                : Math.min(100.0, avgActiveConnections / 2.0);
        if (avgActiveConnections == 0) {
            score = Math.min(score, 74.0);
        }
        if (score < 40) return "LOW";
        if (score < 75) return "MEDIUM";
        return "HIGH";
    }

    private RegionScopedLinks exportVlessLinks(Long userId, boolean restrictToPaidPlans, boolean autoCreatePrimaryDevice, String region) {
        Subscription sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .orElseThrow(() -> new IllegalStateException("Active subscription not found"));

        if (restrictToPaidPlans && "trial".equalsIgnoreCase(sub.getTariff().getId())) {
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
        List<Node> activeNodes = nodeRepository.findByPoolAndStatus("paid", "ONLINE");
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
        String remark = URLEncoder.encode(node.getRegion() + "-" + node.getHostname(), StandardCharsets.UTF_8);

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
