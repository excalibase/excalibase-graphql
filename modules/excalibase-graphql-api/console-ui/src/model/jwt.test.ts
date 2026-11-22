import { describe, expect, test } from 'vitest';
import { buildSection, claimPaths, decodeToken, extraName, formFrom } from './jwt';

const part = (value: unknown) => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value)))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const token = (claims: unknown) => `${part({ alg: 'HS256' })}.${part(claims)}.signature`;

describe('the sample token', () => {
  test('claims are read from the middle part, with unicode intact', () => {
    expect(decodeToken(token({ sub: 'u-1', name: 'Zoë' }))).toEqual({ sub: 'u-1', name: 'Zoë' });
  });

  test('a token without three parts, or with unreadable claims, says so', () => {
    expect(() => decodeToken('abc')).toThrow('three parts');
    expect(() => decodeToken('a.%%%.c')).toThrow('not readable claims');
    expect(() => decodeToken(token([1, 2]))).toThrow('not readable claims');
  });

  test('nested claims become dotted paths and registered claims come last', () => {
    const paths = claimPaths({ exp: 1, app: { org: 'acme' }, role: 'user' }).map((entry) => entry.path);

    expect(paths).toEqual(['app.org', 'role', 'exp']);
  });

  test('an extra-claim header name is the last path part, lowercased and made safe', () => {
    expect(extraName('app.Org Name')).toBe('org-name');
  });

  test('the section holds only what was filled in; two audiences become a list', () => {
    const form = { ...formFrom(null), role: ' role ', audience: 'a, b', extra: { org: 'app.org' } };

    expect(buildSection(form)).toEqual({ audience: ['a', 'b'], claims: { role: 'role', extra: { org: 'app.org' } } });
    expect(buildSection(formFrom(null))).toEqual({});
  });

  test('a saved section fills the form back in', () => {
    const form = formFrom({ issuer: 'i', audience: ['a', 'b'], claims: { userId: 'sub', extra: { x: 'p.x' } } });

    expect(form).toMatchObject({ issuer: 'i', audience: 'a, b', userId: 'sub', extra: { x: 'p.x' } });
  });
});
