import dns from 'dns';
import net from 'net';
import { logger } from '../utils/logger.js';

// Mandatory destination-ACL for a p2p relay session (docs/research/
// P2P_RELAY_FEASIBILITY.md §8.8, added by the repo owner directly — treat as
// a hard requirement, not a nice-to-have). This relay agent runs on someone's
// own infrastructure (a manually-deployed Linux box, or — in later phases —
// a user's own desktop/phone); without this check, a malicious or
// compromised connecting client could instruct it to open a TCP connection
// to 192.168.1.1, 127.0.0.1, or any other address on the OPERATOR's own
// private network, using this relay as an SSRF pivot into infrastructure the
// operator never intended to expose. This mirrors config-builder.ts's
// `{ ip: ['geoip:private'], outboundTag: 'block' }` xray-core routing rule —
// same intent, reimplemented here because this relay path never runs
// Xray-core at all (see relay-session.ts's header comment).
const blockList = new net.BlockList();
blockList.addSubnet('10.0.0.0', 8, 'ipv4');
blockList.addSubnet('172.16.0.0', 12, 'ipv4');
blockList.addSubnet('192.168.0.0', 16, 'ipv4');
blockList.addSubnet('127.0.0.0', 8, 'ipv4');
blockList.addSubnet('169.254.0.0', 16, 'ipv4');
blockList.addSubnet('0.0.0.0', 8, 'ipv4');
blockList.addSubnet('::1', 128, 'ipv6');
blockList.addSubnet('fe80::', 10, 'ipv6');
blockList.addSubnet('fc00::', 7, 'ipv6');

export interface AclCheckResult {
  allowed: boolean;
  resolvedIp?: string;
  reason?: string;
}

// Injectable purely for tests (avoids exercising real DNS in unit tests) —
// production callers always use the default (real dns.promises.lookup).
export type LookupFn = (host: string) => Promise<{ address: string; family: number }[]>;

const defaultLookup: LookupFn = (host) => dns.promises.lookup(host, { all: true });

/**
 * Resolves `host` and checks EVERY resolved address against the private/
 * loopback/link-local block list, returning the single IP a caller should
 * actually connect to (the first allowed one) only if none of the resolved
 * addresses were blocked. Checking the resolved IP — and connecting to that
 * exact IP, never re-resolving the hostname a second time at connect time —
 * is what closes the DNS-rebinding bypass: an attacker's hostname could
 * otherwise resolve to a public IP at check-time and a private one moments
 * later at connect-time if the two steps re-resolved independently.
 */
export async function checkDestination(host: string, lookup: LookupFn = defaultLookup): Promise<AclCheckResult> {
  // A caller-supplied literal IP (not a hostname) skips DNS entirely — dns.lookup
  // happily accepts one and returns it as-is, but being explicit here makes the
  // "no rebinding possible" invariant obvious without reading dns.lookup's docs.
  if (net.isIP(host)) {
    return checkedResult(host, net.isIP(host) === 6 ? 'ipv6' : 'ipv4');
  }

  let addresses: { address: string; family: number }[];
  try {
    addresses = await lookup(host);
  } catch (err) {
    logger.warn(`P2P dest ACL: DNS lookup failed for "${host}": ${(err as Error).message}`);
    return { allowed: false, reason: 'dns_lookup_failed' };
  }

  if (addresses.length === 0) {
    return { allowed: false, reason: 'no_addresses' };
  }

  for (const { address, family } of addresses) {
    const result = checkedResult(address, family === 6 ? 'ipv6' : 'ipv4');
    if (!result.allowed) {
      logger.warn(`P2P dest ACL: rejecting "${host}" — resolved address ${address} is private/loopback/link-local`);
      return result;
    }
  }

  // All resolved addresses are public — use the first one, and only ever
  // connect to this exact IP (never re-resolve host at connect time).
  return { allowed: true, resolvedIp: addresses[0].address };
}

function checkedResult(ip: string, family: 'ipv4' | 'ipv6'): AclCheckResult {
  if (blockList.check(ip, family)) {
    return { allowed: false, reason: 'private_or_loopback_address', resolvedIp: ip };
  }
  return { allowed: true, resolvedIp: ip };
}
