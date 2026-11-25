import { useEffect, useRef, type ReactNode } from 'react';
import type { Change } from '../lib/docmodel';
import { OUTAGE_NOTE_CODE, TOKEN_NOTE_CODES, type Warning } from '../lib/outcome';

export function Loading({ label = 'Loading' }: { label?: string }) {
  return (
    <div role="status" className="card">
      <span className="spinner" aria-hidden="true" />
      {label}…
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div role="alert" className="notice bad">
      <p>{message}</p>
      {onRetry ? (
        <button type="button" className="btn" onClick={onRetry}>
          Try again
        </button>
      ) : null}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="card empty">
      <h2>{title}</h2>
      {children}
    </div>
  );
}

function TokenNotice({ role, testId, notes }: { role: 'note' | 'alert'; testId: string; notes: Warning[] }) {
  if (notes.length === 0) {
    return null;
  }
  return (
    <div className="notice warn" role={role} data-testid={testId}>
      <p>About how tokens are checked:</p>
      <ul>
        {notes.map((note) => (
          <li key={note.message}>{note.message}</li>
        ))}
      </ul>
    </div>
  );
}

export function Warnings({ warnings, heading }: { warnings: Warning[] | undefined; heading: string }) {
  if (!warnings || warnings.length === 0) {
    return null;
  }
  const tokenNotes = warnings.filter((warning) => warning.code !== undefined && TOKEN_NOTE_CODES.has(warning.code));
  const outage = tokenNotes.filter((warning) => warning.code === OUTAGE_NOTE_CODE);
  const notes = tokenNotes.filter((warning) => warning.code !== OUTAGE_NOTE_CODE);
  const schema = warnings.filter((warning) => !tokenNotes.includes(warning));
  return (
    <>
      {schema.length > 0 ? (
        <div className="notice warn" role="alert" data-testid="warnings">
          <p>{heading}</p>
          <ul>
            {schema.map((warning) => (
              <li key={warning.message}>{warning.message}</li>
            ))}
          </ul>
        </div>
      ) : null}
      <TokenNotice role="note" testId="token-notes" notes={notes} />
      <TokenNotice role="alert" testId="token-outage" notes={outage} />
    </>
  );
}

export function ErrorList({ heading, errors }: { heading: string; errors: string[] }) {
  return (
    <div className="notice bad" role="alert">
      <p>{heading}</p>
      <ul>
        {errors.map((message) => (
          <li key={message}>{message}</li>
        ))}
      </ul>
    </div>
  );
}

/** What a save or a restore changes. Red: access gained. Amber: a rule or the token settings rewritten. */
export function ChangeList({ changes, label = 'What this changes' }: { changes: Change[] | null; label?: string }) {
  if (changes === null) {
    return <p className="hint">The list of changes could not be built.</p>;
  }
  if (changes.length === 0) {
    return <p className="hint">No role or table gets or loses an operation.</p>;
  }
  return (
    <ul className="changes" aria-label={label}>
      {changes.map((change) => (
        <li key={change.text} className={change.gained.length > 0 ? 'gain' : change.changed.length > 0 || change.other ? 'modify' : ''}>
          {change.text}
        </li>
      ))}
    </ul>
  );
}

interface DialogProps {
  title: string;
  describedBy?: string;
  onClose: () => void;
  children: ReactNode;
}

/** A native modal: the browser traps focus inside it and Escape closes it. Whatever is marked data-autofocus gets focus. */
export function Dialog({ title, describedBy, onClose, children }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) {
      return undefined;
    }
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (typeof dialog.showModal === 'function') {
      dialog.showModal();
    } else {
      dialog.setAttribute('open', '');
    }
    dialog.querySelector<HTMLElement>('[data-autofocus]')?.focus();
    // Whoever opened the question gets their place back when it closes.
    return () => {
      if (opener?.isConnected) {
        opener.focus();
      }
    };
  }, []);
  return (
    <dialog
      ref={ref}
      aria-labelledby="dialog-title"
      aria-describedby={describedBy}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <h2 id="dialog-title">{title}</h2>
      {children}
    </dialog>
  );
}

export function PageHeader({ trail, children }: { trail: string[]; children?: ReactNode }) {
  const last = trail.length - 1;
  return (
    <header className="topbar">
      <nav aria-label="Where you are" className="crumbs">
        {trail.map((part, index) => (
          <span key={part} className="crumbs">
            {index > 0 ? <span aria-hidden="true">/</span> : null}
            {index === last ? <strong aria-current="page">{part}</strong> : <span>{part}</span>}
          </span>
        ))}
      </nav>
      <div className="actions">{children}</div>
    </header>
  );
}
