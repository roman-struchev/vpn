package com.vpn.server.service;

import com.vpn.server.entity.DeviceNodeKey;
import com.vpn.server.entity.SubscriptionAccessLog;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.DeviceNodeKeyRepository;
import com.vpn.server.repository.SubscriptionAccessLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Phase 10 hardening: detects a single account whose subscription/export
 * endpoint is being pulled from an unusually large number of distinct IPs in
 * a short window — the signature of someone (e.g. a censor) distributing one
 * paid subscription across many vantage points purely to enumerate the node
 * pool (docs/PLAN.md §6: "аккаунт тянет ссылку с многих IP"), not a real
 * customer's own handful of devices.
 *
 * Response is to rotate that account's VLESS keys immediately, so whatever
 * was already scraped goes stale fast, rather than a hard account ban (which
 * risks locking out a legitimate paying customer on a flaky mobile network —
 * false positives there are cheap to recover from, a wrongful ban is not).
 */
@Service
public class AntiEnumerationService {

    private static final Logger log = LoggerFactory.getLogger(AntiEnumerationService.class);

    private final SubscriptionAccessLogRepository accessLogRepository;
    private final DeviceNodeKeyRepository deviceNodeKeyRepository;
    private final AgentStreamServiceImpl agentStreamService;

    @Value("${vpn.anti-enum.window-minutes:60}")
    private int windowMinutes;

    @Value("${vpn.anti-enum.max-distinct-ips:5}")
    private int maxDistinctIps;

    public AntiEnumerationService(
            SubscriptionAccessLogRepository accessLogRepository,
            DeviceNodeKeyRepository deviceNodeKeyRepository,
            AgentStreamServiceImpl agentStreamService) {
        this.accessLogRepository = accessLogRepository;
        this.deviceNodeKeyRepository = deviceNodeKeyRepository;
        this.agentStreamService = agentStreamService;
    }

    @Transactional
    public void recordAccessAndEnforce(Long userId, String ipAddress) {
        if (userId == null || ipAddress == null || ipAddress.isBlank()) {
            return;
        }
        accessLogRepository.save(new SubscriptionAccessLog(userId, ipAddress));

        Instant since = Instant.now().minus(windowMinutes, ChronoUnit.MINUTES);
        long distinctIps = accessLogRepository.countDistinctIpsSince(userId, since);

        if (distinctIps > maxDistinctIps) {
            rotateDeviceKeys(userId, distinctIps);
        }
    }

    private void rotateDeviceKeys(Long userId, long distinctIps) {
        List<DeviceNodeKey> keys = deviceNodeKeyRepository.findByDeviceUserId(userId);
        if (keys.isEmpty()) {
            return;
        }
        for (DeviceNodeKey key : keys) {
            key.setUuid(UUID.randomUUID());
        }
        deviceNodeKeyRepository.saveAll(keys);

        log.warn("Anti-enumeration: rotated {} VLESS key(s) for user {} after {} distinct IPs pulled their " +
                        "subscription within {}m (threshold {}) — any already-exported links are now stale.",
                keys.size(), userId, distinctIps, windowMinutes, maxDistinctIps);

        agentStreamService.pushConfigSyncToAll();
    }
}
