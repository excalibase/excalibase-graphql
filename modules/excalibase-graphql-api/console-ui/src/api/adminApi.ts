import type { Tree } from '../model/docmodel';

// The only place the admin secret lives and the only place it is sent: the request header, to this origin's
// /_admin/v1. It is kept in sessionStorage so a reload of the tab keeps the session; closing the tab ends it.

const HEADER = 'X-Excalibase-Admin-Secret';
const BASE = '/_admin/v1';
const KEY = 'excalibase.console.session';

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, readonly body: unknown) {
    super(code);
  }
}

export interface Metadata {
  sourceVersion: string; version: string; source: string; writable: boolean; text: string | null;
  lastRefusal?: string | null; lastRefusalAt?: string | null; document?: string | null;
}
export interface SaveReply { version: string; historyRecorded?: boolean; warnings?: Warning[] }
export interface Warning { code?: string; message: string }
export interface Column { name: string; type: string; primaryKey: boolean }
export interface TableInfo { schema: string; name: string; kind: string; columns: Column[] }
export interface FunctionInfo { schema: string; name: string; kind: string; returnsSet: boolean; returnTable: string | null }
export interface SchemaReply { tables: TableInfo[]; functions: FunctionInfo[] }
export interface Replica { self: boolean; version: string; ready: boolean; source: string }
export interface HistoryEntry { id: number | string; at: string; hash: string; size: number }
export interface HistoryList { available: boolean; readable?: boolean; entries: HistoryEntry[] }
export interface HistoryText extends HistoryEntry { text: string }
export interface ValidateReply { valid: boolean; tables?: number; functions?: number; errors?: string[]; warnings?: Warning[] }
export type ParseReply = { valid: true; tree: Tree } | { valid: false; errors: string[] };

function readStored(): string {
  try {
    return window.sessionStorage.getItem(KEY) ?? '';
  } catch {
    return '';
  }
}

let secret = readStored();

function store(value: string): void {
  secret = value;
  try {
    if (value) {
      window.sessionStorage.setItem(KEY, value);
    } else {
      window.sessionStorage.removeItem(KEY);
    }
  } catch {
    // the secret then lives in memory only, for this page load
  }
}

function codeOf(status: number, parsed: unknown): string {
  const body = parsed as { error?: unknown; errors?: unknown } | null;
  if (body?.error) {
    return String(body.error);
  }
  return Array.isArray(body?.errors) ? 'invalid_document' : 'error_' + status;
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

async function call<T>(method: string, path: string, body?: string, extra: Record<string, string> = {}, signal?: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { ...extra, [HEADER]: secret };
  if (body !== undefined) {
    headers['Content-Type'] = 'text/plain';
  }
  const response = await fetch(BASE + path, { method, headers, body, credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer', signal });
  const parsed = await readJson(response);
  if (response.status === 401) {
    store('');
  }
  if (!response.ok) {
    throw new ApiError(response.status, codeOf(response.status, parsed), parsed);
  }
  return parsed as T;
}

const quoted = (version: string): Record<string, string> => ({ 'If-Match': '"' + version + '"' });

export const adminApi = {
  signedIn: (): boolean => secret !== '',
  signOut: (): void => store(''),
  /** For a test: forget everything this module holds, as a fresh page load would. */
  reset: (): void => { secret = readStored(); },
  /** Opens a session: the secret is checked against the admin API and kept only if it is accepted. */
  async signIn(candidate: string): Promise<void> {
    store(candidate);
    try {
      await call('GET', '/replicas');
    } catch (failure) {
      store('');
      throw failure;
    }
  },
  async getMetadata(): Promise<Metadata> {
    const reply = await call<Metadata>('GET', '/metadata');
    return { ...reply, text: reply.document ?? null };
  },
  /** Saves the text if the source still holds the version it was loaded at (the quoted tag of If-Match). */
  putMetadata: (text: string, sourceVersion: string) => call<SaveReply>('PUT', '/metadata', text, quoted(sourceVersion)),
  schema: () => call<SchemaReply>('GET', '/schema'),
  replicas: () => call<{ replicas: Replica[] }>('GET', '/replicas'),
  validate: (text: string) => call<ValidateReply>('POST', '/validate', text),
  parse: (text: string, signal?: AbortSignal) => call<ParseReply>('POST', '/parse', text, {}, signal),
  history: () => call<HistoryList>('GET', '/history'),
  historyEntry: (id: number | string) => call<HistoryText>('GET', '/history/' + encodeURIComponent(id)),
  /** Puts an earlier version back: a save of its text, conditional on the version the page started from. */
  restore: (id: number | string, sourceVersion: string) =>
    call<SaveReply>('POST', '/history/' + encodeURIComponent(id) + '/restore', undefined, quoted(sourceVersion)),
};
