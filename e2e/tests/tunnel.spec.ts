import { test, expect } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { setUpAdmin } from './adminHelpers';
import { startLocalAgent, stopLocalAgent, createBootstrapToken, resolveXrayBinaryPath } from './agentHelpers';

// The rest of this suite (nodes.spec.ts, full-user-flow.spec.ts) verifies the
// control plane and the web UI, but never proves a client can actually move
// bytes through a node's REALITY tunnel with a real xray-core binary on both
// ends — the exact gap that let two real bugs ship silently:
//   1. the desktop client's bundled xray-core being a version ahead of what
//      server nodes run, so REALITY's outbound "password" field went unset
//      ("empty \"password\"");
//   2. NodeManagementService never generating REALITY key material at all,
//      so every node's public/private key stayed NULL ("empty \"publicKey\"").
// Both only surface once a real xray-core process actually parses the
// generated config — simulated mode and a browser-only client never touch
// that code path. This test does: real server, real agent, real node-side
// xray-core, and a real client-side xray-core, moving an actual HTTP request
// through the tunnel end to end.
//
// Uses gRPC+Reality (port 8443, see vpn.grpc-fallback.port) rather than the
// primary XHTTP inbound (port 443) deliberately: binding port 443 needs root.
// This is also exactly the transport production desktop clients fall back to
// the moment 443/XHTTP is unreachable (VpnController's "XHTTP exhausted
// across all nodes, falling back to gRPC+Reality" — Phase 9), so it's a
// realistic path, not a test-only shortcut. Both transports share the same
// Reality key material (NodeManagementService#buildNodeConfigSync), so this
// test exercises the fixed key-generation/version-pin plumbing either way.
//
// Requires a real xray-core binary (see resolveXrayBinaryPath) and outbound
// internet access to vpn.reality.dest (dl.google.com:443 by default) — the
// node's xray must reach it to complete REALITY's handshake camouflage.
// Skips instead of failing when no binary is available, same policy
// nodes.spec.ts documents for XraySupervisor's simulated-mode fallback.

const xrayBinPath = resolveXrayBinaryPath();

