import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { XraySupervisor } from './xray-supervisor.js';
import { hasStructuralChanges, ServerConfigSyncPayload } from './config-builder.js';

describe('hasStructuralChanges', () => {
  const baseConfig: ServerConfigSyncPayload = {
    configVersion: 1,
    configHash: 'hash-1',
    nodeType: 'NODE_TYPE_STANDALONE',
    inbound: {
      listenPort: 443,
      protocol: 'vless',
      transport: 'xhttp',
      reality: {
        enabled: true,
        dest: 'dl.google.com:443',
        serverNames: ['dl.google.com'],
        privateKey: 'priv-key-1',
        shortIds: ['abcd'],
      },
      xhttpSettings: {
        path: '/vless-xhttp',
      },
    },
    clients: [
      { userId: 1, deviceId: 1, uuid: 'u-1', emailTag: 'u1', isActive: true },
    ],
  };

  it('returns true if prevConfig is null', () => {
    expect(hasStructuralChanges(null, baseConfig)).toBe(true);
  });

  it('returns false if only clients change', () => {
    const updatedClientsConfig: ServerConfigSyncPayload = {
      ...baseConfig,
      configVersion: 2,
      configHash: 'hash-2',
      clients: [
        { userId: 1, deviceId: 1, uuid: 'u-1', emailTag: 'u1', isActive: true },
        { userId: 2, deviceId: 2, uuid: 'u-2', emailTag: 'u2', isActive: true },
      ],
    };
    expect(hasStructuralChanges(baseConfig, updatedClientsConfig)).toBe(false);
  });

  it('returns true if transport changes', () => {
    const grpcConfig: ServerConfigSyncPayload = {
      ...baseConfig,
      inbound: {
        ...baseConfig.inbound,
        transport: 'grpc',
        grpcSettings: { serviceName: 'vless-grpc' },
      },
    };
    expect(hasStructuralChanges(baseConfig, grpcConfig)).toBe(true);
  });

  it('returns true if reality private key changes', () => {
    const keyChangedConfig: ServerConfigSyncPayload = {
      ...baseConfig,
      inbound: {
        ...baseConfig.inbound,
        reality: {
          ...baseConfig.inbound.reality!,
          privateKey: 'new-key',
        },
      },
    };
    expect(hasStructuralChanges(baseConfig, keyChangedConfig)).toBe(true);
  });
});

describe('XraySupervisor', () => {
  let tmpDir: string;
  let configPath: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'xray-test-'));
    configPath = path.join(tmpDir, 'xray-config.json');
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('applies initial config and creates directory if missing', async () => {
    const supervisor = new XraySupervisor(configPath, 'xray-mock');
    const syncPayload: ServerConfigSyncPayload = {
      configVersion: 1,
      configHash: 'h1',
      nodeType: 'NODE_TYPE_STANDALONE',
      inbound: {
        listenPort: 443,
        protocol: 'vless',
        transport: 'xhttp',
      },
      clients: [],
    };

    const res = await supervisor.applyConfig(syncPayload);
    expect(res).toBe(true);
    expect(fs.existsSync(configPath)).toBe(true);

    const stats = supervisor.getWatchdogStats();
    expect(stats.watchdogAttempts).toBe(0);
    await supervisor.stop();
  });
});
