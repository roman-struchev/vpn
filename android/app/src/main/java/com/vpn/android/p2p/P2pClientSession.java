package com.vpn.android.p2p;

import android.util.Log;

import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The *connecting* side of a P2P relay session — the mirror of
 * {@link P2pWebRtcSession}, which only ever answers.
 *
 * This side creates the data channel (which is what makes it the offerer),
 * names the address it wants reached, and then carries opaque bytes: the
 * VLESS/Reality session inside them is negotiated end-to-end between the local
 * xray-core and the real node, so the relay — someone else's device — carries
 * the traffic without being able to read it.
 *
 * Like the answering side: STUN only, no TURN. If the two NATs cannot be
 * traversed the session simply never opens and the caller tries another relay,
 * rather than falling back to routing through infrastructure we pay for, which
 * is the cost P2P mode exists to avoid.
 */
public class P2pClientSession implements P2pRelayConnector.ClientSession {

    private static final String TAG = "P2pClientSession";

    private static final List<PeerConnection.IceServer> ICE_SERVERS = List.of(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

    private final PeerConnection peerConnection;
    private final Consumer<SignalEnvelope> outgoingSignal;
    private final String targetHost;
    private final int targetPort;

    private volatile DataChannel dataChannel;
    private volatile Listener listener;
    private volatile Runnable onOpen;
    /** Bytes that arrived before the bridge attached its listener — dropping them would corrupt the stream. */
    private final List<byte[]> pendingMessages = new ArrayList<>();

    public P2pClientSession(PeerConnectionFactory factory,
                            String targetHost,
                            int targetPort,
                            Consumer<SignalEnvelope> outgoingSignal) {
        this.outgoingSignal = outgoingSignal;
        this.targetHost = targetHost;
        this.targetPort = targetPort;

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(ICE_SERVERS);
        this.peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                outgoingSignal.accept(SignalEnvelope.ice(candidate.sdp, candidate.sdpMid));
            }

            @Override
            public void onDataChannel(DataChannel dc) {
                // This side opens the channel; a remote-initiated one would
                // mean the peer is not a relay at all.
            }

            @Override
            public void onSignalingChange(PeerConnection.SignalingState newState) {
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                Log.d(TAG, "ICE connection state: " + newState);
                if (newState == PeerConnection.IceConnectionState.FAILED
                        || newState == PeerConnection.IceConnectionState.CLOSED) {
                    Listener current = listener;
                    if (current != null) {
                        current.onClosed();
                    }
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            }

            @Override
            public void onAddStream(org.webrtc.MediaStream stream) {
            }

            @Override
            public void onRemoveStream(org.webrtc.MediaStream stream) {
            }

            @Override
            public void onRenegotiationNeeded() {
            }

            @Override
            public void onAddTrack(org.webrtc.RtpReceiver receiver, org.webrtc.MediaStream[] streams) {
            }
        });
    }

    /** Called once the channel is open and bytes may flow. */
    public void setOnOpen(Runnable callback) {
        this.onOpen = callback;
    }

    /**
     * Creates the data channel and offers it to the relay, naming the address
     * the relay should dial on our behalf.
     */
    public void start() {
        DataChannel.Init init = new DataChannel.Init();
        // Ordered and reliable: this carries a TCP stream, so a reordered or
        // dropped chunk would corrupt the tunnel rather than merely delay it.
        init.ordered = true;
        DataChannel dc = peerConnection.createDataChannel("relay", init);
        bindDataChannel(dc);

        peerConnection.createOffer(new SdpObserverAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                peerConnection.setLocalDescription(new SdpObserverAdapter(), offer);
                outgoingSignal.accept(SignalEnvelope.offer(offer.description, targetHost, targetPort));
            }

            @Override
            public void onCreateFailure(String error) {
                Log.w(TAG, "createOffer failed: " + error);
                Listener current = listener;
                if (current != null) {
                    current.onClosed();
                }
            }
        }, new MediaConstraints());
    }

    public void handleAnswer(String answerSdp) {
        peerConnection.setRemoteDescription(
                new SdpObserverAdapter(), new SessionDescription(SessionDescription.Type.ANSWER, answerSdp));
    }

    public void handleRemoteIceCandidate(String candidate, String sdpMid) {
        // Same as the answering side: only one m-line is ever negotiated (the
        // implicit one backing the data channel), so index 0 is correct.
        peerConnection.addIceCandidate(new IceCandidate(sdpMid, 0, candidate));
    }

    private void bindDataChannel(DataChannel dc) {
        this.dataChannel = dc;
        dc.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                DataChannel.State state = dc.state();
                if (state == DataChannel.State.OPEN) {
                    Runnable callback = onOpen;
                    if (callback != null) {
                        callback.run();
                    }
                } else if (state == DataChannel.State.CLOSED) {
                    Listener current = listener;
                    if (current != null) {
                        current.onClosed();
                    }
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                ByteBuffer bb = buffer.data;
                byte[] bytes = new byte[bb.remaining()];
                bb.get(bytes);
                synchronized (pendingMessages) {
                    if (listener == null) {
                        pendingMessages.add(bytes);
                        return;
                    }
                }
                listener.onMessage(bytes);
            }
        });
    }

    @Override
    public void send(byte[] data) {
        DataChannel dc = this.dataChannel;
        if (dc == null || dc.state() != DataChannel.State.OPEN) return;
        dc.send(new DataChannel.Buffer(ByteBuffer.wrap(data), false));
    }

    @Override
    public void close() {
        try {
            if (dataChannel != null) {
                dataChannel.close();
            }
        } catch (Exception ignored) {
            // already closing
        }
        try {
            peerConnection.close();
        } catch (Exception ignored) {
            // already closing
        }
    }

    @Override
    public void setListener(Listener listener) {
        List<byte[]> toFlush;
        synchronized (pendingMessages) {
            this.listener = listener;
            toFlush = new ArrayList<>(pendingMessages);
            pendingMessages.clear();
        }
        for (byte[] bytes : toFlush) {
            listener.onMessage(bytes);
        }
    }

    private static class SdpObserverAdapter implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sdp) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String error) {
            Log.w(TAG, "SDP create failed: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            Log.w(TAG, "SDP set failed: " + error);
        }
    }
}
