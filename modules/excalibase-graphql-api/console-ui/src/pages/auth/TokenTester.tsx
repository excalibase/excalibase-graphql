import { useState } from 'react';
import { ApiError, checkToken, type TokenCheckReply } from '../../api';
import { REASON_TEXT, REASON_UNKNOWN } from '../../lib/authText';
import { describe, UNREACHABLE } from '../../lib/outcome';
import { useSession } from '../../session';

function Result({ reply }: { reply: TokenCheckReply }) {
  if (!reply.valid) {
    return (
      <div role="alert" className="notice bad">
        <p>{REASON_TEXT[reply.reason ?? ''] ?? REASON_UNKNOWN}</p>
        <p className="hint">
          Reason: <span className="mono">{reply.reason}</span>
        </p>
      </div>
    );
  }
  const variables = Object.entries(reply.sessionVariables ?? {});
  return (
    <div role="status" className="notice ok">
      <p>The engine accepts this token.</p>
      <dl className="facts">
        <div className="field">
          <dt className="label">Acts as role</dt>
          <dd className="mono">{reply.role}</dd>
        </div>
        <div className="field">
          <dt className="label">May act as</dt>
          <dd className="mono">{(reply.allowedRoles ?? []).join(', ')}</dd>
        </div>
        <div className="field">
          <dt className="label">User id</dt>
          <dd className="mono">{reply.userId}</dd>
        </div>
      </dl>
      <table className="tbl" aria-label="Values a rule can use">
        <thead>
          <tr>
            <th scope="col">Value</th>
            <th scope="col">Is</th>
          </tr>
        </thead>
        <tbody>
          {variables.map(([name, value]) => (
            <tr key={name}>
              <td className="mono">{name}</td>
              <td className="mono">{value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Paste a token to see whether the engine accepts it. The token lives in this component's state and nowhere else: it
 * is emptied the moment it is sent, never written to storage, a draft or a history, and the engine answers a reason.
 */
export function TokenTester() {
  const { expired } = useSession();
  const [token, setToken] = useState('');
  const [reply, setReply] = useState<TokenCheckReply | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function test() {
    const sent = token;
    setToken('');
    setBusy(true);
    setError(null);
    setReply(null);
    try {
      setReply(await checkToken(sent));
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        expired();
        return;
      }
      setError(failure instanceof ApiError ? describe(failure) : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card form" aria-labelledby="test-token-title">
      <h2 id="test-token-title">Test a token</h2>
      <p className="hint">
        Paste a token to see whether this engine accepts it. It is sent once to the engine, checked and dropped: this page does not keep it,
        and the engine does not log or store it.
      </p>
      <div className="field">
        <label htmlFor="token-to-test">Token to test</label>
        <textarea id="token-to-test" className="textarea" rows={4} spellCheck={false} autoComplete="off" value={token} onChange={(event) => setToken(event.target.value)} />
      </div>
      <div className="actions">
        <button type="button" className="btn primary" disabled={busy || token.trim() === ''} onClick={test}>
          {busy ? 'Testing…' : 'Test this token'}
        </button>
      </div>
      {error ? <p role="alert" className="notice bad">{error}</p> : null}
      {reply ? <Result reply={reply} /> : null}
    </section>
  );
}
