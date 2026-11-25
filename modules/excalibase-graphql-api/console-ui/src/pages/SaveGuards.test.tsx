import { act, render, screen, waitFor } from '@testing-library/react';
import { parse } from 'yaml';
import { describe, expect, it, vi } from 'vitest';
import { fakeEngine, healthy, META } from '../test/engine';
import { OutcomeView } from '../components/Outcome';
import { useDocument, WorkbenchProvider, type Workbench } from '../workbench';

const DOC = `# who\nformat: 1\nprojectId: my-app\ntables:\n  - table: public.todos\n    role: anon\n    select:\n      filter: {}\n      columns: ["id"]\n`;
const WIDER = `${DOC}  - table: public.todos\n    role: hacker\n    delete:\n      filter: {}\n`;
const TODOS = 'public.todos';
const MOVED = 'The text changed while checking. Save again.';

let wb: Workbench;
function Probe() {
  wb = useDocument();
  return null;
}

/** A console on a commented document, with the Nth /parse call of the next save held until released. */
async function setup(misread = false) {
  const engine = fakeEngine(
    healthy({
      'GET /metadata': { body: { ...META, document: DOC } },
      'POST /parse': ({ body }) => {
        const read = parse(body ?? '', { version: '1.1' });
        // an engine that reads the patched text differently, so the save falls back to the grid's own text
        const wrong = misread && (body ?? '').includes('insert') && !(body ?? '').trim().startsWith('{');
        return { body: { valid: true, tree: wrong ? { ...read, projectId: 'misread' } : read } };
      },
      'PUT /metadata': { body: { version: 4, historyRecorded: true, warnings: [] } },
    }),
  );
  const fake = globalThis.fetch;
  const gate = { holdNth: -1, seen: 0, release: () => {} };
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    if (String(url).endsWith('/parse')) {
      gate.seen++;
      if (gate.seen === gate.holdNth) {
        await new Promise<void>((resolve) => { gate.release = resolve; });
      }
    }
    return fake(url, init);
  });
  render(<WorkbenchProvider onSignedOut={() => {}}><Probe /></WorkbenchProvider>);
  await waitFor(() => expect(wb.tree).not.toBeNull());
  await waitFor(() => expect(wb.treeFor).toBe(wb.draft));
  return { engine, gate };
}

const puts = (engine: { calls: Array<{ method: string; body?: string }> }) => engine.calls.filter((call) => call.method === 'PUT');

async function saveHolding(holdNth: number, misread = false) {
  const ctx = await setup(misread);
  act(() => wb.toggle(TODOS, 'anon', 'insert', true));
  await waitFor(() => expect(wb.patched).not.toBeNull());
  ctx.gate.seen = 0;
  ctx.gate.holdNth = holdNth;
  let saving!: Promise<void>;
  act(() => { saving = wb.save(); });
  await waitFor(() => expect(ctx.gate.seen).toBeGreaterThanOrEqual(holdNth));
  return { ...ctx, saving };
}

async function finish(release: () => void, saving: Promise<void>) {
  await act(async () => { release(); await saving; });
}

function expectStopped(engine: Parameters<typeof puts>[0], draft: string) {
  expect(wb.draft).toBe(draft);
  expect(puts(engine)).toHaveLength(0);
  expect(wb.confirm).toBeNull();
  expect(wb.busy).toBe(false);
  expect(wb.outcome).toEqual({ kind: 'moved' });
}

describe('a save stops when the text moves under it', () => {
  it('discarding while the patch is being checked saves nothing', async () => {
    const { engine, gate, saving } = await saveHolding(1, true);
    act(() => wb.discard());
    await finish(gate.release, saving);
    expectStopped(engine, DOC);
  });

  it('typing while the patch is being checked saves nothing', async () => {
    const { engine, gate, saving } = await saveHolding(1, true);
    act(() => wb.edit(WIDER));
    await finish(gate.release, saving);
    expectStopped(engine, WIDER);
  });

  it('typing while the changes are being listed saves nothing', async () => {
    const { engine, gate, saving } = await saveHolding(2);
    act(() => wb.edit(WIDER));
    await finish(gate.release, saving);
    expectStopped(engine, WIDER);
  });

  it('discarding while the changes are being listed saves nothing', async () => {
    const { engine, gate, saving } = await saveHolding(2);
    act(() => wb.discard());
    await finish(gate.release, saving);
    expectStopped(engine, DOC);
  });
});

describe('while a save is running', () => {
  it('a token edit leaves the draft unchanged', async () => {
    const { engine, gate, saving } = await saveHolding(1);
    const before = wb.draft;
    act(() => wb.applyToken({ secret: 'env:X', claims: { role: 'role' } }));
    expect(wb.draft).toBe(before);
    await finish(gate.release, saving);
    await waitFor(() => expect(wb.confirm?.kind === 'save' || puts(engine).length === 1).toBe(true));
    expect(wb.draft).toBe(before);
  });
});

describe('the dialog', () => {
  it('writes the text it listed, whatever the draft became while it was open', async () => {
    const ctx = await setup();
    act(() => wb.toggle(TODOS, 'anon', 'insert', true));
    await waitFor(() => expect(wb.patched).not.toBeNull());
    await act(async () => { await wb.save(); });
    expect(wb.confirm?.kind).toBe('save');
    const listed = wb.confirm?.kind === 'save' ? wb.confirm.text : '';
    act(() => wb.applyToken({ secret: 'env:X', claims: { role: 'role' } }));
    act(() => wb.toggle(TODOS, 'anon', 'delete', true));
    act(() => wb.edit(WIDER));
    act(() => wb.confirmSave(false));
    await waitFor(() => expect(puts(ctx.engine)).toHaveLength(1));
    expect(puts(ctx.engine)[0]?.body).toContain(listed);
    expect(puts(ctx.engine)[0]?.body).not.toContain('hacker');
  });
});

it('says so when the save stopped because the text moved', () => {
  render(<OutcomeView outcome={{ kind: 'moved' }} theirs={null} handlers={{ onTakeTheirs: () => {}, onOverwrite: () => {} }} />);
  expect(screen.getByRole('alert').textContent).toBe(MOVED);
});
