import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { adminApi, type HistoryText } from '../api/adminApi';
import { HistoryMissed } from '../components/SaveBar';
import { ErrorNote } from '../components/ui';
import { saveAs } from '../download';
import { lineDiff, withContext, type ShownLine } from '../model/diff';
import { exportName } from '../model/docmodel';
import { messageOf } from '../messages';
import { useDocument } from '../state/DocumentContext';
import { shortTime } from '../time';

const MAX_DRAWN = 5000;
const MARKS = { add: '+ ', del: '- ', same: '  ' } as const;

function DiffLine({ line }: { readonly line: ShownLine }) {
  if (line.type === 'gap') {
    return <div className="line gap px-3 opacity-60">... {line.count} unchanged lines</div>;
  }
  const tone = line.type === 'add' ? 'line add diff-add' : line.type === 'del' ? 'line del diff-del' : 'line';
  return <div className={tone + ' px-3'}>{MARKS[line.type] + line.text}</div>;
}

/** The old version against the text served now. A diff that would draw too many lines is not drawn. */
function DiffView({ entry, current }: { readonly entry: HistoryText; readonly current: string }) {
  const lines = lineDiff(entry.text, current);
  if (lines.every((line) => line.type === 'same')) {
    return <p id="diff-same">This version is the same as the text served now.</p>;
  }
  const shown = withContext(lines);
  if (shown.length > MAX_DRAWN) {
    const offer = (label: string, text: string) => { const { filename, type } = exportName(text, label); saveAs(filename, text, type); };
    return (
      <div id="diff-large" className="space-y-2">
        <p>This diff is too large to show here. Save both versions as files and compare them there.</p>
        <div className="flex gap-2">
          <button type="button" id="save-old" className="btn-quiet" onClick={() => offer('old', entry.text)}>Save this version as a file</button>
          <button type="button" id="save-current" className="btn-quiet" onClick={() => offer('current', current)}>Save the served text as a file</button>
        </div>
      </div>
    );
  }
  return (
    <div>
      <p className="hint mb-1">Lines marked - are only in this old version. Lines marked + are only in the text served now.</p>
      <pre id="diff" className="card overflow-auto whitespace-pre py-2 font-mono text-xs">{shown.map((line, index) => <DiffLine key={index} line={line} />)}</pre>
    </div>
  );
}

export function HistoryTab() {
  const { store, state } = useDocument();
  const [selected, setSelected] = useState<HistoryText | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const note = state.historyNote;
  useEffect(() => { if (note?.kind === 'ok') { setSelected(null); } }, [note]);
  const list = useQuery({ queryKey: ['history', state.meta?.sourceVersion], queryFn: () => adminApi.history(), retry: false });

  if (list.isError) {
    return <ErrorNote>{messageOf(list.error)}</ErrorNote>;
  }
  if (!list.data) {
    return <p className="hint">Reading the history...</p>;
  }
  if (!list.data.available) {
    return <p id="history-unavailable">History is available for the file source.</p>;
  }
  if (list.data.readable === false) {
    return (
      <ErrorNote>
        The history could not be read, so no versions are listed. Saves still go ahead; check the file next to permissions.yaml.
      </ErrorNote>
    );
  }
  const choose = async (id: number | string) => {
    try {
      setSelected(await adminApi.historyEntry(id));
      setFailure(null);
      store.clearNote();
    } catch (error) {
      setFailure(messageOf(error));
    }
  };
  const entries = list.data.entries;
  return (
    <section className="space-y-4">
      <p className="hint">Each save adds the text it replaced, comments included. The last 20 versions are kept. Edits made to the file outside the console are not recorded.</p>
      {failure ? <ErrorNote>{failure}</ErrorNote> : null}
      {note?.kind === 'ok' ? (
        <div><p role="status" className="text-ok">{note.text}</p>{note.historyMissed ? <HistoryMissed /> : null}</div>
      ) : null}
      {note?.kind === 'error' ? (
        <ErrorNote><p>{note.text}</p>{note.errors ? <ul className="ml-5 list-disc">{note.errors.map((m) => <li key={m}>{m}</li>)}</ul> : null}</ErrorNote>
      ) : null}
      {entries.length === 0 ? <p>Nothing has been replaced yet.</p> : (
        <div className="card overflow-x-auto">
          <table id="history-list" className="w-full">
            <thead><tr><th className="cell">Replaced at</th><th className="cell">Version</th><th className="cell">Size</th><th className="cell" /></tr></thead>
            <tbody>
              {entries.map((entry) => (
                <tr key={entry.id} className={selected?.id === entry.id ? 'bg-accent-wash' : ''}>
                  <td className="cell">{shortTime(entry.at)}</td>
                  <td className="cell font-mono text-xs">{entry.hash.slice(0, 12)}</td>
                  <td className="cell">{entry.size} bytes</td>
                  <td className="cell">
                    <button type="button" className="btn-quiet" aria-label={'View the version replaced at ' + shortTime(entry.at)} onClick={() => void choose(entry.id)}>View</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {selected ? (
        <section id="history-entry" className="space-y-3">
          <h2 className="text-base font-semibold">Version replaced at {shortTime(selected.at)}</h2>
          <DiffView entry={selected} current={state.meta?.text ?? ''} />
          <button type="button" id="restore" className="btn" onClick={() => void store.askRestore(selected)}>Restore this version</button>
        </section>
      ) : null}
    </section>
  );
}
