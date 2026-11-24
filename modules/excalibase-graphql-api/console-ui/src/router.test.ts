import { describe, expect, it } from 'vitest';
import { hrefOf, parseRoute } from './router';

describe('parseRoute', () => {
  it('reads a page and what follows it', () => {
    expect(parseRoute('#/schema/public.orders')).toEqual({ page: 'schema', arg: 'public.orders' });
    expect(parseRoute('#/permissions/%40user')).toEqual({ page: 'permissions', arg: '@user' });
  });

  it('sends an empty or unknown address to the first page, never a blank one', () => {
    expect(parseRoute('')).toEqual({ page: 'data', arg: '' });
    expect(parseRoute('#/nowhere')).toEqual({ page: 'data', arg: '' });
  });

  it('survives a broken escape', () => {
    expect(parseRoute('#/schema/%E0%A4%A')).toEqual({ page: 'schema', arg: '' });
  });
});

describe('hrefOf', () => {
  it('escapes the part after the page', () => {
    expect(hrefOf('schema', 'public.a b')).toBe('#/schema/public.a%20b');
    expect(hrefOf('replicas')).toBe('#/replicas');
  });
});
