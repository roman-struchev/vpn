import type { ParsedVlessUri } from './vlessUri';
import { vlessParam } from './vlessUri';
import type { Fingerprint } from './reconnectBackoffPolicy';
import type { Transport } from './transportFallbackPolicy';

/**
 * Local proxy ports the desktop client listens on. MVP mode is system
 * proxy, not TUN (docs/PLAN.md §5: "системный прокси, а не TUN" — no admin
 * rights, no driver, no code signing needed for auto-update to keep working).
 */
export const SOCKS_PORT = 10808;
export const HTTP_PORT = 10809;
/**
 * A third local HTTP inbound used only by the app's own liveness probe
 * (main/vpn/tunnelProbe.ts). It always routes to the proxy outbound, whatever
 * the RU routing mode: through the ordinary inbound, 'onlyRu' sends a foreign
 * probe target direct, and the probe would pass with the tunnel dead.
 */
export const PROBE_PORT = 10810;
export const PROBE_INBOUND_TAG = 'probe-in';

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
export interface GrpcFallback {
  port: number;
  serviceName?: string;
}

/**
 * 'off': no special RU routing, everything through the proxy.
 * 'bypassRu': for a user physically in Russia — RU domains/IPs go direct
 *   (banks/Gosuslugi work normally), everything else through the proxy.
 * 'onlyRu': the reverse, for a Russian-speaking user physically outside
 *   Russia who wants to reach RU-geo-restricted services — RU domains/IPs
 *   route through the proxy (which must be a Russia-located exit node for
 *   this to actually satisfy RU geo-restrictions — see vpnController's
 *   region preference for 'onlyRu'), everything else goes direct.
 */
export type RussianRoutingMode = 'off' | 'bypassRu' | 'onlyRu';

export interface XrayConfigOptions {
  russianRoutingMode?: RussianRoutingMode;
  /**
   * Dial this address instead of the node's own, keeping everything else
   * about the outbound identical — used for the P2P relay hop, where a local
   * bridge (main/p2p/relayClient.ts) forwards the connection to the node
   * through somebody else's device.
   *
   * Deliberately affects only where the TCP connection goes: the Reality
   * serverName, the fingerprint, the UUID and the transport all stay as the
   * node issued them, because the VLESS/Reality session is negotiated
   * end-to-end with that node. Rewriting the SNI to the local address would
   * break the handshake — and would be the relay reading the traffic, which
   * is exactly what this design avoids.
   */
  dialThrough?: { host: string; port: number };
}

export function buildXrayConfig(
  vless: ParsedVlessUri,
  fingerprint: Fingerprint,
  transport: Transport = 'XHTTP',
  grpcFallback?: GrpcFallback,
  options?: XrayConfigOptions
): object {
  if (fingerprint !== 'firefox' && fingerprint !== 'edge') {
    throw new Error(`fingerprint must be firefox or edge, got: ${fingerprint}`);
  }
  if (transport === 'GRPC' && !grpcFallback) {
    throw new Error('grpcFallback (port) is required when transport is GRPC');
  }

  const reality = vlessParam(vless, 'security', 'none').toLowerCase() === 'reality';

  const rules: object[] = [
    { type: 'field', inboundTag: [PROBE_INBOUND_TAG], outboundTag: PROXY_OUTBOUND_TAG },
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
  ];

  const mode = options?.russianRoutingMode ?? 'off';
  if (mode === 'bypassRu') {
    rules.push(
      {
        type: 'field',
        domain: ['geosite:category-ru', 'domain:ru'],
        outboundTag: 'direct',
      },
      {
        type: 'field',
        ip: ['geoip:ru'],
        outboundTag: 'direct',
      }
    );
  } else if (mode === 'onlyRu') {
    // Reverse of 'bypassRu': RU traffic goes through the proxy, everything
    // else direct. Xray's implicit "no rule matched -> first outbound"
    // default would still pick 'proxy' (outbound #1) here, so an explicit
    // catch-all is required to make 'direct' the actual default instead of
    // reordering the outbounds array.
    rules.push(
      {
        type: 'field',
        domain: ['geosite:category-ru', 'domain:ru'],
        outboundTag: PROXY_OUTBOUND_TAG,
      },
      {
        type: 'field',
        ip: ['geoip:ru'],
        outboundTag: PROXY_OUTBOUND_TAG,
      },
      {
        type: 'field',
        network: 'tcp,udp',
        outboundTag: 'direct',
      }
    );
  }

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
      { tag: PROBE_INBOUND_TAG, protocol: 'http', listen: '127.0.0.1', port: PROBE_PORT },
    ],

    outbounds: [
      buildProxyOutbound(vless, fingerprint, reality, transport, grpcFallback, options),
      { tag: DNS_OUTBOUND_TAG, protocol: 'dns' },
      { tag: BLOCK_OUTBOUND_TAG, protocol: 'blackhole' },
      { tag: 'direct', protocol: 'freedom' },
    ],

    routing: {
      domainStrategy: 'IPIfNonMatch',
      rules,
    },
  };
}

