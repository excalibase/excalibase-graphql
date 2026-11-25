import { useMemo } from 'react';
import { ApiError, getHistory, getHistoryEntry, type HistoryEntry } from '../../api';
import { Empty, ErrorList, ErrorState, Loading, Warnings } from '../../components/ui';
import { lineDiff, withContext, type DiffLine, type Gap } from '../../lib/diff';
import { describe, shortTime, UNREACHABLE } from '../../lib/outcome';
import { useRequest, useSession } from '../../session';
import { useWorkbench } from '../../workbench';
import { useState } from 'react';

const MAX_DRAWN = 5000;
const MARKS = { add: '+ ', del: '- ', same: '  ' } as const;

function Diff({ old, current }: { old: HistoryEntry & { text: string }; current: string }) {
  const { saveVersionFile } = useWorkbench();
  const lines = useMemo(() => lineDiff(old.text, current), [old.text, current]);
  if (lines.every((line) => line.type === 'same')) {
    return <p id="diff-same">This version is the same as the text in use now.</p>;
  }
  const shown = withContext(lines);
  if (shown.length > MAX_DRAWN) {
    return (
      <div id="diff-large">
        <p>This difference is too large to show here. Save both versions as files and compare them there.</p>
        <div className="actions">
          <button type="button" className="btn" onClick={() => saveVersionFile('old', old.text)}>
            Save this version as a file
          </button>
          <button type="button" className="btn" onClick={() => saveVersionFile('current', current)}>
            Save the text in use as a file
          </button>
        </div>
      </div>
    );
  }
  return (
    <div>
      <p className="hint">Lines marked - are only in this old version. Lines marked + are only in the text in use now.</p>
      <pre id="diff" className="codebox diff" tabIndex={0} aria-label="Difference between the versions">
        {shown.map((entry: DiffLine | Gap, index) =>
          entry.type === 'gap' ? (
            <div key={index} className="line gap">
              … {entry.count} unchanged lines
            </div>
          ) : (
            <div key={index} className={`line ${entry.type}`}>
              {MARKS[entry.type]}
              {entry.text}
            </div>
          ),
        )}
      </pre>
    </div>
  );
}

export function HistoryView() {
  const workbench = useWorkbench();
  const { expired } = useSession();
  const request = useRequest(getHistory, [workbench.historyStamp]);
  const [problem, setProblem] = useState<string | null>(null);
  const selected = workbench.historySelected;
  const note = workbench.historyNote;

  async function view(id: string) {
    setProblem(null);
    try {
      workbench.selectVersion(await getHistoryEntry(id));
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        expired();
        return;
      }
      setProblem(failure instanceof ApiError ? describe(failure) : UNREACHABLE);
    }
  }

  if (request.status === 'loading' && !request.data) {
    return <Loading label="Loading the history" />;
  }
  if (request.status === 'error' || !request.data) {
    return <ErrorState message={request.error ?? UNREACHABLE} onRetry={request.reload} />;
  }
  const list = request.data;
  return (
    <section aria-label="Version history" className="content">
      <div>
        <h1>Version history</h1>
        <p className="muted">Each save adds the text it replaced, comments included. The last 20 versions are kept. Edits made to the file outside this console are not recorded.</p>
      </div>
      {note?.ok ? (
        <div>
          <p className="notice ok" role="status">{note.text}</p>
          {note.historyMissed ? <p className="notice warn" role="alert">Restored, but this version could not be added to the history.</p> : null}
          <Warnings warnings={note.warnings} heading="Restored, but your database does not fit everything in it:" />
        </div>
      ) : null}
      {note && !note.ok ? <ErrorList heading={note.text} errors={note.errors ?? []} /> : null}
      {problem ? <p role="alert" className="notice bad">{problem}</p> : null}
      {!list.available ? (
        <Empty title="No history for this source">
          <p id="history-unavailable">History is kept when the permissions come from a file.</p>
        </Empty>
      ) : list.readable === false ? (
        <p id="history-unreadable" role="alert" className="notice bad">
          The history could not be read, so no versions are listed. Saving still works. Check the folder next to permissions.yaml.
        </p>
      ) : list.entries.length === 0 ? (
        <Empty title="Nothing has been replaced yet">
          <p>Once you save a change, the version it replaced shows up here.</p>
        </Empty>
      ) : (
        <div className="card flush scroll">
          <table className="tbl" id="history-list">
            <thead>
              <tr>
                <th scope="col">Replaced at</th>
                <th scope="col">Version</th>
                <th scope="col">Size</th>
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {list.entries.map((entry) => (
                <tr key={entry.id} className={selected?.id === entry.id ? 'sel' : ''}>
                  <td>{shortTime(entry.at)}</td>
                  <td className="mono">{entry.hash.slice(0, 12)}</td>
                  <td>{entry.size} bytes</td>
                  <td>
                    <button type="button" className="btn" aria-label={`View the version replaced at ${shortTime(entry.at)}`} onClick={() => void view(entry.id)}>
                      View
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {selected ? (
        <section id="history-entry" className="card" aria-label="The version you picked">
          <h2>Version replaced at {shortTime(selected.at)}</h2>
          <Diff old={selected} current={workbench.meta?.text ?? ''} />
          <div className="actions">
            <button type="button" id="restore" className="btn primary" disabled={workbench.meta?.writable === false} onClick={() => void workbench.askRestore(selected)}>
              Restore this version
            </button>
          </div>
        </section>
      ) : null}
    </section>
  );
}
