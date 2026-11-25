import { useCallback, useMemo, useState } from 'react';
import { signedIn, signOut as endSession } from './api';
import { Shell } from './components/Shell';
import { LoginPage } from './pages/LoginPage';
import { PermissionsPage } from './pages/PermissionsPage';
import { ReplicasPage } from './pages/ReplicasPage';
import { SchemaPage } from './pages/SchemaPage';
import { SettingsPage } from './pages/SettingsPage';
import { SoonPage } from './pages/SoonPage';
import { useRoute, type Route } from './router';
import { SchemaProvider } from './schema';
import { SessionContext } from './session';
import { ThemeContext, useTheme } from './theme';
import { WorkbenchProvider } from './workbench';

function PageFor({ route }: { route: Route }) {
  switch (route.page) {
    case 'schema':
      return <SchemaPage arg={route.arg} />;
    case 'permissions':
      return <PermissionsPage arg={route.arg} />;
    case 'replicas':
      return <ReplicasPage />;
    case 'settings':
      return <SettingsPage />;
    case 'explorer':
      return <SoonPage title="API explorer" trail="API explorer" what="Try your GraphQL and REST calls here, as any role you choose, and see exactly what that role gets back." />;
    default:
      return <SoonPage title="Data" trail="Data" what="Browse and edit the rows in your tables here." />;
  }
}

function Console({ onSignedOut }: { onSignedOut: () => void }) {
  const route = useRoute();
  return (
    <WorkbenchProvider onSignedOut={onSignedOut}>
      <SchemaProvider>
        <Shell route={route}>
          <PageFor route={route} />
        </Shell>
      </SchemaProvider>
    </WorkbenchProvider>
  );
}

const SESSION_ENDED = 'Your session ended. Sign in again.';

export function App() {
  const theme = useTheme();
  const [open, setOpen] = useState(signedIn());
  const [notice, setNotice] = useState<string | null>(null);

  const expired = useCallback(() => {
    endSession();
    setNotice(SESSION_ENDED);
    setOpen(false);
  }, []);
  const signOut = useCallback(() => {
    endSession();
    setNotice(null);
    setOpen(false);
  }, []);
  const session = useMemo(() => ({ expired, signOut }), [expired, signOut]);

  return (
    <ThemeContext.Provider value={theme}>
      <SessionContext.Provider value={session}>
        {open ? (
          <Console onSignedOut={expired} />
        ) : (
          <LoginPage
            notice={notice}
            onSignedIn={() => {
              setNotice(null);
              setOpen(true);
            }}
          />
        )}
      </SessionContext.Provider>
    </ThemeContext.Provider>
  );
}
