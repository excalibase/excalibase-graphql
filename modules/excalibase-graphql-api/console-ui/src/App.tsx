import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Suspense, lazy, useEffect, useMemo, useState } from 'react';
import { adminApi } from './api/adminApi';
import { Login } from './components/Login';
import { Shell } from './components/Shell';
import { ErrorNote } from './components/ui';
import { useRoute } from './hooks/useRoute';
import { messageOf } from './messages';
const DataPage = lazy(() => import('./pages/DataPage').then((module) => ({ default: module.DataPage })));
const ExplorerPage = lazy(() => import('./pages/ExplorerPage').then((module) => ({ default: module.ExplorerPage })));
const PermissionsPage = lazy(() => import('./pages/PermissionsPage').then((module) => ({ default: module.PermissionsPage })));
const ReplicasPage = lazy(() => import('./pages/ReplicasPage').then((module) => ({ default: module.ReplicasPage })));
const SchemaPage = lazy(() => import('./pages/SchemaViews').then((module) => ({ default: module.SchemaPage })));
import type { PermissionTab, Route } from './route';
import { DocumentProvider, useDocument } from './state/DocumentContext';
import { DocumentStore } from './state/documentStore';

function Page({ route }: { readonly route: Route }) {
  switch (route.page) {
    case 'data': return <DataPage table={route.sub} />;
    case 'schema': return <SchemaPage />;
    case 'permissions': return <PermissionsPage tab={(route.sub ?? 'document') as PermissionTab} />;
    case 'explorer': return <ExplorerPage />;
    case 'replicas': return <ReplicasPage />;
  }
}

function Console({ onEnded, onSignOut }: { readonly onEnded: () => void; readonly onSignOut: () => void }) {
  const { store, state } = useDocument();
  const [route] = useRoute();
  useEffect(() => { void store.load(); }, [store]);
  useEffect(() => { if (state.sessionEnded) { onEnded(); } }, [state.sessionEnded, onEnded]);
  return (
    <Shell route={route} onSignOut={onSignOut}>
      {state.failure ? <ErrorNote>{state.failure}</ErrorNote> : null}
      {state.loaded ? <Suspense fallback={<p className="hint">Loading...</p>}><Page route={route} /></Suspense> : <p className="hint">Loading...</p>}
    </Shell>
  );
}

export function App() {
  const [signedIn, setSignedIn] = useState(adminApi.signedIn());
  const [error, setError] = useState<string | null>(null);
  const client = useMemo(() => new QueryClient({ defaultOptions: { queries: { staleTime: 0, refetchOnWindowFocus: false } } }), []);
  const [store, setStore] = useState(() => new DocumentStore());

  const leave = (reason: string | null) => {
    adminApi.signOut();
    client.clear();
    setStore(new DocumentStore());
    setError(reason);
    setSignedIn(false);
  };
  const enter = async (secret: string) => {
    try {
      await adminApi.signIn(secret);
      setError(null);
      setSignedIn(true);
    } catch (failure) {
      setError(messageOf(failure));
    }
  };
  if (!signedIn) {
    return <Login error={error} onSubmit={enter} />;
  }
  return (
    <QueryClientProvider client={client}>
      <DocumentProvider store={store}>
        <Console onEnded={() => leave('Your session ended. Sign in again.')} onSignOut={() => leave(null)} />
      </DocumentProvider>
    </QueryClientProvider>
  );
}
