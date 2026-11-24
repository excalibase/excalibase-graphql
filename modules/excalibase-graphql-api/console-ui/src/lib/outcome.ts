import type { ApiError } from '../api';

export type Outcome =
  | { kind: 'saved'; version: string | number; historyMissed: boolean; warnings: Warning[] }
  | { kind: 'conflict'; currentVersion: string | number }
  | { kind: 'invalid'; errors: string[] }
  | { kind: 'readonly' }
  | { kind: 'missing' }
  | { kind: 'refused' }
  | { kind: 'unreachable' }
  | { kind: 'moved' }
  | { kind: 'failed'; code: string };

export interface Warning {
  message: string;
}

/** What a save came to, from the status the admin API answered with. Server text is shown as text, never as markup. */
export function classify(failure: ApiError): Outcome {
  const body = (failure.body ?? {}) as { currentVersion?: string | number; errors?: unknown };
  switch (failure.status) {
    case 409:
      return { kind: 'conflict', currentVersion: body.currentVersion ?? '' };
    case 422:
      return { kind: 'invalid', errors: Array.isArray(body.errors) ? body.errors.map(String) : [] };
    case 405:
      return { kind: 'readonly' };
    case 428:
      return { kind: 'missing' };
    case 500:
      return { kind: 'refused' };
    case 502:
      return { kind: 'unreachable' };
    default:
      return { kind: 'failed', code: failure.code };
  }
}

/** What a refused restore says, from the answer's status; the reasons of a 422 are the engine's own. */
export function restoreFailure(failure: ApiError): { text: string; errors?: string[] } {
  const body = (failure.body ?? {}) as { errors?: unknown };
  switch (failure.status) {
    case 409:
      return { text: 'Someone else saved first, so nothing was restored. Reload the page to see their version.' };
    case 422:
      return {
        text: "Not restored: this version no longer passes the engine's checks.",
        errors: Array.isArray(body.errors) ? body.errors.map(String) : [],
      };
    case 404:
      return { text: 'That version is no longer in the history.' };
    case 405:
      return { text: 'This source is read-only. Nothing was restored.' };
    default:
      return { text: `The restore failed (${failure.code}). The last good document is still served.` };
  }
}

const MESSAGES: Record<string, string> = {
  unauthorized: 'That secret was not accepted.',
  too_many_failed_attempts: 'Too many failed attempts. Wait a minute and try again.',
  no_metadata_source: 'This engine has no permissions text.',
  schema_unavailable: 'The database schema is not loaded yet. Try again in a moment.',
  invalid_document: 'The document is not valid.',
};

export function describe(failure: ApiError): string {
  return MESSAGES[failure.code] ?? `The request failed (${failure.code}).`;
}

export const UNREACHABLE = 'The engine could not be reached.';

/** The time as the engine saved it, in UTC, so two people read the same list. */
export function shortTime(iso: string): string {
  return `${String(iso).replace('T', ' ').slice(0, 19)} UTC`;
}
