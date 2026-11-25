import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { serialize, setOperation, type PermissionsDoc } from './docmodel';
import { checkPatch } from '../workbench';
import { patchYaml } from './yamlPatch';

const TODOS = 'public.todos';
const SERVED = '# keep\nformat: 1\nprojectId: my-app\ntables:\n  - table: public.todos\n    role: user\n    select:\n      filter: {}\n      columns: "*"\n';
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
  // an engine that refuses a merge key as a role, the way a strict YAML 1.1 reader does
  vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
    const text = String(init.body);
    return json(text.includes('<<') && !text.trim().startsWith('{') ? { valid: false, errors: ['merge key'] } : { valid: true, tree: {} });
  }));
});
afterEach(() => vi.unstubAllGlobals());

describe('a patched text the engine will not read as intended', () => {
  it('falls back to the grid JSON, and counts the comments that costs, when a role is a merge key', async () => {
    const base = { format: 1, projectId: 'my-app', tables: [{ table: TODOS, role: 'user', select: { filter: {}, columns: '*' } }] } as PermissionsDoc;
    const intended = setOperation(base, TODOS, '<<', 'select', true);
    const draft = patchYaml(SERVED, intended) as string;

    const checked = await checkPatch({ draft, patched: { intended, original: SERVED, base }, rewriteLoses: false } as never);

    expect(checked).toMatchObject({ draft: serialize(intended), patched: null, rewriteLoses: true });
  });

  it('is trusted, and the draft kept, when the engine reads it as intended', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ valid: true, tree: { a: 1 } })));

    const checked = await checkPatch({ draft: 'a: 1\n', patched: { intended: { a: 1 }, original: 'a: 1\n', base: {} }, rewriteLoses: false } as never);

    expect(checked).toEqual({ patched: null });
  });

  it('needs no check when nothing was patched', async () => {
    expect(await checkPatch({ draft: 'x', patched: null } as never)).toBeNull();
  });

  it('says the session ended instead of treating a 401 as a bad patch', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 401 })));

    await expect(checkPatch({ draft: 'a: 1\n', patched: { intended: { a: 1 }, original: '', base: {} }, rewriteLoses: false } as never))
      .rejects.toMatchObject({ status: 401 });
  });
});
