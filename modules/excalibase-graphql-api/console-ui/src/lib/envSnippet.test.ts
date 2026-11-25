import { describe, expect, it } from 'vitest';
import type { AuthSettings } from '../api';
import { emptyForm, envText, formFrom, k8sText, SECRET_PLACEHOLDER } from './envSnippet';

const ENGINE: AuthSettings = {
  source: 'engine',
  issuer: 'https://idp.example.com/',
  algorithms: ['HS256'],
  audience: ['my-api', 'other'],
  clockSkew: 30,
  claims: { role: 'role', defaultRole: '', userId: 'sub', extra: { org: 'app.org' } },
  secret: 'set',
  keysLoaded: true,
  documentJwt: 'absent',
  warnings: [],
};

describe('formFrom', () => {
  it('starts from what the engine runs with', () => {
    expect(formFrom(ENGINE)).toMatchObject({ keys: 'secret', issuer: 'https://idp.example.com/', audience: 'my-api, other', algorithms: 'HS256', userId: 'sub', extra: { org: 'app.org' } });
  });

  it('an engine with a key set and no secret is a provider', () => {
    expect(formFrom({ ...ENGINE, secret: 'unset', jwksUrl: 'https://idp.example.com/jwks.json' }).keys).toBe('provider');
  });

  it('nothing known is an empty form', () => {
    expect(formFrom(null)).toEqual(emptyForm());
  });
});

describe('envText', () => {
  it('writes only what is filled in, a secret placeholder for a shared secret, and the lists without spaces', () => {
    const text = envText(formFrom(ENGINE));

    expect(text.split('\n')).toEqual([
      `APP_AUTH_SECRET="${SECRET_PLACEHOLDER}"`,
      'APP_AUTH_ALGORITHMS=HS256',
      'APP_AUTH_AUDIENCE=my-api,other',
      'APP_AUTH_CLAIMS_ROLE=role',
      'APP_AUTH_CLAIMS_USER_ID=sub',
      'APP_AUTH_CLAIMS_EXTRA=org=app.org',
    ]);
  });

  it('a provider writes the issuer and the key set, and no secret line', () => {
    const text = envText({ ...emptyForm(), keys: 'provider', issuer: 'https://idp/', jwksUrl: 'https://idp/jwks', audience: 'a' });

    expect(text).toContain('APP_AUTH_ISSUER=https://idp/');
    expect(text).toContain('APP_AUTH_JWKS_URL=https://idp/jwks');
    expect(text).not.toContain('APP_AUTH_SECRET');
  });

  it('a value that needs quoting is quoted', () => {
    expect(envText({ ...emptyForm(), keys: 'provider', issuer: 'https://idp/ x' })).toContain('APP_AUTH_ISSUER="https://idp/ x"');
  });
});

describe('k8sText', () => {
  it('takes the secret from the Secret and never writes a value for it', () => {
    const text = k8sText(formFrom(ENGINE));

    expect(text).toContain('- name: APP_AUTH_SECRET');
    expect(text).toContain('secretKeyRef');
    expect(text).not.toContain(SECRET_PLACEHOLDER);
    expect(text).toContain('- {name: APP_AUTH_AUDIENCE, value: "my-api,other"}');
  });
});
