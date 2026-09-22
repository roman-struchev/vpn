import { describe, expect, it } from 'vitest';
import { parseVlessUri } from '../src/shared/vlessUri';
import { buildP2pExitConfig, buildXrayConfig, HTTP_PORT, PROBE_PORT, SOCKS_PORT } from '../src/shared/xrayConfigFactory';

const LINK =
  'vless://d290f1ee-6c54-4b01-90e6-d701748f0851@203.0.113.10:443' +
  '?encryption=none&security=reality&type=xhttp&path=%2Fvless-xhttp' +
  '&sni=dl.google.com&pbk=abcDEF123&sid=0123456789abcdef' +
  '#eu-west-node1.example.com';

describe('buildXrayConfig', () => {
  it('listens SOCKS5 + HTTP on localhost (no TUN — system-proxy MVP)', () => {
    const config = buildXrayConfig(parseVlessUri(LINK), 'firefox') as any;
    const tags = config.inbounds.map((i: any) => i.tag);
    expect(tags).toEqual(['socks-in', 'http-in', 'probe-in']);
    expect(config.inbounds.every((i: any) => i.listen === '127.0.0.1')).toBe(true);
    expect(config.inbounds[0].port).toBe(SOCKS_PORT);
    expect(config.inbounds[1].port).toBe(HTTP_PORT);
  });

  it.each(['off', 'bypassRu', 'onlyRu'] as const)(
    'sends the liveness probe through the tunnel in %s mode, ahead of every other rule',
    (mode) => {
      // In onlyRu the ordinary inbounds send a foreign address direct, so a
      // probe through them passed with the tunnel dead.
      const config = buildXrayConfig(parseVlessUri(LINK), 'firefox', 'XHTTP', undefined, {
        russianRoutingMode: mode,
      }) as any;
      expect(config.inbounds.find((i: any) => i.tag === 'probe-in').port).toBe(PROBE_PORT);
      expect(config.routing.rules[0]).toEqual({ type: 'field', inboundTag: ['probe-in'], outboundTag: 'proxy' });
    }
  );

  it('proxy outbound is the default route (first in the list)', () => {
    const config = buildXrayConfig(parseVlessUri(LINK), 'firefox') as any;
    expect(config.outbounds[0].tag).toBe('proxy');
    expect(config.outbounds[0].protocol).toBe('vless');
  });

  it('carries the reality keys and the session-fixed fingerprint', () => {
    const config = buildXrayConfig(parseVlessUri(LINK), 'edge') as any;
    const reality = config.outbounds[0].streamSettings.realitySettings;
    expect(reality.fingerprint).toBe('edge');
    expect(reality.publicKey).toBe('abcDEF123');
    expect(reality.shortId).toBe('0123456789abcdef');
  });

  it('always enables xmux', () => {
    const config = buildXrayConfig(parseVlessUri(LINK), 'firefox') as any;
    expect(config.outbounds[0].streamSettings.xmuxSettings).toBeDefined();
  });

  it('rejects a random or missing fingerprint', () => {
    const uri = parseVlessUri(LINK);
    expect(() => buildXrayConfig(uri, 'chrome' as any)).toThrow();
  });

  it('gRPC transport uses the fallback port and service name, keeping Reality', () => {
    const config = buildXrayConfig(parseVlessUri(LINK), 'firefox', 'GRPC', { port: 8443, serviceName: 'vless-grpc' }) as any;
    const streamSettings = config.outbounds[0].streamSettings;

    expect(streamSettings.network).toBe('grpc');
    expect(streamSettings.security).toBe('reality');
    expect(streamSettings.grpcSettings.serviceName).toBe('vless-grpc');
    expect(streamSettings.xhttpSettings).toBeUndefined();

    const vnext = config.outbounds[0].settings.vnext[0];
    expect(vnext.port).toBe(8443);
    expect(vnext.address).toBe('203.0.113.10');
    expect(streamSettings.realitySettings.publicKey).toBe('abcDEF123');
  });

  it('gRPC transport requires a fallback port', () => {
    const uri = parseVlessUri(LINK);
    expect(() => buildXrayConfig(uri, 'firefox', 'GRPC')).toThrow();
  });

  it('includes direct routing rules for RU domains and IPs in bypassRu mode', () => {
    const uri = parseVlessUri(LINK);
    const config = buildXrayConfig(uri, 'firefox', 'XHTTP', undefined, { russianRoutingMode: 'bypassRu' }) as any;
    const rules = config.routing.rules;
    const directRules = rules.filter((r: any) => r.outboundTag === 'direct');
    expect(directRules.length).toBe(2);
    expect(config.outbounds.some((o: any) => o.tag === 'direct' && o.protocol === 'freedom')).toBe(true);
  });

  it('routes RU domains/IPs through the proxy and everything else direct in onlyRu mode', () => {
    const uri = parseVlessUri(LINK);
    const config = buildXrayConfig(uri, 'firefox', 'XHTTP', undefined, { russianRoutingMode: 'onlyRu' }) as any;
    const rules = config.routing.rules;
    const ruProxyRules = rules.filter((r: any) => r.outboundTag === 'proxy' && (r.domain || r.ip));
    expect(ruProxyRules.length).toBe(2);
    const catchAll = rules.find((r: any) => r.outboundTag === 'direct' && r.network === 'tcp,udp');
    expect(catchAll).toBeDefined();
  });

  it('adds no RU-specific rules in off mode', () => {
    const uri = parseVlessUri(LINK);
    const config = buildXrayConfig(uri, 'firefox', 'XHTTP', undefined, { russianRoutingMode: 'off' }) as any;
    const rules = config.routing.rules;
    expect(rules.some((r: any) => r.domain || r.ip?.includes('geoip:ru'))).toBe(false);
  });
});


