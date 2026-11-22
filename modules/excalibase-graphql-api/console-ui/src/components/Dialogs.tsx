import { useState } from 'react';
import type { HistoryText } from '../api/adminApi';
import { widens, type Change } from '../model/docmodel';
import { shortTime } from '../time';
import { ChangeList } from './ChangeList';
import { Modal } from './ui';

interface Handlers { readonly onConfirm: (remember: boolean) => void; readonly onCancel: () => void }

/**
 * Asks before a save that loses comments or widens access, and lists what the save changes. The comment notice can be
 * silenced for the session; the access notice never can.
 */
export function SaveDialog({ changes, comment, widens: wider, onConfirm, onCancel }:
  Handlers & { readonly changes: Change[] | null; readonly comment: boolean; readonly widens: boolean }) {
  const [remember, setRemember] = useState(false);
  return (
    <Modal id={comment ? 'rewrite-warning' : 'access-confirm'} describedBy="rewrite-text" onCancel={onCancel} focus="cancel">
      <div id="rewrite-text" className="space-y-2">
        {comment ? <p>This save rewrites permissions.yaml. Comments and your own layout will be lost.</p> : null}
        {wider ? <p>This save changes who can access your data. Check the list before you save.</p> : null}
      </div>
      <ChangeList changes={changes} id="rewrite-changes" />
      {comment ? (
        <label className="mb-3 flex items-center gap-2" htmlFor="rewrite-remember">
          <input type="checkbox" id="rewrite-remember" checked={remember} onChange={(event) => setRemember(event.target.checked)} />
          Don't ask again about comments this session
        </label>
      ) : null}
      <div className="flex gap-2">
        <button type="button" id="rewrite-save" className="btn-danger" onClick={() => onConfirm(remember)}>Save</button>
        <button type="button" id="rewrite-cancel" data-initial-focus className="btn-quiet" onClick={onCancel}>Cancel</button>
      </div>
    </Modal>
  );
}

/** Asks before a version replaces the text served now, and lists what that changes: a re-granted access is red. */
export function RestoreDialog({ entry, dirty, changes, onConfirm, onCancel }:
  Handlers & { readonly entry: HistoryText; readonly dirty: boolean; readonly changes: Change[] | null }) {
  return (
    <Modal id="restore-confirm" describedBy="restore-text" onCancel={onCancel} focus="cancel">
      <p id="restore-text" className="mb-2">
        Restore this version, replaced at {shortTime(entry.at)}? The text served now is added to the history, so you can undo this.
      </p>
      {changes === null || widens(changes)
        ? <p className="warn mb-2 text-warn">This restore changes who can access your data. Check the list before you restore.</p> : null}
      <ChangeList changes={changes} id="restore-changes" />
      {dirty ? <p className="mb-2 text-danger">Your unsaved edits in the editor will be discarded.</p> : null}
      <div className="flex gap-2">
        <button type="button" id="restore-yes" className="btn-danger" onClick={() => onConfirm(false)}>Restore</button>
        <button type="button" id="restore-no" data-initial-focus className="btn-quiet" onClick={onCancel}>Cancel</button>
      </div>
    </Modal>
  );
}
