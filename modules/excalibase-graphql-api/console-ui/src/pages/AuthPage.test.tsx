import { screen, waitFor, within } from '@testing-library/react';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { AuthSettings } from '../api';
import { DOC, healthy, META, type Reply, type Routes } from '../test/engine';
import { openConsole } from '../test/render';

const SETTINGS: AuthSettings = {
  source: 'engine',
  issuer: 'https://idp.example.com/',
  algorithms: ['HS256'],
  audience: ['my-api'],
  clockSkew: 30,
  claims: { role: 'role', defaultRole: '', userId: 'sub', extra: { org: 'app.org' } },
  secret: 'set',
  keysLoaded: true,
  documentJwt: 'absent',
  warnings: [],
};
const CANARY_SIGNATURE = 'canary-sig';
const TOKEN = `eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1LTEifQ.${CANARY_SIGNATURE}-9f2c`;
const WITH_JWT = 'format: 1\nprojectId: my-app\njwt:\n  secret: env:JWT_SECRET\n  audience: my-api\ntables:\n  - table: public.todos\n    role: user\n    select: {filter: {}, columns: "*"}\n';
const TABLES = [{ table: 'public.todos', role: 'user', select: { filter: {}, columns: '*' } }];
const AUTH_PAGE = '#/settings/auth';
const SETTINGS_TITLE = 'How tokens are checked now';
const REMOVE = 'Remove jwt: from the document';
const AUTH = 'GET /auth';
const CHECK = 'POST /auth/check';

const on = (settings: Partial<AuthSettings> = {}, extra: Routes = {}): Routes =>
  healthy({ [AUTH]: { body: { ...SETTINGS, ...settings } }, ...extra });

const storage = (): string => JSON.stringify({ ...window.sessionStorage, ...window.localStorage });

