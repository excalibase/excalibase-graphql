import { vi } from 'vitest';

export const SECRET = 'right-secret';
export const EXAMPLE = '# who may read what\nformat: 1\nprojectId: my-app\ntables:\n  - table: public.todos\n    role: user\n    select:\n      filter: {}\n      columns: "*"\n';
export const SCHEMA = {
  tables: [
    { schema: 'public', name: 'todos', kind: 'table', columns: [{ name: 'id', type: 'int4', primaryKey: true }, { name: 'title', type: 'text', primaryKey: false }] },
    { schema: 'public', name: 'v_open', kind: 'view', columns: [{ name: 'id', type: 'int4', primaryKey: false }] },
  ],
  functions: [],
};

export interface EngineOptions { writable?: boolean; text?: string; history?: unknown }

const json = (status: number, body: unknown): Response => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/** A small stand-in for the admin API for component tests. The real one is what the Playwright specs use. */
export function fakeEngine(options: EngineOptions = {}) {
  let text = options.text ?? EXAMPLE;
  let version = 1;
  const puts: { body: string; ifMatch: string | null }[] = [];
  const handler = vi.fn(async (url: string, init: RequestInit = {}): Promise<Response> => {
    const headers = (init.headers ?? {}) as Record<string, string>;
    if (headers['X-Excalibase-Admin-Secret'] !== SECRET) {
      return json(401, { error: 'unauthorized' });
    }
    const path = url.replace('/_admin/v1', '');
    const method = init.method ?? 'GET';
    if (path === '/metadata' && method === 'GET') {
      return json(200, { document: text, version: 'v' + version, sourceVersion: 's' + version, source: 'file', writable: options.writable ?? true });
    }
    if (path === '/metadata' && method === 'PUT') {
      puts.push({ body: String(init.body), ifMatch: headers['If-Match'] ?? null });
      text = String(init.body);
      version += 1;
      return json(200, { version: 'v' + version, historyRecorded: true });
    }
    if (path === '/parse') {
      const body = String(init.body);
      return json(200, body.trimStart().startsWith('{') ? { valid: true, tree: JSON.parse(body) }
        : { valid: true, tree: { format: 1, projectId: 'my-app', tables: [{ table: 'public.todos', role: 'user', select: { filter: {}, columns: '*' } }] } });
    }
    if (path === '/schema') {
      return json(200, SCHEMA);
    }
    if (path === '/replicas') {
      return json(200, { replicas: [{ self: true, version: 'v' + version, ready: true, source: 'file' }] });
    }
    if (path === '/history') {
      return json(200, options.history ?? { available: true, entries: [] });
    }
    if (path === '/validate') {
      return json(200, { valid: true, tables: 1, functions: 0 });
    }
    return json(404, { error: 'not_found' });
  });
  return { handler, puts, install: () => vi.stubGlobal('fetch', handler) };
}
