import { useState } from 'react';
import { shortTime } from '../lib/outcome';
import { widens } from '../lib/docmodel';
import { useWorkbench } from '../workbench';
import { ChangeList, Dialog } from './ui';

function SaveConfirm() {
  const workbench = useWorkbench();
  const confirm = workbench.confirm;
  const [remember, setRemember] = useState(false);
  if (confirm?.kind !== 'save') {
    return null;
  }
  return (
    <Dialog title="Check before you save" describedBy="confirm-text" onClose={workbench.cancelConfirm}>
      <div id="confirm-text">
        {confirm.comment ? <p>Saving from the grid or the token form rewrites the permissions file. Comments and your own layout will be lost.</p> : null}
        {confirm.widens ? <p>This save changes who can reach your data. Check the list before you save.</p> : null}
      </div>
      <ChangeList changes={confirm.changes} />
      {confirm.comment ? (
        <label className="hint">
          <input type="checkbox" checked={remember} onChange={(event) => setRemember(event.target.checked)} /> Do not warn me about comments again in this tab
        </label>
      ) : null}
      <div className="actions">
        <button type="button" className="btn" data-autofocus onClick={workbench.cancelConfirm}>
          Cancel
        </button>
        <button type="button" className="btn danger" onClick={() => workbench.confirmSave(remember)}>
          Save and apply
        </button>
      </div>
    </Dialog>
  );
}

function RestoreConfirm() {
  const workbench = useWorkbench();
  const confirm = workbench.confirm;
  if (confirm?.kind !== 'restore') {
    return null;
  }
  return (
    <Dialog title="Restore this version?" describedBy="restore-text" onClose={workbench.cancelConfirm}>
      <p id="restore-text">
        Restore the version replaced at {shortTime(confirm.entry.at)}? The text in use now is added to the history, so you can undo this.
      </p>
      {confirm.changes === null || widens(confirm.changes) ? (
        <p className="notice warn">This restore changes who can reach your data. Check the list before you restore.</p>
      ) : null}
      <ChangeList changes={confirm.changes} label="What restoring changes" />
      {confirm.dirty ? <p className="notice bad">Your unsaved edits will be discarded.</p> : null}
      <div className="actions">
        <button type="button" className="btn" data-autofocus onClick={workbench.cancelConfirm}>
          Cancel
        </button>
        <button type="button" className="btn danger" onClick={workbench.confirmRestore}>
          Restore
        </button>
      </div>
    </Dialog>
  );
}

export function Confirmations() {
  return (
    <>
      <SaveConfirm />
      <RestoreConfirm />
    </>
  );
}
