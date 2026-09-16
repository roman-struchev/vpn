package com.vpn.server;

import com.google.protobuf.ByteString;
import com.vpn.server.entity.Node;
import com.vpn.server.entity.NodeBootstrapToken;
import com.vpn.server.entity.Subscription;
import com.vpn.server.entity.Tariff;
import com.vpn.server.entity.User;
import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.grpc.agent.v1.AgentMessage;
import com.vpn.server.grpc.agent.v1.P2pSessionTrafficReport;
import com.vpn.server.grpc.agent.v1.P2pSignal;
import com.vpn.server.grpc.agent.v1.RegisterNodeRequest;
import com.vpn.server.grpc.agent.v1.RegisterNodeResponse;
import com.vpn.server.grpc.agent.v1.ServerMessage;
import com.vpn.server.repository.NodeRepository;
import com.vpn.server.repository.P2pRelayCreditRepository;
import com.vpn.server.repository.SubscriptionRepository;
import com.vpn.server.repository.TariffRepository;
import com.vpn.server.repository.UserRepository;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.service.P2pRelayAccountingService;
import com.vpn.server.task.NodeHealthTask;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * End-to-end regression coverage for the P2P relay feature's whole lifecycle
 * (docs/research/P2P_RELAY_FEASIBILITY.md §8) — register, use (signal +
 * dual-report credit), and shut down (health/pruning) — running against the
 * real Spring context and a real H2 test datasource (see
 * application-test.yml), not mocked repositories. Complements the many
 * Mockito unit tests covering each piece in isolation (NodeManagementServiceTest,
 * AgentStreamServiceImplTest, P2pRelayAccountingServiceTest, NodeHealthTaskTest)
 * the same way SubscriptionRepositoryTest complements QuotaEnforcementTaskTest —
 * a real integration pass can catch a wiring bug (wrong bean, a JPQL query that
 * doesn't do what its Java caller assumes, a transaction boundary issue) that
 * mocking every collaborator can't.
 */
@SpringBootTest
@ActiveProfiles("test")
// See p2p-lifecycle-test-schema.sql's own header comment: Hibernate's
// ddl-auto silently fails to create the "nodes" table under H2 (a
// Postgres-only array column type it can't parse), a gap no test before
// this one ever hit. IF NOT EXISTS makes running it before every method safe.
@Sql(scripts = "/p2p-lifecycle-test-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class P2pNodeLifecycleIntegrationTest {

    @Autowired
    private NodeManagementService nodeManagementService;

    @Autowired
    private AgentStreamServiceImpl agentStreamService;

    @Autowired
    private P2pRelayAccountingService p2pRelayAccountingService;

    @Autowired
    private NodeHealthTask nodeHealthTask;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private P2pRelayCreditRepository creditRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private TariffRepository tariffRepository;

    // Flyway is disabled for the "test" profile — create whatever a test needs itself.
    private Tariff tariff(String id) {
        return tariffRepository.findById(id).orElseGet(() -> {
            Tariff t = new Tariff();
            t.setId(id);
            t.setName(id.toUpperCase());
            t.setMonthlyPriceUsdtMicro(0L);
            t.setAnnualPriceUsdtMicro(0L);
            t.setTrafficQuotaBytes(1_000_000_000L);
            t.setMaxDevices(1);
            t.setServerPool("paid");
            return tariffRepository.save(t);
        });
    }

    private User newUser() {
        String unique = UUID.randomUUID().toString();
        User user = new User();
        user.setEmail("p2p-lifecycle-" + unique + "@example.com");
        user.setReferralCode("p" + unique.substring(0, 8));
        return userRepository.save(user);
    }

    private Subscription newActiveSubscription(User user, long trafficLimitBytes) {
        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setTariff(tariff("trial"));
        sub.setStatus("ACTIVE");
        sub.setCurrentPeriodStart(Instant.now());
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        sub.setTrafficLimitBytes(trafficLimitBytes);
        return subscriptionRepository.save(sub);
    }

    /**
     * Registers, opens the node's real gRPC stream, round-trips a signal
     * through it, has both sides self-report matching traffic, and confirms
     * a real credit lands on the owner's real subscription — the "used"
     * part of "регистрируются, используются, тушатся" against real Spring
     * beans and a real DB, not mocks.
     */
    @Test
    @SuppressWarnings("unchecked")
    void p2pNode_registersAndRelaysASession_andCreditsTheOwnerOnMatchingDualReports() throws Exception {
        User owner = newUser();
        Subscription sub = newActiveSubscription(owner, 5_000_000_000L);

        NodeBootstrapToken token = nodeManagementService.createP2pBootstrapTokenForUser(owner);

        RegisterNodeRequest request = RegisterNodeRequest.newBuilder()
                .setBootstrapToken(token.getToken())
                .setHostname("integration-test-laptop-" + UUID.randomUUID())
                .setPublicIp("0.0.0.0")
                .setRegion("de-fra")
                .setRelayMode("ALWAYS")
                .build();
        RegisterNodeResponse response = nodeManagementService.registerNode(request);

        long nodeId = response.getNodeId();
        Node savedNode = nodeRepository.findById(nodeId).orElseThrow();
        assertEquals("both", savedNode.getPool());
        assertTrue(savedNode.getAvailableToTrial());
        assertTrue(savedNode.getAvailableToPaid());
        assertEquals(owner.getId(), savedNode.getOwnerUser().getId());
        assertTrue(savedNode.isEligibleForRelay());

        // Open the node's real gRPC stream (authenticateNode runs for real
        // against the real NodeCredential row registerNode just created).
        StreamObserver<ServerMessage> responseObserver = mock(StreamObserver.class);
        StreamObserver<AgentMessage> nodeStream = agentStreamService.syncStream(responseObserver);
        nodeStream.onNext(AgentMessage.newBuilder()
                .setNodeId(nodeId)
                .setNodeToken(response.getNodeToken())
                .build());

        // A connecting client's signal, dispatched through the real
        // gRPC-routing code — answered by the node on its own stream shortly
        // after, exactly like a real relay agent would.
        String sessionId = "integration-session-" + UUID.randomUUID();
        Thread nodeReplyThread = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            nodeStream.onNext(AgentMessage.newBuilder()
                    .setNodeId(nodeId)
                    .setNodeToken(response.getNodeToken())
                    .setP2PSignal(P2pSignal.newBuilder()
                            .setSessionId(sessionId)
                            .setPayload(ByteString.copyFromUtf8("{\"kind\":\"answer\",\"sdp\":\"v=0...\"}"))
                            .build())
                    .build());
        });
        nodeReplyThread.start();

        CompletableFuture<byte[]> replyFuture = CompletableFuture.supplyAsync(() ->
                agentStreamService.sendSignalToNodeAndAwaitReply(nodeId, sessionId, "offer-sdp".getBytes()));
        byte[] reply = replyFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
        nodeReplyThread.join();

        assertTrue(reply != null && reply.length > 0, "the node's answer must actually round-trip back");

        // Both sides self-report the same byte count — the relay node over
        // its real gRPC stream, the connecting client via the same call
        // P2pRelayController#reportSessionTraffic makes.
        long bytesRelayed = 2_000_000_000L; // 2GB
        nodeStream.onNext(AgentMessage.newBuilder()
                .setNodeId(nodeId)
                .setNodeToken(response.getNodeToken())
                .setP2PTrafficReport(P2pSessionTrafficReport.newBuilder()
                        .setSessionId(sessionId)
                        .setBytesRelayed(bytesRelayed)
                        .build())
                .build());
        p2pRelayAccountingService.recordClientReport(nodeId, sessionId, bytesRelayed);

        Optional<com.vpn.server.entity.P2pRelayCredit> credit = creditRepository.findBySessionId(sessionId);
        assertTrue(credit.isPresent(), "a real credit row must be persisted once both sides agree");
        assertEquals(1_000_000_000L, credit.get().getBytesCredited()); // 2GB relayed * 0.5 rate

        Subscription refreshed = subscriptionRepository.findById(sub.getId()).orElseThrow();
        assertEquals(5_000_000_000L + 1_000_000_000L, refreshed.getTrafficLimitBytes(),
                "the owner's real subscription must actually gain the credited traffic");
    }

    /**
     * The "тушатся" (shut down) part: a p2p node whose heartbeats stop gets
     * marked OFFLINE, then pruned outright once long enough has passed —
     * against the real scheduled task's real queries, not mocked
     * repository responses. A same-age direct/cdn node is marked OFFLINE
     * too but must never be pruned — that's still real infra an operator
     * needs to see and fix.
     */
    @Test
    void p2pNode_goesOfflineThenGetsPruned_whileARegularNodeNeverGetsPruned() {
        User owner = newUser();

        NodeBootstrapToken p2pToken = nodeManagementService.createP2pBootstrapTokenForUser(owner);
        RegisterNodeResponse p2pResponse = nodeManagementService.registerNode(RegisterNodeRequest.newBuilder()
                .setBootstrapToken(p2pToken.getToken())
                .setHostname("integration-test-p2p-" + UUID.randomUUID())
                .setPublicIp("0.0.0.0")
                .setRegion("default")
                .build());

        NodeBootstrapToken vpsToken = nodeManagementService.createBootstrapToken("paid", "direct", 24);
        RegisterNodeResponse vpsResponse = nodeManagementService.registerNode(RegisterNodeRequest.newBuilder()
                .setBootstrapToken(vpsToken.getToken())
                .setHostname("integration-test-vps-" + UUID.randomUUID())
                .setPublicIp("198.51.100.9")
                .setRegion("de-fra")
                .build());

        // Backdate both nodes' last heartbeat well past the stale-online
        // threshold (90s) but not yet past the p2p prune window (24h).
        Node p2pNode = nodeRepository.findById(p2pResponse.getNodeId()).orElseThrow();
        Node vpsNode = nodeRepository.findById(vpsResponse.getNodeId()).orElseThrow();
        p2pNode.setLastHeartbeatAt(Instant.now().minus(2, ChronoUnit.HOURS));
        vpsNode.setLastHeartbeatAt(Instant.now().minus(2, ChronoUnit.HOURS));
        nodeRepository.save(p2pNode);
        nodeRepository.save(vpsNode);

        nodeHealthTask.run();

        Node p2pAfterFirstPass = nodeRepository.findById(p2pResponse.getNodeId()).orElseThrow();
        Node vpsAfterFirstPass = nodeRepository.findById(vpsResponse.getNodeId()).orElseThrow();
        assertEquals("OFFLINE", p2pAfterFirstPass.getStatus());
        assertEquals("OFFLINE", vpsAfterFirstPass.getStatus());

        // Now push the p2p node's heartbeat past the 24h prune window too;
        // the VPS node stays at the same 2h-stale point.
        p2pAfterFirstPass.setLastHeartbeatAt(Instant.now().minus(25, ChronoUnit.HOURS));
        nodeRepository.save(p2pAfterFirstPass);

        nodeHealthTask.run();

        assertFalse(nodeRepository.findById(p2pResponse.getNodeId()).isPresent(),
                "a p2p node offline for over 24h must actually be gone");
        assertTrue(nodeRepository.findById(vpsResponse.getNodeId()).isPresent(),
                "a VPS node must never be pruned, no matter how long it's been offline");
    }
}
