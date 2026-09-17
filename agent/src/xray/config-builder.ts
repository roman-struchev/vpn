export interface InboundRealityConfig {
  enabled: boolean;
  dest: string;
  serverNames: string[];
  privateKey: string;
  shortIds: string[];
}

export interface InboundXhttpSettings {
  path: string;
  mode?: string;
  host?: string;
}

export interface InboundGrpcSettings {
  serviceName: string;
}

/**
 * Real TLS (not Reality) — used for CDN nodes (nodeType NODE_TYPE_CDN), since
 * a CDN terminates TLS itself and Reality's cert-stealing handshake requires
 * an unmodified path to the origin. See docs/ROADMAP_PROGRESS.md Phase 9 and
 * docs/research/ru-blocking.md's CDN section.
 */
export interface InboundTlsSettings {
  enabled: boolean;
  serverName?: string;
  certPath?: string;
  keyPath?: string;
}

export interface ServerClientConfig {
  userId: number;
  deviceId: number;
  uuid: string;
  emailTag: string;
  isActive: boolean;
}

export interface InboundSyncConfig {
  listenPort: number;
  protocol: string;
  transport: string; // 'xhttp' | 'grpc'
  reality?: InboundRealityConfig;
  xhttpSettings?: InboundXhttpSettings;
  grpcSettings?: InboundGrpcSettings;
  tlsSettings?: InboundTlsSettings;
}

export interface ServerConfigSyncPayload {
  configVersion: number;
  configHash: string;
  nodeType: string | number;
  inbound: InboundSyncConfig;
  clients: ServerClientConfig[];
  // Optional second inbound listening concurrently, e.g. gRPC+Reality as a
  // client-selectable fallback when XHTTP is degraded for a given
  // operator/region (transport_policy.fallback_transport).
  fallbackInbound?: InboundSyncConfig;
}

export function hasStructuralChanges(
  prevSync: ServerConfigSyncPayload | null,
  nextSync: ServerConfigSyncPayload
): boolean {
  if (!prevSync) return true;
  if (prevSync.nodeType !== nextSync.nodeType) return true;

  const compareInbound = (a?: InboundSyncConfig, b?: InboundSyncConfig): boolean => {
    if (!a && !b) return true;
    if (!a || !b) return false;
    if (a.listenPort !== b.listenPort) return false;
    if (a.protocol !== b.protocol) return false;
    if (a.transport !== b.transport) return false;

    if (Boolean(a.reality?.enabled) !== Boolean(b.reality?.enabled)) return false;
    if (a.reality?.enabled) {
      if (a.reality.dest !== b.reality?.dest) return false;
      if (a.reality.privateKey !== b.reality?.privateKey) return false;
      if (JSON.stringify(a.reality.serverNames) !== JSON.stringify(b.reality?.serverNames)) return false;
      if (JSON.stringify(a.reality.shortIds) !== JSON.stringify(b.reality?.shortIds)) return false;
    }

    if (Boolean(a.tlsSettings?.enabled) !== Boolean(b.tlsSettings?.enabled)) return false;
    if (a.tlsSettings?.enabled) {
      if (a.tlsSettings.serverName !== b.tlsSettings?.serverName) return false;
      if (a.tlsSettings.certPath !== b.tlsSettings?.certPath) return false;
      if (a.tlsSettings.keyPath !== b.tlsSettings?.keyPath) return false;
    }

    if (a.xhttpSettings?.path !== b.xhttpSettings?.path) return false;
    if (a.xhttpSettings?.mode !== b.xhttpSettings?.mode) return false;
    if (a.xhttpSettings?.host !== b.xhttpSettings?.host) return false;

    if (a.grpcSettings?.serviceName !== b.grpcSettings?.serviceName) return false;

    return true;
  };

  if (!compareInbound(prevSync.inbound, nextSync.inbound)) return true;
  if (!compareInbound(prevSync.fallbackInbound, nextSync.fallbackInbound)) return true;

  return false;
}

// from the server (NodeManagementService hardcodes 443 in production), and
// binding 443 needs root. Unset by default and never referenced by
// scripts/install-node.sh or agent/Dockerfile — only e2e/tests/tunnel.spec.ts
// sets it, so a real xray-core process can actually start as a non-root test
// runner (the fallback gRPC+Reality inbound it actually connects through
// keeps its normal unprivileged port either way).
const PRIMARY_INBOUND_PORT_OVERRIDE = process.env.AGENT_PRIMARY_INBOUND_PORT_OVERRIDE
  ? Number(process.env.AGENT_PRIMARY_INBOUND_PORT_OVERRIDE)
  : undefined;

