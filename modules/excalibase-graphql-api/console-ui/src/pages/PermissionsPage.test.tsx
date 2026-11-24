import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DOC, healthy, META } from '../test/engine';
import { openConsole } from '../test/render';

const cell = (name: RegExp) => screen.findByRole('button', { name });

describe('the permissions grid', () => {
  it('lists the tables for a role with what it can do, in plain words', async () => {
    await openConsole('#/permissions/%40user');

    expect(await screen.findByRole('heading', { name: 'What can “user” do?' })).toBeVisible();
    expect(await cell(/user can read orders: Some columns/)).toBeVisible();
    expect(await cell(/user can insert orders: None/)).toBeVisible();
    expect(await cell(/user can read customers: None/)).toBeVisible();
  });

  it('marks a rule written by hand as some rows', async () => {
    const hand = { tables: [{ table: 'public.orders', role: 'user', select: { filter: { id: { _eq: 1 } }, columns: '*' } }] };
    await openConsole('#/permissions/%40user', healthy({ 'POST /parse': { body: { valid: true, tree: hand } } }));

    expect(await cell(/user can read orders: Some rows. A rule written by hand/)).toBeVisible();
  });

  it('opens a row rule instead of dropping it when its cell is clicked, and removes it only on request', async () => {
    const hand = { tables: [{ table: 'public.orders', role: 'user', select: { filter: { id: { _eq: 1 } }, columns: '*' } }] };
    const { user } = await openConsole('#/permissions/%40user', healthy({ 'POST /parse': { body: { valid: true, tree: hand } } }));

    await user.click(await cell(/user can read orders: Some rows/));

    const panel = await screen.findByRole('region', { name: 'Rule for user on orders: read' });
    expect(panel).toHaveTextContent('"_eq": 1');
    expect(screen.getByRole('button', { name: 'Save and apply' })).toBeDisabled();
    expect(screen.queryByText(/unsaved change/)).toBeNull();
    expect(await cell(/user can read orders: Some rows/)).toBeVisible();

    await user.click(within(panel).getByRole('button', { name: 'Remove this rule' }));

    expect(await screen.findByText('1 unsaved change')).toBeVisible();
    expect(await cell(/user can read orders: None/)).toBeVisible();
  });

  it('does not carry an open rule over to another role', async () => {
    const hand = { tables: [{ table: 'public.orders', role: 'user', select: { filter: { id: { _eq: 1 } }, columns: '*' } }, { table: 'public.orders', role: 'editor', select: { filter: { id: { _eq: 2 } }, columns: '*' } }] };
    const { user } = await openConsole('#/permissions/%40user', healthy({ 'POST /parse': { body: { valid: true, tree: hand } } }));
    await user.click(await cell(/user can read orders: Some rows/));
    await screen.findByRole('region', { name: 'Rule for user on orders: read' });

    window.location.hash = '#/permissions/%40editor';

    expect(await screen.findByRole('heading', { name: 'What can “editor” do?' })).toBeVisible();
    expect(screen.queryByRole('region', { name: /^Rule for/ })).toBeNull();
  });

  it('moves focus into the rule panel and gives it back to the cell on Close', async () => {
    const hand = { tables: [{ table: 'public.orders', role: 'user', select: { filter: { id: { _eq: 1 } }, columns: '*' } }] };
    const { user } = await openConsole('#/permissions/%40user', healthy({ 'POST /parse': { body: { valid: true, tree: hand } } }));
    const opener = await cell(/user can read orders: Some rows/);
    expect(opener).toHaveAttribute('aria-expanded', 'false');

    await user.click(opener);

    const panel = await screen.findByRole('region', { name: 'Rule for user on orders: read' });
    expect(panel).toHaveFocus();
    expect(opener).toHaveAttribute('aria-expanded', 'true');
    expect(opener).toHaveAttribute('aria-controls', panel.id);
    await user.click(within(panel).getByRole('button', { name: 'Close' }));
    expect(opener).toHaveFocus();
    expect(opener).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('region', { name: /^Rule for/ })).toBeNull();
  });

  it('gives focus to the now-None cell after Remove this rule', async () => {
    const hand = { tables: [{ table: 'public.orders', role: 'user', select: { filter: { id: { _eq: 1 } }, columns: '*' } }] };
    const { user } = await openConsole('#/permissions/%40user', healthy({ 'POST /parse': { body: { valid: true, tree: hand } } }));
    await user.click(await cell(/user can read orders: Some rows/));

    await user.click(within(await screen.findByRole('region', { name: /^Rule for/ })).getByRole('button', { name: 'Remove this rule' }));

    expect(await cell(/user can read orders: None/)).toHaveFocus();
  });

  it('opens a column rule instead of dropping it', async () => {
    const { user } = await openConsole('#/permissions/%40user');

    await user.click(await cell(/user can read orders: Some columns/));

    expect(await screen.findByRole('region', { name: 'Rule for user on orders: read' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Save and apply' })).toBeDisabled();
  });

  it('counts the unsaved changes and keeps Save off until there is one', async () => {
    const { user } = await openConsole('#/permissions/%40user', healthy({
      'POST /parse': ({ body }) => ({ body: { valid: true, tree: (body ?? '').includes('"insert"') ? { tables: [{ ...DOC.tables[0], insert: { check: {}, columns: '*' } }] } : DOC } }),
    }));
    const save = await screen.findByRole('button', { name: 'Save and apply' });
    expect(save).toBeDisabled();

    await user.click(await cell(/user can insert orders: None/));

    expect(save).toBeEnabled();
    expect(await screen.findByText('1 unsaved change')).toBeVisible();
  });

  it('asks before a save that gives more access, lists it, and sends the saved version in If-Match', async () => {
    const { user, engine } = await openConsole('#/permissions/%40user', healthy({
      'POST /parse': ({ body }) => ({ body: { valid: true, tree: (body ?? '').includes('"insert"') ? { tables: [{ ...DOC.tables[0], insert: { check: {}, columns: '*' } }] } : DOC } }),
      'PUT /metadata': { body: { version: 4, historyRecorded: true, warnings: [] } },
    }));
    await user.click(await cell(/user can insert orders: None/));

    await user.click(screen.getByRole('button', { name: 'Save and apply' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/changes who can reach your data/)).toBeVisible();
    expect(within(dialog).getByRole('list', { name: 'What this changes' })).toHaveTextContent('user · public.orders: + insert');
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.click(within(dialog).getByRole('button', { name: 'Save and apply' }));

    expect(await screen.findByText('Saved. The engine now uses version 4.')).toBeVisible();
    const put = engine.calls.find((call) => call.method === 'PUT');
    expect(put?.headers['If-Match']).toBe('"abc"');
    expect(put?.headers['Content-Type']).toBe('text/plain');
  });

  it('cancelling the question saves nothing', async () => {
    const { user, engine } = await openConsole('#/permissions/%40user');
    await user.click(await cell(/user can insert orders: None/));
    await user.click(screen.getByRole('button', { name: 'Save and apply' }));

    await user.click(await within(await screen.findByRole('dialog')).findByRole('button', { name: 'Cancel' }));

    expect(engine.calls.some((call) => call.method === 'PUT')).toBe(false);
    expect(screen.getByRole('button', { name: 'Save and apply' })).toBeEnabled();
  });

  it('shows the warnings the engine sent after a save, as sentences', async () => {
    const { user } = await openConsole('#/permissions/%40user', healthy({
      'PUT /metadata': { body: { version: 4, historyRecorded: false, warnings: [{ message: 'Table public.ghost is not in the database.' }] } },
    }));
    await user.click(await cell(/user can delete orders: None/));
    await user.click(screen.getByRole('button', { name: 'Save and apply' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Save and apply' }));

    expect(await screen.findByText('Table public.ghost is not in the database.')).toBeVisible();
    expect(screen.getByText('Saved, but this version could not be added to the history.')).toBeVisible();
  });

  it('offers their version when someone else saved first, and never overwrites on its own', async () => {
    const { user, engine } = await openConsole('#/permissions/%40user', healthy({
      'PUT /metadata': { status: 409, body: { error: 'conflict', currentVersion: 'zzz' } },
    }));
    await user.click(await cell(/user can delete orders: None/));
    await user.click(screen.getByRole('button', { name: 'Save and apply' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Save and apply' }));

    expect(await screen.findByText(/Someone else saved first/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Use their version' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Keep mine and replace theirs' })).toBeVisible();
    expect(engine.calls.filter((call) => call.method === 'PUT')).toHaveLength(1);
  });

  it('lists the engine’s own reasons when it refuses the text', async () => {
    const { user } = await openConsole('#/permissions/%40user', healthy({
      'PUT /metadata': { status: 422, body: { errors: ['tables[0].role: must not be empty'] } },
    }));
    await user.click(await cell(/user can delete orders: None/));
    await user.click(screen.getByRole('button', { name: 'Save and apply' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Save and apply' }));

    expect(await screen.findByText('tables[0].role: must not be empty')).toBeVisible();
  });

  it('turns Save off and says why on a read-only source', async () => {
    await openConsole('#/permissions/%40user', healthy({ 'GET /metadata': { body: { ...META, writable: false } } }));

    expect(await screen.findByText(/Read-only source/)).toBeVisible();
    expect(screen.getByRole('button', { name: 'Save and apply' })).toBeDisabled();
  });

  it('says the grid needs a valid text, with the reasons, when the text is not valid', async () => {
    await openConsole('#/permissions/%40user', healthy({ 'POST /parse': { body: { valid: false, errors: ['line 3: mapping values are not allowed here'] } } }));

    expect(await screen.findByText(/needs a valid permissions text/)).toBeVisible();
    expect(screen.getByText('line 3: mapping values are not allowed here')).toBeVisible();
    expect(screen.getByRole('link', { name: 'Open the YAML view' })).toHaveAttribute('href', '#/permissions/yaml');
  });

  it('says what went wrong when the permissions cannot be read at all', async () => {
    await openConsole('#/permissions', healthy({ 'GET /metadata': { status: 404, body: { error: 'no_metadata_source' } } }));

    expect(await screen.findByText('This engine has no permissions text.')).toBeVisible();
  });

  it('lists the roles in the side menu with how many tables each can use, and adds a new one', async () => {
    const { user } = await openConsole('#/permissions');
    const roles = await screen.findByRole('group', { name: 'ROLES' });
    const userRole = await within(roles).findByRole('link', { name: /user/ });
    await waitFor(() => expect(userRole).toHaveTextContent('1 table'));

    await user.click(within(roles).getByRole('button', { name: '+ New role' }));
    await user.type(await screen.findByLabelText('Role name'), 'Bad Name');
    await user.click(screen.getByRole('button', { name: 'Add role' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/lowercase letters/);

    await user.clear(screen.getByLabelText('Role name'));
    await user.type(screen.getByLabelText('Role name'), 'editor');
    await user.click(screen.getByRole('button', { name: 'Add role' }));

    await waitFor(() => expect(window.location.hash).toBe('#/permissions/%40editor'));
    expect(await screen.findByRole('heading', { name: 'What can “editor” do?' })).toBeVisible();
  });
});

describe('the YAML view', () => {
  it('shows the text in an editor a keyboard user can reach, and checks it on request', async () => {
    const { user } = await openConsole('#/permissions/yaml', healthy({
      'POST /validate': { body: { valid: true, tables: 1, functions: 0, warnings: [] } },
    }));

    expect(await screen.findByRole('heading', { name: 'Permissions as YAML' })).toBeVisible();
    expect(await screen.findByLabelText('Permissions document')).toHaveTextContent('projectId: my-app');
    await user.click(screen.getByRole('button', { name: 'Check this text' }));

    expect(await screen.findByText(/This text is fine: 1 table rules/)).toBeVisible();
  });

  it('lists what is wrong when the text is not valid', async () => {
    const { user } = await openConsole('#/permissions/yaml', healthy({
      'POST /validate': { body: { valid: false, errors: ['projectId must be my-app'] } },
    }));

    await user.click(await screen.findByRole('button', { name: 'Check this text' }));

    expect(await screen.findByText('projectId must be my-app')).toBeVisible();
  });
});

describe('the sample token', () => {
  const token = `${btoa('{"alg":"none"}')}.${btoa('{"sub":"u1","app":{"role":"admin"}}').replace(/=+$/, '')}.sig`;

  it('reads the claims, lets a click choose the role, and writes the settings into the document', async () => {
    const { user } = await openConsole('#/permissions/token');

    await user.type(await screen.findByLabelText('A sample token'), token);
    await user.click(screen.getByRole('button', { name: 'Read claims' }));
    await user.click(await screen.findByRole('button', { name: 'Use app.role as the role' }));

    expect(screen.getByLabelText('Role claim')).toHaveValue('app.role');
    expect(screen.getByLabelText('A sample token')).toHaveValue('');
  });

  it('says in plain words when the token cannot be read', async () => {
    const { user } = await openConsole('#/permissions/token');

    await user.type(await screen.findByLabelText('A sample token'), 'not-a-token');
    await user.click(screen.getByRole('button', { name: 'Read claims' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('A token has three parts separated by dots.');
  });
});

describe('the version history', () => {
  const entries = [{ id: 'h1', at: '2026-11-20T10:00:00Z', hash: 'abcdef0123456789', size: 120 }];
  const old = { ...entries[0], text: 'format: 1\nprojectId: my-app\ntables: []\n' };

  it('lists versions and shows one against the text in use', async () => {
    const { user } = await openConsole('#/permissions/history', healthy({
      'GET /history': { body: { available: true, entries } },
      'GET /history/h1': { body: old },
    }));

    await user.click(await screen.findByRole('button', { name: 'View the version replaced at 2026-11-20 10:00:00 UTC' }));

    const diff = await screen.findByLabelText('Difference between the versions');
    expect(diff).toHaveTextContent('- tables: []');
    expect(diff).toHaveTextContent('+ tables:');
  });

  it('asks before restoring and says what restoring changes', async () => {
    const { user, engine } = await openConsole('#/permissions/history', healthy({
      'GET /history': { body: { available: true, entries } },
      'GET /history/h1': { body: old },
      'POST /history/h1/restore': { body: { version: 5, historyRecorded: true, warnings: [] } },
    }));
    await user.click(await screen.findByRole('button', { name: /View the version replaced/ }));

    await user.click(await screen.findByRole('button', { name: 'Restore this version' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/is added to the history, so you can undo this/)).toBeVisible();
    await user.click(within(dialog).getByRole('button', { name: 'Restore' }));

    expect(await screen.findByText('Restored. The engine now serves version 5.')).toBeVisible();
    expect(engine.calls.find((call) => call.path === '/history/h1/restore')?.headers['If-Match']).toBe('"abc"');
  });

  it('explains a refused restore', async () => {
    const { user } = await openConsole('#/permissions/history', healthy({
      'GET /history': { body: { available: true, entries } },
      'GET /history/h1': { body: old },
      'POST /history/h1/restore': { status: 422, body: { errors: ['projectId must be my-app'] } },
    }));
    await user.click(await screen.findByRole('button', { name: /View the version replaced/ }));
    await user.click(await screen.findByRole('button', { name: 'Restore this version' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Restore' }));

    expect(await screen.findByText(/no longer passes the engine's checks/)).toBeVisible();
    expect(screen.getByText('projectId must be my-app')).toBeVisible();
  });

  it('says so when nothing has been replaced yet', async () => {
    await openConsole('#/permissions/history');

    expect(await screen.findByText('Nothing has been replaced yet')).toBeVisible();
  });

  it('says history is for the file source when the source keeps none', async () => {
    await openConsole('#/permissions/history', healthy({ 'GET /history': { body: { available: false, entries: [] } } }));

    expect(await screen.findByText(/History is kept when the permissions come from a file/)).toBeVisible();
  });

  it('says the history could not be read, and that saving still works', async () => {
    await openConsole('#/permissions/history', healthy({ 'GET /history': { body: { available: true, readable: false, entries: [] } } }));

    expect(await screen.findByText(/Saving still works/)).toBeVisible();
  });

  it('offers a retry when the history request fails', async () => {
    await openConsole('#/permissions/history', healthy({ 'GET /history': { status: 500, body: {} } }));

    expect(await screen.findByRole('button', { name: 'Try again' })).toBeVisible();
  });
});
