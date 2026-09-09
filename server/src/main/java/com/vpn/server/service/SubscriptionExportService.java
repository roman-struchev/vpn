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

    @Transactional
    public String exportVlessSubscription(Long userId) {
        Subscription sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .orElseThrow(() -> new IllegalStateException("Active subscription not found"));

        if ("trial".equalsIgnoreCase(sub.getTariff().getId())) {
            throw new IllegalStateException("Subscription link export is available for paid plans only");
        }

        if (sub.getCurrentPeriodEnd().isBefore(Instant.now())) {
            throw new IllegalStateException("Subscription has expired");
        }

        List<Device> devices = deviceRepository.findByUserIdAndIsActiveTrue(userId);
        if (devices.isEmpty()) {
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
