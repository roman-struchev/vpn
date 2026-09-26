import dgram from 'node:dgram';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { checkNat, parseMappedAddress } from '../src/main/p2p/natCheck';

// Fake STUN servers on loopback: each answers a Binding Request with the
// XOR-MAPPED-ADDRESS it is told to report. Same port from both = a NAT that
// keeps one mapping per socket; different ports = symmetric.
const servers: dgram.Socket[] = [];

function xorMapped(ip: string, port: number, request: Buffer): Buffer {
  const res = Buffer.alloc(32);
  res.writeUInt16BE(0x0101, 0);
  res.writeUInt16BE(12, 2);
  request.copy(res, 4, 4, 20);
  res.writeUInt16BE(0x0020, 20);
  res.writeUInt16BE(8, 22);
  res.writeUInt8(0, 24);
  res.writeUInt8(0x01, 25);
  res.writeUInt16BE(port ^ 0x2112, 26);
  const cookie = [0x21, 0x12, 0xa4, 0x42];
  ip.split('.').forEach((o, i) => res.writeUInt8(Number(o) ^ cookie[i], 28 + i));
  return res;
}

async function fakeStun(reportPort: number | null): Promise<number> {
  const s = dgram.createSocket('udp4');
  servers.push(s);
  s.on('message', (msg, rinfo) => {
    if (reportPort === null) return; // silent: UDP "blocked"
    s.send(xorMapped('203.0.113.7', reportPort, msg), rinfo.port, rinfo.address);
  });
  await new Promise<void>((r) => s.bind(0, '127.0.0.1', () => r()));
  return s.address().port;
}

afterEach(() => {
  servers.splice(0).forEach((s) => s.close());
});

describe('checkNat', () => {
  it('one public port for both servers: relaying can work', async () => {
    const a = await fakeStun(40000);
    const b = await fakeStun(40000);
    expect(await checkNat([{ host: '127.0.0.1', port: a }, { host: '127.0.0.1', port: b }], 500)).toBe('OK');
  });

  it('a new public port per server: a symmetric NAT, nobody can reach the device', async () => {
    const a = await fakeStun(40000);
    const b = await fakeStun(40001);
    expect(await checkNat([{ host: '127.0.0.1', port: a }, { host: '127.0.0.1', port: b }], 500)).toBe('SYMMETRIC');
  });

  it('no answer at all: UDP is blocked', async () => {
    const a = await fakeStun(null);
    const b = await fakeStun(null);
    expect(await checkNat([{ host: '127.0.0.1', port: a }, { host: '127.0.0.1', port: b }], 300)).toBe('NO_UDP');
  });

  it('only one answer: cannot tell, so it is not refused', async () => {
    const a = await fakeStun(40000);
    const b = await fakeStun(null);
    expect(await checkNat([{ host: '127.0.0.1', port: a }, { host: '127.0.0.1', port: b }], 300)).toBe('UNKNOWN');
  });

  it('reads the XOR-MAPPED-ADDRESS a real STUN server sends', () => {
    const req = Buffer.alloc(20);
    req.writeUInt32BE(0x2112a442, 4);
    expect(parseMappedAddress(xorMapped('79.143.107.32', 55021, req))).toBe('79.143.107.32:55021');
  });
});

describe('RelayManager on a network nobody can reach', () => {
  it('refuses to start, stays OFF and says why', async () => {
    vi.resetModules();
    vi.doMock('electron', () => ({ app: { setLoginItemSettings: vi.fn() } }));
    const agentStart = vi.fn();
    vi.doMock('../src/main/p2p/relayAgent', () => ({
      RelayAgent: vi.fn().mockImplementation(() => ({ on: vi.fn(), start: agentStart, stop: vi.fn() })),
    }));
    const { RelayManager } = await import('../src/main/p2p/relayManager');
    let saved = { mode: 'OFF', expiresAtEpochMs: null as number | null, durationMs: null as number | null };
    const tokenStore = {
      saveP2pRelayMode: vi.fn((mode, exp) => (saved = { mode, expiresAtEpochMs: exp, durationMs: null })),
      getP2pRelayMode: () => saved,
      getP2pRelayRegion: () => 'Montenegro, Podgorica',
      saveP2pRelayRegion: vi.fn(),
    };
    const apiClient = { createP2pBootstrapToken: vi.fn(), getGrpcTarget: () => 'x', getProfile: vi.fn() };
    const manager = new RelayManager(apiClient as never, tokenStore as never, async () => 'SYMMETRIC');

    await expect(manager.setMode('ALWAYS', null)).rejects.toThrow('RELAY_UNSUPPORTED_NETWORK:SYMMETRIC');
    expect(apiClient.createP2pBootstrapToken).not.toHaveBeenCalled();
    expect(agentStart).not.toHaveBeenCalled();
    expect(manager.getMode()).toMatchObject({ mode: 'OFF', unsupportedNetwork: 'SYMMETRIC' });

    await manager.setMode('OFF', null);
    expect(manager.getMode().unsupportedNetwork).toBeNull();
  });
});

describe('RelayManager when the server cannot reach this device', () => {
  async function build(states: string[]) {
    vi.resetModules();
    vi.doMock('electron', () => ({ app: { setLoginItemSettings: vi.fn() } }));
    const agent = { on: vi.fn(), start: vi.fn(), stop: vi.fn(), getNodeId: () => 16 };
    vi.doMock('../src/main/p2p/relayAgent', () => ({ RelayAgent: vi.fn().mockImplementation(() => agent) }));
    const { RelayManager } = await import('../src/main/p2p/relayManager');
    let saved = { mode: 'OFF', expiresAtEpochMs: null as number | null, durationMs: null as number | null };
    const tokenStore = {
      saveP2pRelayMode: vi.fn((mode, exp) => (saved = { mode, expiresAtEpochMs: exp, durationMs: null })),
      getP2pRelayMode: () => saved,
      getP2pRelayRegion: () => 'Montenegro, Podgorica',
      saveP2pRelayRegion: vi.fn(),
    };
    const answers = [...states];
    const apiClient = {
      createP2pBootstrapToken: vi.fn(async () => ({ token: 't' })),
      getGrpcTarget: () => 'x',
      getProfile: vi.fn(async () => ({ email: 'a@b.c' })),
      getP2pReachability: vi.fn(async () => answers.shift() ?? 'PENDING'),
    };
    const manager = new RelayManager(apiClient as never, tokenStore as never, async () => 'OK', async () => undefined);
    return { manager, agent, apiClient };
  }

  it('turns relaying off with the reason once the server says nobody gets through', async () => {
    const { manager, agent } = await build(['PENDING', 'PENDING', 'UNREACHABLE']);
    await expect(manager.setMode('ALWAYS', null)).rejects.toThrow('RELAY_UNSUPPORTED_NETWORK:UNREACHABLE');
    expect(agent.stop).toHaveBeenCalled();
    expect(manager.getMode()).toMatchObject({ mode: 'OFF', unsupportedNetwork: 'UNREACHABLE' });
  });

  it('keeps relaying once reachable, and with a server that does not check', async () => {
    for (const verdict of ['REACHABLE', 'UNKNOWN', 'INCONCLUSIVE']) {
      const { manager, agent } = await build(['PENDING', verdict]);
      await manager.setMode('ALWAYS', null);
      expect(agent.stop).not.toHaveBeenCalled();
      expect(manager.getMode()).toMatchObject({ mode: 'ALWAYS', unsupportedNetwork: null });
    }
  });
});

