package com.vpn.server;

import com.vpn.server.entity.Device;
import com.vpn.server.entity.Node;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.DeviceNodeKeyRepository;
import com.vpn.server.repository.DeviceRepository;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.DeviceManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeviceManagementServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceNodeKeyRepository deviceNodeKeyRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NodeRepository nodeRepository;

    @Mock
    private AgentStreamServiceImpl agentStreamService;

    private DeviceManagementService deviceManagementService;

    @BeforeEach
    void setUp() {
        deviceManagementService = new DeviceManagementService(
                deviceRepository,
                deviceNodeKeyRepository,
                subscriptionRepository,
                userRepository,
                nodeRepository,
                agentStreamService
        );
    }

    @Test
    void testAddDeviceSuccess() {
        User user = new User();
        user.setId(7L);

        Tariff standardTariff = new Tariff();
        standardTariff.setId("standard");
        standardTariff.setName("Standard");

        Subscription sub = new Subscription();
        sub.setId(10L);
        sub.setUser(user);
        sub.setTariff(standardTariff);
        sub.setCurrentPeriodEnd(Instant.now().plus(15, ChronoUnit.DAYS));

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(7L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.countRecentlyActiveByUserId(eq(7L), any(Instant.class))).thenReturn(1L);

        Device savedDevice = new Device();
        savedDevice.setId(105L);
        savedDevice.setUser(user);
        savedDevice.setDeviceName("iPad Air");
        savedDevice.setPlatform("IOS");
        savedDevice.setIsActive(true);

        when(deviceRepository.save(any(Device.class))).thenReturn(savedDevice);
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of());

        Device result = deviceManagementService.addDevice(7L, "iPad Air", "IOS");

        assertNotNull(result);
        assertEquals(105L, result.getId());
        assertEquals("iPad Air", result.getDeviceName());
        verify(agentStreamService).pushConfigSyncToAll();
    }

    @Test
    void testAddDeviceExceedsLimit() {
        User user = new User();
        user.setId(7L);

        Tariff trialTariff = new Tariff();
        trialTariff.setId("trial");
        trialTariff.setName("Trial");

        Subscription sub = new Subscription();
        sub.setId(10L);
        sub.setUser(user);
        sub.setTariff(trialTariff);
        sub.setCurrentPeriodEnd(Instant.now().plus(2, ChronoUnit.DAYS));

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(7L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        // Trial limit is 1 device
        when(deviceRepository.countRecentlyActiveByUserId(eq(7L), any(Instant.class))).thenReturn(1L);

        assertThrows(IllegalStateException.class, () ->
                deviceManagementService.addDevice(7L, "Second Device", "ANDROID")
        );

        verify(deviceRepository, never()).save(any());
    }

    @Test
    void testDeleteDeviceSuccess() {
        Device d = new Device();
        d.setId(99L);
        d.setIsActive(true);

        when(deviceRepository.findByIdAndUserId(99L, 7L)).thenReturn(Optional.of(d));

        deviceManagementService.deleteDevice(7L, 99L);

        assertFalse(d.getIsActive());
        verify(deviceRepository).save(d);
        verify(deviceNodeKeyRepository).deleteByDeviceId(99L);
        verify(agentStreamService).pushConfigSyncToAll();
    }

    @Test
    void testTouchDeviceUpdatesLastSeenAt() {
        Device d = new Device();
        d.setId(42L);
        d.setIsActive(true);
        d.setLastSeenAt(Instant.now().minus(20, ChronoUnit.DAYS));

        when(deviceRepository.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(d));

        deviceManagementService.touchDevice(7L, 42L);

        assertTrue(d.getLastSeenAt().isAfter(Instant.now().minus(1, ChronoUnit.MINUTES)));
        verify(deviceRepository).save(d);
    }

    @Test
    void testTouchDeviceNotFoundThrows() {
        when(deviceRepository.findByIdAndUserId(42L, 7L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> deviceManagementService.touchDevice(7L, 42L));
    }

    @Test
    void testTouchDeviceRevokedThrows() {
        Device d = new Device();
        d.setId(42L);
        d.setIsActive(false);

        when(deviceRepository.findByIdAndUserId(42L, 7L)).thenReturn(Optional.of(d));

        assertThrows(IllegalArgumentException.class, () -> deviceManagementService.touchDevice(7L, 42L));
        verify(deviceRepository, never()).save(any());
    }

    @Test
    void testGetUserDevicesOnlyReturnsActiveOnes() {
        // Regression test: deleteDevice() above is a soft delete (isActive=false),
        // but getUserDevices used to query findByUserId (unfiltered) — a revoked
        // device stayed listed on the dashboard forever, with a working revoke
        // button, as if it were still active. See docs/ROADMAP_PROGRESS.md
        // "Пост-Фаза-10" for the full write-up (found via the e2e Playwright suite,
        // not a unit test — a mocked repository can't catch a query-shape bug on
        // its own, only that the *correct* repository method gets called).
        Device active = new Device();
        active.setId(1L);
        active.setIsActive(true);
        when(deviceRepository.findByUserIdAndIsActiveTrue(7L)).thenReturn(List.of(active));

        List<Device> result = deviceManagementService.getUserDevices(7L);

        assertEquals(1, result.size());
        assertSame(active, result.get(0));
        verify(deviceRepository).findByUserIdAndIsActiveTrue(7L);
    }

    @Test
    void testOverlongNameAndPlatformAreClippedToTheirColumns() {
        // A long hostname used to reach Postgres verbatim and come back as a
        // 500, i.e. that machine could not register a device at all. The two
        // fields are cosmetic, so they are clipped rather than refused.
        User user = new User();
        user.setId(7L);

        Tariff standardTariff = new Tariff();
        standardTariff.setId("standard");

        Subscription sub = new Subscription();
        sub.setId(10L);
        sub.setUser(user);
        sub.setTariff(standardTariff);
        sub.setCurrentPeriodEnd(Instant.now().plus(15, ChronoUnit.DAYS));

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(7L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.countRecentlyActiveByUserId(eq(7L), any(Instant.class))).thenReturn(0L);
        when(deviceRepository.save(any(Device.class))).thenAnswer(i -> i.getArgument(0));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of());

        Device result = deviceManagementService.addDevice(7L, "n".repeat(5000), "p".repeat(500));

        assertEquals(128, result.getDeviceName().length(), "device_name is VARCHAR(128)");
        assertEquals(32, result.getPlatform().length(), "platform is VARCHAR(32)");
    }

    @Test
    void testClippingNeverSplitsACharacterInHalf() {
        // Java counts an emoji as two chars, so a naive substring at the limit
        // can leave an unpaired surrogate behind — an invalid string Postgres
        // would reject in its own right. An odd-length prefix puts the cut
        // exactly on such a boundary at char 128.
        User user = new User();
        user.setId(7L);

        Tariff standardTariff = new Tariff();
        standardTariff.setId("standard");

        Subscription sub = new Subscription();
        sub.setId(10L);
        sub.setUser(user);
        sub.setTariff(standardTariff);
        sub.setCurrentPeriodEnd(Instant.now().plus(15, ChronoUnit.DAYS));

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByCurrentPeriodEndDesc(7L, "ACTIVE"))
                .thenReturn(Optional.of(sub));
        when(deviceRepository.countRecentlyActiveByUserId(eq(7L), any(Instant.class))).thenReturn(0L);
        when(deviceRepository.save(any(Device.class))).thenAnswer(i -> i.getArgument(0));
        when(nodeRepository.findByStatus("ONLINE")).thenReturn(List.of());

        String emoji = "\uD83D\uDCF1"; // one emoji = two Java chars
        Device result = deviceManagementService.addDevice(7L, "x" + emoji.repeat(200), "ANDROID");

        String name = result.getDeviceName();
        assertEquals(127, name.length(), "the orphaned high surrogate must be dropped, not stored");
        assertFalse(Character.isHighSurrogate(name.charAt(name.length() - 1)));
        assertTrue(name.codePoints().allMatch(Character::isDefined));
    }
}
