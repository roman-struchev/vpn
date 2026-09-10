import type { ParsedVlessUri } from './vlessUri';
import { vlessParam } from './vlessUri';
import type { Fingerprint } from './reconnectBackoffPolicy';

/**
 * Local proxy ports the desktop client listens on. MVP mode is system
 * proxy, not TUN (docs/PLAN.md §5: "системный прокси, а не TUN" — no admin
 * rights, no driver, no code signing needed for auto-update to keep working).
 */
export const SOCKS_PORT = 10808;
export const HTTP_PORT = 10809;

const PROXY_OUTBOUND_TAG = 'proxy';
const DNS_OUTBOUND_TAG = 'dns-out';
const BLOCK_OUTBOUND_TAG = 'block';

/**
 * Builds the Xray-core JSON config for the local xray child process.
 * Mirrors agent/src/xray/config-builder.ts (server/node side) and
 * android/.../XrayConfigFactory.java (mobile TUN side): same transport
 * (VLESS + XHTTP + Reality), XMUX always on, session-fixed real fingerprint.
 * The only structural difference is the inbound: SOCKS5 + HTTP listening on
 * localhost instead of a TUN device.
 */
export function buildXrayConfig(vless: ParsedVlessUri, fingerprint: Fingerprint): object {
  if (fingerprint !== 'firefox' && fingerprint !== 'edge') {
    throw new Error(`fingerprint must be firefox or edge, got: ${fingerprint}`);
  }

  const reality = vlessParam(vless, 'security', 'none').toLowerCase() === 'reality';

  return {
    log: { loglevel: 'warning' },

    // System-wide DNS queries routed through the proxy are answered here over
    // DoH so the ISP resolver never sees them (PLAN.md §6).
    dns: {
      servers: ['https://1.1.1.1/dns-query', 'https://1.0.0.1/dns-query'],
      queryStrategy: 'UseIP',
    },

    policy: {
      levels: { '0': { statsUserUplink: true, statsUserDownlink: true } },
    },

    inbounds: [
      {
        tag: 'socks-in',
        protocol: 'socks',
        listen: '127.0.0.1',
        port: SOCKS_PORT,
        settings: { udp: true, auth: 'noauth' },
        sniffing: { enabled: true, destOverride: ['http', 'tls'] },
      },
      {
        tag: 'http-in',
        protocol: 'http',
        listen: '127.0.0.1',
        port: HTTP_PORT,
        sniffing: { enabled: true, destOverride: ['http', 'tls'] },
      },
    ],

    outbounds: [
      buildProxyOutbound(vless, fingerprint, reality),
      { tag: DNS_OUTBOUND_TAG, protocol: 'dns' },
      { tag: BLOCK_OUTBOUND_TAG, protocol: 'blackhole' },
    ],

    routing: {
      domainStrategy: 'IPIfNonMatch',
      rules: [
        {
          type: 'field',
          inboundTag: ['socks-in', 'http-in'],
          port: '53',
          network: 'udp',
          outboundTag: DNS_OUTBOUND_TAG,
        },
        {
          type: 'field',
          ip: ['geoip:private'],
          outboundTag: BLOCK_OUTBOUND_TAG,
        },
      ],
    },
  };
}

function buildProxyOutbound(vless: ParsedVlessUri, fingerprint: Fingerprint, reality: boolean) {
  const streamSettings: Record<string, unknown> = {
    network: 'xhttp',
    security: reality ? 'reality' : 'none',
    xhttpSettings: {
      path: vlessParam(vless, 'path', '/vless-xhttp'),
      mode: vlessParam(vless, 'mode', 'auto'),
    },
    // XMUX always on (docs/ROADMAP_PROGRESS.md §1.5).
    xmuxSettings: { maxConcurrency: 16 },
  };

  if (reality) {
    streamSettings.realitySettings = {
      show: false,
      serverName: vlessParam(vless, 'sni', 'dl.google.com'),
      publicKey: vlessParam(vless, 'pbk', ''),
      shortId: vlessParam(vless, 'sid', ''),
      // Real browser fingerprint only, fixed for the whole session — never
      // randomized on reconnect (docs/PLAN.md §6).
      fingerprint,
    };
  }

  return {
    tag: PROXY_OUTBOUND_TAG,
    protocol: 'vless',
    settings: {
      vnext: [
        {
          address: vless.host,
          port: vless.port,
          users: [{ id: vless.uuid, encryption: 'none' }],
        },
      ],
    },
    streamSettings,
  };
}
