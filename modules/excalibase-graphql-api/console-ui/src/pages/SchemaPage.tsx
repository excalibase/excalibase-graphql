import { useState } from 'react';
import type { SchemaTable, TableLink } from '../api';
import { Empty, ErrorState, Loading, PageHeader } from '../components/ui';
import { hiddenColumns, listRoles } from '../lib/roles';
import { hrefOf } from '../router';
import { keyOf, useSchema } from '../schema';
import { useDocument } from '../workbench';

const clock = (at: Date | null): string => (at ? at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '');
const sameTable = (table: SchemaTable, link: TableLink): boolean => table.name === link.table && table.schema === (link.schema ?? table.schema);

function Relationship({ table, link, outgoing, tables }: { table: SchemaTable; link: TableLink; outgoing: boolean; tables: SchemaTable[] }) {
  const other = tables.find((candidate) => sameTable(candidate, link));
  const there = other ? <a href={hrefOf('schema', keyOf(other))}>{link.table}</a> : <span>{link.table}</span>;
  const columns = link.columns.join(', ');
  const referenced = link.referencedColumns.join(', ');
  return (
    <div className="card" data-testid="relationship">
      <div className="actions">
        <strong>{table.name}</strong>
        <span aria-hidden="true">{outgoing ? '→' : '⇉'}</span>
        <strong>{there}</strong>
      </div>
      <p className="hint">
        {outgoing ? `Each row in ${table.name} points to one row in ${link.table}` : `Each row in ${table.name} can have many rows in ${link.table}`} ·{' '}
        <span className="mono">
          {outgoing ? `${columns} → ${link.table}.${referenced}` : `${link.table}.${columns} → ${referenced}`}
        </span>
      </p>
    </div>
  );
}

function ColumnNotes({ table, name, links, hidden, role }: { table: SchemaTable; name: string; links: TableLink[]; hidden: boolean; role: string }) {
  const column = table.columns.find((entry) => entry.name === name);
  return (
    <>
      {column?.primaryKey ? <span className="chip key">Primary key</span> : null}{' '}
      {links.map((link) => (
        <span key={`${link.table}-${link.columns.join()}`} className="chip link">
          → {link.table}
        </span>
      ))}{' '}
      {hidden ? <span className="chip hidden">Hidden from {role}</span> : null}
    </>
  );
}

function SchemaDetail({ table, tables }: { table: SchemaTable; tables: SchemaTable[] }) {
  const workbench = useDocument();
  const roles = listRoles(workbench.tree, workbench.addedRoles);
  const [chosen, setChosen] = useState<string | null>(null);
  const role = chosen ?? roles.find((name) => name !== 'service') ?? 'user';
  const hidden = workbench.tree ? hiddenColumns(workbench.tree, keyOf(table), role, table.columns.map((column) => column.name)) : null;
  const hiddenSet = new Set(hidden === 'all' || hidden === null ? [] : hidden);
  const references = table.references ?? [];
  const referencedBy = table.referencedBy ?? [];
  return (
    <div className="split">
      <section className="grow card flush" aria-label="Columns">
        <div className="content">
          <div>
            <h1>{table.name}</h1>
            <p className="muted">
              {table.columns.length} {table.columns.length === 1 ? 'column' : 'columns'} · {table.kind === 'view' ? 'a view' : 'a table'} in <span className="mono">{table.schema}</span>
            </p>
          </div>
          {table.columns.length === 0 ? (
            <Empty title="No columns to show">
              <p>This table has columns the engine cannot read.</p>
            </Empty>
          ) : (
            <div className="scroll">
              <table className="tbl">
                <thead>
                  <tr>
                    <th scope="col">Column</th>
                    <th scope="col">Type</th>
                    <th scope="col">Notes</th>
                  </tr>
                </thead>
                <tbody>
                  {table.columns.map((column) => (
                    <tr key={column.name}>
                      <td className="mono">{column.name}</td>
                      <td className="mono">{column.type}</td>
                      <td>
                        <ColumnNotes
                          table={table}
                          name={column.name}
                          links={references.filter((link) => link.columns.includes(column.name))}
                          hidden={hiddenSet.has(column.name)}
                          role={role}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>
      <aside className="rail" aria-label="Relationships">
        <h2>Relationships</h2>
        {references.length + referencedBy.length === 0 ? <p className="hint">This table is not linked to any other table.</p> : null}
        <div className="content">
          {references.map((link) => (
            <Relationship key={`out-${link.table}-${link.columns.join()}`} table={table} link={link} outgoing tables={tables} />
          ))}
          {referencedBy.map((link) => (
            <Relationship key={`in-${link.table}-${link.columns.join()}`} table={table} link={link} outgoing={false} tables={tables} />
          ))}
        </div>
        <div className="field">
          <label htmlFor="as-role">Show what this role can read</label>
          <select id="as-role" className="select" value={role} onChange={(event) => setChosen(event.target.value)}>
            {roles.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </div>
        {hidden === 'all' ? (
          <p className="notice warn" role="status">
            <b>Heads-up:</b> role <b>{role}</b> cannot read <span className="mono">{table.name}</span> at all. <a href={hrefOf('permissions', `@${role}`)}>See permissions</a>
          </p>
        ) : null}
        {Array.isArray(hidden) && hidden.length > 0 ? (
          <p className="notice warn" role="status">
            <b>Heads-up:</b> <span className="mono">{hidden.join(', ')}</span> {hidden.length === 1 ? 'is' : 'are'} hidden from role <b>{role}</b>.{' '}
            <a href={hrefOf('permissions', `@${role}`)}>See permissions</a>
          </p>
        ) : null}
      </aside>
    </div>
  );
}

export function SchemaPage({ arg }: { arg: string }) {
  const schema = useSchema();
  const tables = schema.data?.tables ?? [];
  const table = tables.find((candidate) => keyOf(candidate) === arg) ?? (arg === '' ? tables[0] : undefined);
  return (
    <>
      <PageHeader trail={table ? ['Schema', table.name] : ['Schema']}>
        {schema.loadedAt ? <span className="muted small">Read from the database at {clock(schema.loadedAt)}</span> : null}
        <button type="button" className="btn" onClick={schema.reload}>
          Refresh
        </button>
      </PageHeader>
      <div className="content">
        {schema.status === 'loading' && !schema.data ? <Loading label="Reading your tables" /> : null}
        {schema.status === 'error' ? <ErrorState message={schema.error ?? ''} onRetry={schema.reload} /> : null}
        {schema.status === 'ready' && tables.length === 0 ? (
          <Empty title="No tables to show">
            <p>The engine found no tables it can serve. Create a table in your database, then refresh.</p>
          </Empty>
        ) : null}
        {schema.status === 'ready' && tables.length > 0 && !table ? (
          <Empty title="That table is not here">
            <p>
              There is no table called <span className="mono">{arg}</span>. Pick one from the list.
            </p>
          </Empty>
        ) : null}
        {table ? <SchemaDetail key={keyOf(table)} table={table} tables={tables} /> : null}
      </div>
    </>
  );
}
