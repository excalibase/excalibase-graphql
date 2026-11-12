const WebSocket = require('ws');
const { request } = require('./lib/http');
const { stack } = require('./lib/stack');
const { token, bearer } = require('./lib/tokens');
const { graphql, openSocket, PROFILE } = require('./lib/traffic');
const { upgradeWithPipelinedFrame } = require('./lib/rawSocket');
const { waitUntil } = require('./lib/http');

const P = stack.project;
const WS_URL = `${stack.app.replace('http', 'ws')}/${P}/graphql`;
const service = () => bearer(token({ role: 'service' }));
const SUBSCRIBE = (id) => JSON.stringify({ id, type: 'subscribe', payload: { query: 'subscription { hanaCustomerChanges { operation table data timestamp } }' } });
const send = (socket, message) => socket.send(JSON.stringify(message));

/** The refusal an upgrade gets instead of a 101: status and reason header. */
function refusedUpgrade(headers, path = `/${P}/graphql`) {
  return new Promise((resolve) => {
    const socket = new WebSocket(`${stack.app.replace('http', 'ws')}${path}`, 'graphql-transport-ws', { headers });
    socket.on('unexpected-response', (req, res) => {
      const chunks = [];
      res.on('data', (chunk) => chunks.push(chunk));
      res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8'), reasonHeader: res.headers['x-auth-reason'] }));
    });
    socket.on('error', () => {});
    socket.on('open', () => { socket.close(); resolve({ status: 101 }); });
  });
}

const refusal = (status, code, message) => ({
  status, body: JSON.stringify({ errors: [{ message, extensions: { code } }] }), reasonHeader: undefined,
});

describe('graphql-transport-ws handshake', () => {
  test('upgrade answers 101 with the graphql-transport-ws subprotocol', async () => {
    const upgrade = await new Promise((resolve, reject) => {
      const socket = new WebSocket(WS_URL, ['graphql-transport-ws'], { headers: service() });
      socket.on('upgrade', (res) => { resolve({ status: res.statusCode, protocol: res.headers['sec-websocket-protocol'], accept: !!res.headers['sec-websocket-accept'] }); socket.close(); });
      socket.on('error', reject);
    });
    expect(upgrade).toEqual({ status: 101, protocol: 'graphql-transport-ws', accept: true });
  });

  test('connection_init is acknowledged with exactly connection_ack', async () => {
    const session = openSocket(stack.app, service());
    await waitUntil(() => session.frames.length > 0, { label: 'connection_ack' });
    expect(session.frames).toEqual([{ type: 'connection_ack' }]);
    session.socket.close();
    expect(await session.closed).toEqual({ code: 1000, reason: '' });
  });

  test('a connection_init sent in the same write as the upgrade is not dropped', async () => {
    const answer = await upgradeWithPipelinedFrame(`/${P}/graphql`, token({ role: 'service' }), JSON.stringify({ type: 'connection_init', payload: {} }));
    expect(answer.startsWith('HTTP/1.1 101')).toBe(true);
    expect(answer).toContain('{"type":"connection_ack"}');
  });

  test('ping is answered with pong', async () => {
    const session = openSocket(stack.app, service());
    await waitUntil(() => session.frames.length === 1, { label: 'ack' });
    send(session.socket, { type: 'ping' });
    await waitUntil(() => session.frames.length === 2, { label: 'pong' });
    expect(session.frames[1]).toEqual({ type: 'pong' });
    session.socket.close();
  });

  test('an anonymous session is acknowledged', async () => {
    const session = openSocket(stack.app, {});
    await waitUntil(() => session.frames.length === 1, { label: 'ack' });
    expect(session.frames).toEqual([{ type: 'connection_ack' }]);
    session.socket.close();
  });
});

