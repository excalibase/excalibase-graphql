import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  ApiError,
  getMetadata,
  parseText,
  putMetadata,
  restoreHistory,
  type HistoryEntry,
  type Metadata,
} from './api';
import {
  describeChanges,
  exportName,
  rewriteLosesText,
  serialize,
  setOperation,
  validRole,
  widens,
  withJwt,
  type Change,
  type JwtSection,
  type Operation,
  type PermissionsDoc,
} from './lib/docmodel';
import { classify, describe, restoreFailure, UNREACHABLE, type Outcome, type Warning } from './lib/outcome';
import { saveAs } from './download';

const CHANGE_LIST_TIMEOUT_MS = 5000;
const TREE_DELAY_MS = 250;

export type Confirm =
  | { kind: 'save'; changes: Change[] | null; comment: boolean; widens: boolean }
  | { kind: 'restore'; entry: HistoryEntry & { text: string }; dirty: boolean; changes: Change[] | null };

export interface HistoryNote {
  ok: boolean;
  text: string;
  historyMissed?: boolean;
  warnings?: Warning[];
  errors?: string[];
}

interface State {
  meta: Metadata | null;
  draft: string;
  /** The document of `treeFor`, as the engine read it; null when that text is not valid. */
  tree: PermissionsDoc | null;
  treeFor: string | null;
  treeErrors: string[];
  /** The document as served now, read the same way: what "unsaved changes" is counted against. */
  baseTree: PermissionsDoc | null;
  baseFor: string | null;
  addedRoles: string[];
  outcome: Outcome | null;
  theirs: Metadata | null;
  rewriteLoses: boolean;
  skipRewriteWarning: boolean;
  busy: boolean;
  confirm: Confirm | null;
  historySelected: (HistoryEntry & { text: string }) | null;
  historyNote: HistoryNote | null;
  /** Bumped when History must be read again (a save or a restore added a version). */
  historyStamp: number;
}

const INITIAL: State = {
  meta: null,
  draft: '',
  tree: null,
  treeFor: null,
  treeErrors: [],
  baseTree: null,
  baseFor: null,
  addedRoles: [],
  outcome: null,
  theirs: null,
  rewriteLoses: false,
  skipRewriteWarning: false,
  busy: false,
  confirm: null,
  historySelected: null,
  historyNote: null,
  historyStamp: 0,
};

export interface Workbench extends State {
  /** `loading` until the first read of the document ends; `error` if it could not be read. */
  phase: 'loading' | 'ready' | 'error';
  problem: string | null;
  dirty: boolean;
  /** The changes the draft makes against what is served, when both could be read. */
  pending: Change[] | null;
  reload: () => Promise<void>;
  edit: (text: string) => void;
  toggle: (table: string, role: string, operation: Operation, on: boolean) => void;
  addRole: (name: string, known: string[]) => string | null;
  applyToken: (section: JwtSection) => void;
  save: () => Promise<void>;
  confirmSave: (remember: boolean) => void;
  cancelConfirm: () => void;
  discard: () => void;
  exportDocument: () => void;
  takeTheirs: () => Promise<void>;
  overwrite: () => void;
  selectVersion: (entry: HistoryEntry & { text: string } | null) => void;
  askRestore: (entry: HistoryEntry & { text: string }) => Promise<void>;
  confirmRestore: () => void;
  saveVersionFile: (which: string, text: string) => void;
  /** Asks for the document of the draft to be read; the returned function stops asking. */
  watch: () => () => void;
}

const Context = createContext<Workbench | null>(null);

export function useWorkbench(): Workbench {
  const value = useContext(Context);
  if (!value) {
    throw new Error('useWorkbench needs a WorkbenchProvider above it');
  }
  return value;
}

/** The workbench, with the document of the draft read for as long as the calling component is mounted. */
export function useDocument(): Workbench {
  const workbench = useWorkbench();
  const { watch } = workbench;
  useEffect(() => watch(), [watch]);
  return workbench;
}

const unauthorized = (failure: unknown): boolean => failure instanceof ApiError && failure.status === 401;
const messageOf = (failure: unknown): string => (failure instanceof ApiError ? describe(failure) : UNREACHABLE);
const textOf = (meta: Metadata | null): string => meta?.text ?? '';

/** The list of what a change set does, or null when it could not be built (an error, a timeout, a text refused now). */
async function changesBetween(fromText: string | null, toText: string): Promise<{ changes: Change[] | null; invalid: boolean }> {
  const signal = AbortSignal.timeout(CHANGE_LIST_TIMEOUT_MS);
  try {
    const [from, to] = await Promise.all([
      fromText === null ? Promise.resolve({ valid: true as const, tree: { tables: [] } }) : parseText(fromText, signal),
      parseText(toText, signal),
    ]);
    if (!to.valid) {
      return { changes: null, invalid: true };
    }
    if (!from.valid) {
      return { changes: null, invalid: false };
    }
    return { changes: describeChanges(from.tree, to.tree), invalid: false };
  } catch {
    return { changes: null, invalid: false };
  }
}

