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
 * One P2P relay session's WebRTC side (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8.1) — always the *answerer* (a relay node never
 * initiates; it only ever reacts to an incoming offer from a connecting
 * client). STUN-only, no TURN fallback (accepted risk — see the doc's §8.1
 * decision log): if the two peers' NATs can't be traversed with just
 * server-reflexive candidates, the session simply times out rather than
 * relaying through a TURN relay server, which would reintroduce exactly the
 * "traffic through our own infrastructure" cost problem P2P mode exists to
 * avoid.
 *
 * Implements {@link RelayChannel} directly (backed by the underlying
 * DataChannel) so {@link P2pTcpBridge} never needs to know WebRTC exists.
 */
public class P2pWebRtcSession implements RelayChannel {

    private static final String TAG = "P2pWebRtcSession";

    // Google's public STUN server — the only ICE server configured, by
    // design (no TURN entry at all, so there is no fallback path to
    // accidentally rely on).
    private static final List<PeerConnection.IceServer> ICE_SERVERS = List.of(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

    private final PeerConnection peerConnection;
    private volatile DataChannel dataChannel;
    private volatile Listener listener;
    private final Consumer<SignalEnvelope> outgoingSignal;
    // The DataChannel can open (and the remote side can start sending, e.g.
    // the very first bytes of the connecting client's VLESS handshake)
    // before P2pRelayAgent has finished setting up the TCP bridge and calling
    // setListener — dropping those early bytes would silently corrupt the
    // session. Buffered here and flushed, in order, the instant a real
    // listener is attached.
    private final List<byte[]> pendingMessages = new ArrayList<>();

    /**
     * @param outgoingSignal called (possibly many times, for trickled ICE
     *                       candidates) whenever this session needs to send
     *                       something back to the connecting client over the
     *                       existing gRPC signaling channel.
     */
    public P2pWebRtcSession(PeerConnectionFactory factory, Consumer<SignalEnvelope> outgoingSignal) {
        this.outgoingSignal = outgoingSignal;
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(ICE_SERVERS);
        this.peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                outgoingSignal.accept(SignalEnvelope.ice(candidate.sdp, candidate.sdpMid));
            }

            @Override
            public void onDataChannel(DataChannel dc) {
                bindDataChannel(dc);
            }

            @Override
            public void onSignalingChange(PeerConnection.SignalingState newState) {
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                Log.d(TAG, "ICE connection state: " + newState);
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

    /** Feeds the connecting client's SDP offer, answers it, and sends the answer back via {@code outgoingSignal}. */
    public void handleOffer(String offerSdp) {
        SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, offerSdp);
        peerConnection.setRemoteDescription(new SdpObserverAdapter() {
            @Override
            public void onSetSuccess() {
                peerConnection.createAnswer(new SdpObserverAdapter() {
                    @Override
                    public void onCreateSuccess(SessionDescription answer) {
                        peerConnection.setLocalDescription(new SdpObserverAdapter(), answer);
                        outgoingSignal.accept(SignalEnvelope.answer(answer.description));
                    }

                    @Override
                    public void onCreateFailure(String error) {
                        Log.w(TAG, "createAnswer failed: " + error);
                    }
                }, new MediaConstraints());
            }

            @Override
            public void onSetFailure(String error) {
                Log.w(TAG, "setRemoteDescription(offer) failed: " + error);
            }
        }, offer);
    }

    public void handleRemoteIceCandidate(String candidate, String sdpMid) {
        // mLineIndex isn't carried in our envelope (docs §8.1 keeps it
        // minimal) — 0 is safe here since every session only ever negotiates
        // a single m-line (the implicit one backing the data channel).
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
                if (dc.state() == DataChannel.State.CLOSED && listener != null) {
                    listener.onClosed();
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
        if (dataChannel != null) {
            dataChannel.close();
        }
        peerConnection.close();
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

    /** onCreateFailure/onSetFailure default to a no-op log in most call sites — reduces SdpObserver's 4-method boilerplate to only what each call site actually overrides. */
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
