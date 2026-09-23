package com.vpn.android.p2p;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.vpn.android.BuildConfig;
import com.vpn.android.api.ApiClient;
import com.vpn.android.api.TokenStore;
import com.vpn.server.grpc.agent.v1.AgentMessage;
import com.vpn.server.grpc.agent.v1.AgentRegistrationServiceGrpc;
import com.vpn.server.grpc.agent.v1.AgentStreamServiceGrpc;
import com.vpn.server.grpc.agent.v1.Heartbeat;
import com.vpn.server.grpc.agent.v1.P2pSessionTrafficReport;
import com.vpn.server.grpc.agent.v1.P2pSignal;
import com.vpn.server.grpc.agent.v1.RegisterNodeRequest;
import com.vpn.server.grpc.agent.v1.RegisterNodeResponse;
import com.vpn.server.grpc.agent.v1.ServerMessage;

import org.webrtc.PeerConnectionFactory;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.ByteString;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

/**
 * The RELAY side of P2P mode (docs/research/P2P_RELAY_FEASIBILITY.md §8) —
 * this device registers as a NODE_TYPE_P2P node and earns credit relaying
 * OTHER users' traffic, distinct from this app's normal role as a VPN
 * *client* dialing into someone else's node.
 *
 * Deliberately does NOT run a local Xray-core node inbound. A relay node is
 * a destination-ACL-gated, protocol-blind byte pipe between a WebRTC
 * DataChannel and a raw TCP socket (see P2pTcpBridge) — the CONNECTING
 * client's own xray-core is what actually speaks VLESS/Reality to some real
 * egress node; this device never needs to understand that traffic, only
 * forward it. This is both simpler (no local Reality cert/key material to
 * provision here) and safer (this device is never itself a decrypting
 * endpoint for someone else's VPN traffic).
 *
 * Same plaintext gRPC (no TLS) as every VPS node agent (agent/src/client/
 * grpc-client.ts) — not a regression introduced here, matches the existing
 * systemic convention (server/application.yml's grpc.server.port has no TLS
 * wired at the gRPC layer today).
 */
public class P2pRelayAgent {

    private static final String TAG = "P2pRelayAgent";
    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int TRAFFIC_REPORT_INTERVAL_SECONDS = 30;
    /** SSRF-prevention (docs §8.8): never let a slow/blackholed destination pin a bridge thread indefinitely. */
    private static final int TCP_CONNECT_TIMEOUT_MS = 8000;

    public interface StateListener {
        void onError(String message);
    }

    private final Context appContext;
    private final TokenStore tokenStore;
    private final ApiClient apiClient;
    private final StateListener listener;

    private ManagedChannel channel;
    private StreamObserver<AgentMessage> outgoingStream;
    /**
     * Guards every call into outgoingStream. gRPC's StreamObserver is not
     * thread-safe, and this one is written from the heartbeat scheduler, the
     * traffic reports and WebRTC's own threads (ICE candidates) at once.
     */
    private final Object streamLock = new Object();
    private volatile boolean stopped = false;
    /** How long to wait before reopening a stream the server or the network dropped. */
    private static final long RECONNECT_DELAY_SECONDS = 5;
    private ScheduledExecutorService scheduler;
    private PeerConnectionFactory peerConnectionFactory;
    private volatile long nodeId = -1;
    private volatile String nodeToken;
    private volatile String relayMode = "OFF";
    private volatile long relayExpiresAtEpochMs;
    // The relay node's own declared location (docs §8.4/§8.5) — geo-IP
    // auto-detected once, the first time this device ever registers (see
    // start()), the same way a regular VPS node determines its region at
    // install time; cached in TokenStore and reused on every later start,
    // never re-detected and never re-sent after registration (there is no
    // "update my own region" heartbeat field, unlike relayMode/expiresAt).
    private volatile String region = "default";

    private final Map<String, ActiveSession> sessions = new ConcurrentHashMap<>();

