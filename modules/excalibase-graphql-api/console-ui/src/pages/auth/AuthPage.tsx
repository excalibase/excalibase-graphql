import { useState } from 'react';
import type { AuthSettings } from '../../api';
import { Confirmations } from '../../components/Confirm';
import { OutcomeView } from '../../components/Outcome';
import { Warnings } from '../../components/ui';
import { NOTE_TEXT } from '../../lib/authText';
import { useWorkbench } from '../../workbench';
import { AuthSettingsCard } from './AuthSettingsCard';
import { RemoveJwt } from './RemoveJwt';
import { SnippetBuilder } from './SnippetBuilder';
import { TokenTester } from './TokenTester';

const DIFFERENT_TEXT =
  "The document's jwt: section differs from the engine's own settings, so the engine refuses that version of the document and keeps the last good one. Make the engine's settings say the same, then remove the section from the document.";
const LEGACY_TEXT =
  'A jwt: section in the document is always refused while the older settings (APP_SECURITY_AUTH_*) are set. Keep the settings in one place.';

function Banners({ settings }: { settings: AuthSettings }) {
  const notes = settings.warnings.map((warning) => ({ code: warning.code, message: NOTE_TEXT[warning.code] ?? warning.code }));
  return (
    <>
      <Warnings warnings={notes} heading="" />
      {settings.documentJwt === 'different' ? (
        <div className="notice bad" role="alert" data-testid="jwt-different">
          <p>{settings.source === 'legacy-engine' ? LEGACY_TEXT : DIFFERENT_TEXT}</p>
        </div>
      ) : null}
    </>
  );
}

/** Settings, then Auth: read-only, behind its own flag. What changes the engine's settings is done where it is configured. */
export function AuthPage({ settings, reload }: { settings: AuthSettings; reload: () => void }) {
  const workbench = useWorkbench();
  const [removing, setRemoving] = useState(false);
  return (
    <>
      <Banners settings={settings} />
      <AuthSettingsCard settings={settings} />
      {settings.documentJwt === 'identical' ? <RemoveJwt onStart={() => setRemoving(true)} onSaved={reload} /> : null}
      {removing ? (
        <>
          <OutcomeView outcome={workbench.outcome} theirs={workbench.theirs} handlers={{ onTakeTheirs: () => void workbench.takeTheirs(), onOverwrite: workbench.overwrite }} />
          <Confirmations />
        </>
      ) : null}
      <TokenTester />
      <SnippetBuilder settings={settings} />
    </>
  );
}
