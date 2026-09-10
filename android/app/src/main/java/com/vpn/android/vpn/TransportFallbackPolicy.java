package com.vpn.android.vpn;

/**
 * Phase 9 "transport flexibility": once every node has been tried on the
 * primary transport (XHTTP) without success, switch to the gRPC+Reality
 * fallback the server advertises per node (docs/PLAN.md §6: "VLESS + gRPC +
 * Reality — запасной") before giving up. Pure decision logic, driven by the
 * caller each time {@link ReconnectBackoffPolicy} signals a node switch.
 */
public class TransportFallbackPolicy {

    public enum Transport {
        XHTTP, GRPC
    }

    public static final class Outcome {
        public final boolean transportChanged;
        public final boolean allTransportsExhausted;

        Outcome(boolean transportChanged, boolean allTransportsExhausted) {
            this.transportChanged = transportChanged;
            this.allTransportsExhausted = allTransportsExhausted;
        }
    }

    private final int nodeCount;
    private final boolean grpcAvailable;
    private Transport current;
    private int switchesSinceTransportStart = 0;

    public TransportFallbackPolicy(int nodeCount, boolean grpcAvailable) {
        this(nodeCount, grpcAvailable, Transport.XHTTP);
    }

    /**
     * @param initialTransport server-advertised {@code transport_policy.primaryTransport}
     *                         (docs/PLAN.md §6/§10). Only honored when {@code grpcAvailable} —
     *                         otherwise starting on GRPC would have nowhere to fall back to.
     */
    public TransportFallbackPolicy(int nodeCount, boolean grpcAvailable, Transport initialTransport) {
        this.nodeCount = Math.max(1, nodeCount);
        this.grpcAvailable = grpcAvailable;
        this.current = (initialTransport == Transport.GRPC && grpcAvailable) ? Transport.GRPC : Transport.XHTTP;
    }

    public Transport getCurrentTransport() {
        return current;
    }

    /** Call once per node switch (i.e. whenever ReconnectBackoffPolicy.Decision.switchNode is true). */
    public Outcome onNodeSwitch() {
        switchesSinceTransportStart++;
        if (switchesSinceTransportStart < nodeCount) {
            return new Outcome(false, false);
        }

        // Every node has now been tried at least once on the current transport.
        switchesSinceTransportStart = 0;
        if (current == Transport.XHTTP && grpcAvailable) {
            current = Transport.GRPC;
            return new Outcome(true, false);
        }
        return new Outcome(false, true);
    }
}
