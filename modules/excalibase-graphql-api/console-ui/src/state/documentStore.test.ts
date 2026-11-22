import { parse } from 'yaml';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import { ApiError, adminApi, type Metadata } from '../api/adminApi';
import { serialize } from '../model/docmodel';
import { DocumentStore } from './documentStore';

vi.mock('../api/adminApi', async (original) => {
  const real = await original<typeof import('../api/adminApi')>();
  return { ...real, adminApi: { getMetadata: vi.fn(), putMetadata: vi.fn(), parse: vi.fn(), restore: vi.fn(), historyEntry: vi.fn(), signOut: vi.fn() } };
});

const api = vi.mocked(adminApi);
const PLAIN = JSON.stringify({ format: 1, projectId: 'app', tables: [] }, null, 2) + '\n';
const THEIRS = JSON.stringify({ format: 1, projectId: 'app', tables: [] });
const COMMENTED = '# keep me\nformat: 1\nprojectId: app\ntables: []\n';
const meta = (text: string | null, extra: Partial<Metadata> = {}): Metadata =>
  ({ sourceVersion: 's1', version: 'v1', source: 'file', writable: true, text, ...extra });
// A tiny engine: /parse reads JSON and YAML the way the real one does, unless a test makes it misread YAML.
let misread = false;
const parseText = async (text: string) => {
  if (text.trimStart().startsWith('{')) {
    return { valid: true as const, tree: JSON.parse(text) };
  }
  if (text.includes('surprise')) {
    return { valid: false as const, errors: ['surprise: unknown key'] };
  }
  return { valid: true as const, tree: misread ? { format: 1, projectId: 'misread', tables: [] } : parse(text, { version: '1.1' }) };
};

async function loaded(text: string, extra: Partial<Metadata> = {}): Promise<DocumentStore> {
  api.getMetadata.mockResolvedValue(meta(text, extra));
  const store = new DocumentStore();
  await store.load();
  return store;
}

beforeEach(() => {
  vi.resetAllMocks();
  misread = false;
  api.parse.mockImplementation(parseText);
});

