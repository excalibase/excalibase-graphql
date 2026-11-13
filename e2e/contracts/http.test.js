const { request } = require('./lib/http');
const { stack } = require('./lib/stack');
const { token, bearer, sign } = require('./lib/tokens');
const { capture } = require('./lib/capture');
const { expectFixture } = require('./lib/fixture');

const P = stack.project;
const REST = `/${P}/api/v1`;
const GRAPHQL = `/${P}/graphql`;
const PROFILE = { 'Accept-Profile': 'hana', 'Content-Profile': 'hana' };
const service = () => bearer(token({ role: 'service' }));
const user = () => bearer(token({ role: 'user' }));
const query = (text) => ({ method: 'POST', body: { query: text } });
const withHeaders = (spec, headers) => ({ ...spec, headers: { ...(spec.headers || {}), ...headers } });
const ORIGIN = 'https://app.contract.test';
const BIG_TEXT = 'x'.repeat(12 * 1024 * 1024);

function deepQuery(depth) {
  let selection = 'customer_id';
  for (let level = 0; level < depth; level++) selection = `${level % 2 ? 'hanaCustomer' : 'hanaOrders'} { ${selection} }`;
  return `{ hanaCustomer { ${selection} } }`;
}

const cases = {
  // authentication
  'graphql without token runs as anon': [GRAPHQL, query('{ __typename }')],
  'bad token': [GRAPHQL, withHeaders(query('{ __typename }'), { Authorization: 'Bearer abc.def.ghi' })],
  'token signed with another key': [GRAPHQL, withHeaders(query('{ __typename }'), bearer(token({ secret: 'y'.repeat(40) })))],
  'expired token': [GRAPHQL, withHeaders(query('{ __typename }'), bearer(token({ extra: { exp: 1 } })))],
  'token for another project': [GRAPHQL, withHeaders(query('{ __typename }'), bearer(token({ project: 'other' })))],
  'refresh token': [GRAPHQL, withHeaders(query('{ __typename }'), bearer(token({ extra: { token_use: 'refresh' } })))],
  'role the token may not act as': [GRAPHQL, withHeaders(query('{ __typename }'), { ...user(), 'X-Excalibase-Role': 'admin' })],
  'anon asking for another role': [GRAPHQL, withHeaders(query('{ __typename }'), { 'X-Excalibase-Role': 'admin' })],
  'rest bad token': [`${REST}/customer`, { headers: { Authorization: 'Bearer abc.def.ghi', ...PROFILE } }],
  // routing
  'unknown project': ['/no-such-project/graphql', withHeaders(query('{ __typename }'), bearer(token({ project: 'no-such-project' })))],
  'unknown project rest': ['/no-such-project/api/v1/customer', { headers: bearer(token({ project: 'no-such-project' })) }],
  // firewall and route matching
  'plain get on an unknown project graphql path': ['/no-such-project/graphql', { headers: service() }],
  'plain get on a known project graphql path': [GRAPHQL, { headers: service() }],
  'trace method': [GRAPHQL, { method: 'TRACE', headers: service() }],
  'custom method': [GRAPHQL, { method: 'FOO', headers: service() }],
  'custom method on rest': [`${REST}/customer`, { method: 'PROPFIND', headers: service() }],
  'encoded line feed in a path': [`${REST}/rpc/x%0ay`, { headers: service() }],
  'encoded carriage return in a path': [`${REST}/rpc/x%0Dy`, { headers: service() }],
  'encoded line separator in a path': [`${REST}/rpc/x%E2%80%A8y`, { headers: service() }],
  'encoded paragraph separator in a path': [`${REST}/rpc/x%e2%80%a9y`, { headers: service() }],
  'unscoped graphql': ['/graphql', withHeaders(query('{ __typename }'), service())],
  'trailing slash graphql': [`${GRAPHQL}/`, withHeaders(query('{ __typename }'), service())],
  'unknown path': ['/', {}],
  'actuator is not on the public port': ['/actuator/health', {}],
  // graphql behaviour
  'graphql query': [GRAPHQL, withHeaders(query('{ hanaCustomer(limit: 1, orderBy: { customer_id: ASC }) { customer_id first_name } }'), service())],
  'graphql unknown field': [GRAPHQL, withHeaders(query('{ nope }'), service())],
  'graphql syntax error': [GRAPHQL, withHeaders(query('{ hanaCustomer { '), service())],
  'graphql too deep': [GRAPHQL, withHeaders(query(deepQuery(20)), service())],
  'graphql missing query': [GRAPHQL, { method: 'POST', headers: service(), body: '{}' }],
  'graphql malformed json': [GRAPHQL, { method: 'POST', headers: service(), body: '{nope' }],
  'graphql get': [`${GRAPHQL}?query=%7B__typename%7D`, { headers: service() }],
  'graphql text/plain': [GRAPHQL, { method: 'POST', headers: { ...service(), 'Content-Type': 'text/plain' }, body: '{}' }],
  'graphql json body of 12 MiB': [GRAPHQL, { method: 'POST', headers: service(), body: JSON.stringify({ query: '{ __typename }', pad: BIG_TEXT }) }],
  'graphql form body of 12 MiB': [GRAPHQL, { method: 'POST', headers: { ...service(), 'Content-Type': 'application/x-www-form-urlencoded' }, body: `query=${BIG_TEXT}` }],
  'graphql request header of 70 KB': [GRAPHQL, withHeaders(query('{ __typename }'), { ...service(), 'X-Big': 'y'.repeat(70000) })],
  // rest behaviour
  'rest read': [`${REST}/customer?limit=2&order=customer_id.asc&select=customer_id,first_name`, { headers: { ...service(), ...PROFILE } }],
  'rest read as user': [`${REST}/customer?limit=1&order=customer_id.asc&select=customer_id`, { headers: { ...user(), ...PROFILE } }],
  'rest read as anon': [`${REST}/customer?limit=1&order=customer_id.asc&select=customer_id`, { headers: PROFILE }],
  'rest unknown table': [`${REST}/nope`, { headers: { ...service(), ...PROFILE } }],
  'rest bad filter value': [`${REST}/customer?customer_id=eq.bogus`, { headers: { ...service(), ...PROFILE } }],
  'rest unknown filter column': [`${REST}/customer?nope=eq.1`, { headers: { ...service(), ...PROFILE } }],
  'rest unknown rpc': [`${REST}/rpc/nope_fn`, { method: 'POST', headers: { ...service(), ...PROFILE }, body: {} }],
  'rest rpc name that is not an identifier': [`${REST}/rpc/a.b.c`, { method: 'POST', headers: { ...service(), ...PROFILE }, body: {} }],
  'rest rpc name with a space': [`${REST}/rpc/bad%20name`, { method: 'POST', headers: { ...service(), ...PROFILE }, body: {} }],
  'rest rpc name with a semicolon': [`${REST}/rpc/no-such;fn`, { headers: { ...service(), ...PROFILE } }],
  'rest delete without filter': [`${REST}/customer`, { method: 'DELETE', headers: { ...service(), ...PROFILE } }],
  'rest value too long': [`${REST}/customer`, { method: 'POST', headers: { ...service(), ...PROFILE }, body: { first_name: BIG_TEXT, last_name: 'x' } }],
  // unencoded query characters
  'unencoded braces and quotes': [`${REST}/customer?select=customer_id&first_name=in.("MARY","JOHN")&order=customer_id.asc`, { headers: { ...service(), ...PROFILE } }],
  'unencoded braces in cs': [`${REST}/customer?select=customer_id&first_name=cs.{A}`, { headers: { ...service(), ...PROFILE } }],
  'unencoded pipe backtick caret': [`${REST}/customer?select=customer_id&first_name=eq.a|b\`c^d`, { headers: { ...service(), ...PROFILE } }],
  'malformed percent escape': [`${REST}/customer?select=customer_id&limit=1&first_name=eq.%zz`, { headers: { ...service(), ...PROFILE } }],
  'matrix parameter in path': [`${REST}/customer;x=1`, { headers: service() }],
  'path traversal': [`${REST}/../graphql`, { headers: service() }],
  'encoded slash in path': [`${REST}/customer%2f..`, { headers: service() }],
  'double slash prefix': [`/${GRAPHQL}`, withHeaders(query('{ __typename }'), service())],
  // media types
  'rest vendor object json one row': [`${REST}/customer?customer_id=eq.1&select=customer_id`, { headers: { ...service(), ...PROFILE, Accept: 'application/vnd.pgrst.object+json' } }],
  'rest vendor object json many rows': [`${REST}/customer?limit=2&select=customer_id`, { headers: { ...service(), ...PROFILE, Accept: 'application/vnd.pgrst.object+json' } }],
  'rest csv': [`${REST}/customer?limit=2&order=customer_id.asc&select=customer_id,first_name`, { headers: { ...service(), ...PROFILE, Accept: 'text/csv' } }],
  'rest openapi': [REST, { headers: { ...service(), ...PROFILE, Accept: 'application/openapi+json' } }],
  'rest root as plain json': [REST, { headers: { ...service(), ...PROFILE, Accept: 'application/json' } }],
  'rest count exact': [`${REST}/customer?customer_id=lt.5&limit=1&order=customer_id.asc&select=customer_id`, { headers: { ...service(), ...PROFILE, Prefer: 'count=exact' } }],
  'rest head': [`${REST}/customer?limit=1`, { method: 'HEAD', headers: { ...service(), ...PROFILE } }],
};

