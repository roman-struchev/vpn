package com.vpn.server.service;

import com.vpn.server.entity.*;
import com.vpn.server.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class SubscriptionExportService {

    private final SubscriptionRepository subscriptionRepository;
    private final DeviceRepository deviceRepository;
    private final NodeRepository nodeRepository;
    private final DeviceNodeKeyRepository deviceNodeKeyRepository;

    public SubscriptionExportService(
            SubscriptionRepository subscriptionRepository,
            DeviceRepository deviceRepository,
            NodeRepository nodeRepository,
            DeviceNodeKeyRepository deviceNodeKeyRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.deviceRepository = deviceRepository;
        this.nodeRepository = nodeRepository;
        this.deviceNodeKeyRepository = deviceNodeKeyRepository;
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
        return exportVlessLinks(userId, true, true);
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
        return exportVlessLinks(userId, false, false);
    }

    private List<String> exportVlessLinks(Long userId, boolean restrictToPaidPlans, boolean autoCreatePrimaryDevice) {
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
                return List.of();
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

        List<String> links = new ArrayList<>();

        for (Node node : activeNodes) {
            final Node currentNode = node;
            DeviceNodeKey key = deviceNodeKeyRepository.findByDeviceIdAndNodeId(primaryDevice.getId(), node.getId())
                    .orElseGet(() -> {
                        DeviceNodeKey newKey = new DeviceNodeKey(primaryDevice, currentNode, UUID.randomUUID());
                        return deviceNodeKeyRepository.save(newKey);
                    });

            String vlessLink = buildVlessUrl(node, key.getUuid());
            links.add(vlessLink);
        }

        return links;
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
