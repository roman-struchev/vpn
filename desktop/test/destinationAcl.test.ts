import dns from 'node:dns/promises';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DestinationBlockedError, isBlockedIp, resolveAllowedTarget } from '../src/main/p2p/destinationAcl';

// §8.8 (docs/research/P2P_RELAY_FEASIBILITY.md, added by the repo owner
// directly): a relay peer must refuse to open an outbound connection into
// private/loopback/link-local ranges, or a malicious connecting client could
// use it as an SSRF pivot into the relay operator's own home/office LAN.
describe('isBlockedIp', () => {
  it.each([
    ['10.0.0.1', true],
    ['10.255.255.255', true],
    ['172.16.0.1', true],
    ['172.31.255.255', true],
    ['172.32.0.1', false], // just outside 172.16.0.0/12
    ['192.168.0.1', true],
    ['192.168.255.255', true],
    ['127.0.0.1', true],
    ['127.255.255.255', true],
    ['169.254.1.1', true],
    ['0.0.0.0', true],
    ['0.1.2.3', true],
    ['8.8.8.8', false],
    ['1.1.1.1', false],
    ['203.0.113.7', false], // a plausible real VPS node IP
  ])('IPv4 %s -> blocked=%s', (ip, expected) => {
    expect(isBlockedIp(ip)).toBe(expected);
  });

  it.each([
    ['::1', true],
    ['fe80::1', true],
    ['fc00::1', true],
    ['fd12:3456:789a::1', true], // fc00::/7 covers both fc.. and fd..
    ['2001:4860:4860::8888', false], // Google public DNS
    ['2606:4700:4700::1111', false], // Cloudflare public DNS
  ])('IPv6 %s -> blocked=%s', (ip, expected) => {
    expect(isBlockedIp(ip)).toBe(expected);
  });

  it('refuses anything that is not a parseable IP at all, rather than guessing', () => {
    expect(isBlockedIp('not-an-ip')).toBe(true);
  });
});

describe('resolveAllowedTarget', () => {
  afterEach(() => vi.restoreAllMocks());

  it('allows a bare public IP literal without any DNS lookup', async () => {
    const spy = vi.spyOn(dns, 'lookup');
    await expect(resolveAllowedTarget('203.0.113.7')).resolves.toBe('203.0.113.7');
    expect(spy).not.toHaveBeenCalled();
  });

  it('rejects a bare private IP literal', async () => {
    await expect(resolveAllowedTarget('192.168.1.1')).rejects.toThrow(DestinationBlockedError);
  });

  it('rejects a bare loopback IP literal', async () => {
    await expect(resolveAllowedTarget('127.0.0.1')).rejects.toThrow(DestinationBlockedError);
  });

  it('resolves a hostname and allows it when every address is public', async () => {
    vi.spyOn(dns, 'lookup').mockResolvedValue([{ address: '203.0.113.7', family: 4 }] as never);
    await expect(resolveAllowedTarget('node.example.com')).resolves.toBe('203.0.113.7');
  });

  it('rejects a hostname whose only resolved address is private (the SSRF case §8.8 exists for)', async () => {
    vi.spyOn(dns, 'lookup').mockResolvedValue([{ address: '192.168.1.1', family: 4 }] as never);
    await expect(resolveAllowedTarget('attacker-controlled.example.com')).rejects.toThrow(DestinationBlockedError);
  });

  it('picks a safe address when a hostname resolves to a mix of public and private addresses', async () => {
    vi.spyOn(dns, 'lookup').mockResolvedValue([
      { address: '10.0.0.5', family: 4 },
      { address: '203.0.113.7', family: 4 },
    ] as never);
    // Whichever address is returned, it must never be the private one — the
    // caller connects to exactly this returned IP, never re-resolving.
    await expect(resolveAllowedTarget('mixed.example.com')).resolves.toBe('203.0.113.7');
  });

  it('rejects when DNS returns no records at all', async () => {
    vi.spyOn(dns, 'lookup').mockResolvedValue([] as never);
    await expect(resolveAllowedTarget('nowhere.example.com')).rejects.toThrow();
  });
});
