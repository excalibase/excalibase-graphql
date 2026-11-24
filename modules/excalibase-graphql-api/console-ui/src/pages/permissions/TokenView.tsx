import { useState } from 'react';
import { Loading } from '../../components/ui';
import { buildSection, claimPaths, decodeToken, extraName, formFrom, type ClaimPath, type TokenForm } from '../../lib/token';
import { hrefOf, navigate } from '../../router';
import { useWorkbench } from '../../workbench';

interface FieldProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}

function Field({ id, label, value, onChange }: FieldProps) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} className="input" type="text" autoComplete="off" spellCheck={false} value={value} onChange={(event) => onChange(event.target.value)} />
    </div>
  );
}

/**
 * The sample-token flow: paste a token, see its claims, say which one is the role and which the user id, and fill in
 * the issuer, audience and key source. The token lives in this view only: it is never sent and never kept.
 */
export function TokenView() {
  const workbench = useWorkbench();
  const doc = workbench.tree;
  const ready = doc !== null && workbench.treeFor === workbench.draft;
  const [form, setForm] = useState<TokenForm>(() => formFrom(doc?.jwt));
  const [token, setToken] = useState('');
  const [claims, setClaims] = useState<ClaimPath[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const [loadedFor, setLoadedFor] = useState<string | null>(ready ? workbench.draft : null);

  if (!ready) {
    if (workbench.treeFor === workbench.draft) {
      return (
        <div role="alert" className="notice bad">
          <p>The token form needs a valid permissions text. Fix it in the YAML view first.</p>
          <a className="btn" href={hrefOf('permissions', 'yaml')}>
            Open the YAML view
          </a>
        </div>
      );
    }
    return <Loading label="Reading the permissions" />;
  }
  if (loadedFor === null) {
    setForm(formFrom(doc.jwt));
    setLoadedFor(workbench.draft);
  }

  const set = (key: keyof Omit<TokenForm, 'extra'>) => (value: string) => setForm((current) => ({ ...current, [key]: value }));
  const pick = (key: 'role' | 'userId' | 'extra', path: string) =>
    setForm((current) => (key === 'extra' ? { ...current, extra: { ...current.extra, [extraName(path)]: path } } : { ...current, [key]: path }));

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
    <section aria-label="Sample token" className="content">
      <div>
        <h1>Sample token</h1>
        <p className="muted">Paste a token your app signs in with, then pick which claim is the role and which is the user id.</p>
      </div>
      <div className="card form">
        <div className="field">
          <label htmlFor="sample-token">A sample token</label>
          <textarea id="sample-token" className="textarea" rows={4} spellCheck={false} autoComplete="off" placeholder="Paste a sample token. It is read here and never sent or kept." value={token} onChange={(event) => setToken(event.target.value)} />
        </div>
        <div className="actions">
          <button type="button" className="btn" disabled={token.trim() === ''} onClick={read}>
            Read claims
          </button>
        </div>
        {problem ? <p role="alert" className="notice bad">{problem}</p> : null}
        {claims.length > 0 ? (
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
                        <button type="button" className="btn" aria-label={`Use ${claim.path} as the role`} onClick={() => pick('role', claim.path)}>
                          Role
                        </button>
                        <button type="button" className="btn" aria-label={`Use ${claim.path} as the user id`} onClick={() => pick('userId', claim.path)}>
                          User id
                        </button>
                        <button type="button" className="btn" aria-label={`Pass ${claim.path} on as an extra value`} onClick={() => pick('extra', claim.path)}>
                          Extra
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}
      </div>
      <div className="card form">
        <h2>Token settings</h2>
        <Field id="jwt-role" label="Role claim" value={form.role} onChange={set('role')} />
        <Field id="jwt-default-role" label="Default role (when the role claim is a list)" value={form.defaultRole} onChange={set('defaultRole')} />
        <Field id="jwt-user" label="User id claim" value={form.userId} onChange={set('userId')} />
        {Object.keys(form.extra).length === 0 ? (
          <p className="hint">No extra values are passed on.</p>
        ) : (
          <ul>
            {Object.entries(form.extra).map(([name, path]) => (
              <li key={name}>
                <span className="mono">X-Excalibase-{name}</span> from <span className="mono">{path}</span>{' '}
                <button type="button" className="btn quiet" onClick={() => setForm((current) => ({ ...current, extra: Object.fromEntries(Object.entries(current.extra).filter(([key]) => key !== name)) }))}>
                  Remove
                </button>
              </li>
            ))}
          </ul>
        )}
        <Field id="jwt-issuer" label="Issuer" value={form.issuer} onChange={set('issuer')} />
        <Field id="jwt-audience" label="Audience" value={form.audience} onChange={set('audience')} />
        <Field id="jwt-jwks" label="Key set address (JWKS URL)" value={form.jwksUrl} onChange={set('jwksUrl')} />
        <Field id="jwt-secret" label="Secret reference (env:NAME or file:PATH, never the secret itself)" value={form.secret} onChange={set('secret')} />
        <div className="actions">
          <button
            type="button"
            className="btn primary"
            onClick={() => {
              workbench.applyToken(buildSection(form));
              navigate('permissions', 'yaml');
            }}
          >
            Apply to the document
          </button>
        </div>
      </div>
    </section>
  );
}
