import { describe, expect, it } from 'vitest';
import { parseVlessUri } from '../src/shared/vlessUri';
import { buildXrayConfig, HTTP_PORT, SOCKS_PORT } from '../src/shared/xrayConfigFactory';

const LINK =
  'vless://d290f1ee-6c54-4b01-90e6-d701748f0851@203.0.113.10:443' +
  '?encryption=none&security=reality&type=xhttp&path=%2Fvless-xhttp' +
  '&sni=dl.google.com&pbk=abcDEF123&sid=0123456789abcdef' +
  '#eu-west-node1.example.com';

describe('buildXrayConfig', () => {
  it('listens SOCKS5 + HTTP on localhost (no TUN — system-proxy MVP)', () => {
    const config = buildXrayConfig(parseVlessUri(LINK), 'firefox') as any;
    const tags = config.inbounds.map((i: any) => i.tag);
    expect(tags).toEqual(['socks-in', 'http-in']);
    expect(config.inbounds[0].listen).toBe('127.0.0.1');
    expect(config.inbounds[0].port).toBe(SOCKS_PORT);
    expect(config.inbounds[1].port).toBe(HTTP_PORT);
  });

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
});
