import { useEffect, useState } from 'react';
import { useDocument } from '../../workbench';

/**
 * Offered only when the document's jwt: section says exactly what the engine's settings say. One click takes the
 * section out and saves, through the same confirmation as any save. It refuses to run beside unsaved edits, which the
 * same save would otherwise write too.
 */
export function RemoveJwt({ onStart, onSaved }: { onStart: () => void; onSaved: () => void }) {
  const workbench = useDocument();
  const [removing, setRemoving] = useState(false);
  const { tree, treeFor, draft, dirty, busy, meta, outcome, removeJwt, save } = workbench;
  const ready = tree !== null && treeFor === draft;
  const writable = meta?.writable === true;

  useEffect(() => {
    if (removing && dirty && tree !== null && !('jwt' in tree) && !busy) {
      setRemoving(false);
      void save();
    }
  }, [removing, dirty, tree, busy, save]);

  useEffect(() => {
    if (outcome?.kind === 'saved') {
      onSaved();
    }
  }, [outcome, onSaved]);

  const blocked = !writable || !ready || (dirty && !removing);
  return (
    <section className="card form" aria-labelledby="remove-jwt-title">
      <h2 id="remove-jwt-title">Remove jwt: from the document</h2>
      <p>
        The engine accepts a jwt: section next to its own settings only when it says exactly the same, so you can move without a gap. It does,
        so you can take it out now. Do this only when every replica runs a version that has these settings: a replica still on an older version
        loses its token checking when the section goes, and rejects every token until it is upgraded.
      </p>
      {dirty && !removing ? <p className="hint">You have unsaved permission edits. Save or discard them first, so this does not save them too.</p> : null}
      {writable ? null : <p className="hint">This source is read-only. Remove the section where the document is kept.</p>}
      <div className="actions">
        <button
          type="button"
          className="btn primary"
          disabled={blocked || removing}
          onClick={() => {
            setRemoving(true);
            onStart();
            removeJwt();
          }}
        >
          Remove jwt: from the document
        </button>
      </div>
    </section>
  );
}
