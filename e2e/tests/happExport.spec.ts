import { test, expect, type APIRequestContext } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { PASSWORD, setUpAdmin } from './adminHelpers';
import { startLocalAgent, stopLocalAgent, resolveXrayBinaryPath } from './agentHelpers';

// The subscription link, used the way Happ uses it: fetched from the public
// export URL with Happ's User-Agent, checked against Happ's documented
// headers (https://www.happ.su/main/dev-docs/app-management), its vless://
// lines turned into an outbound by the generic share-link rules every
// Xray-based client applies (not by our own apps' code), and then dialled
// with Happ's own xray-core binary through a real node to the open internet.
//
// Unlike tunnel.spec.ts (gRPC fallback on 8443), this goes over the primary
// XHTTP+REALITY inbound — the only thing the link hands out. The one test
// concession: the node's 443 is remapped to 18443 (AGENT_PRIMARY_INBOUND_PORT_OVERRIDE,
// binding 443 needs root), so the link's port is swapped the same way.
//
// Needs: a node xray (XRAY_BIN_PATH, >= 26.x — see agentHelpers) and Happ's
// core (HAPP_XRAY_PATH, default /Applications/Happ.app/Contents/MacOS/core/xray).
// Skips when either is missing.

const nodeXray = resolveXrayBinaryPath();
const happXray = process.env.HAPP_XRAY_PATH || '/Applications/Happ.app/Contents/MacOS/core/xray';
const HAPP_UA = 'Happ/4.2.1';
const LOCAL_PRIMARY_PORT = 18443;

