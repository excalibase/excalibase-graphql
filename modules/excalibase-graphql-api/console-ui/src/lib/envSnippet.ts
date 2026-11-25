import type { AuthSettings } from '../api';

/** The settings a person can choose to write as engine variables. The secret is never in it. */
export interface EnvForm {
  /** A shared secret (HS256), or an identity provider with an issuer. */
  keys: 'secret' | 'provider';
  issuer: string;
  jwksUrl: string;
  audience: string;
  algorithms: string;
  role: string;
  defaultRole: string;
  userId: string;
  extra: Record<string, string>;
}

/** What stands where the secret goes. The page cannot know it, and never asks for it. */
export const SECRET_PLACEHOLDER = '<a secret of 32 bytes or more>';
const SECRET_NAME = 'jwt-secret';

export function emptyForm(): EnvForm {
  return { keys: 'secret', issuer: '', jwksUrl: '', audience: '', algorithms: '', role: '', defaultRole: '', userId: '', extra: {} };
}

/** The form that says what the engine runs with now, so a move starts from the values in use. */
export function formFrom(settings: AuthSettings | null): EnvForm {
  if (!settings) {
    return emptyForm();
  }
  return {
    keys: settings.secret === 'set' || (!settings.jwksUrl && !settings.issuer) ? 'secret' : 'provider',
    issuer: settings.issuer ?? '',
    jwksUrl: settings.jwksUrl ?? '',
    audience: settings.audience.join(', '),
    algorithms: settings.algorithms.join(', '),
    role: settings.claims?.role ?? '',
    defaultRole: settings.claims?.defaultRole ?? '',
    userId: settings.claims?.userId ?? '',
    extra: { ...settings.claims?.extra },
  };
}

const list = (text: string): string => text.split(',').map((item) => item.trim()).filter(Boolean).join(',');

/** The variables, in the order the README lists them; a blank setting is left out. */
export function envPairs(form: EnvForm): Array<[string, string]> {
  const extra = Object.entries(form.extra).map(([name, path]) => `${name}=${path}`).join(',');
  const pairs: Array<[string, string]> = [
    ['APP_AUTH_ISSUER', form.keys === 'provider' ? form.issuer.trim() : ''],
    ['APP_AUTH_JWKS_URL', form.keys === 'provider' ? form.jwksUrl.trim() : ''],
    ['APP_AUTH_SECRET', form.keys === 'secret' ? SECRET_PLACEHOLDER : ''],
    ['APP_AUTH_ALGORITHMS', list(form.algorithms)],
    ['APP_AUTH_AUDIENCE', list(form.audience)],
    ['APP_AUTH_CLAIMS_ROLE', form.role.trim()],
    ['APP_AUTH_CLAIMS_DEFAULT_ROLE', form.defaultRole.trim()],
    ['APP_AUTH_CLAIMS_USER_ID', form.userId.trim()],
    ['APP_AUTH_CLAIMS_EXTRA', extra],
  ];
  return pairs.filter(([, value]) => value !== '');
}

const quoted = (value: string): string => (/^[A-Za-z0-9_./:@,=-]+$/.test(value) ? value : JSON.stringify(value));

/** One line per variable, for a compose file or a shell. The secret line holds a placeholder to replace. */
export function envText(form: EnvForm): string {
  return envPairs(form).map(([name, value]) => `${name}=${quoted(value)}`).join('\n');
}

/** The same as a Kubernetes `env:` block; the secret comes from the Secret, never from the manifest. */
export function k8sText(form: EnvForm): string {
  const lines = envPairs(form).map(([name, value]) =>
    name === 'APP_AUTH_SECRET'
      ? `- name: ${name}\n  valueFrom: {secretKeyRef: {name: excalibase-engine, key: ${SECRET_NAME}}}`
      : `- {name: ${name}, value: ${JSON.stringify(value)}}`,
  );
  return ['env:', ...lines.map((line) => line.split('\n').map((part) => `  ${part}`).join('\n'))].join('\n');
}
