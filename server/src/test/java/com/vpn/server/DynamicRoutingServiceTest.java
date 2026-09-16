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
        org.springframework.test.util.ReflectionTestUtils.setField(dynamicRoutingService, "grpcFallbackPort", 8443);
        org.springframework.test.util.ReflectionTestUtils.setField(dynamicRoutingService, "grpcFallbackServiceName", "vless-grpc");
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
        node1.setType("direct");

        when(nodeRepository.findByPoolInAndStatus(List.of("paid", "both"), "ONLINE"))
                .thenReturn(List.of(node1));

        DynamicRoutingService.RoutingConfigResponse config = dynamicRoutingService.getRoutingConfig("MTS", "RU-MOW");

        assertNotNull(config);
        assertEquals("XHTTP", config.primaryTransport());
        assertEquals("GRPC", config.fallbackTransport());
        assertEquals("chrome", config.fingerprint());
        assertEquals(1, config.nodes().size());
        assertEquals("1.2.3.4", config.nodes().get(0).publicIp());
        // Phase 9: direct nodes advertise the gRPC+Reality fallback inbound.
        assertEquals(8443, config.nodes().get(0).grpcFallbackPort());
        assertEquals("vless-grpc", config.nodes().get(0).grpcFallbackServiceName());
    }

    @Test
    void testGetRoutingConfigOmitsGrpcFallbackForCdnNodes() {
        when(transportPolicyRepository.findFirstByScopeAndScopeValueAndIsActiveTrue(eq("global"), eq("*")))
                .thenReturn(Optional.empty());

        Node cdnNode = new Node();
        cdnNode.setId(2L);
        cdnNode.setPublicIp("2.2.2.2");
        cdnNode.setRegion("RU-MOW");
        cdnNode.setPool("paid");
        cdnNode.setType("cdn");

        when(nodeRepository.findByPoolInAndStatus(List.of("paid", "both"), "ONLINE"))
                .thenReturn(List.of(cdnNode));

        DynamicRoutingService.RoutingConfigResponse config = dynamicRoutingService.getRoutingConfig(null, null);

        assertNull(config.nodes().get(0).grpcFallbackPort());
        assertNull(config.nodes().get(0).grpcFallbackServiceName());
    }

    @Test
    void testGetRoutingConfigIncludesBothPoolNodes() {
        // "both" (docs/research/P2P_RELAY_FEASIBILITY.md §8.3) is paid-
        // accessible too, same as a plain "paid" pool node.
        when(transportPolicyRepository.findFirstByScopeAndScopeValueAndIsActiveTrue(eq("global"), eq("*")))
                .thenReturn(Optional.empty());

        Node bothPoolNode = new Node();
        bothPoolNode.setId(3L);
        bothPoolNode.setPublicIp("3.3.3.3");
        bothPoolNode.setRegion("DE-FRA");
        bothPoolNode.setPool("both");
        bothPoolNode.setType("direct");

        when(nodeRepository.findByPoolInAndStatus(List.of("paid", "both"), "ONLINE"))
                .thenReturn(List.of(bothPoolNode));

        DynamicRoutingService.RoutingConfigResponse config = dynamicRoutingService.getRoutingConfig(null, null);

        assertEquals(1, config.nodes().size());
        assertEquals("3.3.3.3", config.nodes().get(0).publicIp());
    }

    @Test
    void testGetRoutingConfigExcludesP2pNodes() {
        // Regression: this endpoint embeds publicIp as a directly-dialable
        // address — a p2p node's is a meaningless placeholder (0.0.0.0), so
        // it must never show up here, same reasoning as
        // SubscriptionExportService's own p2p exclusion from VLESS links.
        when(transportPolicyRepository.findFirstByScopeAndScopeValueAndIsActiveTrue(eq("global"), eq("*")))
                .thenReturn(Optional.empty());

        Node p2pNode = new Node();
        p2pNode.setId(4L);
        p2pNode.setPublicIp("0.0.0.0");
        p2pNode.setRegion("default");
        p2pNode.setPool("both");
        p2pNode.setType("p2p");

        when(nodeRepository.findByPoolInAndStatus(List.of("paid", "both"), "ONLINE"))
                .thenReturn(List.of(p2pNode));

        DynamicRoutingService.RoutingConfigResponse config = dynamicRoutingService.getRoutingConfig(null, null);

        assertTrue(config.nodes().isEmpty());
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

    @Test
    void testQuarantinePromotesReserveNodeInSameRegion() {
        Node node = new Node();
        node.setId(5L);
        node.setPublicIp("5.5.5.5");
        node.setPool("paid");
        node.setRegion("RU-MOW");

        Node reserve = new Node();
        reserve.setId(99L);
        reserve.setPublicIp("9.9.9.9");
        reserve.setPool("reserve");
        reserve.setRegion("RU-MOW");

        when(nodeRepository.findById(5L)).thenReturn(Optional.of(node));
        when(nodeRepository.findByPoolAndRegionAndStatus("reserve", "RU-MOW", "ONLINE")).thenReturn(List.of(reserve));

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
        // The reserve node takes over the quarantined node's former ('paid') pool.
        assertEquals("paid", reserve.getPool());
        verify(nodeRepository).save(reserve);
    }
}
