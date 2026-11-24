import { useState, type FormEvent } from 'react';
import { ApiError, signIn } from '../api';
import { describe, UNREACHABLE } from '../lib/outcome';

export function LoginPage({ notice, onSignedIn }: { notice: string | null; onSignedIn: () => void }) {
  const [secret, setSecret] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(secret);
      setSecret('');
      onSignedIn();
    } catch (failure) {
      setError(failure instanceof ApiError ? describe(failure) : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login" id="content">
      <form className="card" onSubmit={submit}>
        <div className="brand">
          <div className="logo" aria-hidden="true">
            E
          </div>
          <h1>Excalibase console</h1>
        </div>
        <p className="muted">Sign in with the admin secret you set when you started the engine.</p>
        <div className="field">
          <label htmlFor="secret">Admin secret</label>
          <input
            id="secret"
            className="input"
            type="password"
            autoComplete="off"
            spellCheck={false}
            value={secret}
            onChange={(event) => setSecret(event.target.value)}
            aria-describedby={error || notice ? 'login-message' : undefined}
          />
        </div>
        <button type="submit" className="btn primary" disabled={busy || secret === ''}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        {error || notice ? (
          <p id="login-message" role="alert" className="notice bad">
            {error ?? notice}
          </p>
        ) : null}
      </form>
    </main>
  );
}
