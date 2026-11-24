import { ApiError, type Warning } from './api/adminApi';

const MESSAGES: Record<string, string> = {
  unauthorized: 'That secret was not accepted.',
  too_many_failed_attempts: 'Too many failed attempts. Wait a minute and try again.',
  no_metadata_source: 'This engine has no permissions text.',
  schema_unavailable: 'The schema is not loaded yet.',
  invalid_document: 'The document is not valid.',
};

export const UNREACHABLE = 'The engine could not be reached.';

export function describeFailure(failure: ApiError): string {
  return MESSAGES[failure.code] ?? 'The request failed (' + failure.code + ').';
}

export const messageOf = (failure: unknown): string => (failure instanceof ApiError ? describeFailure(failure) : UNREACHABLE);

export type Outcome =
  | { kind: 'saved'; version: string; historyMissed: boolean; warnings: Warning[] }
  | { kind: 'conflict'; currentVersion: string }
  | { kind: 'invalid'; errors: string[] }
  | { kind: 'readonly' | 'missing' | 'refused' | 'unreachable' }
  | { kind: 'failed'; code: string };

const errorsOf = (body: unknown): string[] => {
  const errors = (body as { errors?: unknown } | null)?.errors;
  return Array.isArray(errors) ? errors.map(String) : [];
};

/** What a save came to, from the status the admin API answered with. Server text is shown as text, never as markup. */
export function classify(failure: ApiError): Exclude<Outcome, { kind: 'saved' }> {
  switch (failure.status) {
    case 409:
      return { kind: 'conflict', currentVersion: String((failure.body as { currentVersion?: unknown } | null)?.currentVersion ?? '') };
    case 422:
      return { kind: 'invalid', errors: errorsOf(failure.body) };
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

export const OUTCOME_TEXT: Record<string, (outcome: never) => string> = {
  saved: (outcome: { version: string }) => 'Saved. The engine now serves version ' + outcome.version + '.',
  readonly: () => 'This source is read-only. Nothing was saved; use Export to take a copy.',
  missing: () => 'The console did not send the version it started from, so nothing was saved. Reload the page.',
  refused: () => 'The document was written, but the engine could not load it. The last good document is still served.',
  unreachable: () => 'The source could not be written, so nothing was saved. The last good document is still served.',
  failed: (outcome: { code: string }) => 'The save failed (' + outcome.code + ').',
};

export interface Note { kind: 'ok' | 'error'; text: string; errors?: string[]; historyMissed?: boolean; warnings?: Warning[] }

/** What a refused restore says, from the answer's status; the reasons of a 422 are the engine's own. */
export function restoreFailure(failure: ApiError): Note {
  switch (failure.status) {
    case 409:
      return { kind: 'error', text: 'Someone else saved first, so nothing was restored. Reload the page to see their version.' };
    case 422:
      return { kind: 'error', text: "Not restored: this version no longer passes the engine's checks.", errors: errorsOf(failure.body) };
    case 404:
      return { kind: 'error', text: 'That version is no longer in the history.' };
    case 405:
      return { kind: 'error', text: 'This source is read-only. Nothing was restored.' };
    default:
      return { kind: 'error', text: 'The restore failed (' + failure.code + '). The last good document is still served.' };
  }
}
