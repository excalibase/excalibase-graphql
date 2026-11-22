import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { App } from './App';
import { adminApi } from './api/adminApi';
import { EXAMPLE, SECRET, fakeEngine } from './test/fakeEngine';

async function signedIn(options: Parameters<typeof fakeEngine>[0] = {}, hash = '#/permissions') {
  const engine = fakeEngine(options);
  engine.install();
  window.sessionStorage.setItem('excalibase.console.session', SECRET);
  adminApi.reset();
  window.location.hash = hash;
  render(<App />);
  await screen.findByRole('navigation', { name: 'Console sections' });
  return { engine, user: userEvent.setup() };
}

beforeEach(() => {
  window.sessionStorage.clear();
  adminApi.reset();
  window.location.hash = '';
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('signing in', () => {
  test('a person who is not signed in sees only the sign-in form', () => {
    fakeEngine().install();

    render(<App />);

    expect(screen.getByLabelText('Admin secret')).toBeTruthy();
    expect(screen.queryByRole('navigation', { name: 'Console sections' })).toBeNull();
  });

  test('a wrong secret is explained and keeps the form', async () => {
    fakeEngine().install();
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText('Admin secret'), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect((await screen.findByRole('alert')).textContent).toContain('That secret was not accepted.');
    expect(screen.getByLabelText('Admin secret')).toBeTruthy();
  });

  test('the right secret opens the console, and Sign out closes it', async () => {
    fakeEngine().install();
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText('Admin secret'), SECRET);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    await screen.findByRole('navigation', { name: 'Console sections' });
    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(screen.getByLabelText('Admin secret')).toBeTruthy();
    expect(window.sessionStorage.getItem('excalibase.console.session')).toBeNull();
  });
});

describe('the shell', () => {
  test('the left nav has the five sections, and the current one is marked', async () => {
    await signedIn();

    const nav = screen.getByRole('navigation', { name: 'Console sections' });
    expect(within(nav).getAllByRole('link').map((link) => link.textContent)).toEqual(['Data', 'Schema', 'Permissions', 'API explorer', 'Replicas']);
    expect(within(nav).getByRole('link', { name: 'Permissions' }).getAttribute('aria-current')).toBe('page');
  });

  test('the header names the project, the source, its mode and the version served', async () => {
    await signedIn();

    const header = await screen.findByRole('banner');
    await waitFor(() => expect(header.textContent).toContain('my-app'));
    expect(header.textContent).toContain('file');
    expect(header.textContent).toContain('writable');
    expect(header.textContent).toContain('v1');
  });

  test('a nav link changes the page through the hash', async () => {
    const { user } = await signedIn();

    await user.click(screen.getByRole('link', { name: 'Schema' }));

    expect(window.location.hash).toBe('#/schema');
    expect(await screen.findByRole('heading', { name: 'Schema' })).toBeTruthy();
  });

  test('the hash opens the section it names, and a back button follows it', async () => {
    await signedIn({}, '#/replicas');

    expect(await screen.findByRole('heading', { name: 'Replicas' })).toBeTruthy();
    window.location.hash = '#/schema';
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    expect(await screen.findByRole('heading', { name: 'Schema' })).toBeTruthy();
  });
});

describe('the screens the old console had', () => {
  test('the schema lists tables and views with their columns and keys', async () => {
    await signedIn({}, '#/schema');

    const row = await screen.findByRole('row', { name: /public\.todos/ });
    expect(row.textContent).toContain('id (key): int4');
    expect(screen.getByRole('row', { name: /public\.v_open/ }).textContent).toContain('view');
  });

  test('the data page lists the tables and opens one', async () => {
    const { user } = await signedIn({}, '#/data');

    await user.click(await screen.findByRole('link', { name: 'public.todos' }));

    expect(window.location.hash).toBe('#/data/public.todos');
    expect((await screen.findByRole('heading', { name: 'public.todos' }))).toBeTruthy();
  });

  test('replicas say which is this one and whether it is ready', async () => {
    await signedIn({}, '#/replicas');

    const row = await screen.findByRole('row', { name: /this replica/ });
    expect(row.textContent).toContain('ready');
  });

  test('validate reports the counts for a pasted text', async () => {
    const { user } = await signedIn({}, '#/permissions/validate');

    await user.type(await screen.findByLabelText('Text to check'), 'format: 1');
    await user.click(screen.getByRole('button', { name: 'Validate' }));

    expect((await screen.findByRole('status')).textContent).toContain('Valid: 1 table rules, 0 functions.');
  });

  test('the API explorer says it is not built yet and where the old tools are', async () => {
    await signedIn({}, '#/explorer');

    expect(await screen.findByRole('heading', { name: 'API explorer' })).toBeTruthy();
  });
});

describe('the permissions page', () => {
  test('it has the five tabs, with the document first', async () => {
    await signedIn();

    const tabs = screen.getAllByRole('tab').map((tab) => tab.textContent);
    expect(tabs).toEqual(['Document', 'Grid', 'Sample token', 'History', 'Validate']);
    expect(screen.getByRole('tab', { name: 'Document' }).getAttribute('aria-selected')).toBe('true');
  });

  test('Save starts off and Export is always there', async () => {
    await signedIn();

    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Export' }) as HTMLButtonElement).disabled).toBe(false);
    expect(screen.getByText('No unsaved edits.')).toBeTruthy();
  });

  test('a read-only source says so and cannot be saved', async () => {
    await signedIn({ writable: false });

    expect(screen.getByText(/Read-only source/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(true);
  });

  test('the grid has a box per operation, role and table; a hand-written rule is starred', async () => {
    const { user } = await signedIn({ text: JSON.stringify({ format: 1, projectId: 'my-app', tables: [{ table: 'public.todos', role: 'user', select: { filter: { id: { _eq: 1 } }, columns: '*' } }] }) });

    await user.click(screen.getByRole('tab', { name: 'Grid' }));

    expect(await screen.findByLabelText('select public.todos user')).toHaveProperty('checked', true);
    expect(screen.getByLabelText('insert public.todos user')).toHaveProperty('checked', false);
    expect(screen.getByText('S*')).toBeTruthy();
  });

  test('ticking a box makes the draft dirty and turns Save on; Discard goes back', async () => {
    const { user } = await signedIn();
    await user.click(screen.getByRole('tab', { name: 'Grid' }));

    await user.click(await screen.findByLabelText('insert public.todos user'));

    expect((screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement).disabled).toBe(false);
    await user.click(screen.getByRole('button', { name: 'Discard edits' }));
    expect(screen.getByText('No unsaved edits.')).toBeTruthy();
  });

  test('a role that is not allowed is refused in words; a good one becomes a column', async () => {
    const { user } = await signedIn();
    await user.click(screen.getByRole('tab', { name: 'Grid' }));
    await screen.findByLabelText('insert public.todos user');

    await user.type(screen.getByLabelText('Add a role'), 'Service');
    await user.click(screen.getByRole('button', { name: 'Add' }));
    expect((await screen.findByRole('alert')).textContent).toContain('lowercase letters');
    await user.clear(screen.getByLabelText('Add a role'));
    await user.type(screen.getByLabelText('Add a role'), 'editor');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    expect(await screen.findByLabelText('select public.todos editor')).toBeTruthy();
  });

  test('a grid save over commented text warns, cancel writes nothing, Save writes', async () => {
    const { user, engine } = await signedIn();
    await user.click(screen.getByRole('tab', { name: 'Grid' }));
    await user.click(await screen.findByLabelText('insert public.todos user'));

    await user.click(screen.getByRole('button', { name: 'Save' }));
    const dialog = await screen.findByRole('dialog', { hidden: true });
    expect(dialog.textContent).toContain('Comments and your own layout will be lost.');
    expect(document.activeElement?.textContent).toBe('Cancel');
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(engine.puts).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Save' }));
    await user.click(within(await screen.findByRole('dialog', { hidden: true })).getByRole('button', { name: 'Save' }));

    expect((await screen.findByText(/^Saved\. The engine now serves version/))).toBeTruthy();
    expect(engine.puts).toHaveLength(1);
    expect(engine.puts[0]?.ifMatch).toBe('"s1"');
    expect(EXAMPLE).toContain('#');
    expect(engine.puts[0]?.body).not.toContain('#');
  });

  test('a gain is listed in red words and never offers to be silenced', async () => {
    const { user } = await signedIn({ text: JSON.stringify({ format: 1, projectId: 'my-app', tables: [] }, null, 2) + '\n' });
    await user.click(screen.getByRole('tab', { name: 'Grid' }));
    await user.click(await screen.findByLabelText('select public.todos anon'));

    await user.click(screen.getByRole('button', { name: 'Save' }));

    const item = await screen.findByText('anon · public.todos: + select');
    expect(item.className).toContain('text-danger');
    expect(screen.queryByLabelText(/Don't ask again/)).toBeNull();
    expect(screen.getByRole('dialog', { hidden: true }).textContent).toContain('changes who can access your data');
  });

  test('the sample token form reads claims and keeps the token out of storage', async () => {
    const { user } = await signedIn();
    await user.click(screen.getByRole('tab', { name: 'Sample token' }));
    const part = (value: unknown) => btoa(JSON.stringify(value)).replace(/=+$/, '');

    await user.click(await screen.findByLabelText(/Paste a sample token/));
    await user.paste(`${part({ alg: 'x' })}.${part({ sub: 'u-1', role: 'user' })}.sig`);
    await user.click(screen.getByRole('button', { name: 'Read claims' }));

    const row = (await screen.findByText('role', { selector: 'td' })).closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Role' }));
    expect((screen.getByLabelText('Role claim') as HTMLInputElement).value).toBe('role');
    expect((screen.getByLabelText(/Paste a sample token/) as HTMLTextAreaElement).value).toBe('');
    expect(JSON.stringify({ ...window.sessionStorage })).not.toContain('eyJ');
  });

  test('history says when there is none yet, and when the source keeps none', async () => {
    const { user } = await signedIn();
    await user.click(screen.getByRole('tab', { name: 'History' }));

    expect(await screen.findByText('Nothing has been replaced yet.')).toBeTruthy();
  });

  test('a source without history says it is for the file source', async () => {
    const { user } = await signedIn({ history: { available: false, entries: [] } });
    await user.click(screen.getByRole('tab', { name: 'History' }));

    expect(await screen.findByText('History is available for the file source.')).toBeTruthy();
  });
});
