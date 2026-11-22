import clsx from 'clsx';
import type { Change } from '../model/docmodel';

/** What a save or a restore changes. Red: access gained. Amber: a rule or the token settings rewritten. Else plain. */
export function ChangeList({ changes, id }: { readonly changes: Change[] | null; readonly id: string }) {
  if (changes === null) {
    return <p className="hint" id={id}>The list of changes could not be built.</p>;
  }
  if (changes.length === 0) {
    return <p className="hint" id={id}>No role or table gets or loses an operation.</p>;
  }
  return (
    <ul id={id} aria-label="What this changes" className="my-3 space-y-1">
      {changes.map((change) => (
        <li
          key={change.text}
          className={clsx(
            'list-none',
            change.gained.length > 0 && 'gain font-semibold text-danger',
            change.gained.length === 0 && (change.changed.length > 0 || change.other === true) && 'modify font-semibold text-warn',
          )}
        >
          {change.text}
        </li>
      ))}
    </ul>
  );
}
