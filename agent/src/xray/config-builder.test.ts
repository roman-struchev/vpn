import { describe, it, expect } from 'vitest';
import { buildXrayConfig, ServerConfigSyncPayload } from './config-builder.js';

describe('buildXrayConfig', () => {
  it('correctly constructs VLESS + XHTTP + Reality configuration', () => {
    const payload: ServerConfigSyncPayload = {
      configVersion: 1,
      configHash: 'hash-abc-123',
      nodeType: 'NODE_TYPE_DIRECT',
      inbound: {
        listenPort: 443,
        protocol: 'vless',
        transport: 'xhttp',
        reality: {
          enabled: true,
          dest: 'dl.google.com:443',
          serverNames: ['dl.google.com', 'gateway.icloud.com'],
          privateKey: 'testPrivateKeyBase64',
          shortIds: ['0123456789abcdef'],
        },
        xhttpSettings: {
          path: '/vless-xhttp',
          mode: 'auto',
        },
      },
      clients: [
        {
          userId: 1,
          deviceId: 10,
          uuid: 'c8a14b5d-0000-4000-8000-000000000001',
          emailTag: 'user_1_dev_10',
          isActive: true,
        },
        {
          userId: 2,
          deviceId: 20,
          uuid: 'c8a14b5d-0000-4000-8000-000000000002',
          emailTag: 'user_2_dev_20',
          isActive: false, // inactive client should be excluded
        },
      ],
    };

    const config = buildXrayConfig(payload);

    expect(config).toBeDefined();
    expect(config.inbounds).toHaveLength(2);

    // Dokodemo-door API inbound
    const apiInbound = (config.inbounds as any[])[0];
    expect(apiInbound.tag).toBe('api');
    expect(apiInbound.port).toBe(10085);

    // VLESS inbound
    const vlessInbound = (config.inbounds as any[])[1];
    expect(vlessInbound.tag).toBe('vless-inbound');
    expect(vlessInbound.port).toBe(443);
    expect(vlessInbound.protocol).toBe('vless');

    // Only active client should be present
    expect(vlessInbound.settings.clients).toHaveLength(1);
    expect(vlessInbound.settings.clients[0].id).toBe('c8a14b5d-0000-4000-8000-000000000001');
    expect(vlessInbound.settings.clients[0].email).toBe('user_1_dev_10');

    // Stream settings: XHTTP + Reality
    expect(vlessInbound.streamSettings.network).toBe('xhttp');
    expect(vlessInbound.streamSettings.security).toBe('reality');
    expect(vlessInbound.streamSettings.realitySettings.dest).toBe('dl.google.com:443');
    expect(vlessInbound.streamSettings.realitySettings.privateKey).toBe('testPrivateKeyBase64');
    expect(vlessInbound.streamSettings.xhttpSettings.path).toBe('/vless-xhttp');
  });

  it('adds a gRPC+Reality fallback inbound when the server sends one (Phase 9)', () => {
    const payload: ServerConfigSyncPayload = {
      configVersion: 2,
      configHash: 'hash-abc-456',
      nodeType: 'NODE_TYPE_DIRECT',
      inbound: {
        listenPort: 443,
        protocol: 'vless',
        transport: 'xhttp',
        reality: {
          enabled: true,
          dest: 'dl.google.com:443',
          serverNames: ['dl.google.com'],
          privateKey: 'testPrivateKeyBase64',
          shortIds: ['0123456789abcdef'],
        },
        xhttpSettings: { path: '/vless-xhttp', mode: 'auto' },
      },
      fallbackInbound: {
        listenPort: 8443,
        protocol: 'vless',
        transport: 'grpc',
        reality: {
          enabled: true,
          dest: 'dl.google.com:443',
          serverNames: ['dl.google.com'],
          privateKey: 'testPrivateKeyBase64',
          shortIds: ['0123456789abcdef'],
        },
        grpcSettings: { serviceName: 'vless-grpc' },
      },
      clients: [
        { userId: 1, deviceId: 10, uuid: 'c8a14b5d-0000-4000-8000-000000000001', emailTag: 'user_1_dev_10', isActive: true },
      ],
    };

    const config = buildXrayConfig(payload);
    const inbounds = config.inbounds as any[];

    // api, primary xhttp, fallback grpc
    expect(inbounds).toHaveLength(3);

    const grpcInbound = inbounds[2];
    expect(grpcInbound.tag).toBe('vless-inbound-fallback');
    expect(grpcInbound.port).toBe(8443);
    expect(grpcInbound.streamSettings.network).toBe('grpc');
    expect(grpcInbound.streamSettings.security).toBe('reality');
    expect(grpcInbound.streamSettings.grpcSettings.serviceName).toBe('vless-grpc');
    // Same client identities on both transports.
    expect(grpcInbound.settings.clients[0].id).toBe('c8a14b5d-0000-4000-8000-000000000001');
  });

  it('uses real TLS (not Reality, not plaintext) for a CDN node', () => {
    const payload: ServerConfigSyncPayload = {
      configVersion: 1,
      configHash: 'hash-cdn-1',
      nodeType: 'NODE_TYPE_CDN',
      inbound: {
        listenPort: 443,
        protocol: 'vless',
        transport: 'xhttp',
        reality: { enabled: false, dest: '', serverNames: [], privateKey: '', shortIds: [] },
        tlsSettings: {
          enabled: true,
          serverName: 'edge.example.com',
          certPath: '/etc/xray/certs/edge.example.com/fullchain.pem',
          keyPath: '/etc/xray/certs/edge.example.com/privkey.pem',
        },
        xhttpSettings: { path: '/vless-xhttp', mode: 'auto' },
      },
      clients: [],
    };

    const config = buildXrayConfig(payload);
    const vlessInbound = (config.inbounds as any[])[1];

    expect(vlessInbound.streamSettings.security).toBe('tls');
    expect(vlessInbound.streamSettings.tlsSettings.serverName).toBe('edge.example.com');
    expect(vlessInbound.streamSettings.tlsSettings.certificates[0].certificateFile).toBe(
      '/etc/xray/certs/edge.example.com/fullchain.pem'
    );
  });
});
