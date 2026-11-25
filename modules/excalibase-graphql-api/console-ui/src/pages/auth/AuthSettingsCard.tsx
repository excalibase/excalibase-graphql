import type { AuthSettings } from '../../api';
import { Fact } from '../../components/ui';
import { SOURCE_TEXT } from '../../lib/authText';

const none = (value: string | undefined): string | null => (value && value !== '' ? value : null);
const joined = (values: string[]): string | null => (values.length > 0 ? values.join(', ') : null);

function keysText(settings: AuthSettings): string | null {
  if (settings.keysLoaded === undefined) {
    return null;
  }
  return settings.keysLoaded ? 'Yes' : 'No';
}

function extraText(settings: AuthSettings): string | null {
  const extra = Object.entries(settings.claims?.extra ?? {});
  return extra.length > 0 ? extra.map(([name, path]) => `${name} from ${path}`).join(', ') : null;
}

/** What the engine runs with. The secret is shown as set or not set, never its value. */
export function AuthSettingsCard({ settings }: { settings: AuthSettings }) {
  return (
    <section className="card" aria-labelledby="auth-settings-title">
      <h2 id="auth-settings-title">How tokens are checked now</h2>
      <dl className="facts">
        <Fact label="Settings come from" value={SOURCE_TEXT[settings.source] ?? settings.source} />
        <Fact label="Algorithms" value={joined(settings.algorithms)} />
        <Fact label="Audience" value={joined(settings.audience)} />
        <Fact label="Issuer" value={none(settings.issuer)} />
        <Fact label="Key set address" value={none(settings.jwksUrl)} />
        <Fact label="Shared secret" value={settings.secret === 'set' ? 'Set' : 'Not set'} />
        <Fact label="Keys loaded" value={keysText(settings)} />
        <Fact label="Role claim" value={none(settings.claims?.role)} />
        <Fact label="User id claim" value={none(settings.claims?.userId)} />
        <Fact label="Default role" value={none(settings.claims?.defaultRole)} />
        <Fact label="Extra values" value={extraText(settings)} />
        <Fact label="Clock skew" value={settings.clockSkew === undefined ? null : `${settings.clockSkew} seconds`} />
      </dl>
      <p className="hint">This page only reads. The settings live where the engine is configured (its environment), so change them there.</p>
    </section>
  );
}
