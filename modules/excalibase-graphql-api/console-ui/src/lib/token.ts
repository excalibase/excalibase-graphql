import type { JwtSection } from './docmodel';

const SEGMENTS = 3;
const SKIPPED = new Set(['iss', 'aud', 'exp', 'iat', 'nbf', 'jti']);
const EXTRA_NAME = /[^a-z0-9_-]+/g;

export interface ClaimPath {
  path: string;
  preview: string;
}

export interface TokenForm {
  issuer: string;
  jwksUrl: string;
  secret: string;
  audience: string;
  role: string;
  defaultRole: string;
  userId: string;
  extra: Record<string, string>;
}

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
      found.push(...claimPaths(value as Record<string, unknown>, `${path}.`));
    } else {
      found.push({ path, preview: JSON.stringify(value) });
    }
  }
  return found.sort((a, b) => Number(SKIPPED.has(a.path)) - Number(SKIPPED.has(b.path)));
}

export function extraName(path: string): string {
  const last = (path.split('.').pop() ?? '').toLowerCase().replace(EXTRA_NAME, '-').replace(/^[-_]+/, '');
  return last.slice(0, 63);
}

const text = (value: unknown): string => (typeof value === 'string' ? value.trim() : '');

/** The token section of the document, with only what was filled in. Whatever the engine would refuse, it says. */
export function buildSection(form: TokenForm): JwtSection {
  const claims: NonNullable<JwtSection['claims']> = {};
  for (const key of ['role', 'defaultRole', 'userId'] as const) {
    if (text(form[key]) !== '') {
      claims[key] = text(form[key]);
    }
  }
  if (Object.keys(form.extra).length > 0) {
    claims.extra = { ...form.extra };
  }
  const section: JwtSection = {};
  for (const key of ['issuer', 'jwksUrl', 'secret'] as const) {
    if (text(form[key]) !== '') {
      section[key] = text(form[key]);
    }
  }
  const audiences = text(form.audience)
    .split(',')
    .map((audience) => audience.trim())
    .filter(Boolean);
  if (audiences.length > 0) {
    section.audience = audiences.length === 1 ? audiences[0] : audiences;
  }
  return Object.keys(claims).length > 0 ? { ...section, claims } : section;
}

/** The form a saved section fills in, so an existing setup can be changed rather than typed again. */
export function formFrom(section: JwtSection | null | undefined): TokenForm {
  const claims = section?.claims ?? {};
  const audience = section?.audience;
  return {
    issuer: section?.issuer ?? '',
    jwksUrl: section?.jwksUrl ?? '',
    secret: section?.secret ?? '',
    audience: Array.isArray(audience) ? audience.join(', ') : (audience ?? ''),
    role: claims.role ?? '',
    defaultRole: claims.defaultRole ?? '',
    userId: claims.userId ?? '',
    extra: { ...claims.extra },
  };
}
