// The only place the admin secret lives and the only place it is sent: the request header, to this origin's
// /_admin/v1. It is kept in sessionStorage so a reload of the tab keeps the session; closing the tab ends it.
import type { PermissionsDoc } from './lib/docmodel';
import type { Warning } from './lib/outcome';

const HEADER = 'X-Excalibase-Admin-Secret';
const BASE = '/_admin/v1';
const KEY = 'excalibase.console.session';

export interface Metadata {
  source: string;
  version: string | number;
  sourceVersion: string | number;
  writable: boolean;
  lastRefusal: string | null;
  lastRefusalAt: string | null;
  /** The permissions text as the source holds it; null when nothing valid is served. */
  text: string | null;
}

export interface SchemaColumn {
  name: string;
  type: string;
  primaryKey: boolean;
}

export interface TableLink {
  /** Columns on the table that holds the key. */
  columns: string[];
  /** The table on the other end, as `schema.name` or `name`. */
  table: string;
  schema?: string | null;
  referencedColumns: string[];
}

export interface SchemaTable {
  schema: string;
  name: string;
  kind: 'table' | 'view';
  columns: SchemaColumn[];
  references?: TableLink[];
  referencedBy?: TableLink[];
}

export interface SchemaView {
  tables: SchemaTable[];
  functions: unknown[];
}

export interface Replica {
  self: boolean;
  version: string | number;
  ready: boolean;
  source: string;
}

export interface HistoryEntry {
  id: string;
  at: string;
  hash: string;
  size: number;
}

export interface HistoryList {
  available: boolean;
  readable?: boolean;
  entries: HistoryEntry[];
}

export interface SaveReply {
  version: string | number;
  historyRecorded?: boolean;
  warnings?: Warning[];
}

export type ParseReply =
  | { valid: true; tree: PermissionsDoc }
  | { valid: false; errors: string[] };

export interface ValidateReply {
  valid: boolean;
  tables?: number;
  functions?: number;
  errors?: string[];
  warnings?: Warning[];
}

export type AuthSource = 'engine' | 'legacy-engine' | 'document' | 'none';
export type DocumentJwt = 'absent' | 'identical' | 'different' | 'present-deprecated';

export interface AuthClaims {
  role: string;
  defaultRole: string;
  userId: string;
  extra: Record<string, string>;
}

/** How the engine checks tokens, as `GET /auth` reports it: never the secret, only whether one is set. */
export interface AuthSettings {
  source: AuthSource;
  issuer?: string;
  jwksUrl?: string;
  algorithms: string[];
  audience: string[];
  clockSkew?: number;
  claims?: AuthClaims;
  secret: 'set' | 'unset';
  keysLoaded?: boolean;
  documentJwt: DocumentJwt;
  warnings: Array<{ code: string }>;
}

/** What "test a token" answers: a reason name for a token that fails, who it is for one that passes. */
export interface TokenCheckReply {
  valid: boolean;
  reason?: string;
  role?: string;
  allowedRoles?: string[];
  userId?: string;
  sessionVariables?: Record<string, string>;
}

let secret = readStored();

function readStored(): string {
  try {
    return window.sessionStorage.getItem(KEY) ?? '';
  } catch {
    return '';
  }
}

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

export const signedIn = (): boolean => secret !== '';
export const signOut = (): void => store('');

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly body: unknown;

  constructor(status: number, code: string, body: unknown) {
    super(code);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

function codeOf(status: number, parsed: unknown): string {
  const body = parsed as { error?: unknown; errors?: unknown } | null;
  if (body?.error) {
    return String(body.error);
  }
  return Array.isArray(body?.errors) ? 'invalid_document' : `error_${status}`;
}

async function parse(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

async function call<T>(
  method: string,
  path: string,
  body?: string,
  extraHeaders: Record<string, string> = {},
  signal?: AbortSignal,
): Promise<T> {
  const headers: Record<string, string> = { ...extraHeaders, [HEADER]: secret };
  if (body !== undefined) {
    headers['Content-Type'] = 'text/plain';
  }
  const response = await fetch(BASE + path, {
    method,
    headers,
    body,
    credentials: 'omit',
    cache: 'no-store',
    referrerPolicy: 'no-referrer',
    signal,
  });
  const parsed = await parse(response);
  if (response.status === 401) {
    signOut();
  }
  if (!response.ok) {
    throw new ApiError(response.status, codeOf(response.status, parsed), parsed);
  }
  return parsed as T;
}

/** Opens a session: the secret is checked against the admin API and kept only if it is accepted. */
export async function signIn(candidate: string): Promise<void> {
  store(candidate);
  try {
    await call('GET', '/replicas');
  } catch (failure) {
    store('');
    throw failure;
  }
}

export async function getMetadata(): Promise<Metadata> {
  const reply = await call<Omit<Metadata, 'text'> & { document: string | null }>('GET', '/metadata');
  const { document, ...rest } = reply;
  return { ...rest, text: document };
}

/** Saves the text if the source still holds the version it was loaded at (the quoted tag of If-Match). */
export const putMetadata = (text: string, sourceVersion: string | number): Promise<SaveReply> =>
  call('PUT', '/metadata', text, { 'If-Match': `"${sourceVersion}"` });

export const getSchema = (): Promise<SchemaView> => call('GET', '/schema');
export const getReplicas = (): Promise<{ replicas: Replica[] }> => call('GET', '/replicas');
export const validateText = (text: string): Promise<ValidateReply> => call('POST', '/validate', text);
export const parseText = (text: string, signal?: AbortSignal): Promise<ParseReply> =>
  call('POST', '/parse', text, {}, signal);

export const getAuth = (): Promise<AuthSettings> => call('GET', '/auth');
/** The token goes in the body of this one call. It is not kept here, in storage or anywhere else. */
export const checkToken = (token: string): Promise<TokenCheckReply> => call('POST', '/auth/check', token);

export const getHistory = (): Promise<HistoryList> => call('GET', '/history');
export const getHistoryEntry = (id: string): Promise<HistoryEntry & { text: string }> =>
  call('GET', `/history/${encodeURIComponent(id)}`);

/** Puts an earlier version back: a save of its text, conditional on the version the page started from. */
export const restoreHistory = (id: string, sourceVersion: string | number): Promise<SaveReply> =>
  call('POST', `/history/${encodeURIComponent(id)}/restore`, undefined, { 'If-Match': `"${sourceVersion}"` });
