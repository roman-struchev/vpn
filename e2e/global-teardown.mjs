import { execFileSync } from 'node:child_process';

// Every e2e test registers a fresh account (uniqueEmail() in full-user-flow.spec.ts,
// admin.spec.ts) so runs don't collide with each other or with leftover state —
// but that means the local Postgres accumulates one throwaway user per test run
// forever if nobody cleans up. All user-referencing tables (devices, subscriptions,
// crypto_invoices, balance_entries, device_node_keys via devices) are ON DELETE
// CASCADE (see server/src/main/resources/db/migration/V1__initial_schema.sql), so
// deleting the user rows is enough.
export default async function globalTeardown() {
  const container = process.env.E2E_POSTGRES_CONTAINER || 'vpn-postgres';
  const db = process.env.POSTGRES_DB || 'vpn_db';
  const user = process.env.POSTGRES_USER || 'vpn_user';

  try {
    const output = execFileSync(
      'docker',
      ['exec', container, 'psql', '-U', user, '-d', db, '-t', '-c',
        "DELETE FROM users WHERE email LIKE 'e2e-%@example.com' RETURNING id;"],
      { encoding: 'utf-8' }
    );
    const deleted = output.split('\n').map((l) => l.trim()).filter(Boolean).length;
    console.log(`[global-teardown] removed ${deleted} e2e test user(s) from ${db}`);
  } catch (e) {
    // Best-effort — don't fail the whole suite over cleanup (e.g. Postgres isn't
    // running in a Docker container in some environments).
    console.warn('[global-teardown] could not clean up e2e test users:', e.message);
  }

  // deviceTrial.spec.ts exercises the no-signup device-trial login
  // (DeviceAuthService), which auto-creates its own account keyed by a
  // random device UUID with a synthetic `device_<uuid>@device.local` email —
  // a different pattern from the `e2e-...@example.com` convention above, so
  // it needs its own cleanup query or it would accumulate one orphaned row
  // per test run forever (same cascade-delete reasoning as above).
  try {
    const output = execFileSync(
      'docker',
      ['exec', container, 'psql', '-U', user, '-d', db, '-t', '-c',
        "DELETE FROM users WHERE email LIKE 'device_%@device.local' RETURNING id;"],
      { encoding: 'utf-8' }
    );
    const deleted = output.split('\n').map((l) => l.trim()).filter(Boolean).length;
    console.log(`[global-teardown] removed ${deleted} device-trial test user(s) from ${db}`);
  } catch (e) {
    console.warn('[global-teardown] could not clean up device-trial test users:', e.message);
  }

  // Same problem, a different table: nodes.spec.ts and tunnel.spec.ts each
  // register 1-2 real nodes per run (see agentHelpers.ts's `e2e-<label>-...`
  // hostname convention) with nothing to ever delete them. Left alone, the
  // admin nodes table's pagination (NodesSection.tsx, 15 rows/page) eventually
  // pushes a freshly-registered node off the first page — confirmed live: 27
  // accumulated test nodes was enough to make nodes.spec.ts's own
  // `getByText(hostname)` check fail, since that node no longer rendered on
  // page 1. All FKs from nodes (node_credentials, device_node_keys) are ON
  // DELETE CASCADE / SET NULL (V1__initial_schema.sql), so this is safe.
  try {
    const output = execFileSync(
      'docker',
      ['exec', container, 'psql', '-U', user, '-d', db, '-t', '-c',
        "DELETE FROM nodes WHERE hostname LIKE 'e2e-%' RETURNING id;"],
      { encoding: 'utf-8' }
    );
    const deleted = output.split('\n').map((l) => l.trim()).filter(Boolean).length;
    console.log(`[global-teardown] removed ${deleted} e2e test node(s) from ${db}`);
  } catch (e) {
    console.warn('[global-teardown] could not clean up e2e test nodes:', e.message);
  }

  try {
    const output = execFileSync(
      'docker',
      ['exec', container, 'psql', '-U', user, '-d', db, '-t', '-c',
        "DELETE FROM promo_codes WHERE code LIKE 'E2E%' RETURNING id;"],
      { encoding: 'utf-8' }
    );
    const deleted = output.split('\n').map((l) => l.trim()).filter(Boolean).length;
    console.log(`[global-teardown] removed ${deleted} e2e test promo code(s) from ${db}`);
  } catch (e) {
    console.warn('[global-teardown] could not clean up e2e test promo codes:', e.message);
  }
}

