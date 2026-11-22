import { Editor } from '../Editor';
import { useDocument } from '../state/DocumentContext';

export function DocumentTab() {
  const { store, state } = useDocument();
  return (
    <section className="space-y-3">
      {state.meta?.text == null ? <p className="text-danger">No valid document is served yet.</p> : null}
      <Editor key={state.docVersion} initial={state.draft} label="Permissions document" onChange={(text) => store.edit(text)} />
    </section>
  );
}
