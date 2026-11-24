import type { Metadata } from '../api';
import { ErrorList, Warnings } from './ui';
import type { Outcome } from '../lib/outcome';

const FAILURES: Record<string, (outcome: Outcome) => string> = {
  readonly: () => 'This source is read-only. Nothing was saved. Use Export to take a copy.',
  missing: () => 'The console did not send the version it started from, so nothing was saved. Reload the page.',
  refused: () => 'The text was written, but the engine could not load it. The last good version is still in use.',
  unreachable: () => 'The source could not be written, so nothing was saved. The last good version is still in use.',
  failed: (outcome) => `The save failed (${outcome.kind === 'failed' ? outcome.code : ''}).`,
};

interface Handlers {
  onTakeTheirs: () => void;
  onOverwrite: () => void;
}

function conflictNote(currentVersion: string | number, theirs: Metadata | null): string {
  if (!theirs) {
    return 'Their version could not be shown right now. ';
  }
  return theirs.sourceVersion === currentVersion ? '' : 'This replica has not caught up with their save yet, so the text below is older than theirs. ';
}

/** The one place a save is explained. A conflict never overwrites on its own: the person picks. */
export function OutcomeView({ outcome, theirs, handlers }: { outcome: Outcome | null; theirs: Metadata | null; handlers: Handlers }) {
  if (!outcome) {
    return null;
  }
  if (outcome.kind === 'conflict') {
    return (
      <div className="notice bad" role="alert">
        <p>
          Someone else saved first (the source is now at version {outcome.currentVersion}). {conflictNote(outcome.currentVersion, theirs)}Your edits were not saved.
        </p>
        {theirs ? (
          <pre className="codebox" id="theirs" tabIndex={0} aria-label="Their version">
            {theirs.text ?? 'Nothing is served.'}
          </pre>
        ) : null}
        <div className="actions">
          <button type="button" className="btn" onClick={handlers.onTakeTheirs}>
            Use their version
          </button>
          <button type="button" className="btn danger" onClick={handlers.onOverwrite}>
            Keep mine and replace theirs
          </button>
        </div>
      </div>
    );
  }
  if (outcome.kind === 'invalid') {
    return <ErrorList heading="Not saved: the document is not valid." errors={outcome.errors} />;
  }
  if (outcome.kind === 'saved') {
    return (
      <div>
        <p className="notice ok" role="status">
          Saved. The engine now uses version {outcome.version}.
        </p>
        {outcome.historyMissed ? (
          <p className="notice warn" role="alert">
            Saved, but this version could not be added to the history.
          </p>
        ) : null}
        <Warnings warnings={outcome.warnings} heading="Saved, but your database does not fit everything in it:" />
      </div>
    );
  }
  return (
    <p className="notice bad" role="alert">
      {FAILURES[outcome.kind]?.(outcome)}
    </p>
  );
}
