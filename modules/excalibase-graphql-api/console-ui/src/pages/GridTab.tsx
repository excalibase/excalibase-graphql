import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { adminApi } from '../api/adminApi';
import { ErrorNote } from '../components/ui';
import { OPERATIONS, SHORT, allows, columnRoles, isCustom, tableKeysOf } from '../model/docmodel';
import { useDocument } from '../state/DocumentContext';

export function GridTab() {
  const { store, state } = useDocument();
  const [problem, setProblem] = useState<string | null>(null);
  const [name, setName] = useState('');
  const schema = useQuery({ queryKey: ['schema'], queryFn: () => adminApi.schema(), retry: false });
  useEffect(() => {
    if (!state.tree) {
      void store.readTree();
    }
  }, [state.tree, store]);

  if (!state.tree) {
    return state.treeErrors.length > 0 ? (
      <ErrorNote>
        <p>The grid needs a valid text. Fix it on the Document tab:</p>
        <ul className="ml-5 list-disc">{state.treeErrors.map((message) => <li key={message}>{message}</li>)}</ul>
      </ErrorNote>
    ) : <p className="hint">Reading the document...</p>;
  }
  const tree = state.tree;
  const roles = columnRoles(tree, state.addedRoles);
  const tables = tableKeysOf(schema.data?.tables ?? [], tree);
  const add = () => setProblem(store.addRole(name.trim()));
  return (
    <section className="space-y-4">
      <p className="hint">
        A ticked box gives the role that operation on every row and every column. A star marks a rule written by hand, which stays as it is.
        The service role always has full access and has no column.
      </p>
      <div className="card overflow-x-auto">
        <table className="w-full border-collapse">
          <thead>
            <tr><th className="cell">Table</th>{roles.map((role) => <th key={role} className="cell">{role}</th>)}</tr>
          </thead>
          <tbody>
            {tables.map((table) => (
              <tr key={table}>
                <th scope="row" className="cell font-mono text-xs font-medium">{table}</th>
                {roles.map((role) => (
                  <td key={role} className="cell">
                    {OPERATIONS.map((operation) => {
                      const custom = isCustom(tree, table, role, operation);
                      return (
                        <label key={operation} className="mr-3 inline-flex items-center gap-1" title={custom ? operation + ': a custom rule, edit it as text' : operation}>
                          <input
                            type="checkbox"
                            aria-label={operation + ' ' + table + ' ' + role}
                            checked={allows(tree, table, role, operation)}
                            onChange={(event) => store.toggle(table, role, operation, event.target.checked)}
                          />
                          {SHORT[operation] + (custom ? '*' : '')}
                        </label>
                      );
                    })}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {tables.length === 0 ? <p>No tables are exposed.</p> : null}
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor="new-role">Add a role</label>
        <input id="new-role" className="field max-w-xs" autoComplete="off" spellCheck={false} placeholder="new role, e.g. editor" value={name} onChange={(event) => setName(event.target.value)} />
        <button type="button" className="btn-quiet" onClick={add}>Add</button>
        {problem ? <span role="alert" className="text-danger">{problem}</span> : null}
      </div>
      <p className="hint">Saving from the grid writes the document as JSON, so comments in a YAML file are not kept.</p>
    </section>
  );
}
