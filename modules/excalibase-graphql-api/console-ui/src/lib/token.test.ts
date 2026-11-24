import { describe, expect, it } from 'vitest';
import { buildSection, claimPaths, decodeToken, extraName, formFrom } from './token';

const encode = (value: unknown): string => btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
const tokenOf = (claims: unknown): string => `${encode({ alg: 'HS256' })}.${encode(claims)}.signature`;

describe('decodeToken', () => {
  it('reads the claims without checking the signature', () => {
    expect(decodeToken(tokenOf({ sub: 'u1', role: 'user' }))).toEqual({ sub: 'u1', role: 'user' });
  });

  it('refuses a text that is not three parts', () => {
    expect(() => decodeToken('abc.def')).toThrow('three parts');
  });

  it('refuses a middle part that is not claims', () => {
    expect(() => decodeToken(`a.${encode([1, 2])}.c`)).toThrow('not readable claims');
    expect(() => decodeToken('a.%%%.c')).toThrow('not readable claims');
  });
});

describe('claimPaths', () => {
  it('lists nested claims as dotted paths and puts the registered ones last', () => {
    const paths = claimPaths({ iss: 'x', app: { role: 'admin' }, sub: 'u1' }).map((claim) => claim.path);
    expect(paths).toEqual(['app.role', 'sub', 'iss']);
  });
});

describe('extraName', () => {
  it('makes a header-safe name from the last part of the path', () => {
    expect(extraName('app.Org Id')).toBe('org-id');
    expect(extraName('x'.repeat(90)).length).toBe(63);
  });
});

describe('buildSection and formFrom', () => {
  it('keeps only what was filled in', () => {
    const section = buildSection({ ...formFrom(null), role: ' role ', audience: 'a, b', secret: 'env:JWT_SECRET', extra: { org: 'app.org' } });
    expect(section).toEqual({ secret: 'env:JWT_SECRET', audience: ['a', 'b'], claims: { role: 'role', extra: { org: 'app.org' } } });
  });

  it('a single audience stays a single value, and an empty form is an empty section', () => {
    expect(buildSection({ ...formFrom(null), audience: 'only' })).toEqual({ audience: 'only' });
    expect(buildSection(formFrom(null))).toEqual({});
  });

  it('fills the form from a saved section so it can be changed', () => {
    const form = formFrom({ issuer: 'https://id', audience: ['a', 'b'], claims: { userId: 'sub', extra: { org: 'o' } } });
    expect(form).toMatchObject({ issuer: 'https://id', audience: 'a, b', userId: 'sub', extra: { org: 'o' } });
  });
});
