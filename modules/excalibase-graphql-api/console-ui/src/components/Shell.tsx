import { useState, type FormEvent, type ReactNode } from 'react';
import { hrefOf, navigate, type Page, type Route } from '../router';
import { useSession } from '../session';
import { ThemeButton } from './ThemeToggle';
import { Dialog } from './ui';
import { keyOf, useSchema } from '../schema';
import { useDocument, useWorkbench } from '../workbench';
import { listRoles, tableCount } from '../lib/roles';

const NAV: Array<{ page: Page; label: string; soon?: boolean }> = [
  { page: 'data', label: 'Data', soon: true },
  { page: 'schema', label: 'Schema' },
  { page: 'permissions', label: 'Permissions' },
  { page: 'explorer', label: 'API explorer', soon: true },
  { page: 'replicas', label: 'Replicas' },
  { page: 'settings', label: 'Settings' },
];

function TableList({ route }: { route: Route }) {
  const schema = useSchema();
  const tables = schema.data?.tables ?? [];
  return (
    <div className="nav-group" role="group" aria-labelledby="tables-title">
      <span id="tables-title" className="nav-title">
        TABLES
      </span>
      {schema.status === 'loading' ? <span className="hint" role="status">Loading tables…</span> : null}
      {schema.status === 'error' ? <span className="hint" role="alert">Tables could not be read.</span> : null}
      {schema.status === 'ready' && tables.length === 0 ? <span className="hint">No tables yet.</span> : null}
      {tables.map((table) => (
        <a
          key={keyOf(table)}
          className="nav"
          href={hrefOf('schema', keyOf(table))}
          aria-current={route.page === 'schema' && route.arg === keyOf(table) ? 'page' : undefined}
        >
          <span>{table.name}</span>
          {table.kind === 'view' ? <span className="count">view</span> : null}
        </a>
      ))}
    </div>
  );
}

function NewRole({ roles, onClose }: { roles: string[]; onClose: () => void }) {
  const workbench = useWorkbench();
  const [name, setName] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  function add(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    const failed = workbench.addRole(trimmed, roles);
    setProblem(failed);
    if (!failed) {
      onClose();
      navigate('permissions', `@${trimmed}`);
    }
  }

  return (
    <Dialog title="New role" describedBy="new-role-help" onClose={onClose}>
      <form onSubmit={add} className="form">
        <p id="new-role-help" className="hint">
          A role is a kind of caller, such as editor. Use lowercase letters, digits and underscores. The role starts with no access.
        </p>
        <div className="field">
          <label htmlFor="new-role">Role name</label>
          <input id="new-role" className="input" data-autofocus autoComplete="off" spellCheck={false} placeholder="for example editor" value={name} onChange={(event) => setName(event.target.value)} aria-describedby={problem ? 'new-role-problem' : undefined} />
        </div>
        {problem ? (
          <p id="new-role-problem" role="alert" className="notice bad">
            {problem}
          </p>
        ) : null}
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn primary">
            Add role
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function RoleList({ route }: { route: Route }) {
  const workbench = useDocument();
  const roles = listRoles(workbench.tree, workbench.addedRoles);
  const [adding, setAdding] = useState(false);
  return (
    <div className="nav-group" role="group" aria-labelledby="roles-title">
      <span id="roles-title" className="nav-title">
        ROLES
      </span>
      {roles.map((role) => {
        const count = tableCount(workbench.tree, role);
        return (
          <a
            key={role}
            className="nav"
            href={hrefOf('permissions', `@${role}`)}
            aria-current={route.page === 'permissions' && route.arg === `@${role}` ? 'page' : undefined}
          >
            <span>{role}</span>
            <span className="count">{workbench.tree ? `${count} ${count === 1 ? 'table' : 'tables'}` : ''}</span>
          </a>
        );
      })}
      <button type="button" className="btn" onClick={() => setAdding(true)}>
        + New role
      </button>
      {adding ? <NewRole roles={roles} onClose={() => setAdding(false)} /> : null}
    </div>
  );
}

interface ShellProps {
  route: Route;
  children: ReactNode;
}

export function Shell({ route, children }: ShellProps) {
  const [open, setOpen] = useState(false);
  const { meta } = useWorkbench();
  const { signOut } = useSession();
  const database = meta ? `${meta.source} source` : '';
  const showTables = route.page === 'data' || route.page === 'schema' || route.page === 'explorer';
  return (
    <div className="app">
      <a
        className="skip"
        href="#content"
        onClick={(event) => {
          // The address holds the page, so the link moves focus instead of changing it.
          event.preventDefault();
          document.getElementById('content')?.focus();
        }}
      >
        Skip to the page
      </a>
      <nav className="side" aria-label="Console">
        <div className="brand">
          <div className="logo" aria-hidden="true">
            E
          </div>
          <div className="brand-text">
            <span className="brand-name">Excalibase engine</span>
            <span className="small muted">{database}</span>
          </div>
          <button type="button" className="btn menu-button" aria-expanded={open} aria-controls="side-body" onClick={() => setOpen(!open)}>
            Menu
          </button>
        </div>
        <div id="side-body" className={open ? 'side-body open' : 'side-body'}>
          <div className="nav-group">
            {NAV.map((item) => (
              <a
                key={item.page}
                className={item.soon ? 'nav soon' : 'nav'}
                href={hrefOf(item.page)}
                aria-current={route.page === item.page ? 'page' : undefined}
                onClick={() => setOpen(false)}
              >
                <span>{item.label}</span>
              </a>
            ))}
          </div>
          {showTables ? <TableList route={route} /> : null}
          {route.page === 'permissions' ? <RoleList route={route} /> : null}
          <div className="side-foot">
            <ThemeButton />
            <button type="button" className="nav" onClick={signOut}>
              <span>Sign out</span>
            </button>
          </div>
        </div>
      </nav>
      <main className="main" id="content" tabIndex={-1}>
        {children}
      </main>
    </div>
  );
}
