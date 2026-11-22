import { useDocument } from '../state/DocumentContext';
import { RestoreDialog, SaveDialog } from './Dialogs';

/** The one open dialog of the document store, if any. */
export function DialogHost() {
  const { store, state } = useDocument();
  const dialog = state.dialog;
  if (!dialog) {
    return null;
  }
  const handlers = { onConfirm: (remember: boolean) => void store.confirm(remember), onCancel: () => store.cancel() };
  return dialog.kind === 'save'
    ? <SaveDialog changes={dialog.changes} comment={dialog.comment} widens={dialog.widens} {...handlers} />
    : <RestoreDialog entry={dialog.entry} dirty={dialog.dirty} changes={dialog.changes} {...handlers} />;
}
