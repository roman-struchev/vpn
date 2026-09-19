import { describe, expect, it } from 'vitest';
import { firstLinkForRegion, parseVlessUri, vlessParam } from '../src/shared/vlessUri';

// Byte-for-byte what SubscriptionExportService#buildVlessUrl produces on the server.
const SAMPLE =
  'vless://d290f1ee-6c54-4b01-90e6-d701748f0851@203.0.113.10:443' +
  '?encryption=none&security=reality&type=xhttp&path=%2Fvless-xhttp' +
  '&sni=dl.google.com&pbk=abcDEF123&sid=0123456789abcdef' +
  '#eu-west-node1.example.com';

describe('parseVlessUri', () => {
  it('parses all fields from the server-generated link', () => {
    const uri = parseVlessUri(SAMPLE);
    expect(uri.uuid).toBe('d290f1ee-6c54-4b01-90e6-d701748f0851');
    expect(uri.host).toBe('203.0.113.10');
    expect(uri.port).toBe(443);
    expect(uri.params.encryption).toBe('none');
    expect(uri.params.security).toBe('reality');
    expect(uri.params.type).toBe('xhttp');
    expect(uri.params.path).toBe('/vless-xhttp');
    expect(uri.params.sni).toBe('dl.google.com');
    expect(uri.params.pbk).toBe('abcDEF123');
    expect(uri.params.sid).toBe('0123456789abcdef');
    expect(uri.remark).toBe('eu-west-node1.example.com');
  });

  it('falls back to the provided default for a missing param', () => {
    const uri = parseVlessUri(SAMPLE);
    expect(vlessParam(uri, 'doesNotExist', 'fallback')).toBe('fallback');
  });

  it('rejects non-vless links', () => {
    expect(() => parseVlessUri('https://example.com')).toThrow();
  });

  it('rejects a link without a uuid or host', () => {
    expect(() => parseVlessUri('vless://@:443?foo=bar')).toThrow();
  });
});

describe('firstLinkForRegion', () => {
  const link = (host: string, remark: string) =>
    `vless://d3434d41-81de-4494-ad46-af99fada2968@${host}:443` +
    '?encryption=none&security=reality&type=xhttp&sni=dl.google.com&pbk=k&sid=s#' +
    encodeURIComponent(remark);

  it('picks a node that is actually in the requested region', () => {
    const found = firstLinkForRegion(
      [link('203.0.113.10', 'Germany, Berlin · node-a'), link('203.0.113.11', 'Finland, Helsinki · node-b')],
      'Finland, Helsinki'
    );
    expect(found?.host).toBe('203.0.113.11');
  });

  it('reports nothing when the server fell back to another region', () => {
    // requestedRegionAvailable: false — calling that node's latency "Finland"
    // would be a plain lie, so there is simply no number.
    expect(firstLinkForRegion([link('203.0.113.10', 'Germany, Berlin · node-a')], 'Finland, Helsinki')).toBeNull();
  });

  it('skips a malformed link instead of losing the measurement', () => {
    const found = firstLinkForRegion(
      ['not-a-vless-link', link('203.0.113.11', 'Finland, Helsinki · node-b')],
      'Finland, Helsinki'
    );
    expect(found?.host).toBe('203.0.113.11');
  });

  it('survives an empty link list', () => {
    expect(firstLinkForRegion([], 'Finland, Helsinki')).toBeNull();
  });
});
