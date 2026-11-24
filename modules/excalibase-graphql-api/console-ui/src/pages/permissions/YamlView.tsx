import { useState } from 'react';
import { ApiError, validateText, type ValidateReply } from '../../api';
import { Editor } from '../../Editor';
import { describe, UNREACHABLE } from '../../lib/outcome';
import { useSession } from '../../session';
import { useWorkbench } from '../../workbench';
import { CheckResult } from '../SettingsPage';

export function YamlView() {
  const workbench = useWorkbench();
  const { expired } = useSession();
  const [reply, setReply] = useState<ValidateReply | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const writable = workbench.meta?.writable === true;

  async function check() {
    setBusy(true);
    setError(null);
    try {
      setReply(await validateText(workbench.draft));
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        expired();
        return;
      }
      setReply(null);
      setError(failure instanceof ApiError ? describe(failure) : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label="Permissions as YAML" className="content">
      <div>
        <h1>Permissions as YAML</h1>
        <p className="muted">The same permissions as text. Comments and layout are kept when you save from here.</p>
      </div>
      {workbench.meta?.text == null ? <p className="notice warn">The engine has no valid permissions text yet. Write one below.</p> : null}
      {writable ? null : <p className="notice info">This source is read-only. You can edit and check the text, then use Export to take a copy.</p>}
      <Editor value={workbench.draft} label="Permissions document" onChange={workbench.edit} />
      <div className="actions">
        <button type="button" className="btn" disabled={busy} onClick={check}>
          {busy ? 'Checking…' : 'Check this text'}
        </button>
      </div>
      {error ? <p role="alert" className="notice bad">{error}</p> : null}
      {reply ? <CheckResult reply={reply} /> : null}
    </section>
  );
}
