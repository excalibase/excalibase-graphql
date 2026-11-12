const { execFileSync } = require('child_process');
const { request, waitUntil } = require('./lib/http');
const { stack } = require('./lib/stack');
const { token, bearer } = require('./lib/tokens');
const { PROFILE } = require('./lib/traffic');
const { expectFixture } = require('./lib/fixture');
const { newTraceparent, collectTrace, describeSpans, attributeValues, readLines } = require('./lib/otel');

const service = () => bearer(token({ role: 'service' }));
const SINGLE = 'http://127.0.0.1:14200';

const SCENARIOS = {
  'graphql query': (base, traceparent) => request(base, `/${stack.project}/graphql`, { method: 'POST', headers: { ...service(), traceparent }, body: { query: '{ hanaCustomer(limit: 1) { customer_id } }' } }),
  'graphql mutation': (base, traceparent) => request(base, `/${stack.project}/graphql`, { method: 'POST', headers: { ...service(), traceparent }, body: { query: 'mutation { createHanaCustomer(input: { first_name: "Traced", last_name: "Row" }) { customer_id } }' } }),
  'graphql error': (base, traceparent) => request(base, `/${stack.project}/graphql`, { method: 'POST', headers: { ...service(), traceparent }, body: { query: '{ doesNotExist }' } }),
  'rest read': (base, traceparent) => request(base, `/${stack.project}/api/v1/customer?limit=1`, { headers: { ...service(), ...PROFILE, traceparent } }),
  'rest error': (base, traceparent) => request(base, `/${stack.project}/api/v1/customer?customer_id=eq.bogus`, { headers: { ...service(), ...PROFILE, traceparent } }),
  'bad token': (base, traceparent) => request(base, `/${stack.project}/graphql`, { method: 'POST', headers: { Authorization: 'Bearer a.b.c', traceparent }, body: { query: '{ __typename }' } }),
};

async function traced(base, scenario) {
  const { traceId, parentSpanId, header } = newTraceparent();
  const response = await SCENARIOS[scenario](base, header);
  const spans = await collectTrace(traceId, { minSpans: 2 });
  return { response, spans, parentSpanId };
}

describe('traces exported over OTLP to the collector', () => {
  test('span names, kinds and attribute keys for each request class, multi-tenant engine', async () => {
    const result = {};
    for (const scenario of Object.keys(SCENARIOS)) {
      const { spans, parentSpanId } = await traced(stack.app, scenario);
      result[scenario] = describeSpans(spans, parentSpanId);
    }
    expectFixture('tracing-spans-multi-tenant', result);
  }, 180000);

  test('the same request classes on the single-database engine, where JDBC runs on the Spring datasource', async () => {
    const result = {};
    for (const scenario of ['graphql query', 'rest read']) {
      const { spans, parentSpanId } = await traced(SINGLE, scenario);
      result[scenario] = describeSpans(spans, parentSpanId);
    }
    expectFixture('tracing-spans-single-database', result);
  }, 120000);

  test('the server span carries the tenant and outcome attributes with their values', async () => {
    const { spans } = await traced(stack.app, 'graphql query');
    const attributes = attributeValues(spans, 'http post /{projectId}/graphql');
    expect(attributes).toMatchObject({ method: 'POST', status: '200', uri: '/{projectId}/graphql', outcome: 'SUCCESS', exception: 'none', 'tenant.id': stack.project });
    expect(attributeValues(spans, 'graphql.request')).toEqual({ 'operation.type': 'query', 'operation.name': 'anonymous' });
  });

  test('the incoming traceparent is continued: the server span is a child of the caller', async () => {
    const { spans, parentSpanId } = await traced(stack.app, 'rest read');
    expect(spans.find((span) => span.name.startsWith('http get')).parentSpanId).toBe(parentSpanId);
  });

  test('every server span of the trace shares the trace id and has kind SERVER', async () => {
    const { spans } = await traced(stack.app, 'graphql query');
    const server = spans.filter((span) => span.name.startsWith('http '));
    expect(server.length).toBe(1);
    expect(server[0].kind).toBe(2);
  });

  test('a log line written during a traced request carries the trace and span id of that request', async () => {
    const { traceId, header } = newTraceparent();
    await request(stack.app, `/${stack.project}/api/v1/customer?first_name=cs.{A}`, { headers: { ...service(), ...PROFILE, traceparent: header } });
    const container = process.env.CONTRACT_ENGINE_CONTAINER || 'excalibase-contracts-engine-1';
    const line = await waitUntil(() => execFileSync('docker', ['logs', container], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
      .split('\n').filter((entry) => entry.startsWith('{')).map((entry) => JSON.parse(entry)).find((entry) => entry.traceId === traceId), { label: 'log line with the trace id' });
    expect(line.spanId).toMatch(/^[0-9a-f]{16}$/);
    expect(line.tenant).toBe(stack.project);
  });
});

describe('metrics and logs over OTLP', () => {
  test('metric names pushed to the collector', async () => {
    const names = () => new Set(readLines('metrics.jsonl').flatMap((batch) => batch.resourceMetrics.flatMap((resource) => resource.scopeMetrics.flatMap((scope) => scope.metrics.map((metric) => metric.name)))));
    await waitUntil(() => names().has('http.server.requests') && names().has('hikaricp.connections'), { timeoutMs: 90000, intervalMs: 3000, label: 'OTLP metrics push' });
    const exported = [...names()].filter((name) => !name.startsWith('jvm.gc.')).sort();
    expectFixture('tracing-otlp-metric-names', exported);
  }, 120000);

  test('log records are not exported over OTLP: no appender is configured', async () => {
    expect(readLines('logs.jsonl')).toEqual([]);
  });
});