describe('the Auth page and its flag', () => {
  it('is not offered when the engine says the flag is off', async () => {
    await openConsole(AUTH_PAGE);

    await screen.findByText('Permissions come from');
    expect(screen.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible();
    expect(screen.queryByRole('link', { name: 'Auth' })).toBeNull();
    expect(screen.queryByText(SETTINGS_TITLE)).toBeNull();
  });

  it('is offered as a tab, and shown, when it is on', async () => {
    await openConsole('#/settings', on());

    expect(await screen.findByRole('link', { name: 'Auth' })).toHaveAttribute('href', AUTH_PAGE);
  });

  it('a failure that is not "off" is shown, never a page of empty settings', async () => {
    await openConsole(AUTH_PAGE, healthy({ [AUTH]: { status: 500, body: {} } }));

    await screen.findByText('Permissions come from');
    expect(screen.queryByText(SETTINGS_TITLE)).toBeNull();
  });
});

describe('the settings it shows', () => {
  it('names the source, algorithm, audience and the two claims, and the secret only as set', async () => {
    await openConsole(AUTH_PAGE, on());

    expect(await screen.findByText("The engine's own settings (APP_AUTH_*)")).toBeVisible();
    expect(screen.getByText('HS256')).toBeVisible();
    expect(screen.getByText('my-api')).toBeVisible();
    const facts = screen.getByRole('heading', { name: SETTINGS_TITLE }).closest('section') as HTMLElement;
    expect(within(facts).getByText('Set')).toBeVisible();
    expect(within(facts).getByText('role')).toBeVisible();
    expect(within(facts).getByText('sub')).toBeVisible();
    expect(document.body.textContent).not.toMatch(/secret value|an-hmac/);
  });

  it('says nothing verifies a token, as an alert, when the engine reports it', async () => {
    await openConsole(AUTH_PAGE, on({ source: 'none', secret: 'unset', keysLoaded: false, warnings: [{ code: 'no_token_verifier' }] }));

    const outage = await screen.findByTestId('token-outage');
    expect(outage).toHaveAttribute('role', 'alert');
    expect(outage).toHaveTextContent('Every token is rejected');
  });

  it('says a document jwt: section that differs is refused', async () => {
    await openConsole(AUTH_PAGE, on({ documentJwt: 'different' }));

    expect(await screen.findByTestId('jwt-different')).toHaveTextContent('refuses that version');
  });
});

describe('remove jwt: from the document', () => {
  const offered = async (documentJwt: AuthSettings['documentJwt']) => {
    await openConsole(AUTH_PAGE, on({ documentJwt }));
    await screen.findByText(SETTINGS_TITLE);
    return screen.queryByRole('button', { name: REMOVE });
  };

  it.each(['absent', 'present-deprecated', 'different'] as const)('is not offered when the section is %s', async (state) => {
    expect(await offered(state)).toBeNull();
  });

  it('is offered when the section is identical', async () => {
    expect(await offered('identical')).not.toBeNull();
  });

  it('takes the section out in one save, after the usual confirmation, and reads the settings again', async () => {
    let served = WITH_JWT;
    let version = 3;
    const parse = ({ body }: { body: string | undefined }): Reply => ({
      body: { valid: true, tree: body?.includes('jwt:') ? { jwt: { secret: 'env:JWT_SECRET', audience: 'my-api' }, tables: TABLES } : { tables: TABLES } },
    });
    const { user, engine } = await openConsole(AUTH_PAGE, on({ documentJwt: 'identical', warnings: [{ code: 'jwt_identical_remove_it' }] }, {
      'GET /metadata': () => ({ body: { ...META, version, sourceVersion: `v${version}`, document: served } }),
      'POST /parse': parse,
      'PUT /metadata': ({ body }) => {
        served = body ?? '';
        version += 1;
        return { body: { version, warnings: [] } };
      },
    }));

    const remove = await screen.findByRole('button', { name: REMOVE });
    await waitFor(() => expect(remove).toBeEnabled());
    await user.click(remove);
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('token settings change');
    await user.click(within(dialog).getByRole('button', { name: 'Save and apply' }));

    await waitFor(() => expect(engine.calls.filter((call) => call.method === 'PUT')).toHaveLength(1));
    const put = engine.calls.find((call) => call.method === 'PUT');
    expect(put?.body).not.toContain('jwt');
    expect(put?.body).toContain('tables:');
    expect(served).toBe(put?.body);
    await waitFor(() => expect(engine.calls.filter((call) => call.path === '/auth').length).toBeGreaterThan(1));
  });

  it('does not save unsaved permission edits along with it', async () => {
    const withJwtTree = { jwt: { secret: 'env:JWT_SECRET', audience: 'my-api' }, tables: DOC.tables };
    const { user, engine } = await openConsole('#/permissions/%40user', on({ documentJwt: 'identical' }, {
      'GET /metadata': { body: { ...META, document: WITH_JWT } },
      'POST /parse': { body: { valid: true, tree: withJwtTree } },
    }));
    const cells = await screen.findAllByRole('button', { name: /user can .* orders: None/ });
    await user.click(cells[0] as HTMLElement);
    await screen.findByText(/unsaved change/);
    window.location.hash = AUTH_PAGE;

    const button = await screen.findByRole('button', { name: REMOVE });

    expect(button).toBeDisabled();
    expect(screen.getByText(/You have unsaved permission edits/)).toBeVisible();
    expect(engine.calls.some((call) => call.method === 'PUT')).toBe(false);
  });
});

describe('test a token', () => {
  const paste = async (user: Awaited<ReturnType<typeof openConsole>>['user'], token = TOKEN) => {
    await user.type(await screen.findByLabelText('Token to test'), token);
    await user.click(screen.getByRole('button', { name: 'Test this token' }));
  };

  it('shows who a good token is, and what a rule can use', async () => {
    const { user } = await openConsole(AUTH_PAGE, on({}, {
      [CHECK]: { body: { valid: true, role: 'user', allowedRoles: ['user'], userId: 'u-1', sessionVariables: { 'x-excalibase-user-id': 'u-1' } } },
    }));

    await paste(user);

    expect(await screen.findByText('The engine accepts this token.')).toBeVisible();
    const variables = screen.getByRole('table', { name: 'Values a rule can use' });
    expect(within(variables).getByText('x-excalibase-user-id')).toBeVisible();
  });

  it.each([
    ['expired', 'The token has expired'],
    ['audience', 'made for a different audience'],
    ['bad_signature', 'signature does not match'],
    ['no_verifier', 'rejects every token'],
  ])('says in plain words why a %s token fails', async (reason, words) => {
    const { user } = await openConsole(AUTH_PAGE, on({}, { [CHECK]: { body: { valid: false, reason } } }));

    await paste(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(words);
  });

  it('keeps the token in the box only until it is sent, and nowhere else', async () => {
    const { user, engine } = await openConsole(AUTH_PAGE, on({}, { [CHECK]: { body: { valid: false, reason: 'expired' } } }));

    await paste(user);
    await screen.findByText(/The token has expired/);

    expect(screen.getByLabelText('Token to test')).toHaveValue('');
    expect(storage()).not.toContain(TOKEN);
    expect(storage()).not.toContain(CANARY_SIGNATURE);
    expect(document.body.textContent).not.toContain(CANARY_SIGNATURE);
    const sends = engine.calls.filter((call) => call.body?.includes(CANARY_SIGNATURE));
    expect(sends).toHaveLength(1);
    expect(sends[0]).toMatchObject({ method: 'POST', path: '/auth/check' });
    expect(engine.calls.some((call) => call.method === 'PUT')).toBe(false);
  });

  describe('the draft of the permissions', () => {
    const rangeProto = Range.prototype as unknown as Record<string, unknown>;
    const saved = { rects: rangeProto.getClientRects, box: rangeProto.getBoundingClientRect };

    // jsdom has no layout, and the editor measures text with ranges.
    beforeAll(() => {
      rangeProto.getClientRects = () => [];
      rangeProto.getBoundingClientRect = () => ({ top: 0, left: 0, right: 0, bottom: 0, width: 0, height: 0 });
    });
    afterAll(() => {
      rangeProto.getClientRects = saved.rects;
      rangeProto.getBoundingClientRect = saved.box;
    });

    it('never holds the token, read from the editor itself once the document has loaded', async () => {
      const { user } = await openConsole(AUTH_PAGE, on({}, { [CHECK]: { body: { valid: false, reason: 'malformed' } } }));
      await paste(user);
      await screen.findByText('This is not a readable token.');

      window.location.hash = '#/permissions/yaml';

      await waitFor(() => expect(document.querySelector('.cm-content')?.textContent).toContain('projectId: my-app'));
      expect(document.querySelector('.cm-content')?.textContent).not.toContain(CANARY_SIGNATURE);
    });
  });

  it('says so when the checks are used up', async () => {
    const { user } = await openConsole(AUTH_PAGE, on({}, { [CHECK]: { status: 429, body: { error: 'too_many_token_checks' } } }));

    await paste(user);

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many token checks');
  });
});

describe('the engine settings it writes', () => {
  it('starts from the values in use and never holds a secret', async () => {
    await openConsole(AUTH_PAGE, on());

    const env = await screen.findByLabelText('Environment variables');
    expect(env).toHaveTextContent('APP_AUTH_AUDIENCE=my-api');
    expect(env).toHaveTextContent('APP_AUTH_CLAIMS_EXTRA=org=app.org');
    expect(env).toHaveTextContent('<a secret of 32 bytes or more>');
    expect(screen.getByLabelText('Kubernetes env block')).toHaveTextContent('secretKeyRef');
  });

  it('reads a sample token in the browser to pick the role, and never sends or keeps it', async () => {
    const claims = btoa('{"sub":"u1","app":{"role":"admin"}}').replace(/=+$/, '');
    const sample = `${btoa('{"alg":"HS256"}')}.${claims}.sample-sig-77`;
    const { user, engine } = await openConsole(AUTH_PAGE, on());

    await user.type(await screen.findByLabelText('A sample token'), sample);
    await user.click(screen.getByRole('button', { name: 'Read claims' }));
    await user.click(await screen.findByRole('button', { name: 'Use app.role as the role' }));

    expect(screen.getByLabelText('Environment variables')).toHaveTextContent('APP_AUTH_CLAIMS_ROLE=app.role');
    expect(screen.getByLabelText('A sample token')).toHaveValue('');
    expect(engine.calls.some((call) => call.body?.includes('sample-sig-77'))).toBe(false);
    expect(storage()).not.toContain('sample-sig-77');
  });
});
