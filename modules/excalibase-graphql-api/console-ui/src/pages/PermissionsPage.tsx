import clsx from 'clsx';
import { DialogHost } from '../components/DialogHost';
import { SaveBar } from '../components/SaveBar';
import { PageTitle } from '../components/ui';
import { PERMISSION_TABS, hashFor, type PermissionTab } from '../route';
import { DocumentTab } from './DocumentTab';
import { GridTab } from './GridTab';
import { HistoryTab } from './HistoryTab';
import { TokenTab } from './TokenTab';
import { ValidateTab } from './ValidateTab';

const LABELS: Record<PermissionTab, string> = {
  document: 'Document', grid: 'Grid', token: 'Sample token', history: 'History', validate: 'Validate',
};
const BODIES: Record<PermissionTab, () => JSX.Element> = {
  document: DocumentTab, grid: GridTab, token: TokenTab, history: HistoryTab, validate: ValidateTab,
};

export function PermissionsPage({ tab }: { readonly tab: PermissionTab }) {
  const Body = BODIES[tab];
  return (
    <div>
      <PageTitle title="Permissions" hint="Who may read and write what. The document is the source of truth; the grid and the token form edit it." />
      {tab === 'validate' ? null : <SaveBar />}
      <div role="tablist" aria-label="Permissions views" className="mb-4 flex gap-1 border-b border-line">
        {PERMISSION_TABS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={name === tab}
            className={clsx('-mb-px rounded-t-md border border-transparent px-3 py-1.5',
              name === tab ? 'border-line border-b-surface bg-surface font-semibold text-accent' : 'text-soft hover:text-ink')}
            onClick={() => { window.location.hash = hashFor({ page: 'permissions', sub: name }); }}
          >
            {LABELS[name]}
          </button>
        ))}
      </div>
      <Body />
      <DialogHost />
    </div>
  );
}
