import { useState, type FormEvent } from 'react';
import { ErrorNote } from './ui';

export function Login({ error, onSubmit }: { readonly error: string | null; readonly onSubmit: (secret: string) => Promise<void> }) {
  const [secret, setSecret] = useState('');
  const submit = (event: FormEvent) => { event.preventDefault(); void onSubmit(secret); };
  return (
    <div className="flex h-full items-center justify-center">
      <form onSubmit={submit} className="card grid w-full max-w-sm gap-3 p-6">
        <h1 className="text-xl font-semibold text-accent">Excalibase console</h1>
        <label htmlFor="secret">Admin secret</label>
        <input id="secret" type="password" className="field" autoComplete="off" spellCheck={false} value={secret} onChange={(event) => setSecret(event.target.value)} />
        <button type="submit" className="btn justify-center">Sign in</button>
        {error ? <ErrorNote>{error}</ErrorNote> : null}
      </form>
    </div>
  );
}