// Same rationale as above: 'warning' hides the per-connection REALITY dial
// logging tunnel.spec.ts needs to diagnose a failed handshake.
const XRAY_LOGLEVEL = process.env.AGENT_XRAY_LOGLEVEL || 'warning';

export function buildXrayConfig(configSync: ServerConfigSyncPayload): Record<string, unknown> {
  const activeClients = (configSync.clients || []).filter(c => c.isActive);

  const xrayClients = activeClients.map(c => ({
    id: c.uuid,
    email: c.emailTag || `user_${c.userId}_dev_${c.deviceId}`,
    level: 0,
  }));

  const vlessInbounds = [
    buildVlessInbound('vless-inbound', configSync.inbound, xrayClients, PRIMARY_INBOUND_PORT_OVERRIDE),
  ];
  if (configSync.fallbackInbound) {
    vlessInbounds.push(buildVlessInbound('vless-inbound-fallback', configSync.fallbackInbound, xrayClients));
  }

  return {
    log: {
      loglevel: XRAY_LOGLEVEL,
    },
    api: {
      tag: 'api',
      // HandlerService lets the agent add/remove users in a running xray
      // instead of restarting it for every client-list change (see
      // XraySupervisor#applyConfig and handler-api.ts).
      services: ['StatsService', 'HandlerService'],
    },
    stats: {},
    policy: {
      levels: {
        '0': {
          statsUserUplink: true,
          statsUserDownlink: true,
        },
      },
      system: {
        statsInboundUplink: true,
        statsInboundDownlink: true,
        statsOutboundUplink: true,
        statsOutboundDownlink: true,
      },
    },
    inbounds: [
      // Dokodemo-door for local Stats API
      {
        tag: 'api',
        listen: '127.0.0.1',
        port: 10085,
        protocol: 'dokodemo-door',
        settings: {
          address: '127.0.0.1',
        },
      },
      ...vlessInbounds,
    ],
    outbounds: [
      {
        tag: 'direct',
        protocol: 'freedom',
      },
      {
        tag: 'block',
        protocol: 'blackhole',
      },
    ],
    routing: {
      domainStrategy: 'IPIfNonMatch',
      rules: [
        {
          inboundTag: ['api'],
          outboundTag: 'api',
          type: 'field',
        },
        {
          ip: ['geoip:private'],
          outboundTag: 'block',
          type: 'field',
        },
      ],
    },
  };
}

function buildVlessInbound(
  tag: string,
  inbound: InboundSyncConfig,
  clients: { id: string; email: string; level: number }[],
  portOverride?: number
) {
  return {
    tag,
    port: portOverride || inbound.listenPort || 443,
    protocol: 'vless',
    settings: {
      clients,
      decryption: 'none',
    },
    streamSettings: buildStreamSettings(inbound),
  };
}

function buildStreamSettings(inbound: InboundSyncConfig): Record<string, unknown> {
  const reality = inbound.reality;
  const tls = inbound.tlsSettings;
  const isGrpc = inbound.transport === 'grpc';

  const streamSettings: Record<string, unknown> = {
    network: isGrpc ? 'grpc' : 'xhttp',
    security: reality?.enabled ? 'reality' : tls?.enabled ? 'tls' : 'none',
  };

  if (reality?.enabled) {
    streamSettings.realitySettings = {
      show: false,
      dest: reality.dest || 'dl.google.com:443',
      xver: 0,
      serverNames: reality.serverNames?.length ? reality.serverNames : ['dl.google.com'],
      privateKey: reality.privateKey || '',
      shortIds: reality.shortIds?.length ? reality.shortIds : ['0123456789abcdef'],
    };
  } else if (tls?.enabled) {
    // CDN nodes: real certificate, no Reality. Certs are provisioned
    // out-of-band on the node (see scripts/install-node.sh's optional
    // certbot step) — the agent only references the resulting file paths.
    streamSettings.tlsSettings = {
      serverName: tls.serverName || '',
      certificates: [
        {
          certificateFile: tls.certPath || '/etc/xray/certs/fullchain.pem',
          keyFile: tls.keyPath || '/etc/xray/certs/privkey.pem',
        },
      ],
    };
  }

  if (isGrpc) {
    streamSettings.grpcSettings = {
      serviceName: inbound.grpcSettings?.serviceName || 'vless-grpc',
    };
  } else {
    streamSettings.xhttpSettings = {
      path: inbound.xhttpSettings?.path || '/vless-xhttp',
      mode: inbound.xhttpSettings?.mode || 'auto',
      host: inbound.xhttpSettings?.host || '',
    };
  }

  return streamSettings;
}
