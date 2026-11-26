import { vi } from 'vitest';

/** One scripted answer of the fake engine: a status and a JSON body. */
export interface Reply {
  status?: number;
  body?: unknown;
}

export type Routes = Record<string, Reply | ((request: { body: string | undefined; headers: Record<string, string> }) => Reply)>;

export interface Call {
  method: string;
  path: string;
  body: string | undefined;
  headers: Record<string, string>;
}

/** Replaces fetch with a fake engine: `METHOD /path` maps to an answer. An unscripted call fails the test loudly. */
export function fakeEngine(routes: Routes): { calls: Call[] } {
  const calls: Call[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const path = String(url).replace('/_admin/v1', '');
      const method = init.method ?? 'GET';
      const headers = (init.headers ?? {}) as Record<string, string>;
      const body = init.body as string | undefined;
      calls.push({ method, path, body, headers });
      const scripted = routes[`${method} ${path}`];
      if (!scripted) {
        throw new Error(`the fake engine has no answer for ${method} ${path}`);
      }
      const reply = typeof scripted === 'function' ? scripted({ body, headers }) : scripted;
      const status = reply.status ?? 200;
      return new Response(JSON.stringify(reply.body ?? {}), { status, headers: { 'Content-Type': 'application/json' } });
    }),
  );
  return { calls };
}

export const TODOS_YAML = 'format: 1\nprojectId: my-app\ntables:\n  - table: public.todos\n    role: user\n    select: {filter: {}, columns: "*"}\n';

export const SCHEMA = {
  tables: [
    {
      schema: 'public',
      name: 'orders',
      kind: 'table',
      columns: [
        { name: 'id', type: 'int8', primaryKey: true },
        { name: 'customer_id', type: 'int8', primaryKey: false },
        { name: 'internal_note', type: 'text', primaryKey: false },
      ],
      references: [{ schema: 'public', table: 'customers', columns: ['customer_id'], referencedColumns: ['id'] }],
      referencedBy: [{ schema: 'public', table: 'order_items', columns: ['order_id'], referencedColumns: ['id'] }],
    },
    { schema: 'public', name: 'customers', kind: 'table', columns: [{ name: 'id', type: 'int8', primaryKey: true }], references: [], referencedBy: [] },
    { schema: 'public', name: 'order_items', kind: 'table', columns: [{ name: 'order_id', type: 'int8', primaryKey: false }], references: [], referencedBy: [] },
  ],
  functions: [],
};

export const DOC = {
  tables: [{ table: 'public.orders', role: 'user', select: { filter: {}, columns: ['id', 'customer_id'] } }],
};

export const META = {
  source: 'file',
  version: 3,
  sourceVersion: 'abc',
  writable: true,
  lastRefusal: null,
  lastRefusalAt: null,
  document: TODOS_YAML,
};

/** The usual answers of a healthy engine, to override one at a time. */
export function healthy(overrides: Routes = {}): Routes {
  return {
    'GET /replicas': { body: { replicas: [{ self: true, version: 3, ready: true, source: 'file' }] } },
    'GET /metadata': { body: META },
    'GET /schema': { body: SCHEMA },
    'POST /parse': { body: { valid: true, tree: DOC } },
    'GET /history': { body: { available: true, entries: [] } },
    'GET /auth': { status: 404, body: { error: 'Not Found' } },
    ...overrides,
  };
}
