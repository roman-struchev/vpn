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
        when(deviceRepository.countByUserIdAndIsActiveTrue(7L)).thenReturn(1L);

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
        when(deviceRepository.countByUserIdAndIsActiveTrue(7L)).thenReturn(1L);

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
}