    /**
     * ICE candidates that arrive before their session's offer. A client fires
     * its offer and candidates as separate requests at once, so candidates
     * routinely overtake the offer — and the offer itself spends a moment in
     * the destination check before its session exists. They used to be
     * dropped, and they are exactly what NAT traversal needs. Bounded, and
     * forgotten after a while in case the offer never comes.
     */
    private final Map<String, java.util.List<SignalEnvelope>> earlyIce = new ConcurrentHashMap<>();
    private final Map<String, Long> earlyIceAt = new ConcurrentHashMap<>();
    private static final int MAX_EARLY_ICE_PER_SESSION = 32;
    private static final int MAX_EARLY_ICE_SESSIONS = 500;
    private static final long EARLY_ICE_TTL_MS = 30_000L;

    private static final class ActiveSession {
        final P2pWebRtcSession webrtc;
        final long startedNodeId;
        volatile P2pTcpBridge bridge;
        volatile long lastReportedBytes = 0;

        ActiveSession(P2pWebRtcSession webrtc, long startedNodeId) {
            this.webrtc = webrtc;
            this.startedNodeId = startedNodeId;
        }
    }

    public P2pRelayAgent(Context context, TokenStore tokenStore, ApiClient apiClient, StateListener listener) {
        this.appContext = context.getApplicationContext();
        this.tokenStore = tokenStore;
        this.apiClient = apiClient;
        this.listener = listener;
    }

    /**
     * Registers (first run) or reconnects (subsequent runs, reusing the
     * persisted node id/token) and opens the live stream. Safe to call
     * repeatedly — a change of {@code relayMode}/{@code relayExpiresAtEpochMs}
     * while already connected is picked up on the next heartbeat rather than
     * requiring a fresh registration.
     *
     * Blocking (geo-IP detection on first-ever call, plus the registerNode
     * RPC below) — callers must already be off the main thread
     * (P2pRelayService submits this to its own single-threaded executor).
     */
    public synchronized void start(String relayMode, long relayExpiresAtEpochMs) throws Exception {
        this.relayMode = relayMode;
        this.relayExpiresAtEpochMs = relayExpiresAtEpochMs;

        // Detected once, the first time this device ever registers as a p2p
        // node, then persisted and reused on every later start — exactly
        // like a regular VPS node's install-time geo-IP lookup is a one-shot
        // thing, never re-run on every restart (see GeoLocale#detectNodeRegion's
        // own doc comment). A device that later moves to a different country
        // keeps its originally-detected label rather than silently relabeling.
        String persistedRegion = tokenStore.getP2pRelayRegion();
        if (persistedRegion != null && !persistedRegion.isBlank()) {
            this.region = com.vpn.android.util.GeoLocale.normalizeRegion(persistedRegion);
            if (!this.region.equals(persistedRegion)) tokenStore.saveP2pRelayRegion(this.region);
        } else {
            this.region = com.vpn.android.util.GeoLocale.detectNodeRegion();
            tokenStore.saveP2pRelayRegion(this.region);
        }

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(appContext).createInitializationOptions());
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory();

        // Always registers with a fresh bootstrap token, same as desktop's RelayAgent. Reusing
        // the persisted node id/token broke relay mode for good once the server pruned this
        // node (NodeHealthTask deletes p2p nodes offline for 24h — the saved credentials then
        // point at nothing and the stream is rejected forever), kept a node owned by the
        // previous account after switching accounts, and never refreshed its region label.
        // Re-registering is cheap: the server matches the existing row by hostname and
        // revokes the old credential.
        registerNode();

