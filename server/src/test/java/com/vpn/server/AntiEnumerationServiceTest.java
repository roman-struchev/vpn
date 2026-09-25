package com.vpn.server;

import com.vpn.server.entity.DeviceNodeKey;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.DeviceNodeKeyRepository;
import com.vpn.server.repository.SubscriptionAccessLogRepository;
import com.vpn.server.service.AntiEnumerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AntiEnumerationServiceTest {

    @Mock
    private SubscriptionAccessLogRepository accessLog;
    @Mock
    private DeviceNodeKeyRepository keys;
    @Mock
    private AgentStreamServiceImpl agents;

    private AntiEnumerationService service;

    @BeforeEach
    void setUp() {
        service = new AntiEnumerationService(accessLog, keys, agents);
        ReflectionTestUtils.setField(service, "windowMinutes", 60);
        ReflectionTestUtils.setField(service, "maxDistinctIps", 10);
    }

    private void seen(long distinctIps, long rowsFromThisIp) {
        when(accessLog.countDistinctIpsSince(eq(1L), any(Instant.class))).thenReturn(distinctIps);
        when(accessLog.countByUserIdAndIpAddressAndCreatedAtGreaterThanEqual(eq(1L), anyString(), any(Instant.class)))
                .thenReturn(rowsFromThisIp);
    }

    @Test
    void anOrdinaryCustomerWithAHandfulOfAddressesKeepsTheirKeys() {
        seen(6, 1);
        service.recordAccessAndEnforce(1L, "10.0.0.6");
        verify(keys, never()).saveAll(any());
    }

    @Test
    void theAddressThatCrossesTheLineRotatesTheKeysOnce() {
        DeviceNodeKey key = new DeviceNodeKey();
        UUID before = UUID.randomUUID();
        key.setUuid(before);
        when(keys.findByDeviceUserId(1L)).thenReturn(List.of(key));
        seen(11, 1);

        service.recordAccessAndEnforce(1L, "10.0.0.11");

        assertNotEquals(before, key.getUuid());
        verify(agents).pushConfigSyncToAll();
    }

    @Test
    void laterAccessesPastTheLineDoNotRotateAgain() {
        // A repeat address over the line, and a further new one: each used to
        // rotate again, killing the link a third-party client had just fetched.
        seen(11, 3);
        service.recordAccessAndEnforce(1L, "10.0.0.11");
        seen(12, 1);
        service.recordAccessAndEnforce(1L, "10.0.0.12");
        verify(keys, never()).saveAll(any());
        verify(keys, never()).findByDeviceUserId(anyLong());
        assertEquals(0, org.mockito.Mockito.mockingDetails(agents).getInvocations().size());
    }
}