describe('the document store', () => {
  test('loading puts the served text in the draft and marks it clean', async () => {
    const store = await loaded(PLAIN);

    expect(store.snapshot().draft).toBe(PLAIN);
    expect(store.snapshot().dirty).toBe(false);
  });

  test('typing makes it dirty and drops the tree and the last outcome', async () => {
    const store = await loaded(PLAIN);

    store.edit(PLAIN + ' ');

    expect(store.snapshot().dirty).toBe(true);
    expect(store.snapshot().tree).toBeNull();
  });

  test('a grid tick on a plain file rewrites the draft as the grid writes it, without a comment loss', async () => {
    const store = await loaded(PLAIN);
    await store.readTree();

    store.toggle('public.todos', 'anon', 'select', true);

    expect(JSON.parse(store.snapshot().draft).tables).toHaveLength(1);
    expect(store.snapshot().rewriteLoses).toBe(false);
  });

  test('a grid tick over a commented file keeps the comments and is not a loss', async () => {
    const store = await loaded(COMMENTED);
    await store.readTree();

    store.toggle('public.todos', 'anon', 'select', true);

    expect(store.snapshot().draft).toContain('# keep me');
    expect(store.snapshot().draft).toContain('role: anon');
    expect(store.snapshot().rewriteLoses).toBe(false);
  });

  test('a comment typed between two grid ticks is kept by the second', async () => {
    const store = await loaded(PLAIN);
    await store.readTree();
    store.toggle('public.todos', 'anon', 'select', true);
    store.edit(COMMENTED);
    await store.readTree();

    store.toggle('public.todos', 'anon', 'insert', true);

    expect(store.snapshot().draft).toContain('# keep me');
    expect(store.snapshot().rewriteLoses).toBe(false);
  });

  test('a JSON file stays JSON the way the grid writes it', async () => {
    const store = await loaded(PLAIN);
    await store.readTree();

    store.toggle('public.todos', 'anon', 'select', true);

    expect(store.snapshot().draft).toBe(serialize(JSON.parse(store.snapshot().draft)));
  });

  test('if the engine reads the patched text differently, the save falls back to the grid JSON and warns about the comments', async () => {
    const store = await loaded(COMMENTED);
    await store.readTree();
    store.toggle('public.todos', 'anon', 'select', true);
    misread = true;

    await store.save();

    expect(JSON.parse(store.snapshot().draft).tables).toHaveLength(1);
    expect(store.snapshot().dialog).toMatchObject({ kind: 'save', comment: true });
    expect(api.putMetadata).not.toHaveBeenCalled();
  });

  test('a patched text the engine reads as intended is what is written, byte for byte', async () => {
    const store = await loaded(COMMENTED);
    await store.readTree();
    store.toggle('public.todos', 'anon', 'select', true);
    const patched = store.snapshot().draft;
    api.putMetadata.mockResolvedValue({ version: 'v2' });
    api.getMetadata.mockResolvedValue(meta(patched, { sourceVersion: 's2' }));

    await store.save();
    await store.confirm(false);

    expect(api.putMetadata).toHaveBeenCalledWith(patched, 's1');
  });

  test('a tick with no tree changes nothing and asks for the tree again', async () => {
    const store = await loaded(PLAIN);

    store.toggle('public.todos', 'anon', 'select', true);

    expect(store.snapshot().draft).toBe(PLAIN);
  });

  test('text typed while the tree is being read is not overwritten by the older answer', async () => {
    const store = await loaded(PLAIN);
    let release: () => void = () => undefined;
    api.parse.mockImplementationOnce(async (text) => { await new Promise<void>((resolve) => { release = resolve; }); return parseText(text); });
    const reading = store.readTree();
    const typed = JSON.stringify({ format: 1, projectId: 'app', tables: [{ table: 'public.todos', role: 'anon', select: { filter: {}, columns: '*' } }] });

    store.edit(typed);
    release();
    const tree = await reading;

    expect(tree?.tables).toHaveLength(1);
  });

  test('an invalid draft gives no tree and the engine reasons', async () => {
    const store = await loaded(PLAIN);
    store.edit('surprise: true\n');

    expect(await store.readTree()).toBeNull();
    expect(store.snapshot().treeErrors).toEqual(['surprise: unknown key']);
  });

  test('a save that changes nothing wide goes straight through and reloads', async () => {
    const store = await loaded(PLAIN);
    store.edit(PLAIN + ' ');
    api.putMetadata.mockResolvedValue({ version: 'v2' });
    api.getMetadata.mockResolvedValue(meta(PLAIN + ' ', { sourceVersion: 's2', version: 'v2' }));

    await store.save();

    expect(api.putMetadata).toHaveBeenCalledWith(PLAIN + ' ', 's1');
    expect(store.snapshot().outcome).toEqual({ kind: 'saved', version: 'v2', historyMissed: false });
    expect(store.snapshot().meta?.sourceVersion).toBe('s2');
  });

  test('a save that gives anon access asks first, and writes only after the confirm', async () => {
    const store = await loaded(PLAIN);
    store.edit(JSON.stringify({ format: 1, projectId: 'app', tables: [{ table: 't', role: 'anon', select: { filter: {}, columns: '*' } }] }));

    await store.save();

    expect(api.putMetadata).not.toHaveBeenCalled();
    expect(store.snapshot().dialog).toMatchObject({ kind: 'save', widens: true, comment: false });
    api.putMetadata.mockResolvedValue({ version: 'v2' });
    api.getMetadata.mockResolvedValue(meta('x', { sourceVersion: 's2' }));
    await store.confirm(false);
    expect(api.putMetadata).toHaveBeenCalledTimes(1);
    expect(store.snapshot().dialog).toBeNull();
  });

  test('cancel writes nothing and the next save asks again', async () => {
    const store = await loaded(COMMENTED);
    await store.readTree();
    store.toggle('t', 'user', 'delete', true);
    misread = true;
    await store.save();

    store.cancel();
    await store.save();

    expect(api.putMetadata).not.toHaveBeenCalled();
    expect(store.snapshot().dialog).toMatchObject({ kind: 'save', comment: true });
  });

  test('Do not ask again silences the comment notice for the session, never a widening', async () => {
    const store = await loaded(COMMENTED);
    await store.readTree();
    store.toggle('t', 'anon', 'select', true);
    misread = true;
    await store.save();
    api.putMetadata.mockResolvedValue({ version: 'v2' });
    api.getMetadata.mockResolvedValue(meta(COMMENTED, { sourceVersion: 's2' }));
    await store.confirm(true);
    expect(store.snapshot().skipRewriteWarning).toBe(true);

    await store.readTree();
    store.toggle('t', 'anon', 'insert', true);
    await store.save();

    expect(store.snapshot().dialog).toMatchObject({ kind: 'save', comment: false, widens: true });
  });

  test('a list that cannot be built asks, and says so', async () => {
    const store = await loaded(PLAIN);
    store.edit(JSON.stringify({ format: 1, projectId: 'app', tables: [] }) + ' ');
    api.parse.mockRejectedValue(new Error('down'));

    await store.save();

    expect(store.snapshot().dialog).toMatchObject({ kind: 'save', changes: null, widens: true });
  });

  test('a text the engine refuses is not held back by a dialog: the errors come back from the save', async () => {
    const store = await loaded(PLAIN);
    store.edit('surprise: true\n');
    api.putMetadata.mockRejectedValue(new ApiError(422, 'invalid_document', { errors: ['surprise: unknown key'] }));
    api.getMetadata.mockResolvedValue(meta(PLAIN));

    await store.save();

    expect(store.snapshot().dialog).toBeNull();
    expect(store.snapshot().outcome).toEqual({ kind: 'invalid', errors: ['surprise: unknown key'] });
  });

  test('a conflict shows their version and Keep mine rebases onto the version the 409 named', async () => {
    const store = await loaded(PLAIN);
    store.edit(PLAIN + ' ');
    api.putMetadata.mockRejectedValueOnce(new ApiError(409, 'version_conflict', { currentVersion: 's9' }));
    api.getMetadata.mockResolvedValue(meta(THEIRS, { sourceVersion: 's9' }));

    await store.save();
    expect(store.snapshot().outcome).toEqual({ kind: 'conflict', currentVersion: 's9' });
    expect(store.snapshot().theirs?.text).toBe(THEIRS);
    store.overwrite();

    expect(store.snapshot().meta?.sourceVersion).toBe('s9');
    expect(store.snapshot().draft).toBe(PLAIN + ' ');
    api.putMetadata.mockResolvedValue({ version: 'v3' });
    await store.save();
    expect(api.putMetadata).toHaveBeenLastCalledWith(PLAIN + ' ', 's9');
  });

  test('a conflict whose other version cannot be fetched still offers the overwrite', async () => {
    const store = await loaded(PLAIN);
    store.edit(PLAIN + ' ');
    api.putMetadata.mockRejectedValueOnce(new ApiError(409, 'version_conflict', { currentVersion: 's9' }));
    api.getMetadata.mockRejectedValue(new Error('down'));

    await store.save();

    expect(store.snapshot().theirs).toBeNull();
    store.overwrite();
    expect(store.snapshot().meta?.sourceVersion).toBe('s9');
  });

  test('a 401 during a save ends the session instead of showing a save error', async () => {
    const store = await loaded(PLAIN);
    store.edit(PLAIN + ' ');
    api.putMetadata.mockRejectedValue(new ApiError(401, 'unauthorized', null));

    await store.save();

    expect(store.snapshot().sessionEnded).toBe(true);
  });

  test('a read-only source cannot save', async () => {
    const store = await loaded(PLAIN, { writable: false });
    store.edit(PLAIN + ' ');

    await store.save();

    expect(api.putMetadata).not.toHaveBeenCalled();
  });

  test('a second save while the first is pending does nothing', async () => {
    const store = await loaded(PLAIN);
    store.edit(JSON.stringify({ format: 1, projectId: 'app', tables: [{ table: 't', role: 'anon', select: { filter: {}, columns: '*' } }] }));

    await Promise.all([store.save(), store.save()]);

    expect(api.parse).toHaveBeenCalledTimes(2);
  });

  test('restoring asks with the list, and a refused restore keeps the document and says why', async () => {
    const store = await loaded(PLAIN);
    api.restore.mockRejectedValue(new ApiError(422, 'invalid_document', { errors: ['no'] }));

    await store.askRestore({ id: 1, at: '2026-11-20T10:00:00Z', hash: 'h', size: 3, text: PLAIN });
    expect(store.snapshot().dialog).toMatchObject({ kind: 'restore' });
    api.getMetadata.mockResolvedValue(meta(PLAIN));
    await store.confirm(false);

    expect(store.snapshot().historyNote).toMatchObject({ kind: 'error', errors: ['no'] });
  });

  test('discard puts the served text back', async () => {
    const store = await loaded(PLAIN);
    store.edit('x');

    store.discard();

    expect(store.snapshot().draft).toBe(PLAIN);
    expect(store.snapshot().dirty).toBe(false);
  });
});
