package com.vpn.android.api.model;

import java.util.List;

/** Mirrors DynamicRoutingService.RoutingConfigResponse (GET /api/v1/client/config). */
public class RoutingConfigResponse {
    public String primaryTransport;
    public String fallbackTransport;
    public String fingerprint;
    public int backoffInitialSec;
    public int maxRetriesBeforeNodeSwitch;
    public List<NodeInfo> nodes;

    public static class NodeInfo {
        public long id;
        public String publicIp;
        public int vlessPort;
        public String region;
        public String sni;
        /** Phase 9: gRPC+Reality fallback inbound on the same node; null for CDN nodes. */
        public Integer grpcFallbackPort;
        public String grpcFallbackServiceName;
    }
}
