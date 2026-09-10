package com.vpn.server.service;

import com.vpn.server.entity.Device;
import com.vpn.server.entity.DeviceNodeKey;
import com.vpn.server.entity.Node;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.User;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.DeviceNodeKeyRepository;
import com.vpn.server.repository.DeviceRepository;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class DeviceManagementService {

    private static final Logger log = LoggerFactory.getLogger(DeviceManagementService.class);

    // A device slot only counts against the tariff's device limit if it's been
    // seen (created, or touched by a client on connect) within this window —
    // an old phone nobody's used in months ages out of the count on its own,
    // no explicit "revoke" or auto-eviction needed. See docs/ROADMAP_PROGRESS.md
    // for the write-up ("add device" was confusing: two disconnected creation
    // paths — a silent auto-created "Primary Device" on first VLESS export, and
    // a manual form — with no way for an old, forgotten device to stop counting).
    private static final int DEVICE_ACTIVE_WINDOW_DAYS = 30;

    private final DeviceRepository deviceRepository;
    private final DeviceNodeKeyRepository deviceNodeKeyRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final NodeRepository nodeRepository;
    private final AgentStreamServiceImpl agentStreamService;

    public DeviceManagementService(
            DeviceRepository deviceRepository,
            DeviceNodeKeyRepository deviceNodeKeyRepository,
            SubscriptionRepository subscriptionRepository,
            UserRepository userRepository,
            NodeRepository nodeRepository,
            AgentStreamServiceImpl agentStreamService
    ) {
        this.deviceRepository = deviceRepository;
        this.deviceNodeKeyRepository = deviceNodeKeyRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.nodeRepository = nodeRepository;
        this.agentStreamService = agentStreamService;
    }

    @Transactional(readOnly = true)
    public List<Device> getUserDevices(Long userId) {
        // isActiveTrue only — deleteDevice() below is a soft delete (isActive=false,
        // key material revoked), and the dashboard device list has no way to show
        // "revoked" state: every returned row gets a working revoke button, so an
        // already-revoked device stayed listed (and re-clickably "revocable") forever.
        return deviceRepository.findByUserIdAndIsActiveTrue(userId);
    }

    @Transactional
    public Device addDevice(Long userId, String deviceName, String platform) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Subscription sub = subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(userId, "ACTIVE")
                .orElseThrow(() -> new IllegalStateException("Active subscription required to add a device"));

        if (sub.getCurrentPeriodEnd().isBefore(Instant.now())) {
            throw new IllegalStateException("Subscription has expired");
        }

        int maxDevices = resolveMaxDevices(sub.getTariff().getId());
        Instant activeSince = Instant.now().minus(DEVICE_ACTIVE_WINDOW_DAYS, ChronoUnit.DAYS);
        long activeCount = deviceRepository.countRecentlyActiveByUserId(userId, activeSince);

        if (activeCount >= maxDevices) {
            throw new IllegalStateException("Device limit exceeded (" + maxDevices + " for your plan). "
                    + "A device not used in " + DEVICE_ACTIVE_WINDOW_DAYS
                    + "+ days stops counting automatically, or revoke one in your device list.");
        }

        Device device = new Device();
        device.setUser(user);
        device.setDeviceName(deviceName != null && !deviceName.isBlank() ? deviceName.trim() : "Device " + (activeCount + 1));
        device.setPlatform(platform != null && !platform.isBlank() ? platform.trim() : "OTHER");
        device.setIsActive(true);
        device.setLastSeenAt(Instant.now());
        device = deviceRepository.save(device);

        // Pre-generate device keys for active nodes
        List<Node> activeNodes = nodeRepository.findByStatus("ONLINE");
        for (Node node : activeNodes) {
            DeviceNodeKey key = new DeviceNodeKey(device, node, UUID.randomUUID());
            deviceNodeKeyRepository.save(key);
        }

        log.info("Created device #{} ('{}') for user {}. Pushing config sync to all nodes.",
                device.getId(), device.getDeviceName(), userId);
        agentStreamService.pushConfigSyncToAll();

        return device;
    }

    /**
     * Called by clients on a successful connect to mark the device as recently
     * used — keeps it counting against the device limit (see
     * DEVICE_ACTIVE_WINDOW_DAYS) without the user having to do anything. Not
     * the same as "add a device": clients call this for the device they
     * already registered (locally-persisted deviceId), falling back to
     * addDevice() only if this 404s (e.g. the device was revoked elsewhere).
     */
    @Transactional
    public void touchDevice(Long userId, Long deviceId) {
        Device device = deviceRepository.findByIdAndUserId(deviceId, userId)
                .filter(Device::getIsActive)
                .orElseThrow(() -> new IllegalArgumentException("Device not found or not owned by user: " + deviceId));
        device.setLastSeenAt(Instant.now());
        deviceRepository.save(device);
    }

    @Transactional
    public void deleteDevice(Long userId, Long deviceId) {
        Device device = deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found or not owned by user: " + deviceId));

        device.setIsActive(false);
        deviceRepository.save(device);

        deviceNodeKeyRepository.deleteByDeviceId(deviceId);

        log.info("Revoked device #{} for user {}. Pushing config sync to all nodes.", deviceId, userId);
        agentStreamService.pushConfigSyncToAll();
    }

    private int resolveMaxDevices(String tariffId) {
        if (tariffId == null) return 1;
        return switch (tariffId.toLowerCase()) {
            case "trial" -> 1;
            case "standard" -> 3;
            case "pro" -> 5;
            case "business" -> 10;
            default -> 3;
        };
    }
}
