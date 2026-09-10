import { describe, expect, it } from 'vitest';
import { parseVlessUri, vlessParam } from '../src/shared/vlessUri';

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
