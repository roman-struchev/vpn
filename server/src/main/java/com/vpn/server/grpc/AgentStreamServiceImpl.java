package com.vpn.server.grpc;

import com.vpn.server.grpc.agent.v1.*;
import com.vpn.server.service.DiagnosticsService;
import com.vpn.server.service.NodeManagementService;
import com.vpn.server.service.P2pRelayAccountingService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class AgentStreamServiceImpl extends AgentStreamServiceGrpc.AgentStreamServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamServiceImpl.class);

    private final NodeManagementService nodeManagementService;
    private final P2pRelayAccountingService p2pRelayAccountingService;
    private final DiagnosticsService diagnosticsService;
    // Map of active node streams: nodeId -> StreamObserver<ServerMessage>
    private final Map<Long, StreamObserver<ServerMessage>> activeStreams = new ConcurrentHashMap<>();
    /** Told when a node opens its live stream (P2pReachabilityService checks p2p ones). */
    private volatile java.util.function.Consumer<Long> nodeConnectedListener = id -> { };

    public void setNodeConnectedListener(java.util.function.Consumer<Long> listener) {
        this.nodeConnectedListener = listener != null ? listener : id -> { };
    }

    // Pure message-routing state for P2P signaling (docs/research/
    // P2P_RELAY_FEASIBILITY.md §8.1) — the server never parses/understands
    // the payload, it's just a broker between a connecting client (REST,
    // UserController's p2p endpoints — ordinary clients hold no AgentStream)
    // and a relay node's existing stream. A session_id is minted by the
    // connecting client when it starts signaling to a given node; a signal
    // arriving here from the NODE side (its answer/ICE) is stashed until the
    // client's poll picks it up, or it expires unclaimed.
    private final Map<String, SessionInbox> signalInboxes = new ConcurrentHashMap<>();
    private static final long SIGNAL_WAIT_TIMEOUT_SECONDS = 15;
    /** Per session; ICE trickles a handful of candidates, so this is generous. */
    private static final int MAX_QUEUED_SIGNALS_PER_SESSION = 64;
    /** Sessions with no traffic either way for this long are forgotten. */
    private static final long INBOX_IDLE_TIMEOUT_MS = 300_000;
    /** Ceiling on concurrently tracked sessions, so a caller cannot mint inboxes without bound. */
    private static final int MAX_INBOXES = 2000;

    /**
     * What a relay has said for one session that the connecting client has not
     * collected yet.
     *
     * A queue rather than a single slot because the relay answers with an SDP
     * *and then* trickles ICE candidates, while the client can only be waiting
     * for one of them at a time. Dropping the rest — which is what happened
     * before this existed — loses exactly the candidates a connection through
     * a NAT depends on, so sessions would negotiate and then never open.
     */
    private static final class SessionInbox {
        final java.util.Queue<byte[]> queued = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicInteger queuedCount = new java.util.concurrent.atomic.AtomicInteger();
        volatile CompletableFuture<byte[]> waiter;
        volatile long lastActivityAt = System.currentTimeMillis();
    }

    public AgentStreamServiceImpl(NodeManagementService nodeManagementService,
                                  P2pRelayAccountingService p2pRelayAccountingService,
                                  DiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
        this.nodeManagementService = nodeManagementService;
        this.p2pRelayAccountingService = p2pRelayAccountingService;
    }

    @Override
    public StreamObserver<AgentMessage> syncStream(StreamObserver<ServerMessage> rawResponseObserver) {
        // Everything that talks to a node goes through this one observer:
        // this stream's own replies, config pushes, commands, and — the
        // busiest — P2P signals, each forwarded from its own HTTP request
        // thread. gRPC's StreamObserver is not thread-safe, and concurrent
        // onNext calls made the server cancel the node's stream ("Cancelling
        // the stream because of internal error") as soon as a client sent its
        // offer and ICE candidates in parallel — every signal after that was
        // lost and a P2P exit could never connect. Found on two emulators.
        StreamObserver<ServerMessage> responseObserver = new SerializedStreamObserver<>(rawResponseObserver);
        return new StreamObserver<>() {
            private Long authenticatedNodeId = null;

            @Override
            public void onNext(AgentMessage message) {
                Long nodeId = message.getNodeId();
                String token = message.getNodeToken();

                if (authenticatedNodeId == null) {
                    if (!nodeManagementService.authenticateNode(nodeId, token)) {
                        log.warn("Unauthenticated stream connection attempt from node {}", nodeId);
                        responseObserver.onError(Status.UNAUTHENTICATED.withDescription("Invalid node credentials").asRuntimeException());
                        return;
                    }
                    authenticatedNodeId = nodeId;
                    activeStreams.put(nodeId, responseObserver);
                    log.info("Node {} connected to live sync stream", nodeId);

                    // Send initial configuration sync to the node
                    try {
                        ConfigSync initialConfig = nodeManagementService.buildNodeConfigSync(nodeId);
                        ServerMessage syncMsg = ServerMessage.newBuilder()
                                .setTimestampEpochMs(System.currentTimeMillis())
                                .setConfigSync(initialConfig)
                                .build();
                        responseObserver.onNext(syncMsg);
                    } catch (Exception e) {
                        log.error("Failed to build initial config for node {}", nodeId, e);
                    }
                    try {
                        nodeConnectedListener.accept(nodeId);
                    } catch (Exception e) {
                        log.warn("Node-connected listener failed for node {}", nodeId, e);
                    }
                } else if (!authenticatedNodeId.equals(nodeId)) {
                    log.warn("Node ID mismatch on established stream: expected {}, got {}", authenticatedNodeId, nodeId);
                    responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Node ID mismatch").asRuntimeException());
                    return;
                }

                switch (message.getPayloadCase()) {
                    case HEARTBEAT -> {
                        nodeManagementService.processHeartbeat(nodeId, message.getHeartbeat());
                        ServerMessage ack = ServerMessage.newBuilder()
                                .setTimestampEpochMs(System.currentTimeMillis())
                                .setHeartbeatAck(HeartbeatAck.newBuilder()
                                        .setServerTimeEpochMs(System.currentTimeMillis())
                                        .build())
                                .build();
                        responseObserver.onNext(ack);
                    }
                    case CONFIG_ACK -> nodeManagementService.processConfigAck(nodeId, message.getConfigAck());
                    case TRAFFIC_STATS -> nodeManagementService.processTrafficStats(nodeId, message.getTrafficStats());
                    case CONN_TELEMETRY -> nodeManagementService.processConnTelemetry(nodeId, message.getConnTelemetry());
                    case P2P_SIGNAL -> resolvePendingClientSignal(message.getP2PSignal());
                    case P2P_TRAFFIC_REPORT -> p2pRelayAccountingService.recordRelayNodeReport(
                            nodeId, message.getP2PTrafficReport().getSessionId(), message.getP2PTrafficReport().getBytesRelayed());
                    case DIAGNOSTICS -> recordNodeDiagnostics(nodeId, message.getDiagnostics());
                    case PAYLOAD_NOT_SET -> log.debug("Empty message received from node {}", nodeId);
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Stream error on node {}: {}", authenticatedNodeId, t.getMessage());
                streamEnded();
            }

            @Override
            public void onCompleted() {
                log.info("Stream completed by node {}", authenticatedNodeId);
                streamEnded();
                responseObserver.onCompleted();
            }

            /**
             * Only this stream's own entry: a node that reconnected already put
             * a new one there, and removing it made the node unreachable. A
             * peer that is gone stops being offered right away — it used to
             * stay listed for up to NodeHealthTask's 90 seconds, and every
             * client that picked it waited out a session that could not start.
             */
            private void streamEnded() {
                if (authenticatedNodeId == null) return;
                if (activeStreams.remove(authenticatedNodeId, responseObserver)) {
                    nodeManagementService.markP2pNodeGone(authenticatedNodeId);
                }
            }
        };
    }

    public void pushConfigSync(Long nodeId) {
        StreamObserver<ServerMessage> observer = activeStreams.get(nodeId);
        if (observer != null) {
            try {
                ConfigSync sync = nodeManagementService.buildNodeConfigSync(nodeId);
                ServerMessage msg = ServerMessage.newBuilder()
                        .setTimestampEpochMs(System.currentTimeMillis())
                        .setConfigSync(sync)
                        .build();
                observer.onNext(msg);
                log.info("Pushed updated config sync to node {}", nodeId);
            } catch (Exception e) {
                log.error("Failed to push config sync to node {}", nodeId, e);
            }
        }
    }

    public void pushConfigSyncToAll() {
        for (Long nodeId : activeStreams.keySet()) {
            pushConfigSync(nodeId);
        }
    }

    /**
     * Forwards a connecting client's SDP offer / ICE candidate to a p2p relay
     * node's existing stream, then waits (bounded) for that node's reply on
     * the same session_id. Pure passthrough — payload is opaque to the
     * server on both legs (docs §8.1). Returns null on timeout or if the
     * node isn't currently connected; callers (UserController's p2p signal
     * endpoint) surface that as "try another node" rather than an error the
     * client can't act on.
     */
    /**
     * Hands one signaling payload to a relay node. Returns whether it went out;
     * the relay's own answer/candidates come back through {@link #awaitSignal}
     * rather than as a reply here, because there is no one-to-one relationship
     * between the two directions.
     */
    public boolean sendSignalToNode(Long nodeId, String sessionId, byte[] payload) {
        // The actual enforcement point for Node#isEligibleForRelay (docs
        // §8.5) — a node's relay window is otherwise just stored state nothing
        // consults, letting an expired TIMED node keep answering signals.
        if (!nodeManagementService.isNodeEligibleForRelay(nodeId)) {
            log.warn("P2P signal for session {} rejected — node {} is not currently eligible for relay", sessionId, nodeId);
            return false;
        }
        StreamObserver<ServerMessage> observer = activeStreams.get(nodeId);
        if (observer == null) {
            log.warn("P2P signal for session {} could not be sent — node {} has no active stream", sessionId, nodeId);
            return false;
        }

        inboxFor(sessionId).lastActivityAt = System.currentTimeMillis();
        try {
            observer.onNext(ServerMessage.newBuilder()
                    .setTimestampEpochMs(System.currentTimeMillis())
                    .setP2PSignal(P2pSignal.newBuilder()
                            .setSessionId(sessionId)
                            .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                            .build())
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("Failed to deliver P2P signal for session {} to node {}: {}", sessionId, nodeId, e.toString());
            return false;
        }
    }

    /**
     * The connecting client's side of the broker: the next thing the relay has
     * said for this session, waiting up to {@code timeoutMs} for it. Null when
     * nothing arrives in time, which is an ordinary outcome — a client polls in
     * a loop while negotiating and stops when the channel opens.
     */
    public byte[] awaitSignal(String sessionId, long timeoutMs) {
        SessionInbox inbox = inboxFor(sessionId);
        inbox.lastActivityAt = System.currentTimeMillis();

        byte[] queued = inbox.queued.poll();
        if (queued != null) {
            inbox.queuedCount.decrementAndGet();
            return queued;
        }

        CompletableFuture<byte[]> waiter = new CompletableFuture<>();
        inbox.waiter = waiter;
        try {
            return waiter.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        } finally {
            if (inbox.waiter == waiter) {
                inbox.waiter = null;
            }
            inbox.lastActivityAt = System.currentTimeMillis();
        }
    }

    /** Drops a finished session's mailbox instead of waiting for it to idle out. */
    public void closeSignalSession(String sessionId) {
        SessionInbox inbox = signalInboxes.remove(sessionId);
        if (inbox != null && inbox.waiter != null) {
            inbox.waiter.complete(null);
        }
    }

    private SessionInbox inboxFor(String sessionId) {
        SessionInbox existing = signalInboxes.get(sessionId);
        if (existing != null) {
            return existing;
        }
        evictIdleInboxes();
        return signalInboxes.computeIfAbsent(sessionId, id -> new SessionInbox());
    }

    private void evictIdleInboxes() {
        long cutoff = System.currentTimeMillis() - INBOX_IDLE_TIMEOUT_MS;
        signalInboxes.entrySet().removeIf(e -> e.getValue().lastActivityAt < cutoff && e.getValue().waiter == null);
        if (signalInboxes.size() >= MAX_INBOXES) {
            // Nothing sane left to do but refuse to grow: keep the newest,
            // since an old idle session is the one least likely to still matter.
            signalInboxes.entrySet().stream()
                    .sorted(java.util.Comparator.comparingLong(e -> e.getValue().lastActivityAt))
                    .limit(Math.max(1, signalInboxes.size() - MAX_INBOXES + 1))
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(signalInboxes::remove);
        }
    }

    public byte[] sendSignalToNodeAndAwaitReply(Long nodeId, String sessionId, byte[] payload) {
        // The actual enforcement point for Node#isEligibleForRelay (docs
        // §8.5) — a node's relay window/eligibility is otherwise just stored
        // state nothing consults, letting an already-expired TIMED node (or
        // one that never turned relay on) keep answering signals until its
        // next heartbeat happens to overwrite relayMode. Checked here, on
        // every dispatch, not only at registration/heartbeat time.
        if (!sendSignalToNode(nodeId, sessionId, payload)) {
            return null;
        }
        try {
            return awaitSignal(sessionId, SIGNAL_WAIT_TIMEOUT_SECONDS * 1000);
        } catch (Exception e) {
            log.warn("P2P signal round-trip for session {} on node {} failed/timed out: {}", sessionId, nodeId, e.getMessage());
            return null;
        }
    }

    /**
     * A relay's answer or ICE candidate on its way back to the connecting
     * client: handed straight to whoever is waiting, or queued for the next
     * poll. Queuing is the part that makes NAT traversal work at all —
     * candidates arrive in a burst while the client is between polls.
     */
    private void resolvePendingClientSignal(P2pSignal signal) {
        SessionInbox inbox = inboxFor(signal.getSessionId());
        inbox.lastActivityAt = System.currentTimeMillis();
        byte[] payload = signal.getPayload().toByteArray();

        CompletableFuture<byte[]> waiter = inbox.waiter;
        if (waiter != null && waiter.complete(payload)) {
            inbox.waiter = null;
            return;
        }
        if (inbox.queuedCount.get() >= MAX_QUEUED_SIGNALS_PER_SESSION) {
            log.debug("P2P session {} has more unclaimed signals than the inbox holds — dropping the oldest", signal.getSessionId());
            if (inbox.queued.poll() != null) {
                inbox.queuedCount.decrementAndGet();
            }
        }
        inbox.queued.add(payload);
        inbox.queuedCount.incrementAndGet();
    }

    public boolean sendCommand(Long nodeId, ServerCommand command) {
        StreamObserver<ServerMessage> observer = activeStreams.get(nodeId);
        if (observer != null) {
            try {
                ServerMessage msg = ServerMessage.newBuilder()
                        .setTimestampEpochMs(System.currentTimeMillis())
                        .setCommand(command)
                        .build();
                observer.onNext(msg);
                log.info("Sent command {} to node {}", command.getCommandType(), nodeId);
                return true;
            } catch (Exception e) {
                log.error("Failed to send command to node {}", nodeId, e);
            }
        }
        return false;
    }

    /**
     * Failures the node itself hit, folded into the same store the client
     * apps report into (DiagnosticsService). node_id comes from the stream,
     * not from the payload, so an agent can only ever file errors against
     * itself.
     */
    private void recordNodeDiagnostics(Long nodeId, DiagnosticsReport report) {
        List<DiagnosticsService.Report> reports = report.getEventsList().stream()
                .map(e -> new DiagnosticsService.Report(
                        "NODE_AGENT",
                        e.getSeverity(),
                        e.getComponent(),
                        e.getCode(),
                        e.getMessage(),
                        e.getDetail(),
                        e.getContextMap(),
                        report.getAgentVersion(),
                        nodeId != null ? "node-" + nodeId : null,
                        null,
                        nodeId))
                .toList();
        int accepted = diagnosticsService.record(reports);
        log.debug("Recorded {}/{} diagnostic event(s) from node {}", accepted, reports.size(), nodeId);
    }


    /** Serializes calls into a StreamObserver, which gRPC requires callers to do — see syncStream. */
    static final class SerializedStreamObserver<T> implements StreamObserver<T> {
        private final StreamObserver<T> delegate;

        SerializedStreamObserver(StreamObserver<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void onNext(T value) {
            delegate.onNext(value);
        }

        @Override
        public synchronized void onError(Throwable t) {
            delegate.onError(t);
        }

        @Override
        public synchronized void onCompleted() {
            delegate.onCompleted();
        }
    }
}
