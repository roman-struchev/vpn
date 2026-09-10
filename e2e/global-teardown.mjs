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
}
