import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { serialize, type PermissionsDoc } from '../lib/docmodel';
import { healthy, META, type Routes } from '../test/engine';
import { openConsole } from '../test/render';

const PLAIN: Record<string, unknown> = {
  select: { filter: {}, columns: '*' },
  insert: { check: {}, columns: '*' },
  update: { filter: {}, check: {}, columns: '*' },
  delete: { filter: {} },
};
const rule = (role: string, ...operations: string[]) => ({
  table: 'public.todos',
  role,
  ...Object.fromEntries(operations.map((operation) => [operation, PLAIN[operation]])),
});
const docWith = (...rules: ReturnType<typeof rule>[]): PermissionsDoc => ({ format: 1, projectId: 'my-app', tables: rules }) as PermissionsDoc;

/** An engine whose served text is JSON the grid writes back unchanged, so a save loses no comment. */
function engineServing(doc: PermissionsDoc, extra: Routes = {}, state = { parseFails: false }): Routes {
  const text = serialize(doc);
  return healthy({
    'GET /metadata': { body: { ...META, document: text } },
    'POST /parse': ({ body }) => {
      if (state.parseFails) {
        return { status: 500, body: {} };
      }
      return { body: { valid: true, tree: (body ?? '').trim().startsWith('{') ? JSON.parse(body ?? '') : doc } };
    },
    'PUT /metadata': { body: { version: 4, historyRecorded: true, warnings: [] } },
    ...extra,
  });
}

const cell = (name: RegExp) => screen.findByRole('button', { name });
const puts = (calls: Array<{ method: string }>) => calls.filter((call) => call.method === 'PUT');