async function captureAll() {
  const result = {};
  for (const [label, [path, options]] of Object.entries(cases)) {
    result[label] = capture(await request(stack.app, path, options));
  }
  return result;
}

describe('HTTP behaviour', () => {
  test('status, headers and body for every request class', async () => {
    expectFixture('http-responses', await captureAll());
  }, 120000);

  test('mutations answer with the created row', async () => {
    const created = capture(await request(stack.app, GRAPHQL, withHeaders(query(
      'mutation { createHanaCustomer(input: { first_name: "Contract", last_name: "Pin" }) { first_name last_name } }'), service())));
    expect(created.body).toEqual({ data: { createHanaCustomer: { first_name: 'Contract', last_name: 'Pin' } } });
    const rest = capture(await request(stack.app, `${REST}/customer`, { method: 'POST', headers: { ...service(), ...PROFILE, Prefer: 'return=representation' }, body: { first_name: 'Rest', last_name: 'Pin' } }));
    expect(rest.status).toBe(201);
    expect(Object.keys(rest.body.data)).toEqual(['customer_id', 'first_name', 'last_name', 'email', 'active', 'create_date', 'last_update']);
  });
});

describe('management port', () => {
  test('serves the actuator only; a project route is a 404 there', async () => {
    const result = {};
    for (const [label, method, path] of [['get graphql', 'GET', GRAPHQL], ['post graphql', 'POST', GRAPHQL],
      ['get rest', 'GET', `${REST}/customer`], ['get realtime', 'GET', `${REST}/realtime`], ['get unscoped graphql', 'GET', '/graphql']]) {
      result[label] = capture(await request(stack.management, path, { method, headers: service(), body: method === 'POST' ? { query: '{ __typename }' } : undefined }));
    }
    expectFixture('http-management-port', result);
  });
});

