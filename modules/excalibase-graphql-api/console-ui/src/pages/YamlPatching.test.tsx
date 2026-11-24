import { screen, within } from '@testing-library/react';
import { parse } from 'yaml';
import { describe, expect, it } from 'vitest';
import { healthy, META, type Routes } from '../test/engine';
import { openConsole } from '../test/render';

const ANON_PAGE = '#/permissions/%40anon';
const SAVE = 'Save and apply';
const SAVED = /Saved\. The engine now uses version/;
const LOST = /Comments and your own layout will be lost/;
const COMMENTED = `# who may read what
format: 1
projectId: my-app   # must match the engine
tables:
  - table: public.todos
    role: anon
    select:
      filter: {"public": {"_eq": true}}   # only published rows
      columns: ["id", "title"]
`;

/** An engine that reads YAML the way the real one does, so what the page patches is what the engine understands. */
function engineReading(text: string, extra: Routes = {}, misread = { on: false }): Routes {
  return healthy({
    'GET /metadata': { body: { ...META, document: text } },
    'POST /parse': ({ body }) => {
      const read = parse(body ?? '', { version: '1.1' });
      return { body: { valid: true, tree: misread.on && (body ?? '').includes('insert') && !(body ?? '').trim().startsWith('{') ? { ...read, projectId: 'misread' } : read } };
    },
    'PUT /metadata': { body: { version: 4, historyRecorded: true, warnings: [] } },
    ...extra,
  });
}

const insertCell = () => screen.findByRole('button', { name: /anon can insert todos: None/ });
const puts = (calls: Array<{ method: string; body: string | undefined }>) => calls.filter((call) => call.method === 'PUT');

describe('a grid change to a YAML document', () => {
  it('keeps the comments: the access question is about access only, and the text sent keeps every comment', async () => {
    const { user, engine } = await openConsole(ANON_PAGE, engineReading(COMMENTED));
    await user.click(await insertCell());

    await user.click(screen.getByRole('button', { name: SAVE }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/changes who can reach your data/)).toBeVisible();
    expect(within(dialog).queryByText(LOST)).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: SAVE }));
    await screen.findByText(SAVED);
    const sent = puts(engine.calls)[0]?.body ?? '';
    ['# who may read what', '# must match the engine', '# only published rows'].forEach((comment) => expect(sent).toContain(comment));
    expect((parse(sent, { version: '1.1' }) as { tables: Array<{ insert?: unknown }> }).tables[0]?.insert).toBeDefined();
  });

  it('is written as JSON, with the comment notice, if the engine reads the patched text differently', async () => {
    const { user, engine } = await openConsole(ANON_PAGE, engineReading(COMMENTED, {}, { on: true }));
    await user.click(await insertCell());

    await user.click(screen.getByRole('button', { name: SAVE }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(LOST)).toBeVisible();
    expect(puts(engine.calls)).toHaveLength(0);
  });

  it('a JSON document stays JSON and raises no comment notice', async () => {
    const json = JSON.stringify(parse(COMMENTED), null, 2) + '\n';
    const { user, engine } = await openConsole(ANON_PAGE, engineReading(json));
    await user.click(await insertCell());
    await user.click(screen.getByRole('button', { name: SAVE }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByText(LOST)).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: SAVE }));
    await screen.findByText(SAVED);
    expect(puts(engine.calls)[0]?.body?.trimStart().startsWith('{')).toBe(true);
  });
});
