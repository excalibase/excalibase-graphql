import { act, screen, within } from '@testing-library/react';
import { parse } from 'yaml';
import { expect, it, vi } from 'vitest';
import { healthy, META } from '../test/engine';
import { openConsole } from '../test/render';

const ANON_PAGE = '#/permissions/%40anon';
const SAVE = 'Save and apply';
const COMMENTED = `# who\nformat: 1\nprojectId: my-app\ntables:\n  - table: public.todos\n    role: anon\n    select:\n      filter: {}\n      columns: ["id"]\n`;

it('a grid edit made while the save is being checked is ignored, so what is saved is what the dialog listed', async () => {
  const routes = healthy({
    'GET /metadata': { body: { ...META, document: COMMENTED } },
    'POST /parse': ({ body }) => ({ body: { valid: true, tree: parse(body ?? '', { version: '1.1' }) } }),
    'PUT /metadata': { body: { version: 4, historyRecorded: true, warnings: [] } },
  });
  const { user, engine } = await openConsole(ANON_PAGE, routes);
  const fake = globalThis.fetch;
  let release: () => void = () => {};
  let hold = false;
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    if (hold && String(url).endsWith('/parse')) {
      hold = false;
      await new Promise<void>((resolve) => { release = resolve; });
    }
    return fake(url, init);
  });
  await user.click(await screen.findByRole('button', { name: /anon can insert todos: None/ }));
  hold = true;
  await user.click(screen.getByRole('button', { name: SAVE }));
  // while the engine is still reading the patched text, the person ticks one more cell
  await user.click(await screen.findByRole('button', { name: /anon can delete todos: None/ }));
  await act(async () => { release(); });
  const dialog = await screen.findByRole('dialog');
  const listed = dialog.textContent ?? '';
  await user.click(within(dialog).getByRole('button', { name: SAVE }));
  await screen.findByText(/Saved\. The engine now uses version/);
  const sent = engine.calls.filter((call) => call.method === 'PUT')[0]?.body ?? '';
    expect(listed).toContain('insert');
  expect(sent).toContain('insert:');
  expect(sent).not.toContain('delete:');
});
