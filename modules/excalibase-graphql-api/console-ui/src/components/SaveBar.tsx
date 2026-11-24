import { Download, Save } from 'lucide-react';
import type { Warning } from '../api/adminApi';
import { exportName } from '../model/docmodel';
import { OUTCOME_TEXT, type Outcome } from '../messages';
import { saveAs } from '../download';
import { useDocument } from '../state/DocumentContext';
import { ErrorNote } from './ui';

export function WarningList({ warnings, heading }: { readonly warnings: readonly Warning[] | undefined; readonly heading: string }) {
  if (!warnings || warnings.length === 0) {
    return null;
  }
  return (
    <div role="alert" id="warnings" className="text-warn">
      <p>{heading}</p>
      <ul className="ml-5 list-disc">{warnings.map((warning) => <li key={warning.message}>{warning.message}</li>)}</ul>
    </div>
  );
}

export function HistoryMissed() {
  return <p role="alert" className="text-warn">Saved, but this version could not be added to History.</p>;
}

function ConflictView({ outcome }: { readonly outcome: Extract<Outcome, { kind: 'conflict' }> }) {
  const { store, state } = useDocument();
  const theirs = state.theirs;
  const behind = theirs !== null && theirs.sourceVersion !== outcome.currentVersion;
  return (
    <ErrorNote>
      <p>
        Someone else saved first (the source is now at version {outcome.currentVersion}).{' '}
        {theirs === null ? 'Their version could not be shown right now. ' : ''}
        {behind ? 'This replica has not caught up with their save yet, so the text below is older than theirs. ' : ''}
        Your edits were not saved.
      </p>
      {theirs ? <pre id="theirs" className="my-2 max-h-72 overflow-auto rounded-md bg-surface p-3 font-mono text-xs text-ink">{theirs.text ?? 'Nothing is served.'}</pre> : null}
      <div className="flex flex-wrap gap-2">
        <button type="button" className="btn-quiet" onClick={() => void store.takeTheirs()}>Use their version</button>
        <button type="button" className="btn-danger" onClick={() => store.overwrite()}>Keep mine and overwrite theirs</button>
      </div>
    </ErrorNote>
  );
}

/** The one place a save outcome is explained. A conflict never overwrites on its own: the person picks. */
export function OutcomeView({ outcome }: { readonly outcome: Outcome | null }) {
  if (!outcome) {
    return null;
  }
  if (outcome.kind === 'conflict') {
    return <ConflictView outcome={outcome} />;
  }
  if (outcome.kind === 'invalid') {
    return (
      <ErrorNote>
        <p>Not saved: the document is not valid.</p>
        <ul className="ml-5 list-disc">{outcome.errors.map((message) => <li key={message}>{message}</li>)}</ul>
      </ErrorNote>
    );
  }
  const text = OUTCOME_TEXT[outcome.kind]?.(outcome as never) ?? '';
  return (
    <div>
      {outcome.kind === 'saved'
        ? <p role="status" className="text-ok">{text}</p>
        : <ErrorNote>{text}</ErrorNote>}
      {outcome.kind === 'saved' && outcome.historyMissed ? <HistoryMissed /> : null}
      {outcome.kind === 'saved' ? <WarningList warnings={outcome.warnings} heading="Saved, but the database does not fit everything in it:" /> : null}
    </div>
  );
}

function hint(writable: boolean, dirty: boolean): string {
  if (!writable) {
    return 'Read-only source: edits cannot be saved here. Export saves a copy as a file.';
  }
  return dirty ? 'Unsaved edits.' : 'No unsaved edits.';
}

/** Save is off on a source that cannot be written; Export is then the way to take the document away. */
export function SaveBar() {
  const { store, state } = useDocument();
  const writable = state.meta?.writable === true;
  const offerExport = () => {
    const { filename, type } = exportName(state.draft);
    saveAs(filename, state.draft, type);
  };
  return (
    <section aria-label="Save" className="mb-4 space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" id="save" className="btn" disabled={!writable || !state.dirty} onClick={() => void store.save()}>
          <Save size={15} aria-hidden /> Save
        </button>
        <button type="button" id="export" className="btn-quiet" onClick={offerExport}>
          <Download size={15} aria-hidden /> Export
        </button>
        {state.dirty ? <button type="button" className="btn-link" onClick={() => store.discard()}>Discard edits</button> : null}
        <span role="status" className="hint">{hint(writable, state.dirty)}</span>
      </div>
      <OutcomeView outcome={state.outcome} />
    </section>
  );
}
