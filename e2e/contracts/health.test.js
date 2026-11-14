const { request, waitUntil } = require('./lib/http');
const { stack } = require('./lib/stack');
const { capture } = require('./lib/capture');
const { expectFixture } = require('./lib/fixture');

/** GC cycles show up in the metric names only once one has run, which depends on timing. */
const withoutGcNames = (value) => JSON.parse(JSON.stringify(value, (key, item) => (Array.isArray(item) ? item.filter((entry) => typeof entry !== 'string' || !entry.startsWith('jvm.gc.')) : item)));
const { docker, startEngine, removeContainer, removeAll, SINGLE_DATABASE_ENV } = require('./lib/containers');

const PATHS = ['/actuator', '/actuator/health', '/actuator/health/liveness', '/actuator/health/readiness',
  '/actuator/info', '/actuator/env', '/actuator/beans', '/actuator/loggers', '/health', '/'];

async function capturePaths(base, paths) {
  const result = {};
  for (const path of paths) {
    const { body, ...rest } = capture(await request(base, path));
    result[path] = { ...rest, body: path === '/actuator' ? Object.keys(body._links).sort() : body };
  }
  return result;
}

afterAll(removeAll);

describe('health on the management port', () => {
  test('paths, status codes and JSON shape with probes enabled', async () => {
    expectFixture('health-probes-enabled', await capturePaths(stack.management, PATHS));
  });

  test('the public port serves no actuator', async () => {
    const response = await request(stack.app, '/actuator/health');
    expect(response.status).toBe(404);
  });

  test('the default exposure, with no environment overrides', async () => {
    const engine = await startEngine(SINGLE_DATABASE_ENV, { readyPort: 8081, readyPath: '/actuator/health', readyUntil: (response) => response.status === 200 });
    try {
      expectFixture('health-default-exposure', await capturePaths(engine.management, [...PATHS, '/actuator/prometheus', '/actuator/metrics']), withoutGcNames);
    } finally {
      engine.stop();
    }
  }, 120000);

  test('a database that goes away turns health DOWN with 503, and back UP when it returns', async () => {
    const database = `contract-health-db-${Date.now()}`;
    docker('run', '-d', '--name', database, '--network', stack.network, '--network-alias', 'health-db',
      '-e', 'POSTGRES_PASSWORD=pw', '-e', 'POSTGRES_USER=u', '-e', 'POSTGRES_DB=d', 'postgres:16-alpine');
    try {
      await waitUntil(() => { docker('exec', database, 'pg_isready', '-U', 'u', '-d', 'd'); return true; }, { label: 'database' });
      await new Promise((resolve) => setTimeout(resolve, 3000));
      const engine = await startEngine({ ...SINGLE_DATABASE_ENV, POSTGRES_URL: 'jdbc:postgresql://health-db:5432/d', POSTGRES_USER: 'u', POSTGRES_PASSWORD: 'pw' },
        { readyPort: 8081, readyPath: '/actuator/health', readyUntil: (response) => response.status === 200 });
      try {
        const up = await request(engine.management, '/actuator/health');
        docker('stop', database);
        const down = await waitUntil(async () => { const r = await request(engine.management, '/actuator/health'); return r.status === 503 ? r : null; }, { timeoutMs: 60000, label: 'health DOWN' });
        const liveness = await request(engine.management, '/actuator/health/liveness');
        docker('start', database);
        const recovered = await waitUntil(async () => { const r = await request(engine.management, '/actuator/health'); return r.status === 200 ? r : null; }, { timeoutMs: 60000, label: 'health UP again' });
        expectFixture('health-database-down', {
          up: capture(up), down: capture(down), livenessWhileDown: capture(liveness), recovered: capture(recovered),
        });
      } finally {
        engine.stop();
      }
    } finally {
      removeContainer(database);
    }
  }, 240000);
});
