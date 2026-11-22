import clsx from 'clsx';
import { ErrorNote, PageTitle } from '../components/ui';
import { messageOf } from '../messages';
import { hashFor } from '../route';
import { useSchema } from './SchemaViews';

/** The tables on the left and the one picked on the right. Rows arrive with the data browser; the columns are here now. */
export function DataPage({ table }: { readonly table: string | null }) {
  const schema = useSchema();
  if (schema.isError) {
    return <div><PageTitle title="Data" /><ErrorNote>{messageOf(schema.error)}</ErrorNote></div>;
  }
  const tables = schema.data?.tables ?? [];
  const current = tables.find((candidate) => candidate.schema + '.' + candidate.name === table) ?? null;
  return (
    <div>
      <PageTitle title="Data" hint="Pick a table to see its columns." />
      {schema.isPending ? <p className="hint">Reading the schema...</p> : null}
      <div className="grid gap-4 md:grid-cols-[16rem_1fr]">
        <nav aria-label="Tables" className="card max-h-[70vh] overflow-auto p-1">
          {tables.map((candidate) => {
            const name = candidate.schema + '.' + candidate.name;
            return (
              <a
                key={name}
                href={hashFor({ page: 'data', sub: name })}
                aria-current={name === table ? 'page' : undefined}
                className={clsx('block rounded-md px-2.5 py-1.5 font-mono text-xs', name === table ? 'bg-accent-wash font-semibold text-accent' : 'hover:bg-sunken')}
              >
                {name}
              </a>
            );
          })}
          {schema.data && tables.length === 0 ? <p className="p-2">No tables are exposed.</p> : null}
        </nav>
        <section className="card p-4">
          {current ? (
            <>
              <h2 className="mb-1 text-base font-semibold">{current.schema + '.' + current.name}</h2>
              <p className="hint mb-3">A {current.kind} with {current.columns.length} columns. Browsing its rows comes with the next step of the console.</p>
              <table className="w-full">
                <thead><tr><th className="cell">Column</th><th className="cell">Type</th><th className="cell">Key</th></tr></thead>
                <tbody>
                  {current.columns.map((column) => (
                    <tr key={column.name}><td className="cell font-mono text-xs">{column.name}</td><td className="cell">{column.type}</td><td className="cell">{column.primaryKey ? 'primary' : ''}</td></tr>
                  ))}
                </tbody>
              </table>
            </>
          ) : <p className="text-soft">No table is picked.</p>}
        </section>
      </div>
    </div>
  );
}
