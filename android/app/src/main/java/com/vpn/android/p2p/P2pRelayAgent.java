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
    private ScheduledExecutorService scheduler;
    private PeerConnectionFactory peerConnectionFactory;
    private volatile long nodeId = -1;
    private volatile String nodeToken;
    private volatile String relayMode = "OFF";
    private volatile long relayExpiresAtEpochMs;

    private final Map<String, ActiveSession> sessions = new ConcurrentHashMap<>();

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
     */
    public synchronized void start(String relayMode, long relayExpiresAtEpochMs) throws Exception {
        this.relayMode = relayMode;
        this.relayExpiresAtEpochMs = relayExpiresAtEpochMs;

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(appContext).createInitializationOptions());
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory();

        if (tokenStore.getP2pNodeId() == -1) {
            registerNode();
        } else {
            this.nodeId = tokenStore.getP2pNodeId();
            this.nodeToken = tokenStore.getP2pNodeToken();
        }

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
                    .setHostname("android-" + Build.MODEL + "-" + UUID.randomUUID().toString().substring(0, 8))
                    .setPublicIp("0.0.0.0") // placeholder — a p2p node is never dialed directly, see docs §8.4
                    .setAgentVersion(BuildConfig.VERSION_NAME)
                    .setRegion("p2p")
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
            }

            @Override
            public void onCompleted() {
                Log.i(TAG, "P2P relay stream completed by server");
            }
        });
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
        connectAndBridge(sessionId, session, resolvedTarget, offer.targetPort, webrtc);
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
        if (outgoingStream == null) return;
        outgoingStream.onNext(AgentMessage.newBuilder()
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
        if (outgoingStream == null) return;
        try {
            outgoingStream.onNext(AgentMessage.newBuilder()
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
        if (outgoingStream != null) {
            outgoingStream.onNext(AgentMessage.newBuilder()
                    .setNodeId(nodeId)
                    .setNodeToken(nodeToken)
                    .setTimestampEpochMs(System.currentTimeMillis())
                    .setP2PTrafficReport(P2pSessionTrafficReport.newBuilder()
                            .setSessionId(sessionId)
                            .setBytesRelayed(total)
                            .build())
                    .build());
        }
    }

    /** Updates the relay window without a full reconnect — picked up by the next heartbeat (docs §8.5). Server-side eligibility enforcement (Node#isEligibleForRelay) is the real gate; this is just honest self-reporting. */
    public void updateRelayMode(String relayMode, long relayExpiresAtEpochMs) {
        this.relayMode = relayMode;
        this.relayExpiresAtEpochMs = relayExpiresAtEpochMs;
        sendHeartbeat();
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        sessions.keySet().forEach(this::closeSession);
        if (outgoingStream != null) {
            outgoingStream.onCompleted();
        }
        if (channel != null) {
            channel.shutdownNow();
        }
    }
}