/**
 * The config for a connection whose exit is another user's device (docs/
 * research/P2P_RELAY_FEASIBILITY.md §8.9) rather than a node of ours.
 *
 * Same inbounds, same routing, same DNS as {@link buildXrayConfig} — the only
 * difference is where matched traffic goes: a SOCKS5 outbound pointed at the
 * local P2P bridge (main/p2p/relayClient.ts), which turns each connection
 * into its own WebRTC session to the peer, who dials the destination itself.
 * There is no VLESS/Reality outbound at all here, because there is no node of
 * ours in the path to speak it to; the hop to the peer is encrypted by
 * WebRTC's own DTLS.
 *
 * UDP is blocked outright rather than left to fail late. A peer forwards a
 * TCP stream and nothing else (see the relay agents), and a SOCKS5 outbound
 * with no UDP ASSOCIATE behind it would otherwise let QUIC look available and
 * then black-hole it — the classic "browser works, some sites just hang".
 * DNS is unaffected: it is answered locally over DoH, which is TCP.
 */
export function buildP2pExitConfig(
  bridge: { host: string; port: number },
  options?: XrayConfigOptions
): object {
  const base = buildXrayConfig(
    // A placeholder vless target that is never dialed: the outbound below
    // replaces the proxy entirely. Kept rather than restructuring
    // buildXrayConfig so the two configs cannot drift in their inbounds,
    // DNS, or RU-routing rules, which are what users actually notice.
    { host: '127.0.0.1', port: 1, uuid: '00000000-0000-0000-0000-000000000000', params: {}, remark: '' } as ParsedVlessUri,
    'firefox',
    'XHTTP',
    undefined,
    options
  ) as {
    outbounds: { tag?: string }[];
    routing: { rules: object[] };
  };

  base.outbounds = base.outbounds.map((outbound) =>
    outbound.tag === PROXY_OUTBOUND_TAG
      ? {
          tag: PROXY_OUTBOUND_TAG,
          protocol: 'socks',
          settings: { servers: [{ address: bridge.host, port: bridge.port }] },
        }
      : outbound
  );

  base.routing.rules = [
    // Ahead of everything else, including the DNS rule: that one only catches
    // UDP:53 from our own inbounds, and what must not happen is any *other*
    // UDP reaching the socks outbound.
    { type: 'field', network: 'udp', port: '1-52,54-65535', outboundTag: BLOCK_OUTBOUND_TAG },
    ...base.routing.rules,
  ];

  return base;
}

function buildProxyOutbound(
  vless: ParsedVlessUri,
  fingerprint: Fingerprint,
  reality: boolean,
  transport: Transport,
  grpcFallback?: GrpcFallback,
  options?: XrayConfigOptions
) {
  const useGrpc = transport === 'GRPC';

  const streamSettings: Record<string, unknown> = {
    network: useGrpc ? 'grpc' : 'xhttp',
    security: reality ? 'reality' : 'none',
  };

  if (useGrpc) {
    streamSettings.grpcSettings = { serviceName: grpcFallback?.serviceName || 'vless-grpc' };
  } else {
    streamSettings.xhttpSettings = {
      path: vlessParam(vless, 'path', '/vless-xhttp'),
      mode: vlessParam(vless, 'mode', 'auto'),
    };
    // XMUX applies to the XHTTP transport only (docs/ROADMAP_PROGRESS.md §1.5).
    streamSettings.xmuxSettings = { maxConcurrency: 16 };
  }

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
          address: options?.dialThrough?.host ?? vless.host,
          // Same node, different port when falling back to gRPC — see
          // NodeManagementService#buildNodeConfigSync on the server (Phase 9).
          port: options?.dialThrough?.port ?? (useGrpc ? grpcFallback!.port : vless.port),
          users: [{ id: vless.uuid, encryption: 'none' }],
        },
      ],
    },
    streamSettings,
  };
}
