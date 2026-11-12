const { request } = require('./lib/http');
const { stack } = require('./lib/stack');
const { token, bearer } = require('./lib/tokens');
const { PROFILE } = require('./lib/traffic');
const { expectFixture } = require('./lib/fixture');
const { startEngine, removeAll, SINGLE_DATABASE_ENV } = require('./lib/containers');

const service = () => bearer(token({ role: 'service' }));
const READY = { readyPort: 8081, readyPath: '/actuator/health', readyUntil: (response) => response.status === 200 };

function flatten(entry, prefix = '') {
  return Object.entries(entry).flatMap(([key, value]) => (value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length
    ? flatten(value, `${prefix}${key}.`) : [`${prefix}${key}`]));
}

const jsonLines = (engine) => engine.logs().filter((line) => line.startsWith('{')).map((line) => JSON.parse(line));
const keySets = (entries) => [...new Set(entries.map((entry) => JSON.stringify(flatten(entry).sort())))].sort().map((json) => JSON.parse(json));

async function provokeRequestLogs(engine) {
  const headers = { ...service(), ...PROFILE };
  await request(engine.app, `/${stack.project}/api/v1/customer?limit=1`, { headers });
  await request(engine.app, `/${stack.project}/api/v1/customer?first_name=cs.{A}`, { headers });
  await request(engine.app, `/${stack.project}/api/v1/rpc/a.b.c`, { method: 'POST', headers, body: {} });
  await request(engine.app, `/${stack.project}/graphql`, { method: 'POST', headers, body: { query: '{ hanaCustomer(limit: 1) { customer_id } }' } });
  await new Promise((resolve) => setTimeout(resolve, 500));
}

async function observe(env) {
  const engine = await startEngine({ ...SINGLE_DATABASE_ENV, ...env }, READY);
  try {
    const startup = jsonLines(engine);
    await provokeRequestLogs(engine);
    const afterRequests = jsonLines(engine).slice(startup.length);
    const withStack = afterRequests.filter((entry) => entry.error && entry.error.stack_trace);
    return {
      startup: keySets(startup),
      afterRequests: keySets(afterRequests.filter((entry) => !(entry.error && entry.error.stack_trace))),
      afterRequestsWithStackTrace: keySets(withStack),
      stackTraceSample: withStack.map((entry) => ({ level: entry.log.level, type: entry.error.type, startsWithType: entry.error.stack_trace.startsWith(entry.error.type) })),
      constants: { ecsVersion: startup[0].ecs.version, serviceName: startup[0].service.name, levels: [...new Set(jsonLines(engine).map((entry) => entry.log.level))].sort() },
      nonJsonLines: engine.logs().filter((line) => !line.startsWith('{')).length > 0,
    };
  } finally {
    engine.stop();
  }
}

afterAll(removeAll);

describe('structured logs (ECS JSON)', () => {
  test('field names for startup, request and error-with-stack-trace lines, tracing on', async () => {
    expectFixture('logs-tracing-on', await observe({ OTEL_EXPORTER_OTLP_ENDPOINT: 'http://otel-collector:4318' }));
  }, 180000);

  test('without an OTLP endpoint trace and span ids are still logged', async () => {
    expectFixture('logs-tracing-off', await observe({}));
  }, 180000);
});
