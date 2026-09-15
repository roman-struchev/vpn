import dns from 'node:dns/promises';
import net from 'node:net';

/**
 * Mandatory destination-ACL for P2P relay mode (docs/research/
 * P2P_RELAY_FEASIBILITY.md §8.8, added by the repo owner directly). This
 * relay peer forwards raw bytes for a connecting client it has never met, to
 * whatever targetHost:targetPort that client's signal names — without this
 * check, a malicious/compromised client could point it at this user's own
 * router admin page, NAS, or any other device on their home/office LAN,
 * using this app as an SSRF pivot into a network it would otherwise have no
 * access to. Mirrors agent/src/xray/config-builder.ts's
 * `{ ip: ['geoip:private'], outboundTag: 'block' }` rule for regular nodes.
 */

// RFC1918 + loopback + link-local + unique-local ranges, both address
// families. 0.0.0.0/8 is included too — some stacks treat "0.x.x.x" as
// "this host" and route it locally, so it's just as dangerous a target as
// 127.0.0.0/8 for this purpose even though it's not one of the "private"
// ranges in the strict RFC1918 sense.
const BLOCKED_CIDRS = [
  '10.0.0.0/8',
  '172.16.0.0/12',
  '192.168.0.0/16',
  '127.0.0.0/8',
  '169.254.0.0/16',
  '0.0.0.0/8',
  '::1/128',
  'fe80::/10',
  'fc00::/7',
];

function ipToBigInt(ip: string, family: 4 | 6): bigint {
  if (family === 4) {
    return ip.split('.').reduce((acc, part) => (acc << 8n) + BigInt(part), 0n);
  }
  // Expand to 8 groups of 16 bits, handling "::" compression.
  const [head, tail] = ip.split('::');
  const headParts = head ? head.split(':') : [];
  const tailParts = tail ? tail.split(':') : [];
  const missing = 8 - headParts.length - tailParts.length;
  const groups = [...headParts, ...Array(Math.max(missing, 0)).fill('0'), ...tailParts];
  return groups.reduce((acc, part) => (acc << 16n) + BigInt(parseInt(part || '0', 16)), 0n);
}

function parseCidr(cidr: string): { base: bigint; bits: number; family: 4 | 6 } {
  const [addr, prefix] = cidr.split('/');
  const family = net.isIPv6(addr) ? 6 : 4;
  return { base: ipToBigInt(addr, family), bits: Number(prefix), family };
}

const PARSED_BLOCKED = BLOCKED_CIDRS.map(parseCidr);

/** True if `ip` (a bare, already-resolved address — no hostname) falls inside any blocked range. */
export function isBlockedIp(ip: string): boolean {
  const family = net.isIP(ip);
  if (family === 0) return true; // not a parseable IP at all — refuse rather than guess
  const value = ipToBigInt(ip, family as 4 | 6);
  return PARSED_BLOCKED.some((range) => {
    if (range.family !== family) return false;
    const totalBits = family === 4 ? 32 : 128;
    const shift = BigInt(totalBits - range.bits);
    return (value >> shift) === (range.base >> shift);
  });
}

export class DestinationBlockedError extends Error {
  constructor(host: string, ip: string) {
    super(`Destination ${host} (resolved to ${ip}) is in a blocked private/loopback/link-local range`);
    this.name = 'DestinationBlockedError';
  }
}

/**
 * Resolves `host` and returns ONE safe IP to actually connect to, throwing
 * DestinationBlockedError if none of the resolved addresses are safe.
 * Deliberately resolve-once: the caller must connect to the exact IP this
 * function returns, never re-resolve the hostname for the real connection —
 * re-resolving would let a DNS-rebinding attacker pass this check against a
 * public IP and then have the actual TCP connect() land on a private one.
 */
export async function resolveAllowedTarget(host: string): Promise<string> {
  // A bare IP literal (the overwhelmingly common case for a relay target,
  // which is normally one of our own VPS nodes' public IPs) skips DNS
  // entirely — dns.lookup() on a literal just parses it back out anyway.
  if (net.isIP(host) !== 0) {
    if (isBlockedIp(host)) throw new DestinationBlockedError(host, host);
    return host;
  }

  const results = await dns.lookup(host, { all: true, verbatim: true });
  if (results.length === 0) {
    throw new Error(`Could not resolve host: ${host}`);
  }
  const safe = results.find((r) => !isBlockedIp(r.address));
  if (!safe) {
    throw new DestinationBlockedError(host, results[0].address);
  }
  return safe.address;
}