describe('subscriptions', () => {
  // Without tenant-in-subject the watcher's events carry no tenant, so only a session without one (anon) receives them.
  test('subscribe delivers next frames for changes until complete', async () => {
    const session = openSocket(stack.app, {});
    await waitUntil(() => session.frames.length === 1, { label: 'ack' });
    session.socket.send(SUBSCRIBE('sub-1'));
    await new Promise((resolve) => setTimeout(resolve, 1500));
    await request(stack.app, `/${P}/api/v1/customer`, { method: 'POST', headers: { ...service(), ...PROFILE }, body: { first_name: 'Watched', last_name: 'Row' } });
    const next = await waitUntil(() => session.frames.find((frame) => frame.type === 'next' && frame.payload.data.hanaCustomerChanges.operation !== 'HEARTBEAT'), { label: 'next frame', timeoutMs: 20000 });
    expect(Object.keys(next).sort()).toEqual(['id', 'payload', 'type']);
    expect(next.id).toBe('sub-1');
    expect(Object.keys(next.payload.data)).toEqual(['hanaCustomerChanges']);
    expect(Object.keys(next.payload.data.hanaCustomerChanges).sort()).toEqual(['data', 'operation', 'table', 'timestamp']);
    expect(next.payload.data.hanaCustomerChanges.operation).toBe('INSERT');
    expect(next.payload.data.hanaCustomerChanges.data.first_name).toBe('Watched');

    send(session.socket, { id: 'sub-1', type: 'complete' });
    await new Promise((resolve) => setTimeout(resolve, 500));
    const delivered = session.frames.length;
    await request(stack.app, `/${P}/api/v1/customer`, { method: 'POST', headers: { ...service(), ...PROFILE }, body: { first_name: 'Unwatched', last_name: 'Row' } });
    await new Promise((resolve) => setTimeout(resolve, 2500));
    expect(session.frames.length).toBe(delivered);
    session.socket.close();
  }, 60000);

  test('a subscription query that names no table is answered with an error frame, the session stays open', async () => {
    const session = openSocket(stack.app, service());
    await waitUntil(() => session.frames.length === 1, { label: 'ack' });
    send(session.socket, { id: 'bad', type: 'subscribe', payload: { query: '{ x }' } });
    await waitUntil(() => session.frames.length === 2, { label: 'error frame' });
    expect(session.frames[1]).toEqual({ type: 'error', id: 'bad', payload: { message: 'Could not extract table name from subscription query' } });
    send(session.socket, { type: 'ping' });
    await waitUntil(() => session.frames.length === 3, { label: 'pong after error' });
    session.socket.close();
  });
});

describe('authentication failures', () => {
  test('refusals at the upgrade', async () => {
    expect({
      badToken: await refusedUpgrade({ Authorization: 'Bearer a.b.c' }),
      tokenForAnotherProject: await refusedUpgrade(bearer(token({ project: 'other' }))),
      roleNotAllowed: await refusedUpgrade({ ...bearer(token()), 'X-Excalibase-Role': 'admin' }),
      originNotOnAllowlist: await refusedUpgrade({ ...service(), Origin: 'http://evil.contract.test' }),
      anonAsksForRole: await refusedUpgrade({ 'X-Excalibase-Role': 'admin' }),
      unknownProject: await refusedUpgrade({}, '/no-such-project/graphql'),
    }).toEqual({
      badToken: refusal(401, 'invalid_token', 'Invalid or expired token'),
      tokenForAnotherProject: refusal(401, 'aud_mismatch', 'Invalid or expired token'),
      roleNotAllowed: refusal(403, 'role_not_allowed', 'Role not allowed'),
      originNotOnAllowlist: { status: 403, body: '', reasonHeader: undefined },
      anonAsksForRole: refusal(403, 'role_not_allowed', 'Role not allowed'),
      unknownProject: refusal(404, 'project_not_found', 'Project not found'),
    });
  });

  test('a bad token in connection_init gets connection_error then close 1008', async () => {
    const session = openSocket(stack.app, {}, { Authorization: 'Bearer a.b.c' });
    expect(await session.closed).toEqual({ code: 1008, reason: 'Invalid or expired token: invalid_token' });
    expect(session.frames).toEqual([{ type: 'connection_error', payload: { message: 'Invalid or expired token: invalid_token' } }]);
  });

  test('a good token in connection_init is acknowledged', async () => {
    const session = openSocket(stack.app, {}, { Authorization: `Bearer ${token({ role: 'service' })}` });
    await waitUntil(() => session.frames.length === 1, { label: 'ack' });
    expect(session.frames).toEqual([{ type: 'connection_ack' }]);
    session.socket.close();
  });

  test('a role the token may not act as in connection_init is refused with 1008', async () => {
    const session = openSocket(stack.app, {}, { Authorization: `Bearer ${token()}`, role: 'admin' });
    expect((await session.closed).code).toBe(1008);
    expect(session.frames[0].type).toBe('connection_error');
  });
});

describe('realtime endpoint', () => {
  test('upgrade on /realtime is accepted with a valid token and refused with a bad one', async () => {
    const attempt = (headers) => new Promise((resolve) => {
      const socket = new WebSocket(`${stack.app.replace('http', 'ws')}/${P}/api/v1/realtime`, { headers });
      socket.on('unexpected-response', (req, res) => resolve(res.statusCode));
      socket.on('error', () => {});
      socket.on('open', () => { socket.close(); resolve(101); });
    });
    expect(await attempt(service())).toBe(101);
    expect(await attempt({ Authorization: 'Bearer a.b.c' })).toBe(401);
  });
});
