import dns from 'node:dns';
import net from 'node:net';
import { Agent, setGlobalDispatcher } from 'undici';

/**
 * Phase 10 hardening ("DoH-резолвинг"): makes Node's global fetch (used by
 * ApiClient, the auto-updater, etc.) resolve hostnames over DNS-over-HTTPS
 * instead of the OS/ISP resolver, so a poisoned DNS response can't silently
 * redirect the app. Uses Cloudflare's simple JSON DoH API (not the binary
 * RFC 8484 wire format) — no DNS-packet parsing needed, and it's queried by
 * literal IP (1.1.1.1) so resolving it doesn't recurse back into this same
 * lookup. Falls back to the system resolver if the DoH query itself fails,
 * so a Cloudflare outage degrades rather than breaks connectivity.
 */
export function installDohDispatcher(): void {
  const agent = new Agent({
    connect: { lookup: dohLookup },
  });
  setGlobalDispatcher(agent);
}

type LookupCallback = (
  err: NodeJS.ErrnoException | null,
  address: string | dns.LookupAddress[],
  family?: number
) => void;

function dohLookup(hostname: string, options: dns.LookupOptions, callback: LookupCallback): void {
  // Direct system lookup for localhost and literal IP addresses
  if (hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '::1' || net.isIP(hostname)) {
    dns.lookup(hostname, options as never, callback as never);
    return;
  }
  queryDoh(hostname)
    .then((addresses) => {
      if (!addresses.length) throw new Error('empty DoH answer');
      if (options.all) {
        callback(
          null,
          addresses.map((address) => ({ address, family: 4 }))
        );
      } else {
        callback(null, addresses[0], 4);
      }
    })
    .catch(() => {
      // DoH failed (Cloudflare down, no network yet, ...) — degrade to the
      // system resolver rather than breaking every request.
      dns.lookup(hostname, options as never, callback as never);
    });
}

async function queryDoh(hostname: string): Promise<string[]> {
  const url = `https://1.1.1.1/dns-query?name=${encodeURIComponent(hostname)}&type=A`;
  const response = await fetch(url, { headers: { accept: 'application/dns-json' } });
  if (!response.ok) {
    throw new Error(`DoH query failed: ${response.status}`);
  }
  const data = (await response.json()) as { Answer?: { type: number; data: string }[] };
  return (data.Answer ?? []).filter((a) => a.type === 1).map((a) => a.data);
}
