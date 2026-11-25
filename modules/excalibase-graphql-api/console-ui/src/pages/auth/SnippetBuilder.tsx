import { useState } from 'react';
import type { AuthSettings } from '../../api';
import { Field } from '../../components/ui';
import { emptyForm, envText, formFrom, k8sText, SECRET_PLACEHOLDER, type EnvForm } from '../../lib/envSnippet';
import { claimPaths, decodeToken, extraName, type ClaimPath } from '../../lib/token';

type Pick = 'role' | 'userId' | 'extra';

function Claims({ claims, onPick }: { claims: ClaimPath[]; onPick: (kind: Pick, path: string) => void }) {
  return (
    <div className="scroll">
      <table className="tbl" aria-label="Claims in the token">
        <thead>
          <tr>
            <th scope="col">Claim</th>
            <th scope="col">Value</th>
            <th scope="col">Use as</th>
          </tr>
        </thead>
        <tbody>
          {claims.map((claim) => (
            <tr key={claim.path}>
              <td className="mono">{claim.path}</td>
              <td className="mono">{claim.preview}</td>
              <td>
                <div className="actions">
                  <button type="button" className="btn" aria-label={`Use ${claim.path} as the role`} onClick={() => onPick('role', claim.path)}>
                    Role
                  </button>
                  <button type="button" className="btn" aria-label={`Use ${claim.path} as the user id`} onClick={() => onPick('userId', claim.path)}>
                    User id
                  </button>
                  <button type="button" className="btn" aria-label={`Pass ${claim.path} on as an extra value`} onClick={() => onPick('extra', claim.path)}>
                    Extra
                  </button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Reads a sample token in the browser to pick claim names. The token is emptied at once and never sent. */
function SampleToken({ onPick }: { onPick: (kind: Pick, path: string) => void }) {
  const [token, setToken] = useState('');
  const [claims, setClaims] = useState<ClaimPath[]>([]);
  const [problem, setProblem] = useState<string | null>(null);

  function read() {
    try {
      setClaims(claimPaths(decodeToken(token)));
      setProblem(null);
    } catch (failure) {
      setClaims([]);
      setProblem(failure instanceof Error ? failure.message : 'That token could not be read.');
    }
    setToken('');
  }

  return (
    <div className="form">
      <div className="field">
        <label htmlFor="sample-token">A sample token</label>
        <textarea id="sample-token" className="textarea" rows={3} spellCheck={false} autoComplete="off" placeholder="Paste a sample token to pick the claims. It is read here and never sent or kept." value={token} onChange={(event) => setToken(event.target.value)} />
      </div>
      <div className="actions">
        <button type="button" className="btn" disabled={token.trim() === ''} onClick={read}>
          Read claims
        </button>
      </div>
      {problem ? <p role="alert" className="notice bad">{problem}</p> : null}
      {claims.length > 0 ? <Claims claims={claims} onPick={onPick} /> : null}
    </div>
  );
}

function ExtraList({ form, onRemove }: { form: EnvForm; onRemove: (name: string) => void }) {
  const extra = Object.entries(form.extra);
  if (extra.length === 0) {
    return <p className="hint">No extra values are passed on.</p>;
  }
  return (
    <ul>
      {extra.map(([name, path]) => (
        <li key={name}>
          <span className="mono">X-Excalibase-{name}</span> from <span className="mono">{path}</span>{' '}
          <button type="button" className="btn quiet" onClick={() => onRemove(name)}>
            Remove
          </button>
        </li>
      ))}
    </ul>
  );
}

/** Writes the engine variables for these values. Nothing is saved: the person puts the lines where the engine is configured. */
export function SnippetBuilder({ settings }: { settings: AuthSettings }) {
  const [form, setForm] = useState<EnvForm>(() => formFrom(settings));
  const set = (key: Exclude<keyof EnvForm, 'extra' | 'keys'>) => (value: string) => setForm((current) => ({ ...current, [key]: value }));
  const pick = (kind: Pick, path: string) =>
    setForm((current) => (kind === 'extra' ? { ...current, extra: { ...current.extra, [extraName(path)]: path } } : { ...current, [kind]: path }));
  const remove = (name: string) =>
    setForm((current) => ({ ...current, extra: Object.fromEntries(Object.entries(current.extra).filter(([key]) => key !== name)) }));
  const text = envText(form);

  return (
    <section className="card form" aria-labelledby="snippet-title">
      <h2 id="snippet-title">Write the engine settings</h2>
      <p className="hint">
        Fill in what your tokens look like and copy the lines below into where the engine is configured. Nothing is saved here. The secret is never
        shown or asked for: replace {SECRET_PLACEHOLDER} with your own.
      </p>
      <SampleToken onPick={pick} />
      <fieldset className="field">
        <legend>Tokens are signed with</legend>
        <label>
          <input type="radio" name="keys" checked={form.keys === 'secret'} onChange={() => setForm((current) => ({ ...current, keys: 'secret' }))} /> A shared secret
        </label>{' '}
        <label>
          <input type="radio" name="keys" checked={form.keys === 'provider'} onChange={() => setForm((current) => ({ ...current, keys: 'provider' }))} /> An identity provider
        </label>
      </fieldset>
      {form.keys === 'provider' ? (
        <>
          <Field id="snippet-issuer" label="Issuer" value={form.issuer} onChange={set('issuer')} />
          <Field id="snippet-jwks" label="Key set address (JWKS URL)" value={form.jwksUrl} onChange={set('jwksUrl')} />
        </>
      ) : null}
      <Field id="snippet-audience" label="Audience (comma separated)" value={form.audience} onChange={set('audience')} />
      <Field id="snippet-algorithms" label="Algorithms (comma separated)" value={form.algorithms} onChange={set('algorithms')} />
      <Field id="snippet-role" label="Role claim" value={form.role} onChange={set('role')} />
      <Field id="snippet-default-role" label="Default role (when the role claim is a list)" value={form.defaultRole} onChange={set('defaultRole')} />
      <Field id="snippet-user" label="User id claim" value={form.userId} onChange={set('userId')} />
      <ExtraList form={form} onRemove={remove} />
      <h3>Environment variables</h3>
      <pre className="codebox" aria-label="Environment variables" tabIndex={0}>{text}</pre>
      <h3>Kubernetes env block</h3>
      <pre className="codebox" aria-label="Kubernetes env block" tabIndex={0}>{k8sText(form)}</pre>
      <div className="actions">
        <button type="button" className="btn quiet" onClick={() => setForm(formFrom(settings))}>
          Start from the values in use
        </button>
        <button type="button" className="btn quiet" onClick={() => setForm(emptyForm())}>
          Clear
        </button>
      </div>
    </section>
  );
}
