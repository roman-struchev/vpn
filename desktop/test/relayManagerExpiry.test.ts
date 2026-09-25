import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// RelayManager's own concern here is purely "does a TIMED window turn itself
// off", not anything RelayAgent actually does on the wire (registration,
// WebRTC, gRPC) — that's already covered by relayBridging.test.ts and the
// server-side P2P tests. Mocked out so this test never touches node-datachannel/grpc.
const relayAgentInstances: Array<{ stop: ReturnType<typeof vi.fn>; setRelayMode: ReturnType<typeof vi.fn> }> = [];
vi.mock('../src/main/p2p/relayAgent', () => ({
  RelayAgent: vi.fn().mockImplementation(() => {
    const instance = {
      on: vi.fn(),
      start: vi.fn().mockResolvedValue(undefined),
      stop: vi.fn().mockResolvedValue(undefined),
      setRelayMode: vi.fn(),
    };
    relayAgentInstances.push(instance);
    return instance;
  }),
}));

vi.mock('../src/main/p2p/natCheck', () => ({
  checkNat: vi.fn().mockResolvedValue('OK'),
}));

vi.mock('../src/main/geoLocale', () => ({
  detectNodeRegion: vi.fn().mockResolvedValue('EU'),
}));

vi.mock('electron', () => ({
  app: { setLoginItemSettings: vi.fn() },
}));

describe('RelayManager TIMED auto-off', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    relayAgentInstances.length = 0;
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function buildManager() {
    let saved: { mode: 'OFF' | 'TIMED' | 'ALWAYS'; expiresAtEpochMs: number | null; durationMs: number | null } = {
      mode: 'OFF',
      expiresAtEpochMs: null,
      durationMs: null,
    };
    const tokenStore = {
      saveP2pRelayMode: vi.fn((mode, expiresAtEpochMs, durationMs) => {
        saved = { mode, expiresAtEpochMs: expiresAtEpochMs ?? null, durationMs: durationMs ?? null };
      }),
      getP2pRelayMode: () => saved,
      getP2pRelayRegion: () => 'EU',
      saveP2pRelayRegion: vi.fn(),
    };
    const apiClient = {
      createP2pBootstrapToken: vi.fn().mockResolvedValue({ token: 'tok' }),
      getGrpcTarget: () => 'localhost:9090',
      getProfile: vi.fn().mockResolvedValue({ email: 'user@example.com' }),
    };
    return { tokenStore, apiClient };
  }

  it('setMode(OFF) does not arm an expiry timer', async () => {
    const { RelayManager } = await import('../src/main/p2p/relayManager');
    const { tokenStore, apiClient } = buildManager();
    const manager = new RelayManager(apiClient as any, tokenStore as any);

    await manager.setMode('OFF', null);
    vi.advanceTimersByTime(60 * 60 * 1000);
    expect(manager.getMode().mode).toBe('OFF');
  });

  it('auto-switches TIMED back to OFF once the window elapses, without any further calls', async () => {
    const { RelayManager } = await import('../src/main/p2p/relayManager');
    const { tokenStore, apiClient } = buildManager();
    const manager = new RelayManager(apiClient as any, tokenStore as any);

    const expiresAtEpochMs = Date.now() + 60 * 60 * 1000; // "На 1 час"
    await manager.setMode('TIMED', expiresAtEpochMs, 60 * 60 * 1000);
    expect(manager.getMode().mode).toBe('TIMED');

    // Not expired yet: still on.
    await vi.advanceTimersByTimeAsync(59 * 60 * 1000);
    expect(manager.getMode().mode).toBe('TIMED');

    // Past the hour: must have turned itself off, and stopped the underlying agent.
    await vi.advanceTimersByTimeAsync(2 * 60 * 1000);
    expect(manager.getMode().mode).toBe('OFF');
    expect(relayAgentInstances[0].stop).toHaveBeenCalledTimes(1);
  });

  it('emits modeChanged for the auto-off transition so the renderer can react without polling', async () => {
    const { RelayManager } = await import('../src/main/p2p/relayManager');
    const { tokenStore, apiClient } = buildManager();
    const manager = new RelayManager(apiClient as any, tokenStore as any);
    const onModeChanged = vi.fn();
    manager.on('modeChanged', onModeChanged);

    const expiresAtEpochMs = Date.now() + 60 * 60 * 1000;
    await manager.setMode('TIMED', expiresAtEpochMs, 60 * 60 * 1000);
    onModeChanged.mockClear(); // only care about the auto-off emission below

    await vi.advanceTimersByTimeAsync(61 * 60 * 1000);

    expect(onModeChanged).toHaveBeenCalledWith(expect.objectContaining({ mode: 'OFF' }));
  });

  it('still turns off after the Mac slept through the window (wall clock jumped, timers barely ran)', async () => {
    const { RelayManager } = await import('../src/main/p2p/relayManager');
    const { tokenStore, apiClient } = buildManager();
    const manager = new RelayManager(apiClient as any, tokenStore as any);

    await manager.setMode('TIMED', Date.now() + 60 * 60 * 1000, 60 * 60 * 1000);

    // Lid closed for 9 hours: the wall clock moves on, but Node's monotonic
    // timer clock doesn't, so a single setTimeout(1h) would still be pending.
    vi.setSystemTime(Date.now() + 9 * 60 * 60 * 1000);
    await vi.advanceTimersByTimeAsync(31 * 1000);

    expect(manager.getMode().mode).toBe('OFF');
    expect(relayAgentInstances[0].stop).toHaveBeenCalledTimes(1);
  });

  it('re-arms the timer instead of double-firing when the mode is changed again before expiry', async () => {
    const { RelayManager } = await import('../src/main/p2p/relayManager');
    const { tokenStore, apiClient } = buildManager();
    const manager = new RelayManager(apiClient as any, tokenStore as any);

    await manager.setMode('TIMED', Date.now() + 60 * 60 * 1000, 60 * 60 * 1000);
    // User bumps it up to 8h before the 1h one would have fired.
    await manager.setMode('TIMED', Date.now() + 8 * 60 * 60 * 1000, 8 * 60 * 60 * 1000);

    // The original 1h timer must not have survived to fire an out-of-date auto-off.
    await vi.advanceTimersByTimeAsync(2 * 60 * 60 * 1000);
    expect(manager.getMode().mode).toBe('TIMED');

    await vi.advanceTimersByTimeAsync(7 * 60 * 60 * 1000);
    expect(manager.getMode().mode).toBe('OFF');
  });
});