describe('CORS', () => {
  let mappingId;
  beforeAll(async () => {
    const response = await request(stack.vault, '/__admin/mappings', { method: 'POST', body: {
      request: { method: 'GET', url: `/api/projects/${P}/info` },
      response: { status: 200, headers: { 'Content-Type': 'application/json' }, jsonBody: { corsAllowedOrigins: [ORIGIN] } },
    } });
    mappingId = JSON.parse(response.text).id;
  });
  afterAll(async () => {
    await request(stack.vault, `/__admin/mappings/${mappingId}`, { method: 'DELETE' });
  });

  const preflight = (origin, path = GRAPHQL) => request(stack.app, path, { method: 'OPTIONS', headers: {
    Origin: origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type,x-excalibase-role',
  } });

  test('preflight and actual responses for allowed and refused origins', async () => {
    const result = {
      'preflight allowed origin graphql': capture(await preflight(ORIGIN)),
      'preflight allowed origin rest': capture(await preflight(ORIGIN, `${REST}/customer`)),
      'preflight refused origin': capture(await preflight('https://evil.contract.test')),
      'get from allowed origin': capture(await request(stack.app, `${REST}/customer?limit=1&select=customer_id`, { headers: { ...service(), ...PROFILE, Origin: ORIGIN } })),
      'get from refused origin': capture(await request(stack.app, `${REST}/customer?limit=1&select=customer_id`, { headers: { ...service(), ...PROFILE, Origin: 'https://evil.contract.test' } })),
      'post from allowed origin': capture(await request(stack.app, GRAPHQL, withHeaders(query('{ __typename }'), { ...service(), Origin: ORIGIN }))),
      'preflight on a route with no project': capture(await request(stack.app, '/', { method: 'OPTIONS', headers: { Origin: ORIGIN, 'Access-Control-Request-Method': 'GET' } })),
    };
    expectFixture('http-cors', result);
  });

  /** The first line the server answers a WebSocket upgrade with, the Upgrade header written twice. */
  const upgradeAnswer = (origin, upgradeValues) => new Promise((resolve, reject) => {
    const socket = require('net').connect({ host: new URL(stack.app).hostname, port: new URL(stack.app).port });
    socket.setTimeout(10000, () => { socket.destroy(); reject(new Error('timeout')); });
    const lines = [`GET ${GRAPHQL} HTTP/1.1`, 'Host: localhost', ...upgradeValues.map((value) => `Upgrade: ${value}`), 'Connection: Upgrade',
      'Sec-WebSocket-Version: 13', 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==', `Origin: ${origin}`, '', ''];
    socket.on('connect', () => socket.write(lines.join('\r\n')));
    socket.on('data', (chunk) => { resolve(chunk.toString('latin1').split('\r\n')[0].trim()); socket.destroy(); });
    socket.on('error', reject);
  });

  test('a second Upgrade header naming websocket does not skip the origin check', async () => {
    expectFixture('http-websocket-origin', {
      'refused origin, one Upgrade header': await upgradeAnswer('https://evil.contract.test', ['websocket']),
      'refused origin, two Upgrade headers': await upgradeAnswer('https://evil.contract.test', ['foo', 'websocket']),
      'allowed origin, two Upgrade headers': await upgradeAnswer(ORIGIN, ['foo', 'websocket']),
    });
  });
});
