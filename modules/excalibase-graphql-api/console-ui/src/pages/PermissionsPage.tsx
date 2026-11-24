import { Confirmations } from '../components/Confirm';
import { OutcomeView } from '../components/Outcome';
import { ErrorState, Loading, PageHeader } from '../components/ui';
import { listRoles } from '../lib/roles';
import { hrefOf } from '../router';
import { useDocument } from '../workbench';
import { AccessGrid } from './permissions/AccessGrid';
import { HistoryView } from './permissions/HistoryView';
import { TokenView } from './permissions/TokenView';
import { YamlView } from './permissions/YamlView';

type View = 'access' | 'yaml' | 'token' | 'history';
const TABS: Array<{ view: View; arg: string; label: string }> = [
  { view: 'access', arg: '', label: 'Access' },
  { view: 'yaml', arg: 'yaml', label: 'YAML' },
  { view: 'token', arg: 'token', label: 'Sample token' },
  { view: 'history', arg: 'history', label: 'History' },
];

function viewOf(arg: string): View {
  return arg === 'yaml' || arg === 'token' || arg === 'history' ? arg : 'access';
}

function unsavedText(count: number | null): string {
  if (count === null) {
    return 'Unsaved changes';
  }
  return `${count} unsaved ${count === 1 ? 'change' : 'changes'}`;
}

export function PermissionsPage({ arg }: { arg: string }) {
  const workbench = useDocument();
  const view = viewOf(arg);
  const roles = listRoles(workbench.tree, workbench.addedRoles);
  const role = arg.startsWith('@') ? arg.slice(1) : (roles[0] ?? 'user');
  const writable = workbench.meta?.writable === true;
  const title = view === 'access' ? role : (TABS.find((tab) => tab.view === view)?.label ?? '');

  if (workbench.phase === 'loading') {
    return (
      <>
        <PageHeader trail={['Permissions']} />
        <div className="content">
          <Loading label="Reading the permissions" />
        </div>
      </>
    );
  }
  if (workbench.phase === 'error') {
    return (
      <>
        <PageHeader trail={['Permissions']} />
        <div className="content">
          <ErrorState message={workbench.problem ?? ''} onRetry={() => void workbench.reload()} />
        </div>
      </>
    );
  }
  return (
    <>
      <PageHeader trail={['Permissions', title]}>
        {workbench.dirty ? (
          <span className="muted" role="status">
            {unsavedText(workbench.pending ? workbench.pending.length : null)}
          </span>
        ) : null}
        {view === 'access' ? (
          <a className="btn" href={hrefOf('permissions', 'yaml')}>
            View as YAML
          </a>
        ) : null}
        <button type="button" className="btn primary" disabled={!writable || !workbench.dirty} onClick={() => void workbench.save()}>
          Save and apply
        </button>
      </PageHeader>
      <div className="content">
        <nav aria-label="Permissions views" className="tabs">
          {TABS.map((tab) => (
            <a key={tab.view} className="tab" href={hrefOf('permissions', tab.arg)} aria-current={tab.view === view ? 'page' : undefined}>
              {tab.label}
            </a>
          ))}
        </nav>
        <div className="actions">
          <button type="button" className="btn" onClick={workbench.exportDocument}>
            Export
          </button>
          {workbench.dirty ? (
            <button type="button" className="btn quiet" onClick={workbench.discard}>
              Discard edits
            </button>
          ) : null}
          <span className="hint" role="status">
            {writable ? (workbench.dirty ? 'You have unsaved edits.' : 'No unsaved edits.') : 'Read-only source: edits cannot be saved here. Export saves a copy as a file.'}
          </span>
        </div>
        <OutcomeView outcome={workbench.outcome} theirs={workbench.theirs} handlers={{ onTakeTheirs: () => void workbench.takeTheirs(), onOverwrite: workbench.overwrite }} />
        {view === 'access' ? <AccessGrid role={role} /> : null}
        {view === 'yaml' ? <YamlView /> : null}
        {view === 'token' ? <TokenView /> : null}
        {view === 'history' ? <HistoryView /> : null}
      </div>
      <Confirmations />
    </>
  );
}
