import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import grpc from '@grpc/grpc-js';
import protoLoader from '@grpc/proto-loader';
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

describe('XraySupervisor restart (real child processes)', () => {
  let tmpDir: string;
  let configPath: string;
  let pidDir: string;
  let fakeXray: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'xray-restart-test-'));
    configPath = path.join(tmpDir, 'config.json');
    pidDir = path.join(tmpDir, 'pids');
    fs.mkdirSync(pidDir);
    // Stand-in for xray: records its pid while alive, removes it on SIGTERM.
    fakeXray = path.join(tmpDir, 'fake-xray.sh');
    fs.writeFileSync(
      fakeXray,
      `#!/bin/sh\necho $$ > "${pidDir}/$$"\ntrap 'rm -f "${pidDir}/$$"; exit 0' TERM\nwhile true; do sleep 0.05; done\n`,
      { mode: 0o755 }
    );
  });

  afterEach(() => {
    for (const f of fs.readdirSync(pidDir)) {
      try {
        process.kill(Number(f), 'SIGKILL');
      } catch {
        // already gone
      }
    }
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  const payload = (version: number, privateKey: string): ServerConfigSyncPayload => ({
    configVersion: version,
    configHash: `h${version}`,
    nodeType: 'NODE_TYPE_DIRECT',
    inbound: {
      listenPort: 443,
      protocol: 'vless',
      transport: 'xhttp',
      reality: { enabled: true, dest: 'dl.google.com:443', serverNames: ['dl.google.com'], privateKey, shortIds: ['ab'] },
      xhttpSettings: { path: '/vless-xhttp' },
    },
    clients: [{ userId: version, deviceId: version, uuid: `u-${version}`, emailTag: `user_${version}_dev_${version}`, isActive: true }],
  });

  const alivePids = () => fs.readdirSync(pidDir);
  const waitFor = async (cond: () => boolean, ms = 3000) => {
    const until = Date.now() + ms;
    while (!cond() && Date.now() < until) await new Promise((r) => setTimeout(r, 25));
  };

  it('leaves exactly one xray running after a config-triggered restart (old exit must not trigger the watchdog)', async () => {
    const supervisor = new XraySupervisor(configPath, fakeXray);
    await supervisor.applyConfig(payload(1, 'k1'));
    await waitFor(() => alivePids().length === 1);
    expect(alivePids()).toHaveLength(1);

    // Every client change restarts xray on the node — that's where a second,
    // orphaned xray used to appear (both processes sharing :443 via SO_REUSEPORT).
    await supervisor.applyConfig(payload(2, 'k2'));
    await supervisor.applyConfig(payload(3, 'k3'));
    // Longer than the watchdog's first 1s restart delay.
    await new Promise((r) => setTimeout(r, 1500));

    expect(alivePids()).toHaveLength(1);
    expect(supervisor.getWatchdogStats().watchdogAttempts).toBe(0);
    await supervisor.stop();
    await waitFor(() => alivePids().length === 0);
    expect(alivePids()).toHaveLength(0);
  }, 10000);
});

