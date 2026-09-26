package com.vpn.server.service;

import com.vpn.server.grpc.AgentStreamServiceImpl;
import com.vpn.server.p2p.ReachabilityProbe;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Checks that a device which just started relaying can actually be reached
 * from the internet before anyone is offered it (ReachabilityProbe). Behind a
 * mobile carrier's NAT it usually can't: such a phone used to be listed as an
 * exit and every session through it timed out. Until the check says so the
 * peer is not offered; if it says no, it never is, and the device learns why
 * from {@link #stateOf} (its app then turns relaying off with an explanation).
 *
 * Runs inside the server on a small pool — one probe per UDP port in the
 * published range, a few packets each, a 10-second cap — and never blocks the
 * relay: when the probe itself can't run (our side), the peer is offered as
 * before rather than punished for our problem.
 */
@Service
public class P2pReachabilityService {

    private static final Logger log = LoggerFactory.getLogger(P2pReachabilityService.class);

    public enum State { PENDING, REACHABLE, UNREACHABLE, INCONCLUSIVE }

    public record Status(State state, Instant checkedAt) {
    }

    private final AgentStreamServiceImpl agentStreamService;
    private final NodeManagementService nodeManagementService;
    private final Map<Long, Status> states = new ConcurrentHashMap<>();

    @Value("${vpn.p2p.reachability.enabled:true}")
    private boolean enabled;
    /** Our public address as the devices see it; defaults to the web base URL's host. */
    @Value("${vpn.p2p.reachability.public-ip:}")
    private String publicIp;
    @Value("${vpn.public.web-base-url:http://217.216.79.46:8080}")
    private String webBaseUrl;
    /** First of the UDP ports published 1:1 for the probe (docker-compose.yml). */
    @Value("${vpn.p2p.reachability.port-start:50000}")
    private int portStart;
    @Value("${vpn.p2p.reachability.port-count:10}")
    private int portCount;
    @Value("${vpn.p2p.reachability.timeout-ms:10000}")
    private long timeoutMs;

    private BlockingQueue<Integer> freePorts;
    private ExecutorService pool;
    private String advertisedIp;
    private String targetHost;
    private int targetPort;

    public P2pReachabilityService(AgentStreamServiceImpl agentStreamService, NodeManagementService nodeManagementService) {
        this.agentStreamService = agentStreamService;
        this.nodeManagementService = nodeManagementService;
    }

    @PostConstruct
    void start() {
        if (!enabled) return;
        URI base = URI.create(webBaseUrl);
        advertisedIp = publicIp != null && !publicIp.isBlank() ? publicIp.trim() : base.getHost();
        // The offer's target: the Android relay dials it as soon as an offer
        // arrives, so it is our own server — never anything of anyone else's.
        targetHost = base.getHost();
        targetPort = base.getPort() > 0 ? base.getPort() : ("https".equals(base.getScheme()) ? 443 : 80);
        freePorts = new ArrayBlockingQueue<>(portCount);
        for (int i = 0; i < portCount; i++) freePorts.add(portStart + i);
        pool = Executors.newFixedThreadPool(portCount, r -> {
            Thread t = new Thread(r, "p2p-reachability");
            t.setDaemon(true);
            return t;
        });
        agentStreamService.setNodeConnectedListener(this::onNodeConnected);
    }

    @PreDestroy
    void stop() {
        if (pool != null) pool.shutdownNow();
    }

    /** A node opened its live stream: a p2p one gets checked (again — its network may have changed). */
    void onNodeConnected(Long nodeId) {
        if (!enabled || !nodeManagementService.isP2pNode(nodeId)) return;
        states.put(nodeId, new Status(State.PENDING, Instant.now()));
        try {
            pool.execute(() -> check(nodeId));
        } catch (Exception e) {
            // Saturated or shutting down: offer it as before rather than never.
            states.put(nodeId, new Status(State.INCONCLUSIVE, Instant.now()));
        }
    }

    private void check(Long nodeId) {
        Integer port = null;
        ReachabilityProbe.Result result;
        String sessionId = "reachability-" + UUID.randomUUID();
        try {
            port = freePorts.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (port == null) {
                result = new ReachabilityProbe.Result(ReachabilityProbe.Verdict.INCONCLUSIVE, "no free probe port");
            } else if (!awaitRelayWindow(nodeId)) {
                result = new ReachabilityProbe.Result(ReachabilityProbe.Verdict.INCONCLUSIVE, "node never started relaying");
            } else {
                try (DatagramSocket socket = new DatagramSocket(port)) {
                    ReachabilityProbe probe = new ReachabilityProbe(null, targetHost, targetPort, timeoutMs);
                    result = probe.run(new ReachabilityProbe.Signaling() {
                        @Override
                        public void send(byte[] envelope) {
                            if (!agentStreamService.sendSignalToNode(nodeId, sessionId, envelope)) {
                                throw new IllegalStateException("node " + nodeId + " took no signal");
                            }
                        }

                        @Override
                        public byte[] poll(long waitMs) {
                            return agentStreamService.awaitSignal(sessionId, waitMs);
                        }
                    }, socket, new InetSocketAddress(advertisedIp, port));
                }
            }
        } catch (Exception e) {
            result = new ReachabilityProbe.Result(ReachabilityProbe.Verdict.INCONCLUSIVE, "probe failed on our side: " + e);
        } finally {
            if (port != null) freePorts.add(port);
            agentStreamService.closeSignalSession(sessionId);
        }
        State state = switch (result.verdict()) {
            case REACHABLE -> State.REACHABLE;
            case UNREACHABLE -> State.UNREACHABLE;
            case INCONCLUSIVE -> State.INCONCLUSIVE;
        };
        states.put(nodeId, new Status(state, Instant.now()));
        log.info("P2P node {} reachability: {} ({})", nodeId, state, result.detail());
    }

    /** The relay window arrives with the node's first heartbeat, just after the stream opens. */
    private boolean awaitRelayWindow(Long nodeId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (nodeManagementService.isNodeEligibleForRelay(nodeId)) return true;
            Thread.sleep(250);
        }
        return nodeManagementService.isNodeEligibleForRelay(nodeId);
    }

    /** Whether a peer may be offered to clients: yes unless it is still being checked or failed the check. */
    public boolean isOffered(Long nodeId) {
        Status s = states.get(nodeId);
        return s == null || s.state() == State.REACHABLE || s.state() == State.INCONCLUSIVE;
    }

    /** What the check found for this node, or null if it was never checked (probe off, or before this server started). */
    public Status stateOf(Long nodeId) {
        return states.get(nodeId);
    }

    /** Test seam. */
    void setStateForTests(Long nodeId, State state) {
        states.put(nodeId, new Status(state, Instant.now()));
    }
}
