import { useQuery } from '@tanstack/react-query';
import { adminApi, type TableInfo } from '../api/adminApi';
import { ErrorNote, PageTitle } from '../components/ui';
import { messageOf } from '../messages';

export const useSchema = () => useQuery({ queryKey: ['schema'], queryFn: () => adminApi.schema(), retry: false });

export const columnLine = (table: TableInfo): string =>
  table.columns.map((column) => column.name + (column.primaryKey ? ' (key)' : '') + ': ' + column.type).join(', ');

export function SchemaPage() {
  const schema = useSchema();
  if (schema.isError) {
    return <div><PageTitle title="Schema" /><ErrorNote>{messageOf(schema.error)}</ErrorNote></div>;
  }
  const tables = schema.data?.tables ?? [];
  return (
    <div>
      <PageTitle title="Schema" hint="The tables and views the engine has reflected, and the functions it can call." />
      {schema.isPending ? <p className="hint">Reading the schema...</p> : null}
      <div className="card overflow-x-auto">
        <table className="w-full">
          <thead><tr><th className="cell">Table</th><th className="cell">Kind</th><th className="cell">Columns</th></tr></thead>
          <tbody>
            {tables.map((table) => (
              <tr key={table.schema + '.' + table.name}>
                <td className="cell font-mono text-xs">{table.schema + '.' + table.name}</td>
                <td className="cell">{table.kind}</td>
                <td className="cell">{columnLine(table)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {schema.data && tables.length === 0 ? <p className="mt-3">No tables are exposed.</p> : null}
      {(schema.data?.functions.length ?? 0) > 0 ? (
        <div className="mt-6">
          <h2 className="mb-2 text-base font-semibold">Functions</h2>
          <ul className="space-y-1 font-mono text-xs">
            {schema.data?.functions.map((fn) => <li key={fn.schema + '.' + fn.name}>{fn.schema + '.' + fn.name} ({fn.kind})</li>)}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
