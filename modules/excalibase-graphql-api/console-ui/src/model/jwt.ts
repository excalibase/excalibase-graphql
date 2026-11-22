// What the sample-token form needs, with no page and no network: reading a token's claims and building the jwt section.

const SEGMENTS = 3;
const SKIPPED = new Set(['iss', 'aud', 'exp', 'iat', 'nbf', 'jti']);
const EXTRA_NAME = /[^a-z0-9_-]+/g;
const NAME_MAX = 63;

export interface ClaimPath { path: string; preview: string }
export interface JwtForm {
  issuer: string; jwksUrl: string; secret: string; audience: string;
  role: string; defaultRole: string; userId: string; extra: Record<string, string>;
}
type Section = Record<string, unknown>;

function decodePart(part: string): unknown {
  const padded = part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '=');
  const bytes = Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes));
}

/** Reads a token's claims in the browser. The signature is not checked here, and the token is not kept anywhere. */
export function decodeToken(token: string): Record<string, unknown> {
  const parts = token.trim().split('.');
  if (parts.length !== SEGMENTS) {
    throw new Error('A token has three parts separated by dots.');
  }
  try {
    const claims = decodePart(parts[1] ?? '');
    if (claims === null || typeof claims !== 'object' || Array.isArray(claims)) {
      throw new Error('claims');
    }
    return claims as Record<string, unknown>;
  } catch {
    throw new Error('The middle part of that token is not readable claims.');
  }
}

/** Every claim as a dotted path with a short preview, the registered ones last so the useful ones come first. */
export function claimPaths(claims: Record<string, unknown>, prefix = ''): ClaimPath[] {
  const found: ClaimPath[] = [];
  for (const [name, value] of Object.entries(claims)) {
    const path = prefix + name;
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      found.push(...claimPaths(value as Record<string, unknown>, path + '.'));
    } else {
      found.push({ path, preview: JSON.stringify(value) });
    }
  }
  return found.sort((left, right) => Number(SKIPPED.has(left.path)) - Number(SKIPPED.has(right.path)));
}

export function extraName(path: string): string {
  const last = (path.split('.').pop() ?? '').toLowerCase().replace(EXTRA_NAME, '-').replace(/^[-_]+/, '');
  return last.slice(0, NAME_MAX);
}

const text = (value: unknown): string => (typeof value === 'string' ? value.trim() : '');

/** The jwt section from the form, with only what was filled in. Whatever the engine would refuse, it says. */
export function buildSection(form: JwtForm): Section {
  const claims: Section = {};
  for (const key of ['role', 'defaultRole', 'userId'] as const) {
    if (text(form[key]) !== '') {
      claims[key] = text(form[key]);
    }
  }
  if (Object.keys(form.extra).length > 0) {
    claims.extra = { ...form.extra };
  }
  const section: Section = {};
  for (const key of ['issuer', 'jwksUrl', 'secret'] as const) {
    if (text(form[key]) !== '') {
      section[key] = text(form[key]);
    }
  }
  const audiences = text(form.audience).split(',').map((audience) => audience.trim()).filter(Boolean);
  if (audiences.length > 0) {
    section.audience = audiences.length === 1 ? audiences[0] : audiences;
  }
  return Object.keys(claims).length > 0 ? { ...section, claims } : section;
}

/** The form a saved section fills in, so an existing setup can be changed rather than typed again. */
export function formFrom(section: unknown): JwtForm {
  const saved = (section ?? {}) as Section;
  const claims = (saved.claims ?? {}) as Section;
  const audience = saved.audience;
  const str = (value: unknown): string => (typeof value === 'string' ? value : '');
  return {
    issuer: str(saved.issuer), jwksUrl: str(saved.jwksUrl), secret: str(saved.secret),
    audience: Array.isArray(audience) ? audience.join(', ') : str(audience),
    role: str(claims.role), defaultRole: str(claims.defaultRole), userId: str(claims.userId),
    extra: { ...((claims.extra ?? {}) as Record<string, string>) },
  };
}
