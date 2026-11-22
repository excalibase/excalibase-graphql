import { Suspense, lazy } from 'react';
import { useDocument } from '../state/DocumentContext';

const Editor = lazy(() => import('../Editor').then((module) => ({ default: module.Editor })));

export function DocumentTab() {
  const { store, state } = useDocument();
  return (
    <section className="space-y-3">
      {state.meta?.text == null ? <p className="text-danger">No valid document is served yet.</p> : null}
      <Suspense fallback={<p className="hint">Loading the editor...</p>}>
        <Editor key={state.docVersion} initial={state.draft} label="Permissions document" onChange={(text) => store.edit(text)} />
      </Suspense>
    </section>
  );
}
