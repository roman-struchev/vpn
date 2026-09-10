import { describe, expect, it } from 'vitest';
import { ApiHostRotation } from '../src/shared/apiHostRotation';

describe('ApiHostRotation', () => {
  it('starts on the first host', () => {
    const rotation = new ApiHostRotation(['https://a.example', 'https://b.example']);
    expect(rotation.current()).toBe('https://a.example');
  });

  it('advance moves to the next host', () => {
    const rotation = new ApiHostRotation(['https://a.example', 'https://b.example']);
    expect(rotation.advance()).toBe('https://b.example');
  });

  it('advance wraps around after the last host', () => {
    const rotation = new ApiHostRotation(['https://a.example', 'https://b.example']);
    rotation.advance();
    expect(rotation.advance()).toBe('https://a.example');
  });

  it('rejects an empty host list', () => {
    expect(() => new ApiHostRotation([])).toThrow();
  });
});