        channel = ManagedChannelBuilder.forTarget(BuildConfig.P2P_GRPC_ADDRESS).usePlaintext().build();
        openStream();

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::sendHeartbeat, 0, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::reportActiveSessionTraffic, TRAFFIC_REPORT_INTERVAL_SECONDS,
                TRAFFIC_REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Consumes a fresh user-owned bootstrap token (POST /api/v1/user/p2p/bootstrap-token) and calls the blocking RegisterNode RPC. publicIp is a placeholder — never used for a p2p node (docs §8.4). */
    private void registerNode() throws Exception {
        String bootstrapToken = apiClient.createP2pBootstrapToken();

        ManagedChannel regChannel = ManagedChannelBuilder.forTarget(BuildConfig.P2P_GRPC_ADDRESS).usePlaintext().build();
        try {
            AgentRegistrationServiceGrpc.AgentRegistrationServiceBlockingStub stub =
                    AgentRegistrationServiceGrpc.newBlockingStub(regChannel);
            RegisterNodeResponse resp = stub.registerNode(RegisterNodeRequest.newBuilder()
                    .setBootstrapToken(bootstrapToken)
                    .setHostname(buildNodeHostname())
                    .setPublicIp("0.0.0.0") // placeholder — a p2p node is never dialed directly, see docs §8.4
                    .setAgentVersion(BuildConfig.VERSION_NAME)
                    .setRegion(region)
                    .setAsn("")
                    .addAllSupportedTransports(List.of("webrtc"))
                    .setRelayMode(relayMode)
                    .setRelayExpiresAtEpochMs(relayExpiresAtEpochMs)
                    .build());
            this.nodeId = resp.getNodeId();
            this.nodeToken = resp.getNodeToken();
            tokenStore.saveP2pNode(nodeId, nodeToken);
        } finally {
            regChannel.shutdownNow();
        }
    }