const authed = (token: string) => ({ Authorization: `Bearer ${token}` });
const uid = () => `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

function waitForPort(port: number, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const s = net.connect({ port, host: '127.0.0.1' }, () => {
        s.end();
        resolve();
      });
      s.on('error', () => {
        s.destroy();
        if (Date.now() > deadline) reject(new Error(`nothing on :${port}`));
        else setTimeout(attempt, 300);
      });
    };
    attempt();
  });
}

function getViaProxy(proxyPort: number, url: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: proxyPort, path: url, method: 'GET', timeout: 20_000 }, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => resolve(body));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    req.end();
  });
}

/** Generic vless:// share link -> Xray outbound, as v2rayN/Happ/Hiddify read it. */
function outboundFromShareLink(link: string) {
  const u = new URL(link);
  const q = u.searchParams;
  const network = q.get('type') || 'tcp';
  const security = q.get('security') || 'none';
  const stream: Record<string, unknown> = { network, security };
  if (network === 'xhttp') {
    stream.xhttpSettings = { path: q.get('path') || '/', host: q.get('host') || '', mode: q.get('mode') || 'auto' };
  } else if (network === 'grpc') {
    stream.grpcSettings = { serviceName: q.get('serviceName') || '' };
  }
  if (security === 'reality') {
    stream.realitySettings = {
      serverName: q.get('sni') || '',
      publicKey: q.get('pbk') || '',
      shortId: q.get('sid') || '',
      fingerprint: q.get('fp') || '',
      spiderX: q.get('spx') || '',
    };
  }
  return {
    remark: decodeURIComponent(u.hash.slice(1)),
    address: u.hostname,
    port: Number(u.port || 443),
    outbound: {
      tag: 'proxy',
      protocol: 'vless',
      settings: {
        vnext: [{
          address: u.hostname,
          port: Number(u.port || 443),
          users: [{ id: decodeURIComponent(u.username), encryption: q.get('encryption') || 'none', flow: q.get('flow') || '' }],
        }],
      },
      streamSettings: stream,
    },
  };
}

async function newAccount(request: APIRequestContext, adminToken: string, plan: 'trial' | 'basic') {
  const res = await request.post('/api/v1/auth/register', {
    data: { email: `e2e-happ-${plan}-${uid()}@example.com`, password: PASSWORD },
  });
  const token = (await res.json()).token as string;
  const me = await (await request.get('/api/v1/user/profile', { headers: authed(token) })).json();
  if (plan === 'basic') {
    await request.post(`/api/v1/admin/users/${me.id}/balance`, {
      headers: authed(adminToken),
      data: { amountMicro: 1_000_000, description: 'e2e happ' },
    });
  }
  const buy = await request.post('/api/v1/user/billing/purchase', {
    headers: authed(token),
    data: { tariffId: plan, isAnnual: false },
  });
  expect(buy.status(), await buy.text()).toBe(200);
  const profile = await (await request.get('/api/v1/user/profile', { headers: authed(token) })).json();
  return { token, id: me.id as number, exportPath: new URL(profile.subscriptionUrl).pathname };
}

/** What Happ does on "add subscription": GET the URL with its own User-Agent. */
async function fetchLikeHapp(request: APIRequestContext, exportPath: string) {
  const res = await request.get(exportPath, { headers: { 'User-Agent': HAPP_UA } });
  expect(res.status(), await res.text()).toBe(200);
  const headers = res.headers();
  const lines = Buffer.from((await res.text()).trim(), 'base64').toString('utf8').split('\n').filter(Boolean);
  return { headers, lines };
}

/** Starts Happ's xray with one link and fetches a real page through it. */
async function dialWithHappCore(link: string): Promise<{ ok: boolean; log: string }> {
  const parsed = outboundFromShareLink(link);
  const vnext = (parsed.outbound.settings.vnext as Array<{ port: number }>)[0];
  vnext.port = LOCAL_PRIMARY_PORT;
  const port = 30000 + Math.floor(Math.random() * 10000);
  const config = {
    log: { loglevel: 'warning' },
    inbounds: [{ listen: '127.0.0.1', port, protocol: 'http', settings: {} }],
    outbounds: [parsed.outbound],
  };
  const file = path.join(os.tmpdir(), `e2e-happ-${uid()}.json`);
  fs.writeFileSync(file, JSON.stringify(config));
  let log = '';
  // REALITY borrows dl.google.com's handshake live, which occasionally flakes
  // (see tunnel.spec.ts) — a few fresh client processes, as Happ would retry.
  for (let attempt = 1; attempt <= 4; attempt++) {
    const proc: ChildProcess = spawn(happXray, ['run', '-c', file], { stdio: 'pipe' });
    proc.stdout?.on('data', (d) => (log += d.toString()));
    proc.stderr?.on('data', (d) => (log += d.toString()));
    try {
      await waitForPort(port, 15_000);
      const body = await getViaProxy(port, 'http://example.com/');
      if (body.includes('Example Domain')) {
        proc.kill('SIGTERM');
        fs.unlinkSync(file);
        return { ok: true, log };
      }
      log += `\nattempt ${attempt}: unexpected body ${body.slice(0, 120)}`;
    } catch (e) {
      log += `\nattempt ${attempt}: ${e}`;
    } finally {
      proc.kill('SIGTERM');
      await new Promise((r) => setTimeout(r, 300));
    }
  }
  fs.unlinkSync(file);
  return { ok: false, log };
}

test.describe('the subscription link in Happ', () => {
  test.setTimeout(240_000);
  test.skip(!nodeXray || !fs.existsSync(happXray), 'needs a node xray (XRAY_BIN_PATH) and Happ installed (HAPP_XRAY_PATH)');

  let agent: ReturnType<typeof startLocalAgent> | undefined;
  test.afterEach(() => {
    if (agent) stopLocalAgent(agent);
    agent = undefined;
  });

  test('headers, links and a real connection through Happ\'s core — trial and paid', async ({ page, request }) => {
    const adminEmail = await setUpAdmin(page, 'happ');
    const login = await request.post('/api/v1/auth/login', { data: { email: adminEmail, password: PASSWORD } });
    const adminToken = (await login.json()).token as string;

    // A real node, registered before the links are pulled (links only cover ONLINE nodes).
    const boot = await request.post('/api/v1/admin/nodes/bootstrap-token?pool=paid&type=direct&validHours=1', {
      headers: authed(adminToken),
    });
    agent = startLocalAgent((await boot.json()).token, 'happ', nodeXray!);
    let nodeId = 0;
    await expect(async () => {
      const nodes = await (await request.get('/api/v1/admin/nodes', { headers: authed(adminToken) })).json();
      const n = nodes.find((x: { hostname: string; status: string }) => x.hostname === agent!.hostname);
      expect(n?.status).toBe('ONLINE');
      nodeId = n.id;
    }).toPass({ timeout: 45_000, intervals: [1000] });

    for (const plan of ['trial', 'basic'] as const) {
      await test.step(`${plan}: what Happ gets`, async () => {
        const acct = await newAccount(request, adminToken, plan);
        const { headers, lines } = await fetchLikeHapp(request, acct.exportPath);

        // Happ's documented headers.
        const title = headers['profile-title'];
        expect(title, 'profile-title').toBeTruthy();
        const titleText = title.startsWith('base64:') ? Buffer.from(title.slice(7), 'base64').toString('utf8') : title;
        expect(titleText.length, 'profile-title is at most 25 characters').toBeLessThanOrEqual(25);
        const info = Object.fromEntries(
          headers['subscription-userinfo'].split(';').map((p) => p.trim().split('=')).map(([k, v]) => [k, Number(v)]),
        );
        expect(Object.keys(info).sort()).toEqual(['download', 'expire', 'total', 'upload']);
        expect(info.total, 'total is the plan quota').toBeGreaterThan(0);
        if (plan === 'trial') expect(info.expire, 'the trial has no end: expire=0').toBe(0);
        else expect(info.expire * 1000).toBeGreaterThan(Date.now());
        expect(Number.isInteger(Number(headers['profile-update-interval'])), 'update interval in whole hours').toBe(true);
        expect(() => new URL(headers['profile-web-page-url'])).not.toThrow();
        expect(headers['support-url'], 'support button in Happ').toMatch(/^https:\/\/t\.me\//);

        // The link for our node, read the generic way.
        const ours = lines.find((l) => decodeURIComponent(new URL(l).hash).includes(agent!.hostname));
        expect(ours, `a line for ${agent!.hostname} among ${lines.length}`).toBeTruthy();
        const p = outboundFromShareLink(ours!);
        expect(p.address).toBe('127.0.0.1');
        const rs = (p.outbound.streamSettings as { realitySettings: Record<string, string> }).realitySettings;
        expect(rs.publicKey, 'pbk').not.toBe('');
        expect(rs.fingerprint, 'fp is set explicitly').toBe('firefox');
        expect(p.remark).toContain('e2e-test');

        // The node learns about the key the export just minted, then Happ's core dials it.
        await request.post(`/api/v1/admin/nodes/${nodeId}/sync`, { headers: authed(adminToken) });
        await expect(() => expect(agent!.log()).toMatch(/Received ConfigSync version \d+ with [1-9]\d* clients/))
          .toPass({ timeout: 30_000, intervals: [500] });
        await waitForPort(LOCAL_PRIMARY_PORT, 15_000);
        await new Promise((r) => setTimeout(r, 1500));
        const dial = await dialWithHappCore(ours!);
        expect(dial.ok, `Happ's core could not get through:\n${dial.log}\n--- agent ---\n${agent!.log().slice(-3000)}`).toBe(true);
      });
    }
  });

  test('a user on several networks keeps a working Happ link', async ({ page, request }) => {
    // AntiEnumerationService rotates every key of an account whose links are
    // pulled from more than vpn.anti-enum.max-distinct-ips addresses an hour.
    // Phone on LTE and Wi-Fi, a laptop, the site, Happ: a normal customer
    // gets there, and Happ only refetches every profile-update-interval hours.
    const adminEmail = await setUpAdmin(page, 'happ-ips');
    const login = await request.post('/api/v1/auth/login', { data: { email: adminEmail, password: PASSWORD } });
    const adminToken = (await login.json()).token as string;
    const boot = await request.post('/api/v1/admin/nodes/bootstrap-token?pool=paid&type=direct&validHours=1', {
      headers: authed(adminToken),
    });
    agent = startLocalAgent((await boot.json()).token, 'happips', nodeXray!);
    let nodeId = 0;
    await expect(async () => {
      const nodes = await (await request.get('/api/v1/admin/nodes', { headers: authed(adminToken) })).json();
      const n = nodes.find((x: { hostname: string; status: string }) => x.hostname === agent!.hostname);
      expect(n?.status).toBe('ONLINE');
      nodeId = n.id;
    }).toPass({ timeout: 45_000, intervals: [1000] });

    const acct = await newAccount(request, adminToken, 'basic');
    const { lines } = await fetchLikeHapp(request, acct.exportPath);
    const happLink = lines.find((l) => decodeURIComponent(new URL(l).hash).includes(agent!.hostname))!;

    // Five other addresses this hour — the user's other devices and networks —
    // then their own app refreshes its links once, as it does on every connect.
    const ips = ['10.1.0.1', '10.1.0.2', '10.1.0.3', '10.1.0.4', '10.1.0.5'];
    const values = ips.map((ip) => `(${acct.id}, '${ip}', now())`).join(',');
    execFileSync('docker', ['exec', 'vpn-postgres', 'psql', '-U', 'vpn_user', '-d', 'vpn_db', '-tAc',
      `INSERT INTO subscription_access_log (user_id, ip_address, created_at) VALUES ${values}`]);
    await request.get('/api/v1/user/subscription/links', { headers: authed(acct.token) });

    await request.post(`/api/v1/admin/nodes/${nodeId}/sync`, { headers: authed(adminToken) });
    await expect(() => expect(agent!.log()).toMatch(/Received ConfigSync version \d+ with [1-9]\d* clients/))
      .toPass({ timeout: 30_000, intervals: [500] });
    await waitForPort(LOCAL_PRIMARY_PORT, 15_000);
    await new Promise((r) => setTimeout(r, 1500));
    const dial = await dialWithHappCore(happLink);
    expect(dial.ok, `the link Happ already holds stopped working:\n${dial.log}`).toBe(true);
  });
});
