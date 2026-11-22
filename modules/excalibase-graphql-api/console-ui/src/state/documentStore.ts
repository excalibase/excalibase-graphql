// What the person is working on: the document as served, their edits, the tree the grid edits, and what the last save
// came to. A tree is read from the draft by the engine, so a YAML document is understood there and never in the browser.
import { ApiError, adminApi, type HistoryText, type Metadata } from '../api/adminApi';
import { describeChanges, rewriteLosesText, roleProblem, columnRoles, serialize, setOperation, widens, withJwt,
  type Change, type Operation, type Tree } from '../model/docmodel';
import { UNREACHABLE, classify, describeFailure, restoreFailure, type Note, type Outcome } from '../messages';

const CHANGE_LIST_TIMEOUT_MS = 5000;

export type Dialog =
  | { kind: 'save'; changes: Change[] | null; comment: boolean; widens: boolean }
  | { kind: 'restore'; entry: HistoryText; changes: Change[] | null; dirty: boolean };

export interface DocState {
  loaded: boolean;
  meta: Metadata | null;
  draft: string;
  dirty: boolean;
  /** Bumped when the draft is replaced from outside the editor, so the editor starts again from it. */
  docVersion: number;
  tree: Tree | null;
  treeErrors: string[];
  addedRoles: string[];
  rewriteLoses: boolean;
  skipRewriteWarning: boolean;
  busy: boolean;
  outcome: Outcome | null;
  theirs: Metadata | null;
  dialog: Dialog | null;
  historyNote: Note | null;
  failure: string | null;
  sessionEnded: boolean;
}

const INITIAL: DocState = {
  loaded: false, meta: null, draft: '', dirty: false, docVersion: 0, tree: null, treeErrors: [], addedRoles: [],
  rewriteLoses: false, skipRewriteWarning: false, busy: false, outcome: null, theirs: null, dialog: null,
  historyNote: null, failure: null, sessionEnded: false,
};

const textOf = (meta: Metadata | null): string => meta?.text ?? '';

export class DocumentStore {
  private state: DocState = INITIAL;
  private readonly listeners = new Set<() => void>();

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  readonly snapshot = (): DocState => this.state;

  private set(change: Partial<DocState>): void {
    const next = { ...this.state, ...change };
    this.state = { ...next, dirty: next.draft !== textOf(next.meta) };
    this.listeners.forEach((listener) => listener());
  }

  /** A failure that is not the caller's to explain: a 401 ends the session, anything else is shown as a message. */
  private fail(failure: unknown): void {
    if (failure instanceof ApiError && failure.status === 401) {
      this.set({ sessionEnded: true });
      return;
    }
    this.set({ failure: failure instanceof ApiError ? describeFailure(failure) : UNREACHABLE });
  }

  async load(): Promise<void> {
    try {
      const meta = await adminApi.getMetadata();
      this.set({ meta, draft: textOf(meta), tree: null, rewriteLoses: false, loaded: true, failure: null,
        docVersion: this.state.docVersion + 1 });
    } catch (failure) {
      this.fail(failure);
    }
  }

  edit(text: string): void {
    this.set({ draft: text, tree: null, outcome: null });
  }

  discard(): void {
    this.set({ draft: textOf(this.state.meta), tree: null, rewriteLoses: false, outcome: null, docVersion: this.state.docVersion + 1 });
  }

  /** The tree of the draft. Text typed while the engine was reading is not overwritten by the older answer: read again. */
  async readTree(): Promise<Tree | null> {
    if (this.state.tree) {
      return this.state.tree;
    }
    const read = this.state.draft;
    try {
      const answer = await adminApi.parse(read);
      if (this.state.draft !== read) {
        return this.readTree();
      }
      this.set({ tree: answer.valid ? answer.tree : null, treeErrors: answer.valid ? [] : answer.errors });
      return this.state.tree;
    } catch (failure) {
      this.fail(failure);
      return null;
    }
  }

  /**
   * The grid and the token form write the document anew. Each rewrite checks the text it replaces, so a comment typed
   * between two of them is counted; a tree that cannot be had counts as a loss.
   */
  private rewriteFrom(tree: Tree): void {
    const loses = this.state.rewriteLoses || this.state.tree === null || rewriteLosesText(this.state.draft, this.state.tree);
    this.set({ tree, draft: serialize(tree), rewriteLoses: loses, outcome: null });
  }

  /** A click on a grid drawn before the tree was dropped (a save drops it) has nothing to edit: the tree is read again. */
  toggle(table: string, role: string, operation: Operation, on: boolean): void {
    const tree = this.state.tree;
    if (!tree) {
      void this.readTree();
      return;
    }
    this.rewriteFrom(setOperation(tree, table, role, operation, on));
  }

  /** Returns the reason the role is refused, or null when it became a column. */
  addRole(name: string): string | null {
    const problem = roleProblem(name, columnRoles(this.state.tree ?? {}, this.state.addedRoles));
    if (!problem) {
      this.set({ addedRoles: [...this.state.addedRoles, name] });
    }
    return problem;
  }

