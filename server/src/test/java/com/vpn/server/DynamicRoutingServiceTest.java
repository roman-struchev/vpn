package com.vpn.server;

import com.vpn.server.entity.ConnTelemetry;
import com.vpn.server.entity.Node;
import com.vpn.server.entity.TransportPolicy;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.repository.ConnTelemetryRepository;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.TransportPolicyRepository;
import com.vpn.server.service.DynamicRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicRoutingServiceTest {

    @Mock
    private TransportPolicyRepository transportPolicyRepository;

    @Mock
    private ConnTelemetryRepository connTelemetryRepository;

    @Mock
    private NodeRepository nodeRepository;

    @Mock
    private AgentStreamServiceImpl agentStreamService;

    private DynamicRoutingService dynamicRoutingService;

    @BeforeEach
    void setUp() {
        dynamicRoutingService = new DynamicRoutingService(
                transportPolicyRepository,
                connTelemetryRepository,
                nodeRepository,
                agentStreamService
        );
    }

    @Test
    void testGetRoutingConfigWithOperatorMatch() {
        TransportPolicy opPolicy = new TransportPolicy();
        opPolicy.setScope("operator");
        opPolicy.setScopeValue("MTS");
        opPolicy.setPrimaryTransport("XHTTP");
        opPolicy.setFallbackTransport("GRPC");
        opPolicy.setFingerprint("chrome");
        opPolicy.setBackoffInitialSec(10);
        opPolicy.setMaxRetriesBeforeNodeSwitch(2);

        when(transportPolicyRepository.findFirstByScopeAndScopeValueAndIsActiveTrue("operator", "MTS"))
                .thenReturn(Optional.of(opPolicy));

        Node node1 = new Node();
        node1.setId(1L);
        node1.setPublicIp("1.2.3.4");
        node1.setRegion("RU-MOW");
        node1.setPool("paid");

        when(nodeRepository.findByPoolAndStatus("paid", "ONLINE"))
                .thenReturn(List.of(node1));

        DynamicRoutingService.RoutingConfigResponse config = dynamicRoutingService.getRoutingConfig("MTS", "RU-MOW");

        assertNotNull(config);
        assertEquals("XHTTP", config.primaryTransport());
        assertEquals("GRPC", config.fallbackTransport());
        assertEquals("chrome", config.fingerprint());
        assertEquals(1, config.nodes().size());
        assertEquals("1.2.3.4", config.nodes().get(0).publicIp());
    }

    @Test
    void testQuarantineTriggerOnRepeatedFailures() {
        Node node = new Node();
        node.setId(5L);
        node.setPublicIp("5.5.5.5");
        node.setPool("paid");

        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node));

        List<ConnTelemetry> telemetryList = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ConnTelemetry t = new ConnTelemetry();
            t.setNode(node);
            t.setFailureCount(3);
            telemetryList.add(t);
        }

        when(connTelemetryRepository.findRecent(any(Instant.class))).thenReturn(telemetryList);

        dynamicRoutingService.recordTelemetry(5L, "MTS", "RU-MOW", "XHTTP", 0, 3, true);

        assertEquals("quarantine", node.getPool());
        verify(nodeRepository).save(node);
        verify(agentStreamService).pushConfigSyncToAll();
    }
}
