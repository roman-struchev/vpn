import { describe, it, expect } from 'vitest';
import { checkDestination } from './dest-acl.js';

// Exhaustive coverage of docs/research/P2P_RELAY_FEASIBILITY.md §8.8's
// mandatory destination-ACL — this is the ONE thing standing between a
// malicious connecting client and using this relay agent as an SSRF pivot
// into whatever network it's actually deployed on, so every blocked range
// (both IPv4 and IPv6) gets its own case rather than a couple of spot checks.
describe('checkDestination', () => {
  const stubLookup = (address: string, family: number) => async () => [{ address, family }];

  it.each([
    ['10.0.0.1', 4],
    ['10.255.255.255', 4],
    ['172.16.0.1', 4],
    ['172.31.255.255', 4],
    ['192.168.1.1', 4],
    ['127.0.0.1', 4],
    ['127.255.255.255', 4],
    ['169.254.1.1', 4],
    ['0.0.0.1', 4],
  ])('rejects private/loopback/link-local IPv4 address %s', async (address, family) => {
    const result = await checkDestination('evil.example', stubLookup(address, family));
    expect(result.allowed).toBe(false);
  });

  it.each([
    ['::1', 6],
    ['fe80::1', 6],
    ['fc00::1', 6],
    ['fd12:3456:789a::1', 6],
  ])('rejects loopback/link-local/unique-local IPv6 address %s', async (address, family) => {
    const result = await checkDestination('evil.example', stubLookup(address, family));
    expect(result.allowed).toBe(false);
  });

  it.each([
    ['8.8.8.8', 4],
    ['1.1.1.1', 4],
    ['203.0.113.7', 4], // TEST-NET-3, not in our block list — treated as public
    ['2606:4700:4700::1111', 6],
  ])('allows a normal public address %s', async (address, family) => {
    const result = await checkDestination('example.com', stubLookup(address, family));
    expect(result.allowed).toBe(true);
    expect(result.resolvedIp).toBe(address);
  });

  it('rejects a hostname that resolves to a private IP (not just a literal private IP)', async () => {
    const result = await checkDestination('internal.attacker.example', stubLookup('192.168.1.1', 4));
    expect(result.allowed).toBe(false);
    expect(result.reason).toBe('private_or_loopback_address');
  });

  it('rejects when ANY of several resolved addresses is private, not just the first', async () => {
    const lookup = async () => [
      { address: '8.8.8.8', family: 4 },
      { address: '192.168.1.1', family: 4 },
    ];
    const result = await checkDestination('multi-answer.example', lookup);
    expect(result.allowed).toBe(false);
  });

  it('checks a literal IP argument directly without going through DNS at all', async () => {
    let lookupCalled = false;
    const lookup = async () => {
      lookupCalled = true;
      return [{ address: '8.8.8.8', family: 4 }];
    };
    const result = await checkDestination('192.168.1.1', lookup);
    expect(result.allowed).toBe(false);
    expect(lookupCalled).toBe(false);
  });

  it('fails closed (rejects) when DNS resolution itself fails', async () => {
    const lookup = async () => {
      throw new Error('ENOTFOUND');
    };
    const result = await checkDestination('nonexistent.example', lookup);
    expect(result.allowed).toBe(false);
    expect(result.reason).toBe('dns_lookup_failed');
  });

  it('rejects when DNS resolves to zero addresses', async () => {
    const result = await checkDestination('empty.example', async () => []);
    expect(result.allowed).toBe(false);
    expect(result.reason).toBe('no_addresses');
  });
});