  async applyJwt(section: Record<string, unknown>): Promise<boolean> {
    const tree = await this.readTree();
    if (!tree) {
      return false;
    }
    this.rewriteFrom(withJwt(tree, Object.keys(section).length > 0 ? section : null));
    this.set({ docVersion: this.state.docVersion + 1 });
    return true;
  }

  /** One save at a time: a second click while the warning or the write is pending does nothing. */
  async save(): Promise<void> {
    if (this.state.busy || this.state.meta?.writable !== true) {
      return;
    }
    this.set({ busy: true });
    try {
      const { changes, invalid } = await this.changesBetween(this.state.meta.text, this.state.draft);
      const comment = this.state.rewriteLoses && !this.state.skipRewriteWarning;
      // Fails closed: a list that could not be built asks. A text the engine refuses goes on, and its errors are shown.
      const wider = !invalid && (changes === null || widens(changes));
      if (comment || wider) {
        this.set({ dialog: { kind: 'save', changes, comment, widens: wider } });
        return;
      }
      await this.commit();
    } catch (failure) {
      this.fail(failure);
    }
    this.set({ busy: false });
  }

  /** The list, or null when it could not be built (an error, a timeout, a served text the engine now refuses). */
  private async changesBetween(fromText: string | null, toText: string): Promise<{ changes: Change[] | null; invalid: boolean }> {
    const signal = AbortSignal.timeout(CHANGE_LIST_TIMEOUT_MS);
    try {
      const [from, to] = await Promise.all([
        fromText === null ? { valid: true as const, tree: { tables: [] } } : adminApi.parse(fromText, signal),
        adminApi.parse(toText, signal),
      ]);
      if (!to.valid) {
        return { changes: null, invalid: true };
      }
      return from.valid ? { changes: describeChanges(from.tree, to.tree), invalid: false } : { changes: null, invalid: false };
    } catch {
      return { changes: null, invalid: false };
    }
  }

  cancel(): void {
    this.set({ dialog: null, busy: false });
  }

  /** The person said yes to the open dialog. A comment notice can be silenced for the session; a widening never. */
  async confirm(remember: boolean): Promise<void> {
    const dialog = this.state.dialog;
    if (!dialog) {
      return;
    }
    this.set({ dialog: null, skipRewriteWarning: this.state.skipRewriteWarning || (dialog.kind === 'save' && remember) });
    if (dialog.kind === 'save') {
      await this.commit();
    } else {
      await this.restore(dialog.entry);
    }
    this.set({ busy: false });
  }

  private async commit(): Promise<void> {
    const meta = this.state.meta;
    if (!meta) {
      return;
    }
    try {
      const saved = await adminApi.putMetadata(this.state.draft, meta.sourceVersion);
      await this.load();
      this.set({ outcome: { kind: 'saved', version: saved.version, historyMissed: saved.historyRecorded === false }, theirs: null });
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        this.fail(failure);
        return;
      }
      const outcome = failure instanceof ApiError ? classify(failure) : ({ kind: 'failed', code: 'unreachable' } as const);
      this.set({ outcome, theirs: outcome.kind === 'conflict' ? await this.fetchTheirs() : null });
    }
    this.set({ tree: null });
  }

  private async fetchTheirs(): Promise<Metadata | null> {
    try {
      return await adminApi.getMetadata();
    } catch {
      return null;
    }
  }

  /** Their version becomes the base, and the person's text stays: the next save replaces theirs, by choice. */
  overwrite(): void {
    const { outcome, theirs, meta } = this.state;
    if (outcome?.kind !== 'conflict' || !meta) {
      return;
    }
    this.set({ meta: { ...meta, sourceVersion: outcome.currentVersion, text: theirs ? theirs.text : meta.text }, outcome: null, theirs: null });
  }

  async takeTheirs(): Promise<void> {
    await this.load();
    this.set({ outcome: null, theirs: null });
  }

  async askRestore(entry: HistoryText): Promise<void> {
    if (this.state.busy) {
      return;
    }
    this.set({ busy: true });
    const { changes } = await this.changesBetween(this.state.meta?.text ?? null, entry.text);
    this.set({ dialog: { kind: 'restore', entry, changes, dirty: this.state.dirty } });
  }

  private async restore(entry: HistoryText): Promise<void> {
    const meta = this.state.meta;
    if (!meta) {
      return;
    }
    try {
      const restored = await adminApi.restore(entry.id, meta.sourceVersion);
      await this.load();
      this.set({ historyNote: { kind: 'ok', text: 'Restored. The engine now serves version ' + restored.version + '.',
        historyMissed: restored.historyRecorded === false }, outcome: null });
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        this.fail(failure);
        return;
      }
      this.set({ historyNote: failure instanceof ApiError ? restoreFailure(failure) : { kind: 'error', text: UNREACHABLE } });
    }
  }

  clearNote(): void {
    this.set({ historyNote: null });
  }
}
