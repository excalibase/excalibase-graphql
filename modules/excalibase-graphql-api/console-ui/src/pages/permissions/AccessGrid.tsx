import { useMemo } from 'react';
import { Empty, ErrorList, Loading } from '../../components/ui';
import { allows, isCustom, OPERATIONS, ruleOf, tableKeysOf, type Operation, type PermissionsDoc } from '../../lib/docmodel';
import { hrefOf } from '../../router';
import { useSchema } from '../../schema';
import { useWorkbench } from '../../workbench';

const LABELS: Record<Operation, string> = { select: 'Read', insert: 'Insert', update: 'Update', delete: 'Delete' };
type Level = 'all' | 'some' | 'columns' | 'none';
const TEXT: Record<Level, string> = { all: 'All rows', some: 'Some rows', columns: 'Some columns', none: 'None' };
const CHIP: Record<Level, string> = { all: 'all', some: 'some', columns: 'some', none: 'none' };

function levelOf(doc: PermissionsDoc, table: string, role: string, operation: Operation): Level {
  if (!allows(doc, table, role, operation)) {
    return 'none';
  }
  if (!isCustom(doc, table, role, operation)) {
    return 'all';
  }
  const rule = ruleOf(doc, table, role)?.[operation] as { filter?: object; check?: object; columns?: unknown } | undefined;
  const limitsRows = [rule?.filter, rule?.check].some((part) => part !== undefined && Object.keys(part).length > 0);
  return !limitsRows && Array.isArray(rule?.columns) && Object.keys(rule).every((key) => ['filter', 'check', 'columns'].includes(key)) ? 'columns' : 'some';
}

const shown = (key: string): string => (key.startsWith('public.') ? key.slice('public.'.length) : key);

export function AccessGrid({ role }: { role: string }) {
  const workbench = useWorkbench();
  const schema = useSchema();
  const doc = workbench.tree;
  const tables = useMemo(() => (doc ? tableKeysOf(schema.data?.tables ?? [], doc) : []), [doc, schema.data]);
  const known = useMemo(() => new Set((schema.data?.tables ?? []).map((table) => `${table.schema}.${table.name}`)), [schema.data]);

  if (workbench.treeFor !== workbench.draft && !doc) {
    return <Loading label="Reading the permissions" />;
  }
  if (!doc) {
    return (
      <div role="alert" className="notice bad">
        <p>The grid needs a valid permissions text. Fix it in the YAML view first.</p>
        <a className="btn" href={hrefOf('permissions', 'yaml')}>
          Open the YAML view
        </a>
        {workbench.treeErrors.length > 0 ? <ErrorList heading="What is wrong:" errors={workbench.treeErrors} /> : null}
      </div>
    );
  }
  if (schema.status === 'loading' && tables.length === 0) {
    return <Loading label="Reading your tables" />;
  }
  if (tables.length === 0) {
    return (
      <Empty title="No tables to set permissions on">
        <p>The engine found no tables it can serve yet.</p>
      </Empty>
    );
  }
  return (
    <section className="card flush" aria-label="Access by table">
      <div className="content">
        <div>
          <h1>What can “{role}” do?</h1>
          <p className="muted">Click a cell to allow or stop reading, adding, changing or removing rows.</p>
        </div>
        <div className="scroll">
          <table className="tbl" aria-label={`What ${role} can do, by table`}>
            <thead>
              <tr>
                <th scope="col">Table</th>
                {OPERATIONS.map((operation) => (
                  <th key={operation} scope="col">
                    {LABELS[operation]}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {tables.map((table) => (
                <tr key={table}>
                  <th scope="row" className="mono">
                    {shown(table)} {known.has(table) || schema.status !== 'ready' ? null : <span className="chip bad">Not in the database</span>}
                  </th>
                  {OPERATIONS.map((operation) => {
                    const level = levelOf(doc, table, role, operation);
                    return (
                      <td key={operation}>
                        <button
                          type="button"
                          className="cell"
                          aria-label={`${role} can ${LABELS[operation].toLowerCase()} ${shown(table)}: ${TEXT[level]}${level === 'none' ? '. Activate to allow every row.' : level === 'all' ? '. Activate to stop.' : '. A rule written by hand. Activate to remove it.'}`}
                          onClick={() => workbench.toggle(table, role, operation, level === 'none')}
                        >
                          <span className={`chip ${CHIP[level]}`}>{TEXT[level]}</span>
                        </button>
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="legend">
        <span>
          <span className="chip all">All rows</span> no limit
        </span>
        <span>
          <span className="chip some">Some rows</span> a row rule applies
        </span>
        <span>
          <span className="chip some">Some columns</span> only some columns are open
        </span>
        <span>
          <span className="chip none">None</span> not allowed
        </span>
      </div>
    </section>
  );
}