    /**
     * "<account email> · android-<model>" — Build.MODEL plus a random suffix
     * (the previous behavior) told nothing about who owned the device,
     * useless for telling p2p nodes apart in the admin panel. Falls back to
     * a device-only label if the profile fetch fails — still better to
     * register with SOME hostname than to block relay mode entirely over a
     * display-label lookup.
     */
    private String buildNodeHostname() {
        String deviceLabel = "android-" + Build.MODEL;
        try {
            return apiClient.getProfile().email + " · " + deviceLabel;
        } catch (Exception e) {
            return deviceLabel + "-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private void openStream() {
        AgentStreamServiceGrpc.AgentStreamServiceStub asyncStub = AgentStreamServiceGrpc.newStub(channel);
        outgoingStream = asyncStub.syncStream(new StreamObserver<>() {
            @Override
            public void onNext(ServerMessage message) {
                handleServerMessage(message);
            }

            @Override
            public void onError(Throwable t) {
                Log.w(TAG, "P2P relay stream error: " + t.getMessage());
                if (listener != null) listener.onError("stream error: " + t.getMessage());
                scheduleReconnect(io.grpc.Status.fromThrowable(t).getCode() == io.grpc.Status.Code.UNAUTHENTICATED);
            }

            @Override
            public void onCompleted() {
                Log.i(TAG, "P2P relay stream completed by server");
                scheduleReconnect(false);
            }
        });
    }

    /**
     * A dropped stream is reopened rather than left dead. Without this one
     * network blip or server restart turned relay mode off for good — still
     * shown as on, the node OFFLINE — until the app was opened again. The
     * desktop and Linux agents already reconnect.
     *
     * @param reRegister the server refused the node's credentials (e.g. the
     *     node was pruned); register again with a fresh bootstrap token.
     */
    private void scheduleReconnect(boolean reRegister) {
        ScheduledExecutorService s = scheduler;
        if (stopped || s == null || s.isShutdown()) return;
        try {
            s.schedule(() -> {
                if (stopped) return;
                try {
                    if (reRegister) registerNode();
                    synchronized (streamLock) {
                        openStream();
                    }
                    sendHeartbeat(); // the first message is what authenticates a stream
                    Log.i(TAG, "P2P relay stream reopened");
                } catch (Exception e) {
                    Log.w(TAG, "P2P relay stream reopen failed: " + e.getMessage());
                    scheduleReconnect(reRegister);
                }
            }, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // stopped meanwhile
        }
    }

    /** The only way anything is written to the stream — see streamLock. */
    private void send(AgentMessage message) {
        synchronized (streamLock) {
            if (outgoingStream == null) return;
            outgoingStream.onNext(message);
        }
    }

    private void handleServerMessage(ServerMessage message) {
        if (message.getPayloadCase() != ServerMessage.PayloadCase.P2P_SIGNAL) {
            return; // config_sync/client_update/command are irrelevant to a p2p relay node — it runs no local Xray-core to apply them to.
        }
        P2pSignal signal = message.getP2PSignal();
        SignalEnvelope envelope = SignalEnvelope.parse(signal.getPayload().toByteArray());
        if (envelope == null) {
            Log.w(TAG, "dropping malformed p2p signal for session " + signal.getSessionId());
            return;
        }

        switch (envelope.kind) {
            case SignalEnvelope.KIND_OFFER -> handleOffer(signal.getSessionId(), envelope);
            case SignalEnvelope.KIND_ICE -> {
                ActiveSession session = sessions.get(signal.getSessionId());
                if (session != null) {
                    session.webrtc.handleRemoteIceCandidate(envelope.candidate, envelope.sdpMid);
                } else {
                    bufferEarlyIce(signal.getSessionId(), envelope);
                }
            }
            default -> Log.w(TAG, "unexpected signal kind from client: " + envelope.kind);
        }
    }

    private void handleOffer(String sessionId, SignalEnvelope offer) {
        if (sessions.containsKey(sessionId)) {
            return; // duplicate offer for an already-active session — ignore.
        }
        if (offer.targetPort <= 0 || offer.targetPort > 65535) {
            Log.w(TAG, "rejecting p2p offer for session " + sessionId + " — invalid target port " + offer.targetPort);
            return;
        }
        // Mandatory SSRF guard (docs §8.8) — resolved exactly once, here; the
        // returned address (never offer.targetHost again) is what actually
        // gets dialed below, so a DNS-rebinding attacker can't have this
        // check pass against a public IP and the real connect land on a
        // private one once the hostname's record changes (see
        // DestinationAcl#resolveAllowedAddress's doc for the full reasoning).
        InetAddress resolvedTarget = DestinationAcl.resolveAllowedAddress(offer.targetHost);
        if (resolvedTarget == null) {
            Log.w(TAG, "rejecting p2p offer for session " + sessionId + " — destination " + offer.targetHost + ":" + offer.targetPort + " is not allowed");
            return;
        }

        // No RelayChannel.Listener attached yet — P2pTcpBridge's constructor
        // (in connectAndBridge below) attaches the real one once the TCP
        // connection succeeds. Any DataChannel messages that arrive before
        // then are buffered by P2pWebRtcSession itself, not dropped.
        P2pWebRtcSession webrtc = new P2pWebRtcSession(peerConnectionFactory,
                envelope -> sendSignal(sessionId, envelope));
        ActiveSession session = new ActiveSession(webrtc, nodeId);
        sessions.put(sessionId, session);

        webrtc.handleOffer(offer.sdp);
        java.util.List<SignalEnvelope> early = earlyIce.remove(sessionId);
        earlyIceAt.remove(sessionId);
        if (early != null) {
            synchronized (early) {
                for (SignalEnvelope ice : early) {
                    webrtc.handleRemoteIceCandidate(ice.candidate, ice.sdpMid);
                }
            }
        }
        connectAndBridge(sessionId, session, resolvedTarget, offer.targetPort, webrtc);
    }

    private void bufferEarlyIce(String sessionId, SignalEnvelope ice) {
        long now = System.currentTimeMillis();
        earlyIceAt.entrySet().removeIf(e -> {
            if (now - e.getValue() <= EARLY_ICE_TTL_MS) return false;
            earlyIce.remove(e.getKey());
            return true;
        });
        if (!earlyIce.containsKey(sessionId) && earlyIce.size() >= MAX_EARLY_ICE_SESSIONS) return;
        earlyIceAt.putIfAbsent(sessionId, now);
        java.util.List<SignalEnvelope> list = earlyIce.computeIfAbsent(sessionId, k -> new java.util.ArrayList<>());
        synchronized (list) {
            if (list.size() < MAX_EARLY_ICE_PER_SESSION) list.add(ice);
        }
    }

    private void connectAndBridge(String sessionId, ActiveSession session, InetAddress address, int port, P2pWebRtcSession webrtc) {
        // Off the gRPC callback thread — Socket#connect blocks, and the ACL
        // check above already gated this, but the connect itself still
        // shouldn't stall stream message processing for other sessions.
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                P2pTcpBridge bridge = new P2pTcpBridge(address, port, webrtc, TCP_CONNECT_TIMEOUT_MS);
                session.bridge = bridge;
                bridge.onClosed(() -> closeSession(sessionId));
                bridge.start();
            } catch (Exception e) {
                Log.w(TAG, "failed to connect p2p session " + sessionId + " to " + address + ":" + port + ": " + e.getMessage());
                closeSession(sessionId);
            }
        });
    }

    private void closeSession(String sessionId) {
        ActiveSession session = sessions.remove(sessionId);
        if (session == null) return;
        if (session.bridge != null) {
            reportSessionTraffic(sessionId, session, true);
            session.bridge.close();
        }
        session.webrtc.close();
    }

    private void sendSignal(String sessionId, SignalEnvelope envelope) {
        send(AgentMessage.newBuilder()
                .setNodeId(nodeId)
                .setNodeToken(nodeToken)
                .setTimestampEpochMs(System.currentTimeMillis())
                .setP2PSignal(P2pSignal.newBuilder()
                        .setSessionId(sessionId)
                        .setPayload(ByteString.copyFrom(envelope.toBytes()))
                        .build())
                .build());
    }

    private void sendHeartbeat() {
        try {
            send(AgentMessage.newBuilder()
                    .setNodeId(nodeId)
                    .setNodeToken(nodeToken)
                    .setTimestampEpochMs(System.currentTimeMillis())
                    .setHeartbeat(Heartbeat.newBuilder()
                            .setXrayRunning(false) // this node type runs no local Xray-core — see class doc
                            .setActiveConnections(sessions.size())
                            .setRelayMode(relayMode)
                            .setRelayExpiresAtEpochMs(relayExpiresAtEpochMs)
                            .build())
                    .build());
        } catch (Exception e) {
            Log.w(TAG, "heartbeat send failed: " + e.getMessage());
        }
    }

    private void reportActiveSessionTraffic() {
        sessions.forEach((sessionId, session) -> reportSessionTraffic(sessionId, session, false));
    }

    private void reportSessionTraffic(String sessionId, ActiveSession session, boolean isFinal) {
        if (session.bridge == null) return;
        long total = session.bridge.getBytesRelayed();
        if (total == session.lastReportedBytes && !isFinal) return;
        session.lastReportedBytes = total;
        send(AgentMessage.newBuilder()
                .setNodeId(nodeId)
                .setNodeToken(nodeToken)
                .setTimestampEpochMs(System.currentTimeMillis())
                .setP2PTrafficReport(P2pSessionTrafficReport.newBuilder()
                        .setSessionId(sessionId)
                        .setBytesRelayed(total)
                        .build())
                .build());
    }

    /** Updates the relay window without a full reconnect — picked up by the next heartbeat (docs §8.5). Server-side eligibility enforcement (Node#isEligibleForRelay) is the real gate; this is just honest self-reporting. */
    public void updateRelayMode(String relayMode, long relayExpiresAtEpochMs) {
        this.relayMode = relayMode;
        this.relayExpiresAtEpochMs = relayExpiresAtEpochMs;
        sendHeartbeat();
    }

    public synchronized void stop() {
        stopped = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        sessions.keySet().forEach(this::closeSession);
        synchronized (streamLock) {
            if (outgoingStream != null) {
                outgoingStream.onCompleted();
            }
        }
        if (channel != null) {
            channel.shutdownNow();
        }
    }
}
