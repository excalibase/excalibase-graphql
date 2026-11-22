import { describe, expect, test } from 'vitest';
import { hashFor, parseHash } from './route';

describe('hash routes', () => {
  test.each([
    ['', { page: 'data', sub: null }],
    ['#/', { page: 'data', sub: null }],
    ['#/data', { page: 'data', sub: null }],
    ['#/data/public.orders', { page: 'data', sub: 'public.orders' }],
    ['#/schema', { page: 'schema', sub: null }],
    ['#/permissions', { page: 'permissions', sub: 'document' }],
    ['#/permissions/grid', { page: 'permissions', sub: 'grid' }],
    ['#/explorer', { page: 'explorer', sub: null }],
    ['#/replicas', { page: 'replicas', sub: null }],
  ])('%s is read as %j', (hash, route) => {
    expect(parseHash(hash)).toEqual(route);
  });

  test('anything unknown lands on the first page, never on an error', () => {
    expect(parseHash('#/nope')).toEqual({ page: 'data', sub: null });
    expect(parseHash('#/permissions/nope')).toEqual({ page: 'permissions', sub: 'document' });
  });

  test('a table name that holds odd characters survives a round trip', () => {
    const route = { page: 'data' as const, sub: 'public.we ird/name' };

    expect(parseHash(hashFor(route))).toEqual(route);
  });
});
