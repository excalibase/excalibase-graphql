const net = require('net');
const { request } = require('./lib/http');
const { stack } = require('./lib/stack');
const { parseSamples } = require('./lib/prometheus');

const MAX_CONNECTIONS = 4;
const base = new URL(stack.overload);

/** A connection that holds its slot: half a request line is sent, so it is neither idle between requests nor finished. */
function holdConnection() {
  return new Promise((resolve, reject) => {
    const socket = net.connect(Number(base.port), base.hostname, () => {
      socket.write(`GET /${stack.project}/gra`, () => resolve(socket));
    });
    socket.on('error', reject);
  });
}

/** Sends one request on a fresh connection; resolves when the first byte of the answer arrives. */
function timedRequest() {
  return new Promise((resolve) => {
    const started = Date.now();
    const socket = net.connect(Number(base.port), base.hostname, () => {
      socket.write(`GET /${stack.project}/graphql HTTP/1.1\r\nHost: ${base.host}\r\nConnection: close\r\n\r\n`);
    });
    const finish = (status, text = '') => { socket.destroy(); resolve({ status, text, firstByteAfterMs: Date.now() - started }); };
    socket.once('data', (chunk) => {
      const text = chunk.toString('latin1');
      finish(Number(text.split(' ')[1]), text);
    });
    socket.on('error', () => finish(null));
    socket.setTimeout(30000, () => finish(null));
  });
}

async function gauge(name) {
  const samples = parseSamples((await request(stack.overloadManagement, '/actuator/prometheus')).text);
  const sample = samples.find((candidate) => candidate.name === name);
  return sample ? sample.value : 0;
}

/** Polls a server-side gauge until the condition holds, so a test never acts on a guess about what the server has seen. */
async function awaitGauge(name, condition, description) {
  for (let attempt = 0; attempt < 400; attempt += 1) {
    if (condition(await gauge(name))) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`the server never reported ${description}`);
}

const OPEN = 'tomcat_connections_current_connections';
const WAITING = 'excalibase_connections_waiting_connections';
const awaitOpen = (count) => awaitGauge(OPEN, (value) => value === count, `${count} open connections`);
const awaitWaiting = (count) => awaitGauge(WAITING, (value) => value === count, `${count} waiting connections`);

describe('overload bound on a 1 CPU engine', () => {
  let held = [];
  // The next test starts from an empty gate: slots of destroyed sockets are freed by the server asynchronously.
  afterEach(async () => {
    held.forEach((socket) => socket.destroy());
    held = [];
    await awaitOpen(0);
    await awaitWaiting(0);
  });

  test('the bound is the configured connections per CPU times one CPU', async () => {
    const samples = parseSamples((await request(stack.overloadManagement, '/actuator/prometheus')).text);
    const bound = samples.find((sample) => sample.name === 'tomcat_connections_config_max_connections');
    expect(bound.value).toBe(MAX_CONNECTIONS);
  });

  test('a client beyond the bound is refused at once with 503, Retry-After and a GraphQL error', async () => {
    held = await Promise.all(Array.from({ length: MAX_CONNECTIONS }, holdConnection));
    await awaitOpen(MAX_CONNECTIONS);
    await Promise.all(Array.from({ length: MAX_CONNECTIONS }, () => new Promise((resolve) => {
      const socket = net.connect(Number(base.port), base.hostname, () => resolve(socket));
      held.push(socket);
    })));
    await awaitWaiting(MAX_CONNECTIONS);   // the wait queue is full, so the next connection is refused at once

    const answer = await timedRequest();
    expect(answer.status).toBe(503);
    expect(answer.text.toLowerCase()).toContain('retry-after: ');
    expect(answer.text).toContain('SERVICE_OVERLOADED');
    expect(answer.firstByteAfterMs).toBeLessThan(1000);

    const health = await request(stack.overloadManagement, '/actuator/health');
    expect(health.status).toBe(200);
    const samples = parseSamples((await request(stack.overloadManagement, '/actuator/prometheus')).text);
    const refused = samples.find((sample) => sample.name === 'excalibase_connections_refused_connections_total');
    expect(refused.value).toBeGreaterThanOrEqual(1);
  }, 30000);

  test('a connection that finds a slot within the admission wait is served, not refused', async () => {
    held = await Promise.all(Array.from({ length: MAX_CONNECTIONS }, holdConnection));
    await awaitOpen(MAX_CONNECTIONS);
    const waitingRequest = timedRequest();
    await awaitWaiting(1);                 // the request is queued for a slot, inside its admission wait
    held[0].destroy();
    const answer = await waitingRequest;
    expect(answer.status).toBe(405);
  }, 30000);

  test('with fewer connections than the bound a request is answered immediately', async () => {
    const answer = await timedRequest();
    expect(answer.status).toBe(405);
    expect(answer.firstByteAfterMs).toBeLessThan(1000);
  });
});
