import { describe, expect, it } from 'vitest';
import { parseVlessUri, regionLabel } from '../src/shared/vlessUri';

describe('regionLabel', () => {
  it('drops the node hostname the server appends after the separator', () => {
    // Real link shape from SubscriptionExportService#buildVlessUrl.
    const link =
      'vless://d3434d41-81de-4494-ad46-af99fada2968@37.27.250.158:443?encryption=none&security=reality&type=xhttp&sni=dl.google.com&pbk=k&sid=s#Finland%2C%20Helsinki%20%C2%B7%20centos-4gb-hel1-2';
    expect(regionLabel(parseVlessUri(link).remark)).toBe('Finland, Helsinki');
  });

  it('keeps a remark that carries no hostname (and hostnames with dashes stay intact)', () => {
    expect(regionLabel('India, Mumbai')).toBe('India, Mumbai');
    expect(regionLabel('Finland, Helsinki · centos-4gb-hel1-2')).toBe('Finland, Helsinki');
  });
});