describe('dialing a node through a P2P relay', () => {
  const vless = parseVlessUri(
    'vless://11111111-2222-3333-4444-555555555555@node.example.com:443?security=reality&sni=www.microsoft.com&pbk=abc&sid=ff&type=xhttp&path=/vless-xhttp#Finland'
  );

  it('sends the connection to the local bridge while keeping the node\'s own identity', () => {
    const config: any = buildXrayConfig(vless, 'firefox', 'XHTTP', undefined, {
      dialThrough: { host: '127.0.0.1', port: 51820 },
    });
    const outbound = config.outbounds.find((o: any) => o.protocol === 'vless');

    // Where the packets go changes...
    expect(outbound.settings.vnext[0].address).toBe('127.0.0.1');
    expect(outbound.settings.vnext[0].port).toBe(51820);
    // ...but nothing about the session inside them: the Reality handshake is
    // still with the node, so rewriting the SNI would break it — and would be
    // the relay reading traffic it must not see.
    expect(outbound.streamSettings.realitySettings.serverName).toBe('www.microsoft.com');
    expect(outbound.settings.vnext[0].users[0].id).toBe('11111111-2222-3333-4444-555555555555');
    expect(outbound.streamSettings.network).toBe('xhttp');
  });

  it('dials the node directly when no relay is in use', () => {
    const config: any = buildXrayConfig(vless, 'firefox', 'XHTTP');
    const outbound = config.outbounds.find((o: any) => o.protocol === 'vless');

    expect(outbound.settings.vnext[0].address).toBe('node.example.com');
    expect(outbound.settings.vnext[0].port).toBe(443);
  });
});

describe('buildP2pExitConfig (another user\'s device as the exit)', () => {
  it('routes everything into the local P2P bridge over SOCKS5, with no VLESS outbound at all', () => {
    const config: any = buildP2pExitConfig({ host: '127.0.0.1', port: 34567 });

    // There is no node of ours in this path to speak VLESS/Reality to: the
    // peer dials the destination itself and the hop to it is DTLS.
    expect(config.outbounds.some((o: any) => o.protocol === 'vless')).toBe(false);

    const proxy = config.outbounds.find((o: any) => o.tag === 'proxy');
    expect(proxy.protocol).toBe('socks');
    expect(proxy.settings.servers[0]).toMatchObject({ address: '127.0.0.1', port: 34567 });
    // Still the default route (first outbound), same as the VLESS config.
    expect(config.outbounds[0].tag).toBe('proxy');
  });

  it('keeps the inbounds and DoH the ordinary config has', () => {
    const config: any = buildP2pExitConfig({ host: '127.0.0.1', port: 1080 });

    expect(config.inbounds.map((i: any) => i.tag)).toEqual(['socks-in', 'http-in', 'probe-in']);
    expect(config.inbounds[1].port).toBe(HTTP_PORT);
    expect(config.dns.servers[0]).toContain('dns-query');
  });

  it('blocks UDP outright, since a peer forwards TCP and nothing else', () => {
    const config: any = buildP2pExitConfig({ host: '127.0.0.1', port: 1080 });
    const [first] = config.routing.rules;

    // Ahead of every other rule, and carved around :53 so DNS still reaches
    // the dns outbound (answered over DoH, which is TCP). Without this, QUIC
    // would look available and then black-hole.
    expect(first).toMatchObject({ network: 'udp', outboundTag: 'block' });
    expect(first.port).toBe('1-52,54-65535');
  });

  it('still applies the RU routing mode the user chose', () => {
    const config: any = buildP2pExitConfig({ host: '127.0.0.1', port: 1080 }, { russianRoutingMode: 'bypassRu' });

    expect(config.routing.rules.some((r: any) => r.outboundTag === 'direct' && r.ip?.includes('geoip:ru'))).toBe(true);
  });
});
