import { useState } from 'react';
import { ApiError, validateText, type ValidateReply } from '../api';
import { ThemeChoice } from '../components/ThemeToggle';
import { ErrorList, Fact, PageHeader, Warnings } from '../components/ui';
import { describe, shortTime, UNREACHABLE } from '../lib/outcome';
import { hrefOf } from '../router';
import { useSession } from '../session';
import { useWorkbench } from '../workbench';
import { AuthPage } from './auth/AuthPage';
import { useAuthSettings } from './auth/useAuthSettings';

export function CheckResult({ reply }: { reply: ValidateReply }) {
  if (reply.valid) {
    return (
      <div className="notice ok" role="status">
        <p>
          This text is fine: {reply.tables ?? 0} table rules, {reply.functions ?? 0} functions.
        </p>
        <Warnings warnings={reply.warnings} heading="It is valid, but your database does not fit everything in it:" />
      </div>
    );
  }
  return <ErrorList heading="This text is not valid:" errors={reply.errors ?? []} />;
}

function CheckText() {
  const { expired } = useSession();
  const [text, setText] = useState('');
  const [reply, setReply] = useState<ValidateReply | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function check() {
    setBusy(true);
    setError(null);
    try {
      setReply(await validateText(text));
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        expired();
        return;
      }
      setReply(null);
      setError(failure instanceof ApiError ? describe(failure) : UNREACHABLE);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card" aria-labelledby="check-title">
      <h2 id="check-title">Check a permissions text</h2>
      <p className="hint">Paste a permissions file to see what is wrong with it. Nothing is saved or applied.</p>
      <div className="field">
        <label htmlFor="candidate">Text to check</label>
        <textarea id="candidate" className="textarea" rows={10} spellCheck={false} placeholder="Paste permissions YAML" value={text} onChange={(event) => setText(event.target.value)} />
      </div>
      <div className="actions">
        <button type="button" className="btn primary" disabled={busy || text.trim() === ''} onClick={check}>
          {busy ? 'Checking…' : 'Check'}
        </button>
      </div>
      {error ? <p role="alert" className="notice bad">{error}</p> : null}
      {reply ? <CheckResult reply={reply} /> : null}
    </section>
  );
}

function Tabs({ view, auth }: { view: 'general' | 'auth'; auth: boolean }) {
  if (!auth) {
    return null;
  }
  return (
    <nav aria-label="Settings views" className="tabs">
      <a className="tab" href={hrefOf('settings')} aria-current={view === 'general' ? 'page' : undefined}>
        General
      </a>
      <a className="tab" href={hrefOf('settings', 'auth')} aria-current={view === 'auth' ? 'page' : undefined}>
        Auth
      </a>
    </nav>
  );
}

function General() {
  const { meta } = useWorkbench();
  return (
    <>
      <section className="card" aria-labelledby="engine-title">
        <h2 id="engine-title">This engine</h2>
        {meta ? (
          <dl className="facts">
            <Fact label="Permissions come from" value={meta.source} />
            <Fact label="Version in use" value={meta.version} />
            <Fact label="Saved version" value={meta.sourceVersion} />
            <Fact label="Can be changed here" value={meta.writable ? 'Yes' : 'No, read-only'} />
            <Fact label="Last refused text" value={meta.lastRefusal ? `${meta.lastRefusal} (${meta.lastRefusalAt ? shortTime(meta.lastRefusalAt) : ''})` : null} />
          </dl>
        ) : (
          <p className="hint" role="status">Loading…</p>
        )}
      </section>
      <section className="card">
        <ThemeChoice />
      </section>
      <CheckText />
    </>
  );
}

export function SettingsPage({ arg = '' }: { arg?: string }) {
  const { signOut } = useSession();
  const auth = useAuthSettings();
  const settings = auth.data;
  const view = arg === 'auth' && settings ? 'auth' : 'general';
  return (
    <>
      <PageHeader trail={view === 'auth' ? ['Settings', 'Auth'] : ['Settings']}>
        <button type="button" className="btn" onClick={signOut}>
          Sign out
        </button>
      </PageHeader>
      <div className="content">
        <div>
          <h1>{view === 'auth' ? 'Auth' : 'Settings'}</h1>
          <p className="muted">
            {view === 'auth' ? 'How the engine checks tokens, and a way to try one.' : 'Where the permissions come from and how this console looks.'}
          </p>
        </div>
        <Tabs view={view} auth={settings !== null} />
        {view === 'auth' && settings ? <AuthPage settings={settings} reload={auth.reload} /> : <General />}
      </div>
    </>
  );
}
