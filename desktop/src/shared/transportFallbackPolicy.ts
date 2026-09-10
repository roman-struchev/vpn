/**
 * Phase 9 "transport flexibility": once every node has been tried on the
 * primary transport (XHTTP) without success, switch to the gRPC+Reality
 * fallback the server advertises per node (docs/PLAN.md §6: "VLESS + gRPC +
 * Reality — запасной") before giving up. Same contract as the Android
 * client's TransportFallbackPolicy, ported independently per docs/PLAN.md §5.
 */
export type Transport = 'XHTTP' | 'GRPC';

export interface TransportSwitchOutcome {
  transportChanged: boolean;
  allTransportsExhausted: boolean;
}

export class TransportFallbackPolicy {
  private readonly nodeCount: number;
  private readonly grpcAvailable: boolean;
  private current: Transport = 'XHTTP';
  private switchesSinceTransportStart = 0;

  constructor(nodeCount: number, grpcAvailable: boolean) {
    this.nodeCount = Math.max(1, nodeCount);
    this.grpcAvailable = grpcAvailable;
  }

  getCurrentTransport(): Transport {
    return this.current;
  }

  /** Call once per node switch (i.e. whenever ReconnectBackoffPolicy's Decision.switchNode is true). */
  onNodeSwitch(): TransportSwitchOutcome {
    this.switchesSinceTransportStart += 1;
    if (this.switchesSinceTransportStart < this.nodeCount) {
      return { transportChanged: false, allTransportsExhausted: false };
    }

    this.switchesSinceTransportStart = 0;
    if (this.current === 'XHTTP' && this.grpcAvailable) {
      this.current = 'GRPC';
      return { transportChanged: true, allTransportsExhausted: false };
    }
    return { transportChanged: false, allTransportsExhausted: true };
  }
}
