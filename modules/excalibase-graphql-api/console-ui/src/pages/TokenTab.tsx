import { useEffect, useRef, useState } from 'react';
import { ErrorNote } from '../components/ui';
import { buildSection, claimPaths, decodeToken, extraName, formFrom, type ClaimPath, type JwtForm } from '../model/jwt';
import { useDocument } from '../state/DocumentContext';

type Pick = 'role' | 'userId' | 'extra';

function Row({ id, label, form, field, onChange }: { id: string; label: string; form: JwtForm; field: Exclude<keyof JwtForm, 'extra'>; onChange: (field: keyof JwtForm, value: string) => void }) {
  return (
    <div className="grid max-w-lg gap-1">
      <label htmlFor={id}>{label}</label>
      <input id={id} className="field" autoComplete="off" spellCheck={false} value={form[field]} onChange={(event) => onChange(field, event.target.value)} />
    </div>
  );
}

/**
 * The sample-token flow: paste a token, see its claims, click which one is the role and which the user id, and fill in
 * the issuer, audience and key source. The token is read in this form and never leaves it.
 */
export function TokenTab() {
  const { store, state } = useDocument();
  const [form, setForm] = useState<JwtForm | null>(null);
  const [claims, setClaims] = useState<ClaimPath[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const [token, setToken] = useState('');
  const started = useRef(false);

  useEffect(() => {
    if (!started.current && state.tree) {
      started.current = true;
      setForm(formFrom(state.tree.jwt));
    } else if (!state.tree) {
      void store.readTree();
    }
  }, [state.tree, store]);

  if (!form) {
    return state.treeErrors.length > 0
      ? <ErrorNote><p>The token form needs a valid text. Fix it on the Document tab:</p><ul className="ml-5 list-disc">{state.treeErrors.map((m) => <li key={m}>{m}</li>)}</ul></ErrorNote>
      : <p className="hint">Reading the document...</p>;
  }
  const change = (field: keyof JwtForm, value: string) => setForm({ ...form, [field]: value });
  const decode = () => {
    try {
      setClaims(claimPaths(decodeToken(token)));
      setProblem(null);
    } catch (failure) {
      setClaims([]);
      setProblem(failure instanceof Error ? failure.message : 'That token is not readable.');
    }
    setToken('');
  };
  const pick = (use: Pick, path: string) =>
    setForm(use === 'extra' ? { ...form, extra: { ...form.extra, [extraName(path)]: path } } : { ...form, [use]: path });
  const removeExtra = (name: string) =>
    setForm({ ...form, extra: Object.fromEntries(Object.entries(form.extra).filter(([key]) => key !== name)) });
  const apply = async () => {
    if (await store.applyJwt(buildSection(form))) {
      window.location.hash = '#/permissions/document';
    }
  };
  return (
    <section className="space-y-3">
      <h2 className="text-base font-semibold">Sample token</h2>
      <textarea
        id="sample-token" aria-label="Paste a sample token" rows={4} spellCheck={false} autoComplete="off" className="field font-mono"
        placeholder="Paste a sample token. It is read here and never sent or kept." value={token} onChange={(event) => setToken(event.target.value)}
      />
      <button type="button" id="decode" className="btn" onClick={decode}>Read claims</button>
      {problem ? <ErrorNote>{problem}</ErrorNote> : null}
      {claims.length > 0 ? (
        <div className="card overflow-x-auto">
          <table className="w-full">
            <thead><tr><th className="cell">Claim</th><th className="cell">Value</th><th className="cell">Use as</th></tr></thead>
            <tbody>
              {claims.map((claim) => (
                <tr key={claim.path}>
                  <td className="cell">{claim.path}</td>
                  <td className="cell">{claim.preview}</td>
                  <td className="cell space-x-2">
                    <button type="button" className="btn-quiet" onClick={() => pick('role', claim.path)}>Role</button>
                    <button type="button" className="btn-quiet" onClick={() => pick('userId', claim.path)}>User id</button>
                    <button type="button" className="btn-quiet" onClick={() => pick('extra', claim.path)}>Extra</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      <h2 className="pt-2 text-base font-semibold">Token settings</h2>
      <Row id="jwt-role" label="Role claim" form={form} field="role" onChange={change} />
      <Row id="jwt-default-role" label="Default role (when the role claim is a list)" form={form} field="defaultRole" onChange={change} />
      <Row id="jwt-user" label="User id claim" form={form} field="userId" onChange={change} />
      {Object.keys(form.extra).length === 0 ? <p className="hint">No extra claims are mapped.</p> : (
        <ul className="space-y-1">
          {Object.entries(form.extra).map(([name, path]) => (
            <li key={name}>X-Excalibase-{name} from {path} <button type="button" className="btn-link" onClick={() => removeExtra(name)}>Remove</button></li>
          ))}
        </ul>
      )}
      <Row id="jwt-issuer" label="Issuer" form={form} field="issuer" onChange={change} />
      <Row id="jwt-audience" label="Audience" form={form} field="audience" onChange={change} />
      <Row id="jwt-jwks" label="JWKS URL" form={form} field="jwksUrl" onChange={change} />
      <Row id="jwt-secret" label="Secret reference (env:NAME or file:PATH, never the secret)" form={form} field="secret" onChange={change} />
      <button type="button" id="apply-jwt" className="btn" onClick={() => void apply()}>Apply to the document</button>
    </section>
  );
}