describe('XraySupervisor live user updates', () => {
  let tmpDir: string;
  let configPath: string;
  let pidDir: string;
  let fakeXray: string;
  let handlerServer: grpc.Server;
  let alterCalls: Array<{ tag: string; type: string }>;

  const payload = (version: number, clients: Array<{ id: number; uuid: string }>): ServerConfigSyncPayload => ({
    configVersion: version,
    configHash: `h${version}`,
    nodeType: 'NODE_TYPE_DIRECT',
    inbound: {
      listenPort: 443,
      protocol: 'vless',
      transport: 'xhttp',
      reality: { enabled: true, dest: 'dl.google.com:443', serverNames: ['dl.google.com'], privateKey: 'k', shortIds: ['ab'] },
      xhttpSettings: { path: '/vless-xhttp' },
    },
    clients: clients.map(c => ({ userId: c.id, deviceId: c.id, uuid: c.uuid, emailTag: `user_${c.id}_dev_${c.id}`, isActive: true })),
  });

  beforeEach(async () => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'xray-live-test-'));
    configPath = path.join(tmpDir, 'config.json');
    pidDir = path.join(tmpDir, 'pids');
    fs.mkdirSync(pidDir);
    fakeXray = path.join(tmpDir, 'fake-xray.sh');
    fs.writeFileSync(
      fakeXray,
      `#!/bin/sh\necho $$ > "${pidDir}/$$"\ntrap 'rm -f "${pidDir}/$$"; exit 0' TERM\nwhile true; do sleep 0.05; done\n`,
      { mode: 0o755 }
    );

    // Stands in for the HandlerService xray itself exposes on its api inbound.
    alterCalls = [];
    const def = protoLoader.loadSync(path.resolve(__dirname, '../../proto/xray-handler.proto'), { keepCase: true, defaults: true });
    const pkg: any = grpc.loadPackageDefinition(def);
    handlerServer = new grpc.Server();
    handlerServer.addService(pkg.xray.app.proxyman.command.HandlerService.service, {
      AlterInbound: (call: any, cb: any) => {
        alterCalls.push({ tag: call.request.tag, type: call.request.operation.type });
        cb(null, {});
      },
    });
    await new Promise<void>((resolve, reject) =>
      handlerServer.bindAsync('127.0.0.1:10085', grpc.ServerCredentials.createInsecure(), err => (err ? reject(err) : resolve()))
    );
  });

  afterEach(async () => {
    handlerServer.forceShutdown();
    for (const f of fs.readdirSync(pidDir)) {
      try {
        process.kill(Number(f), 'SIGKILL');
      } catch {
        // already gone
      }
    }
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  const waitFor = async (cond: () => boolean, ms = 3000) => {
    const until = Date.now() + ms;
    while (!cond() && Date.now() < until) await new Promise(r => setTimeout(r, 25));
  };

  it('adds/removes users in the running xray instead of restarting it', async () => {
    const supervisor = new XraySupervisor(configPath, fakeXray);
    await supervisor.applyConfig(payload(1, [{ id: 1, uuid: 'uuid-1' }]));
    await waitFor(() => fs.readdirSync(pidDir).length === 1);
    const pidAfterStart = fs.readdirSync(pidDir)[0];

    // A second device registers: before this was a full restart, which dropped
    // every other user's connection on the node (and broke the new user's own
    // first request right after "connected").
    await supervisor.applyConfig(payload(2, [{ id: 1, uuid: 'uuid-1' }, { id: 2, uuid: 'uuid-2' }]));
    await waitFor(() => alterCalls.length > 0);
    expect(fs.readdirSync(pidDir)).toEqual([pidAfterStart]);
    expect(alterCalls).toEqual([{ tag: 'vless-inbound', type: 'xray.app.proxyman.command.AddUserOperation' }]);

    // ...and a revoked device is removed the same way.
    alterCalls = [];
    await supervisor.applyConfig(payload(3, [{ id: 2, uuid: 'uuid-2' }]));
    await waitFor(() => alterCalls.length > 0);
    expect(fs.readdirSync(pidDir)).toEqual([pidAfterStart]);
    expect(alterCalls).toEqual([{ tag: 'vless-inbound', type: 'xray.app.proxyman.command.RemoveUserOperation' }]);

    // The config on disk still has to be current, so a later restart/crash comes back with the right users.
    const onDisk = JSON.parse(fs.readFileSync(configPath, 'utf-8'));
    const emails = onDisk.inbounds.find((i: any) => i.tag === 'vless-inbound').settings.clients.map((c: any) => c.email);
    expect(emails).toEqual(['user_2_dev_2']);

    await supervisor.stop();
  }, 15000);

  it('falls back to a restart when the HandlerService call fails', async () => {
    const supervisor = new XraySupervisor(configPath, fakeXray);
    await supervisor.applyConfig(payload(1, [{ id: 1, uuid: 'uuid-1' }]));
    await waitFor(() => fs.readdirSync(pidDir).length === 1);
    const pidAfterStart = fs.readdirSync(pidDir)[0];

    handlerServer.forceShutdown(); // xray's api inbound unreachable
    await supervisor.applyConfig(payload(2, [{ id: 1, uuid: 'uuid-1' }, { id: 2, uuid: 'uuid-2' }]));
    await waitFor(() => {
      const pids = fs.readdirSync(pidDir);
      return pids.length === 1 && pids[0] !== pidAfterStart;
    }, 8000);

    const pids = fs.readdirSync(pidDir);
    expect(pids).toHaveLength(1);
    expect(pids[0]).not.toEqual(pidAfterStart);

    await supervisor.stop();
  }, 20000);
});
