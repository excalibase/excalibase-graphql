import { useState } from 'react';
import { adminApi, type ValidateReply } from '../api/adminApi';
import { WarningList } from '../components/SaveBar';
import { ErrorNote } from '../components/ui';
import { messageOf } from '../messages';

export function ValidateTab() {
  const [text, setText] = useState('');
  const [reply, setReply] = useState<ValidateReply | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const run = async () => {
    try {
      setReply(await adminApi.validate(text));
      setFailure(null);
    } catch (error) {
      setReply(null);
      setFailure(messageOf(error));
    }
  };
  return (
    <section className="space-y-3">
      <label htmlFor="candidate" className="block">Text to check</label>
      <textarea id="candidate" rows={14} spellCheck={false} className="field font-mono" placeholder="Paste permissions YAML" value={text} onChange={(event) => setText(event.target.value)} />
      <button type="button" className="btn" onClick={() => void run()}>Validate</button>
      {failure ? <ErrorNote>{failure}</ErrorNote> : null}
      {reply?.valid ? <p role="status" className="text-ok">Valid: {reply.tables} table rules, {reply.functions} functions.</p> : null}
      {reply && !reply.valid ? (
        <ErrorNote><p>Not valid:</p><ul className="ml-5 list-disc">{(reply.errors ?? []).map((message) => <li key={message}>{message}</li>)}</ul></ErrorNote>
      ) : null}
      <WarningList warnings={reply?.warnings} heading="Valid, but the database does not fit everything in it:" />
    </section>
  );
}
