import { describe, expect, it } from 'vitest';
import { formatRegion } from '../src/main/geoLocale';

describe('formatRegion', () => {
  it('expands an ipinfo.io ISO country code to the same English name ip-api.com/install-node.sh use', () => {
    expect(formatRegion('ME', 'Podgorica')).toBe('Montenegro, Podgorica');
    expect(formatRegion('de', null)).toBe('Germany');
    expect(formatRegion('TR', 'Istanbul')).toBe('Turkey, Istanbul');
  });

  it('keeps full country names as-is and falls back to default without a country', () => {
    expect(formatRegion('Netherlands', 'Amsterdam')).toBe('Netherlands, Amsterdam');
    expect(formatRegion(null, 'Berlin')).toBe('default');
  });
});