export function WorkbenchProvider({ onSignedOut, children }: { onSignedOut: () => void; children: ReactNode }) {
  const [state, setState] = useState<State>(INITIAL);
  const [phase, setPhase] = useState<Workbench['phase']>('loading');
  const [problem, setProblem] = useState<string | null>(null);
  const [readers, setReaders] = useState(0);
  const latest = useRef(state);
  latest.current = state;

  const patch = useCallback((change: Partial<State> | ((current: State) => Partial<State>)) => {
    setState((current) => ({ ...current, ...(typeof change === 'function' ? change(current) : change) }));
  }, []);

  const fail = useCallback(
    (failure: unknown) => {
      if (unauthorized(failure)) {
        onSignedOut();
        return;
      }
      setProblem(messageOf(failure));
      setPhase('error');
    },
    [onSignedOut],
  );

  const reload = useCallback(async () => {
    try {
      const meta = await getMetadata();
      patch({
        meta,
        draft: textOf(meta),
        tree: null,
        treeFor: null,
        baseTree: null,
        baseFor: null,
        rewriteLoses: false,
        historySelected: null,
      });
      setProblem(null);
      setPhase('ready');
    } catch (failure) {
      fail(failure);
    }
  }, [fail, patch]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // The engine reads the draft, so a YAML document is understood in one place. Typing waits a moment before asking.
  useEffect(() => {
    if (readers === 0 || !state.meta || state.treeFor === state.draft) {
      return undefined;
    }
    const read = state.draft;
    const timer = window.setTimeout(() => {
      parseText(read)
        .then((answer) => {
          patch((current) =>
            current.draft !== read
              ? {}
              : { tree: answer.valid ? answer.tree : null, treeFor: read, treeErrors: answer.valid ? [] : answer.errors },
          );
        })
        .catch((failure: unknown) => {
          if (unauthorized(failure)) {
            onSignedOut();
          } else {
            patch((current) => (current.draft !== read ? {} : { tree: null, treeFor: read, treeErrors: [messageOf(failure)] }));
          }
        });
    }, TREE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [readers, state.meta, state.draft, state.treeFor, patch, onSignedOut]);

  useEffect(() => {
    const served = state.meta?.text;
    if (readers === 0 || !state.meta || served == null || state.baseFor === served) {
      return;
    }
    parseText(served)
      .then((answer) => patch((current) => (current.meta?.text !== served ? {} : { baseTree: answer.valid ? answer.tree : null, baseFor: served })))
      .catch(() => patch({ baseTree: null, baseFor: served }));
  }, [readers, state.meta, state.baseFor, patch]);

  const edit = useCallback(
    (text: string) => patch({ draft: text, tree: null, treeFor: null, outcome: null }),
    [patch],
  );

  /** The grid and the token form write the document anew; a comment typed between two rewrites is still counted. */
  const rewriteFrom = useCallback(
    (doc: PermissionsDoc) => {
      patch((current) => ({
        rewriteLoses: current.rewriteLoses || current.tree === null || rewriteLosesText(current.draft, current.tree),
        draft: serialize(doc),
        tree: doc,
        treeFor: serialize(doc),
        outcome: null,
      }));
    },
    [patch],
  );

  const toggle = useCallback(
    (table: string, role: string, operation: Operation, on: boolean) => {
      const { tree, treeFor, draft } = latest.current;
      if (tree && treeFor === draft) {
        rewriteFrom(setOperation(tree, table, role, operation, on));
      }
    },
    [rewriteFrom],
  );

  const addRole = useCallback(
    (name: string, known: string[]) => {
      if (!validRole(name)) {
        return 'A role is lowercase letters, digits and underscores, starts with a letter, and is not service.';
      }
      if (known.includes(name)) {
        return 'That role already has a column.';
      }
      patch((current) => ({ addedRoles: [...current.addedRoles, name] }));
      return null;
    },
    [patch],
  );

  const applyToken = useCallback(
    (section: JwtSection) => {
      const { tree, treeFor, draft } = latest.current;
      if (tree && treeFor === draft) {
        rewriteFrom(withJwt(tree, Object.keys(section).length > 0 ? section : null));
      }
    },
    [rewriteFrom],
  );

  const commitSave = useCallback(async () => {
    const { draft, meta } = latest.current;
    if (!meta) {
      return;
    }
    try {
      const saved = await putMetadata(draft, meta.sourceVersion);
      await reload();
      patch((current) => ({
        outcome: { kind: 'saved', version: saved.version, historyMissed: saved.historyRecorded === false, warnings: saved.warnings ?? [] },
        theirs: null,
        historyStamp: current.historyStamp + 1,
      }));
    } catch (failure) {
      if (unauthorized(failure)) {
        onSignedOut();
        return;
      }
      const outcome: Outcome = failure instanceof ApiError ? classify(failure) : { kind: 'failed', code: 'unreachable' };
      const theirs = outcome.kind === 'conflict' ? await getMetadata().catch(() => null) : null;
      patch({ outcome, theirs, tree: null, treeFor: null });
    }
  }, [onSignedOut, patch, reload]);

  /** One save at a time: a second click while the warning or the write is pending does nothing. */
  const save = useCallback(async () => {
    const current = latest.current;
    if (current.busy || !current.meta) {
      return;
    }
    patch({ busy: true });
    const { changes, invalid } = await changesBetween(current.meta.text, current.draft);
    const comment = current.rewriteLoses && !current.skipRewriteWarning;
    // Fails closed: a list that could not be built asks. A text the engine refuses goes on, and its errors are shown.
    const wider = !invalid && (changes === null || widens(changes));
    if (comment || wider) {
      patch({ confirm: { kind: 'save', changes, comment, widens: wider } });
      return;
    }
    await commitSave();
    patch({ busy: false });
  }, [commitSave, patch]);

  const cancelConfirm = useCallback(() => patch({ confirm: null, busy: false }), [patch]);

  const confirmSave = useCallback(
    (remember: boolean) => {
      patch((current) => ({ confirm: null, skipRewriteWarning: current.skipRewriteWarning || remember }));
      void commitSave().finally(() => patch({ busy: false }));
    },
    [commitSave, patch],
  );

  const discard = useCallback(
    () => patch((current) => ({ draft: textOf(current.meta), tree: null, treeFor: null, rewriteLoses: false, outcome: null })),
    [patch],
  );

  const offerFile = useCallback((text: string, label: string) => {
    const { filename, type } = exportName(text, label);
    saveAs(filename, text, type);
  }, []);

  const exportDocument = useCallback(() => offerFile(latest.current.draft, ''), [offerFile]);

  const takeTheirs = useCallback(async () => {
    await reload();
    patch({ outcome: null, theirs: null });
  }, [patch, reload]);

  /** Their version becomes the base, and the person's text stays: the next save replaces theirs, by choice. */
  const overwrite = useCallback(() => {
    patch((current) => {
      const outcome = current.outcome;
      if (!current.meta || outcome?.kind !== 'conflict') {
        return {};
      }
      return {
        meta: { ...current.meta, sourceVersion: outcome.currentVersion, text: current.theirs ? current.theirs.text : current.meta.text },
        outcome: null,
        theirs: null,
        baseFor: null,
      };
    });
  }, [patch]);

  const selectVersion = useCallback((entry: (HistoryEntry & { text: string }) | null) => patch({ historySelected: entry }), [patch]);

  const askRestore = useCallback(
    async (entry: HistoryEntry & { text: string }) => {
      const current = latest.current;
      if (current.busy || !current.meta) {
        return;
      }
      patch({ busy: true });
      const { changes } = await changesBetween(current.meta.text, entry.text);
      patch({ confirm: { kind: 'restore', entry, dirty: current.draft !== textOf(current.meta), changes } });
    },
    [patch],
  );

  const confirmRestore = useCallback(() => {
    const { confirm, meta } = latest.current;
    if (confirm?.kind !== 'restore' || !meta) {
      return;
    }
    patch({ confirm: null });
    void (async () => {
      try {
        const restored = await restoreHistory(confirm.entry.id, meta.sourceVersion);
        await reload();
        patch((current) => ({
          historyNote: {
            ok: true,
            text: `Restored. The engine now serves version ${restored.version}.`,
            historyMissed: restored.historyRecorded === false,
            warnings: restored.warnings ?? [],
          },
          historySelected: null,
          outcome: null,
          historyStamp: current.historyStamp + 1,
        }));
      } catch (failure) {
        if (unauthorized(failure)) {
          onSignedOut();
          return;
        }
        patch({ historyNote: failure instanceof ApiError ? { ok: false, ...restoreFailure(failure) } : { ok: false, text: UNREACHABLE } });
      } finally {
        patch({ busy: false });
      }
    })();
  }, [onSignedOut, patch, reload]);

  const watch = useCallback(() => {
    setReaders((count) => count + 1);
    return () => setReaders((count) => count - 1);
  }, []);

  const dirty = state.meta !== null && state.draft !== textOf(state.meta);
  const pending = useMemo(
    () =>
      dirty && state.baseTree && state.tree && state.treeFor === state.draft ? describeChanges(state.baseTree, state.tree) : null,
    [dirty, state.baseTree, state.tree, state.treeFor, state.draft],
  );

  const value: Workbench = {
    ...state,
    phase,
    problem,
    dirty,
    pending,
    reload,
    edit,
    toggle,
    addRole,
    applyToken,
    save,
    confirmSave,
    cancelConfirm,
    discard,
    exportDocument,
    takeTheirs,
    overwrite,
    selectVersion,
    askRestore,
    confirmRestore,
    saveVersionFile: offerFile,
    watch,
  };
  return <Context.Provider value={value}>{children}</Context.Provider>;
}