const uniqueEmail = () => `e2e-tunnel-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;

function getToken(page: import('@playwright/test').Page) {
  return page.evaluate(() => localStorage.getItem('vpn_auth_token'));
}

function waitForPort(port: number, host: string, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const socket = net.connect({ port, host }, () => {
        socket.end();
        resolve();
      });
      socket.on('error', () => {
        socket.destroy();
        if (Date.now() > deadline) {
          reject(new Error(`Nothing listening on ${host}:${port} after ${timeoutMs}ms`));
        } else {
          setTimeout(attempt, 500);
        }
      });
    };
    attempt();
  });
}

/** GETs `url` through an HTTP proxy listening at 127.0.0.1:proxyPort — the
 * plain-HTTP-proxy request form (absolute-URI in the request line), which
 * needs no extra client library the way HTTPS-via-CONNECT would. */
function fetchThroughHttpProxy(
  proxyPort: number,
  url: string,
  timeoutMs: number
): Promise<{ status: number | undefined; body: string }> {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port: proxyPort, path: url, method: 'GET', timeout: timeoutMs },
      (res) => {
        let data = '';
        res.on('data', (chunk) => (data += chunk));
        res.on('end', () => resolve({ status: res.statusCode, body: data }));
      }
    );
    req.on('timeout', () => req.destroy(new Error('Request through tunnel timed out')));
    req.on('error', reject);
    req.end();
  });
}

function parseVlessLink(link: string) {
  const url = new URL(link);
  return {
    uuid: url.username,
    sni: url.searchParams.get('sni') ?? '',
    pbk: url.searchParams.get('pbk') ?? '',
    sid: url.searchParams.get('sid') ?? '',
  };
}

test.describe('Real xray-core tunnel (node + client)', () => {
  test.setTimeout(120_000);
  test.skip(!xrayBinPath, 'No real xray-core binary found — run `npm run fetch:xray` in desktop/, or set XRAY_BIN_PATH');

  test('a real client tunnels an HTTP request through a real node to the open network', async ({ page }) => {
    let agent: ReturnType<typeof startLocalAgent> | undefined;
    let clientProc: ChildProcess | undefined;
    let clientConfigPath: string | undefined;

    try {
      // 1. Admin session — also doubles as "the end user" purchasing a
      // subscription below (admin accounts are ordinary users otherwise).
      await setUpAdmin(page, 'tunnel');
      const token = await getToken(page);
      const authHeaders = { Authorization: `Bearer ${token}` };

      const profile = await (await page.request.get('/api/v1/user/profile', { headers: authHeaders })).json();
      const userId = profile.id;

      // 2. Give the account enough balance to buy a paid tariff (the paid
      // node pool; the trial's export is covered by happExport.spec.ts).
      await page.request.post(`/api/v1/admin/users/${userId}/balance`, {
        headers: authHeaders,
        data: { amountMicro: 5_000_000, description: 'e2e tunnel test funding' },
      });

      const purchaseRes = await page.request.post('/api/v1/user/billing/purchase', {
        headers: authHeaders,
        data: { tariffId: 'pro', isAnnual: false },
      });
      expect(purchaseRes.ok(), await purchaseRes.text()).toBeTruthy();

      const deviceRes = await page.request.post('/api/v1/user/devices', {
        headers: authHeaders,
        data: { deviceName: 'e2e-tunnel-client', platform: 'OTHER' },
      });
      expect(deviceRes.ok(), await deviceRes.text()).toBeTruthy();

      // 3. Register a real node backed by a real xray-core binary (not
      // XraySupervisor's simulated mode) against a fresh bootstrap token —
      // *before* fetching subscription links: exportVlessLinksForOwnApp only
      // pairs the device with nodes that are already ONLINE at call time
      // (SubscriptionExportService), and a shared dev DB may already have
      // other (possibly stale-keyed) nodes registered, so this test's own
      // node has to exist first and be looked up by hostname below rather
      // than assumed to be links[0].
      const bootstrapRes = await page.request.post(
        '/api/v1/admin/nodes/bootstrap-token?pool=paid&type=direct&validHours=1',
        { headers: authHeaders }
      );
      const { token: bootstrapToken } = await bootstrapRes.json();

      agent = startLocalAgent(bootstrapToken, 'tunnel', xrayBinPath!);

      const agentHostname = agent.hostname;
      let nodeId: number | undefined;
      await expect(async () => {
        const nodesRes = await page.request.get('/api/v1/admin/nodes', { headers: authHeaders });
        const nodes = await nodesRes.json();
        const match = nodes.find((n: { hostname: string; status: string }) => n.hostname === agentHostname);
        expect(match?.status).toBe('ONLINE');
        nodeId = match.id;
      }).toPass({ timeout: 45_000, intervals: [1000] });

      // 4. Now that the node exists, fetching links creates (and returns) the
      // DeviceNodeKey pairing for it — but the currently-running agent
      // process already got its *initial* ConfigSync (zero clients, sent at
      // registration) before this pairing existed, so force a fresh push.
      const linksRes = await page.request.get('/api/v1/user/subscription/links', { headers: authHeaders });
      const { links } = await linksRes.json();
      const ourLink = (links as string[]).find((l) => decodeURIComponent(new URL(l).hash).includes(agentHostname));
      expect(ourLink, `No subscription link for our node (${agentHostname}) among: ${links.join(', ')}`).toBeTruthy();
      const { uuid, sni, pbk, sid } = parseVlessLink(ourLink!);
      expect(pbk, 'REALITY public key must not be empty — see NodeManagementService#registerNode key generation').not.toBe('');

      await page.request.post(`/api/v1/admin/nodes/${nodeId}/sync`, { headers: authHeaders });

      // The agent's *initial* ConfigSync (sent at registration, before this
      // pairing existed) had zero clients; xray-core is already running by
      // then, so a bare port check can't tell that config apart from the one
      // the force-sync above triggers. Wait for the agent's own log line
      // instead ("Received ConfigSync version N with K clients" — see
      // agent/src/client/grpc-client.ts) so the client below only connects
      // once the node's xray-core actually knows about our device's UUID.
      await expect(() => {
        expect(agent!.log()).toMatch(/Received ConfigSync version \d+ with [1-9]\d* clients/);
      }).toPass({ timeout: 30_000, intervals: [500] });
      await waitForPort(8443, '127.0.0.1', 15_000);

      // 4. Real client: a second xray-core process, using the exact
      // gRPC+Reality fallback shape production desktop clients use (see the
      // suite-level comment above) — proxying via a plain HTTP inbound needs
      // no extra client library.
      const clientHttpPort = 20000 + Math.floor(Math.random() * 10000);
      const clientConfig = {
        log: { loglevel: 'debug' },
        inbounds: [{ listen: '127.0.0.1', port: clientHttpPort, protocol: 'http', settings: {} }],
        outbounds: [
          {
            tag: 'proxy',
            protocol: 'vless',
            settings: {
              vnext: [
                {
                  address: '127.0.0.1',
                  port: 8443,
                  users: [{ id: uuid, encryption: 'none' }],
                },
              ],
            },
            streamSettings: {
              network: 'grpc',
              security: 'reality',
              grpcSettings: { serviceName: 'vless-grpc' },
              realitySettings: { show: false, serverName: sni, publicKey: pbk, shortId: sid, fingerprint: 'chrome' },
            },
          },
          { tag: 'direct', protocol: 'freedom' },
        ],
      };

      clientConfigPath = path.join(os.tmpdir(), `e2e-tunnel-client-${Date.now()}.json`);
      fs.writeFileSync(clientConfigPath, JSON.stringify(clientConfig, null, 2));

      // 5. The actual assertion: a request through the client's local proxy
      // must travel client -> REALITY -> node -> node's freedom outbound ->
      // the real open internet, and get a real response back.
      //
      // Deliberately NOT a local echo server: the node's own routing config
      // (agent/src/xray/config-builder.ts) has a `geoip:private -> block`
      // rule specifically to stop the tunnel being used to reach into the
      // node's own private/loopback network — confirmed live while writing
      // this test (a 127.0.0.1 target got real VLESS auth through REALITY,
      // then "app/dispatcher: taking detour [block]"). That's the node
      // behaving correctly, not a bug, so the target here has to be a real
      // public address for the assertion to mean anything. example.com is
      // IANA-reserved for exactly this kind of test — stable, plain HTTP,
      // no auth/redirects to complicate an HTTP-proxy GET.
      //
      // A single real REALITY handshake occasionally comes back
      // "REALITY: processed invalid connection" even with fully correct
      // key material and config (observed live, intermittently, while
      // writing this test) — REALITY's handshake genuinely depends on a
      // live round trip to vpn.reality.dest (dl.google.com) to borrow its
      // TLS certificate for camouflage, and that step can flake. Production
      // clients are already built around exactly this (VpnController's
      // ReconnectBackoffPolicy retries the whole tunnel on failure), so a
      // few retries here — full fresh client process each time, not just
      // re-hitting the same one — matches real usage rather than papering
      // over a real bug.
      let clientLog = '';
      let lastError: unknown;
      let response: { status: number | undefined; body: string } | undefined;
      for (let attempt = 1; attempt <= 4 && !response; attempt++) {
        clientProc?.kill('SIGTERM');
        clientLog = '';
        clientProc = spawn(xrayBinPath!, ['run', '-c', clientConfigPath], { stdio: 'pipe' });
        clientProc.stdout?.on('data', (d) => (clientLog += d.toString()));
        clientProc.stderr?.on('data', (d) => (clientLog += d.toString()));

        try {
          await waitForPort(clientHttpPort, '127.0.0.1', 20_000);
          const res = await fetchThroughHttpProxy(clientHttpPort, 'http://example.com/', 20_000);
          if (res.body.includes('Example Domain')) {
            response = res;
          } else {
            lastError = new Error(`attempt ${attempt}: status=${res.status} body=${res.body.slice(0, 200)}`);
          }
        } catch (e) {
          lastError = e;
        }
      }

      if (!response) {
        throw new Error(
          `All retry attempts failed; last error: ${lastError}\nlast clientLog=\n${clientLog}\nagentLog=\n${agent.log()}`
        );
      }
      expect(response.body).toContain('Example Domain');
    } finally {
      clientProc?.kill('SIGTERM');
      if (clientConfigPath) fs.unlinkSync(clientConfigPath);
      if (agent) stopLocalAgent(agent);
    }
  });
});