describe('a save that gives more access always asks', () => {
  it('a plain widening asks, lists the gain, and says nothing about comments when there are none to lose', async () => {
    const { user, engine } = await openConsole('#/permissions/%40anon', engineServing(docWith(rule('anon', 'select'))));
    await user.click(await cell(/anon can insert todos: None/));

    await user.click(screen.getByRole('button', { name: 'Save and apply' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/changes who can reach your data/)).toBeVisible();
    expect(within(dialog).queryByText(/Comments and your own layout will be lost/)).toBeNull();
    expect(puts(engine.calls)).toHaveLength(0);
  });

  it('"Do not warn me about comments again" hides the comment notice only, never a widening', async () => {
    const yaml = healthy({ 'POST /parse': ({ body }) => ({ body: { valid: true, tree: (body ?? '').trim().startsWith('{') ? JSON.parse(body ?? '') : docWith(rule('anon', 'select')) } }),
      'PUT /metadata': { body: { version: 4, historyRecorded: true, warnings: [] } } });
    const { user, engine } = await openConsole('#/permissions/%40anon', yaml);
    await user.click(await cell(/anon can insert todos: None/));
    await user.click(screen.getByRole('button', { name: 'Save and apply' }));
    let dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Comments and your own layout will be lost/)).toBeVisible();
    await user.click(within(dialog).getByLabelText(/Do not warn me about comments again/));
    await user.click(within(dialog).getByRole('button', { name: 'Save and apply' }));
    await screen.findByText(/Saved\. The engine now uses version/);

    await user.click(await cell(/anon can insert todos: None/));
    await user.click(screen.getByRole('button', { name: 'Save and apply' }));

    dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/changes who can reach your data/)).toBeVisible();
    expect(within(dialog).queryByText(/Comments and your own layout will be lost/)).toBeNull();
    expect(puts(engine.calls)).toHaveLength(1);
  });

  it('once comments are silenced, an edit that only removes access saves with no question at all', async () => {
    const both = docWith(rule('anon', 'select', 'delete'));
    const yaml = healthy({ 'POST /parse': ({ body }) => ({ body: { valid: true, tree: (body ?? '').trim().startsWith('{') ? JSON.parse(body ?? '') : both } }),
      'PUT /metadata': { body: { version: 4, historyRecorded: true, warnings: [] } } });
    const { user, engine } = await openConsole('#/permissions/%40anon', yaml);
    await user.click(await cell(/anon can insert todos: None/));
    await user.click(screen.getByRole('button', { name: 'Save and apply' }));
    const first = await screen.findByRole('dialog');
    await user.click(within(first).getByLabelText(/Do not warn me about comments again/));
    await user.click(within(first).getByRole('button', { name: 'Save and apply' }));
    await screen.findByText(/Saved\. The engine now uses version/);

    await user.click(await cell(/anon can delete todos: All rows/));
    await user.click(screen.getByRole('button', { name: 'Save and apply' }));

    await waitFor(() => expect(puts(engine.calls)).toHaveLength(2));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('without that choice, the comment notice is asked again for a remove-only save of text with comments', async () => {
    const { user } = await openConsole('#/permissions/%40anon', healthy({
      'POST /parse': ({ body }) => ({ body: { valid: true, tree: (body ?? '').trim().startsWith('{') ? JSON.parse(body ?? '') : docWith(rule('anon', 'select', 'delete')) } }),
    }));
    await user.click(await cell(/anon can delete todos: All rows/));

    await user.click(screen.getByRole('button', { name: 'Save and apply' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Comments and your own layout will be lost/)).toBeVisible();
    expect(within(dialog).queryByText(/changes who can reach your data/)).toBeNull();
  });

  it('an edit that only removes access saves without asking', async () => {
    const { user, engine } = await openConsole('#/permissions/%40anon', engineServing(docWith(rule('anon', 'select', 'delete'))));
    await user.click(await cell(/anon can delete todos: All rows/));

    await user.click(screen.getByRole('button', { name: 'Save and apply' }));

    expect(await screen.findByText(/Saved\. The engine now uses version/)).toBeVisible();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(puts(engine.calls)).toHaveLength(1);
  });

  it('when the list of changes cannot be built, the save asks and says so (it fails closed)', async () => {
    const state = { parseFails: false };
    const { user, engine } = await openConsole('#/permissions/%40anon', engineServing(docWith(rule('anon', 'select', 'delete')), {}, state));
    await user.click(await cell(/anon can delete todos: All rows/));
    await screen.findByText('1 unsaved change');
    state.parseFails = true;

    await user.click(screen.getByRole('button', { name: 'Save and apply' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('The list of changes could not be built.')).toBeVisible();
    expect(within(dialog).getByText(/changes who can reach your data/)).toBeVisible();
    expect(puts(engine.calls)).toHaveLength(0);
  });
});

describe('a text the engine refuses', () => {
  it('is sent on, so its reasons are shown, instead of a question about access', async () => {
    const state = { refuse: false };
    const doc = docWith(rule('anon', 'select', 'delete'));
    const { user, engine } = await openConsole('#/permissions/%40anon', engineServing(doc, {
      'POST /parse': ({ body }) => (state.refuse && (body ?? '').trim().startsWith('{')
        ? { body: { valid: false, errors: ['nope'] } }
        : { body: { valid: true, tree: (body ?? '').trim().startsWith('{') ? JSON.parse(body ?? '') : doc } }),
      'PUT /metadata': { status: 422, body: { errors: ['tables: must be a list'] } },
    }));
    await user.click(await cell(/anon can insert todos: None/));
    await screen.findByText('1 unsaved change');
    state.refuse = true;

    await user.click(screen.getByRole('button', { name: 'Save and apply' }));

    expect(await screen.findByText('tables: must be a list')).toBeVisible();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(puts(engine.calls)).toHaveLength(1);
  });
});

describe('a restore', () => {
  const entries = [{ id: 'h1', at: '2026-11-20T10:00:00Z', hash: 'abcdef0123456789', size: 120 }];
  const old = { ...entries[0], text: serialize(docWith(rule('anon', 'select'))) };
  const served = docWith();
  const routes = (extra: Routes = {}, state = { parseFails: false }): Routes =>
    healthy({
      'GET /metadata': { body: { ...META, document: serialize(served) } },
      'GET /history': { body: { available: true, entries } },
      'GET /history/h1': { body: old },
      'POST /parse': ({ body }) => (state.parseFails ? { status: 500, body: {} } : { body: { valid: true, tree: JSON.parse(body ?? '{}') } }),
      'POST /history/h1/restore': { body: { version: 5, historyRecorded: true, warnings: [] } },
      ...extra,
    });
  const openRestore = async (extra: Routes = {}, state = { parseFails: false }) => {
    const opened = await openConsole('#/permissions/history', routes(extra, state));
    await opened.user.click(await screen.findByRole('button', { name: /View the version replaced/ }));
    await opened.user.click(await screen.findByRole('button', { name: 'Restore this version' }));
    return { ...opened, dialog: await screen.findByRole('dialog') };
  };

  it('that gives access back says so and lists the grant', async () => {
    const { dialog } = await openRestore();

    expect(within(dialog).getByText(/This restore changes who can reach your data/)).toBeVisible();
    expect(within(dialog).getByRole('list', { name: 'What restoring changes' })).toHaveTextContent('anon · public.todos: + select');
  });

  it('that only takes access away does not say it changes who can reach your data', async () => {
    const narrower = docWith();
    const { dialog } = await openRestore({
      'GET /metadata': { body: { ...META, document: serialize(docWith(rule('anon', 'select'))) } },
      'GET /history/h1': { body: { ...entries[0], text: serialize(narrower) } },
    });

    expect(within(dialog).queryByText(/This restore changes who can reach your data/)).toBeNull();
  });

  it('whose list of changes cannot be built still asks, and says so (it fails closed)', async () => {
    const state = { parseFails: true };
    const { dialog } = await openRestore({}, state);

    expect(within(dialog).getByText(/This restore changes who can reach your data/)).toBeVisible();
    expect(within(dialog).getByText('The list of changes could not be built.')).toBeVisible();
  });

  it('shows the engine warnings, as sentences, once it is done', async () => {
    const { user, dialog } = await openRestore({
      'POST /history/h1/restore': { body: { version: 5, historyRecorded: false, warnings: [{ message: 'Table public.ghost is not in the database.' }] } },
    });

    await user.click(within(dialog).getByRole('button', { name: 'Restore' }));

    expect(await screen.findByText('Table public.ghost is not in the database.')).toBeVisible();
    expect(screen.getByText('Restored, but this version could not be added to the history.')).toBeVisible();
  });
});

