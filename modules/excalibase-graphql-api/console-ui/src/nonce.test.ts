import { afterEach, describe, expect, test } from 'vitest';
import { readNonce } from './nonce';

afterEach(() => {
  document.head.innerHTML = '';
});

describe('readNonce', () => {
  test('is empty when the page has no nonce element', () => {
    expect(readNonce()).toBe('');
  });

  test('reads the nonce property of the style element the engine added', () => {
    const style = document.createElement('style');
    style.nonce = 'abc123';
    document.head.append(style);

    expect(readNonce()).toBe('abc123');
  });

  test('never reads a meta tag', () => {
    const meta = document.createElement('meta');
    meta.name = 'nonce';
    meta.content = 'from-meta';
    document.head.append(meta);

    expect(readNonce()).toBe('');
  });
});
