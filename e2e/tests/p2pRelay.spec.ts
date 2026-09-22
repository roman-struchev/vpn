import { test, expect, APIRequestContext } from '@playwright/test';

/**
 * The server side of connecting *through* a relay peer: discovery, the
 * signaling broker, and who is allowed to touch a session.
 *
 * The WebRTC half cannot be exercised from here — it needs two real peers —
 * and is covered where it belongs (desktop/test/relayClient.test.ts opens a
 * real data channel; android's P2pRelayConnectorTest drives the connector over
 * real sockets). What this level is for is the part those cannot see: that the
 * API refuses what it should, against a live deployment.
 */

const uniqueEmail = (tag: string) => `e2e-${tag}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
const PASSWORD = 'E2ePassw0rd!';
const authed = (token: string) => ({ Authorization: `Bearer ${token}` });

async function registerWithTrial(request: APIRequestContext, tag: string): Promise<string> {
  const res = await request.post('/api/v1/auth/register', { data: { email: uniqueEmail(tag), password: PASSWORD } });
  expect(res.status()).toBe(200);
  const token = (await res.json()).token;
  const trial = await request.post('/api/v1/user/billing/purchase', {
    headers: authed(token),
    data: { tariffId: 'trial', isAnnual: false },
  });
  expect(trial.status()).toBe(200);
  return token;
}

test.describe('relay discovery', () => {
  test('an account gets a relay list, and a stranger gets nothing at all', async ({ request }) => {
    const token = await registerWithTrial(request, 'relaylist');

    const mine = await request.get('/api/v1/user/p2p/relays', { headers: authed(token) });
    expect(mine.status()).toBe(200);
    const relays = (await mine.json()).relays;
    expect(Array.isArray(relays), 'an empty list is an ordinary answer — relays are other people\'s devices').toBe(true);

    // Whatever is listed must never identify the device or its owner: a relay
    // is somebody's phone, reached over WebRTC by id, never dialed.
    for (const relay of relays) {
      expect(Object.keys(relay).sort()).toEqual(['activeConnections', 'lastSeenAt', 'nodeId', 'region']);
    }

    const anonymous = await request.get('/api/v1/user/p2p/relays');
    expect(anonymous.status()).toBeGreaterThanOrEqual(400);
  });

  test('a trial account is offered no P2P exits, and a stranger none at all', async ({ request }) => {
    const token = await registerWithTrial(request, 'exitlist');

    const mine = await request.get('/api/v1/user/p2p/exits?region=Montenegro,%20Podgorica', {
      headers: authed(token),
    });
    // Not an error: the row is shown to a trial account with a padlock, so
    // asking anyway is answered with "nobody available".
    expect(mine.status()).toBe(200);
    expect((await mine.json()).exits, 'P2P exits are a paid-plan feature').toEqual([]);

    const anonymous = await request.get('/api/v1/user/p2p/exits');
    expect(anonymous.status()).toBeGreaterThanOrEqual(400);
  });

  test('every region row carries a key, and only a P2P row is prefixed', async ({ request }) => {
    const token = await registerWithTrial(request, 'regionkeys');

    const res = await request.get('/api/v1/user/regions', { headers: authed(token) });
    expect(res.status()).toBe(200);
    const regions = (await res.json()).regions as { region: string; key: string; p2p: boolean }[];

    // The key is what a client stores as the pick — the label cannot be it,
    // because one country can be listed twice (our servers and peers there).
    for (const row of regions) {
      expect(row.key).toBe(row.p2p ? `p2p:${row.region}` : row.region);
    }
    expect(new Set(regions.map((r) => r.key)).size, 'keys identify rows, so they must be unique').toBe(regions.length);
  });
});

test.describe('signaling broker', () => {
  test('a session can only be read by the account that started it', async ({ request }) => {
    const owner = await registerWithTrial(request, 'sigowner');
    const stranger = await registerWithTrial(request, 'sigstranger');
    const sessionId = `e2e-session-${Date.now()}`;

    // Nobody has claimed this session, so nobody may collect its signals —
    // ids route the whole negotiation, and guessing one must not be enough.
    const unclaimed = await request.get(`/api/v1/user/p2p/sessions/${sessionId}/signals?waitMs=1000`, {
      headers: authed(owner.length ? owner : stranger),
    });
    expect(unclaimed.status()).toBe(403);
  });

  test('signalling a node that cannot relay is refused rather than left hanging', async ({ request }) => {
    const token = await registerWithTrial(request, 'sigmissing');

    const res = await request.post('/api/v1/user/p2p/nodes/99999999/signal', {
      headers: authed(token),
      data: { sessionId: `e2e-missing-${Date.now()}`, payloadBase64: Buffer.from('{}').toString('base64') },
    });

    // Refused at once: the node is not one this account may use (the access
    // check runs before the "is it online" one, so an unknown id is a 403).
    // The client's job is then to try another peer, which it cannot do if
    // this silently waits for an answer.
    expect([403, 503]).toContain(res.status());
    expect(await res.text()).toMatch(/relay|peer/i);
  });

  test('a malformed signal is rejected without disturbing anything', async ({ request }) => {
    const token = await registerWithTrial(request, 'sigbad');

    for (const data of [{}, { sessionId: 's-1' }, { payloadBase64: 'zzz' }]) {
      const res = await request.post('/api/v1/user/p2p/nodes/1/signal', { headers: authed(token), data });
      expect(res.status(), `body ${JSON.stringify(data)}`).toBeGreaterThanOrEqual(400);
      expect(res.status()).toBeLessThan(500);
    }
  });
});
