import { useQuery } from '@tanstack/react-query';
import clsx from 'clsx';
import { Database, LogOut, ShieldCheck, Server, Table2, Terminal } from 'lucide-react';
import type { ReactNode } from 'react';
import { adminApi } from '../api/adminApi';
import { PAGES, hashFor, type Page, type Route } from '../route';
import { useDocument } from '../state/DocumentContext';

const NAV: Record<Page, { label: string; icon: typeof Database }> = {
  data: { label: 'Data', icon: Database },
  schema: { label: 'Schema', icon: Table2 },
  permissions: { label: 'Permissions', icon: ShieldCheck },
  explorer: { label: 'API explorer', icon: Terminal },
  replicas: { label: 'Replicas', icon: Server },
};

function Pill({ children, tone }: { readonly children: ReactNode; readonly tone?: 'warn' }) {
  return <span className={clsx('rounded-full border px-2 py-0.5 text-xs', tone === 'warn' ? 'border-warn/40 text-warn' : 'border-line text-soft')}>{children}</span>;
}

/** The project the engine serves and where its permissions come from: the first thing to read on every screen. */
function Header({ onSignOut }: { readonly onSignOut: () => void }) {
  const { state } = useDocument();
  const meta = state.meta;
  const served = meta?.text ?? null;
  const project = useQuery({
    queryKey: ['project', meta?.sourceVersion],
    queryFn: async () => {
      const reply = served === null ? null : await adminApi.parse(served);
      return reply?.valid ? String(reply.tree.projectId ?? '') : '';
    },
    enabled: served !== null,
    retry: false,
  });
  return (
    <header className="flex items-center gap-3 border-b border-line bg-surface px-5 py-2.5">
      <span className="font-semibold">{project.data || 'No project'}</span>
      {meta ? <Pill>{meta.source}</Pill> : null}
      {meta ? <Pill tone={meta.writable ? undefined : 'warn'}>{meta.writable ? 'writable' : 'read-only'}</Pill> : null}
      {meta ? <Pill>{meta.version}</Pill> : null}
      {meta?.lastRefusal ? <Pill tone="warn">refused: {meta.lastRefusal}</Pill> : null}
      <button type="button" className="btn-quiet ml-auto" onClick={onSignOut}><LogOut size={15} aria-hidden /> Sign out</button>
    </header>
  );
}

export function Shell({ route, onSignOut, children }: { readonly route: Route; readonly onSignOut: () => void; readonly children: ReactNode }) {
  return (
    <div className="flex h-full">
      <aside className="flex w-56 shrink-0 flex-col border-r border-line bg-sunken">
        <div className="px-5 py-4 text-base font-bold text-accent">Excalibase</div>
        <nav aria-label="Console sections" className="space-y-0.5 px-2">
          {PAGES.map((page) => {
            const { label, icon: Icon } = NAV[page];
            const current = page === route.page;
            return (
              <a
                key={page}
                href={hashFor({ page, sub: page === 'permissions' ? 'document' : null })}
                aria-current={current ? 'page' : undefined}
                className={clsx('flex items-center gap-2 rounded-md px-3 py-2 font-medium', current ? 'bg-accent-wash text-accent' : 'text-ink hover:bg-surface')}
              >
                <Icon size={16} aria-hidden />
                <span>{label}</span>
              </a>
            );
          })}
        </nav>
        <p className="mt-auto px-5 py-3 text-xs text-soft">The earlier console is at /console/legacy.</p>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <Header onSignOut={onSignOut} />
        <main className="min-w-0 flex-1 overflow-auto p-6">{children}</main>
      </div>
    </div>
  );
}
