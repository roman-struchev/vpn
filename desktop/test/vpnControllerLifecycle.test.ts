import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({ app: { getPath: () => '/tmp', isPackaged: false, getAppPath: () => '/tmp' } }));
vi.mock('../src/main/diagnostics', () => ({ reportError: vi.fn() }));
vi.mock('../src/main/p2p/relayClient', () => ({ P2pRelayBridge: vi.fn() }));

const xray = vi.hoisted(() => ({ running: false, starts: 0, stops: 0 }));
vi.mock('../src/main/xray/xrayProcess', () => ({
  XrayProcess: class {
    isRunning() {
      return xray.running;
    }
    start() {
      if (xray.running) throw new Error('xray is already running; call stop() first');
      xray.running = true;
      xray.starts += 1;
    }
    async stop() {
      if (xray.running) xray.stops += 1;
      xray.running = false;
    }
  },
}));
vi.mock('../src/main/vpn/portReady', () => ({ waitForPortOpen: vi.fn(async () => true) }));
const probe = vi.hoisted(() => ({ ok: true }));
vi.mock('../src/main/vpn/tunnelProbe', () => ({ probeThroughHttpProxy: vi.fn(async () => probe.ok) }));
const censorship = vi.hoisted(() => ({ verdict: 'NO_CONNECTIVITY' }));
vi.mock('../src/main/vpn/censorshipProbe', () => ({ probeCensorship: vi.fn(async () => censorship.verdict) }));

const LINK =
  'vless://d290f1ee-6c54-4b01-90e6-d701748f0851@203.0.113.10:443?security=reality&type=xhttp&pbk=k&sid=01#Netherlands';

function fakeApi(overrides: Record<string, unknown> = {}) {
  return {
    getDeviceId: () => 1,
    touchDevice: vi.fn(async () => true),
    addDevice: vi.fn(),
    saveDeviceId: vi.fn(),
    getSelectedRegion: () => null,
    getRegions: vi.fn(async () => []),
    getRoutingConfig: vi.fn(async () => ({
      primaryTransport: 'XHTTP',
      fallbackTransport: 'GRPC',
      fingerprint: 'firefox',
      backoffInitialSec: 15,
      maxRetriesBeforeNodeSwitch: 2,
      nodes: [],
    })),
    getSubscriptionLinks: vi.fn(async () => ({ count: 1, links: [LINK] })),
    getP2pRelays: vi.fn(async () => []),
    submitTelemetry: vi.fn(async () => undefined),
    ...overrides,
  };
}

/**
 * The desktop connect lifecycle end to end, with xray, the system proxy and
 * the network faked: "Protected" must mean traffic flows, a dead tunnel must
 * be noticed, and a dead end must leave the machine online.
 */
describe('VpnController lifecycle', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    xray.running = false;
    xray.starts = 0;
    xray.stops = 0;
    probe.ok = true;
    censorship.verdict = 'NO_CONNECTIVITY';
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.resetModules();
  });

  const build = async (api = fakeApi()) => {
    const { VpnController } = await import('../src/main/vpn/vpnController');
    const proxy = { enable: vi.fn(async () => undefined), disable: vi.fn(async () => undefined) };
    const controller = new VpnController(api as any, proxy as any);
    return { controller, proxy, api };
  };

  it('connects only once traffic actually flows, and turns the proxy on then', async () => {
    const { controller, proxy } = await build();
    await controller.connect();
    expect(controller.getState()).toBe('CONNECTED');
    expect(proxy.enable).toHaveBeenCalledOnce();
  });

  it('never reports a tunnel that carries nothing as connected', async () => {
    probe.ok = false;
    const { controller, proxy } = await build();
    await controller.connect();
    expect(controller.getState()).toBe('RECONNECTING');
    expect(proxy.enable).not.toHaveBeenCalled();
    expect(xray.running).toBe(false);
  });

  it('notices a tunnel that dies later and leaves CONNECTED', async () => {
    const { controller } = await build();
    await controller.connect();
    probe.ok = false;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(controller.getState()).toBe('CONNECTED'); // one miss is not enough
    await vi.advanceTimersByTimeAsync(30_000);
    expect(controller.getState()).toBe('RECONNECTING');
  });

  it('does not start a second flow when connect() comes in while reconnecting', async () => {
    probe.ok = false;
    const { controller, api } = await build();
    await controller.connect();
    expect(controller.getState()).toBe('RECONNECTING');
    await controller.connect();
    expect(api.getSubscriptionLinks).toHaveBeenCalledOnce();
  });

  it('ends in ERROR with the reason, and the proxy off, when there is no plan', async () => {
    const { ApiError } = await import('../src/main/api/apiClient');
    const api = fakeApi({
      getSubscriptionLinks: vi.fn(async () => {
        throw new ApiError(400, 'Active subscription not found');
      }),
    });
    const { controller, proxy } = await build(api);
    const failures: unknown[] = [];
    controller.on('failure', (r) => failures.push(r));
    await controller.connect();
    expect(controller.getState()).toBe('ERROR');
    expect(controller.getFailure()).toBe('NO_SUBSCRIPTION');
    expect(failures).toContain('NO_SUBSCRIPTION');
    expect(proxy.disable).toHaveBeenCalled();
  });

  it('waits for the network instead of failing on the first unreachable API call', async () => {
    let calls = 0;
    const api = fakeApi({
      getSubscriptionLinks: vi.fn(async () => {
        calls += 1;
        if (calls === 1) throw new TypeError('fetch failed');
        return { count: 1, links: [LINK] };
      }),
    });
    const { controller } = await build(api);
    await controller.connect();
    expect(controller.getState()).toBe('CONNECTING');
    await vi.advanceTimersByTimeAsync(10_000);
    expect(controller.getState()).toBe('CONNECTED');
  });

  it('tears everything down on an operator block, leaving the machine online', async () => {
    probe.ok = false;
    censorship.verdict = 'OPERATOR_RESTRICTION';
    const { controller, proxy } = await build();
    await controller.connect(); // failure 1
    await vi.advanceTimersByTimeAsync(15_000); // failure 2 -> node switch -> all exhausted -> probe
    expect(controller.getState()).toBe('OPERATOR_BLOCKED');
    expect(proxy.disable).toHaveBeenCalled();
    expect(xray.running).toBe(false);
  });

  it('reloads the node list after a full failed round instead of reusing the stale one', async () => {
    probe.ok = false;
    const { controller, api } = await build();
    await controller.connect(); // failure 1
    await vi.advanceTimersByTimeAsync(15_000); // failure 2 -> round exhausted, not the operator
    expect(api.getSubscriptionLinks).toHaveBeenCalledOnce();
    probe.ok = true;
    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.getSubscriptionLinks).toHaveBeenCalledTimes(2);
    expect(controller.getState()).toBe('CONNECTED');
  });

  it('switches region without turning the system proxy off in between', async () => {
    const { controller, proxy, api } = await build();
    await controller.connect();
    await controller.reconnectIfActive();
    expect(proxy.disable).not.toHaveBeenCalled();
    expect(api.getSubscriptionLinks).toHaveBeenCalledTimes(2);
    expect(controller.getState()).toBe('CONNECTED');
    expect(xray.running).toBe(true);
  });
});
