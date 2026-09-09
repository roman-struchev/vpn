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

export interface ServerClientConfig {
  userId: number;
  deviceId: number;
  uuid: string;
  emailTag: string;
  isActive: boolean;
}

export interface ServerConfigSyncPayload {
  configVersion: number;
  configHash: string;
  nodeType: string | number;
  inbound: {
    listenPort: number;
    protocol: string;
    transport: string;
    reality?: InboundRealityConfig;
    xhttpSettings?: InboundXhttpSettings;
  };
  clients: ServerClientConfig[];
}

export function buildXrayConfig(configSync: ServerConfigSyncPayload): Record<string, unknown> {
  const activeClients = (configSync.clients || []).filter(c => c.isActive);

  const xrayClients = activeClients.map(c => ({
    id: c.uuid,
    email: c.emailTag || `user_${c.userId}_dev_${c.deviceId}`,
    level: 0,
  }));

  const reality = configSync.inbound?.reality;
  const xhttp = configSync.inbound?.xhttpSettings;

  const streamSettings: Record<string, unknown> = {
    network: 'xhttp',
    security: reality?.enabled ? 'reality' : 'none',
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
  }

  streamSettings.xhttpSettings = {
    path: xhttp?.path || '/vless-xhttp',
    mode: xhttp?.mode || 'auto',
    host: xhttp?.host || '',
  };

  return {
    log: {
      loglevel: 'warning',
    },
    api: {
      tag: 'api',
      services: ['StatsService'],
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
      // Primary VLESS + XHTTP + Reality Inbound
      {
        tag: 'vless-inbound',
        port: configSync.inbound?.listenPort || 443,
        protocol: 'vless',
        settings: {
          clients: xrayClients,
          decryption: 'none',
        },
        streamSettings,
      },
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
