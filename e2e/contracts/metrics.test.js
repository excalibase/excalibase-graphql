const { request } = require('./lib/http');
const { stack } = require('./lib/stack');
const { token, bearer } = require('./lib/tokens');
const { sendKnownMix, graphql, PROFILE } = require('./lib/traffic');
const { parseMetricShape, parseSamples, withoutAbsentOptional } = require('./lib/prometheus');
const { expectFixture } = require('./lib/fixture');
const { startEngine, removeAll, SINGLE_DATABASE_ENV } = require('./lib/containers');

const OPTIONAL_PREFIXES = ['jvm_gc_'];
const service = () => bearer(token({ role: 'service' }));

async function shapeOf(base, path) {
  const response = await request(base, path);
  expect(response.status).toBe(200);
  return parseMetricShape(response.text);
}

/** Compares to the reviewed fixture with the timing-dependent GC names treated as optional. */
function expectShape(name, actual) {
  const fs = require('fs');
  const path = require('path');
  const file = path.join(__dirname, 'fixtures', `${name}.json`);
  if (process.env.UPDATE_CONTRACTS === '1') {
    expectFixture(name, actual);
    return;
  }
  const expected = JSON.parse(fs.readFileSync(file, 'utf8'));
  const [expectedShape, actualShape] = withoutAbsentOptional(expected, actual, OPTIONAL_PREFIXES);
  expect(actualShape).toEqual(expectedShape);
}

afterAll(removeAll);

describe('prometheus metrics: names and label keys', () => {
  beforeAll(async () => {
    await sendKnownMix(stack.app);
    await sendKnownMix(`http://127.0.0.1:14200`);
  }, 60000);

  test('multi-tenant engine, actuator /prometheus', async () => {
    expectShape('metrics-multi-tenant', await shapeOf(stack.management, '/actuator/prometheus'));
  });

  test('single-database engine adds the Hikari pool metrics', async () => {
    expectShape('metrics-single-database', await shapeOf('http://127.0.0.1:14281', '/actuator/prometheus'));
  });

  test('per-project request metrics on the internal port', async () => {
    expectFixture('metrics-request-port', await shapeOf(stack.requestMetrics, '/metrics'));
  });

  test('the request metrics are not served on the public port', async () => {
    expect((await request(stack.app, '/metrics')).status).toBe(404);
  });
});

describe('http.server.requests series', () => {
  const EXPECTED = [
    { method: 'POST', status: '200', uri: '/{projectId}/graphql', outcome: 'SUCCESS', exception: 'none' },
    { method: 'GET', status: '200', uri: '/{projectId}/api/v1/{table}', outcome: 'SUCCESS', exception: 'none' },
    { method: 'GET', status: '400', uri: '/{projectId}/api/v1/{table}', outcome: 'CLIENT_ERROR', exception: 'none' },
    { method: 'POST', status: '401', uri: '/{projectId}/graphql', outcome: 'CLIENT_ERROR', exception: 'none' },
    { method: 'POST', status: '404', uri: '/{projectId}/graphql', outcome: 'CLIENT_ERROR', exception: 'none' },
    { method: 'GET', status: '404', uri: '/**', outcome: 'CLIENT_ERROR', exception: 'none' },
    { method: 'GET', status: '101', uri: 'UNKNOWN', outcome: 'INFORMATIONAL', exception: 'none' },
    { method: 'POST', status: '500', uri: '/{projectId}/api/v1/rpc/{function}', outcome: 'SERVER_ERROR', exception: 'InvalidFunctionNameException' },
    { method: 'GET', status: '400', uri: '/{projectId}/api/v1/rpc/{function}', outcome: 'CLIENT_ERROR', exception: 'RequestRejectedException' },
  ];

  test('uri is the route template, never the raw path, and failures carry outcome and exception', async () => {
    const headers = service();
    await graphql(stack.app, '{ __typename }', headers);
    await request(stack.app, `/${stack.project}/api/v1/customer?limit=1`, { headers: { ...headers, ...PROFILE } });
    await request(stack.app, `/${stack.project}/api/v1/customer?customer_id=eq.bogus`, { headers: { ...headers, ...PROFILE } });
    await request(stack.app, `/${stack.project}/graphql`, { method: 'POST', headers: { Authorization: 'Bearer a.b.c' }, body: { query: '{ __typename }' } });
    await request(stack.app, `/no-such-project/graphql`, { method: 'POST', headers: bearer(token({ project: 'no-such-project' })), body: { query: '{ __typename }' } });
    await request(stack.app, `/${stack.project}/api/v1/rpc/no-such;fn`, { headers });
    await request(stack.app, `/${stack.project}/api/v1/rpc/a.b.c`, { method: 'POST', headers: { ...headers, ...PROFILE }, body: {} });
    await request(stack.app, `/${stack.project}/nothing-here`, { headers });
    const samples = parseSamples((await request(stack.management, '/actuator/prometheus')).text)
      .filter((sample) => sample.name === 'http_server_requests_seconds_count');
    for (const expected of EXPECTED) {
      const found = samples.some((sample) => Object.entries(expected).every(([key, value]) => sample.labels[key] === value));
      expect({ expected, found }).toEqual({ expected, found: true });
    }
    expect(samples.every((sample) => !sample.labels.uri.includes('e2e-test'))).toBe(true);
  });

  test('tenant labels name the project on project routes', async () => {
    const samples = parseSamples((await request(stack.management, '/actuator/prometheus')).text)
      .filter((sample) => sample.name === 'http_server_requests_seconds_count' && sample.labels.uri === '/{projectId}/graphql' && sample.labels.status === '200');
    expect(samples.some((sample) => sample.labels.tenant_id === stack.project)).toBe(true);
  });
});

describe('request metrics are off by default', () => {
  test('nothing listens on the request metrics port unless enabled', async () => {
    const engine = await startEngine(SINGLE_DATABASE_ENV, { readyPort: 8081, readyPath: '/actuator/health', readyUntil: (response) => response.status === 200 });
    try {
      const refused = await request(`http://${engine.address}:9464`, '/metrics').then(() => 'answered', (error) => error.code);
      expect(refused).toBe('ECONNREFUSED');
    } finally {
      engine.stop();
    }
  }, 120000);
});
