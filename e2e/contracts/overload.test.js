const net = require('net');
const { request } = require('./lib/http');
const { stack } = require('./lib/stack');
const { parseSamples } = require('./lib/prometheus');

const MAX_CONNECTIONS = 4;
const KEEP_ALIVE_TIMEOUT_MS = 5000;
const base = new URL(stack.overload);

/** A keep-alive connection that has finished one request and now idles, holding its connection slot. */
function holdConnection() {
  return new Promise((resolve, reject) => {
    const socket = net.connect(Number(base.port), base.hostname, () => {
      socket.write(`GET /${stack.project}/graphql HTTP/1.1\r\nHost: ${base.host}\r\nConnection: keep-alive\r\n\r\n`);
    });
    socket.once('data', () => setTimeout(() => resolve(socket), 200));
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
    const finish = (status) => { socket.destroy(); resolve({ status, firstByteAfterMs: Date.now() - started }); };
    socket.once('data', (chunk) => finish(Number(chunk.toString('latin1').split(' ')[1])));
    socket.on('error', () => finish(null));
    socket.setTimeout(30000, () => finish(null));
  });
}

const stillPending = (promise) => Promise.race([promise.then(() => false), new Promise((resolve) => setTimeout(() => resolve(true), 1500))]);

describe('overload bound on a 1 CPU engine', () => {
  let held = [];
  afterEach(() => { held.forEach((socket) => socket.destroy()); held = []; });

  test('the bound is the configured connections per CPU times one CPU', async () => {
    const samples = parseSamples((await request(stack.overloadManagement, '/actuator/prometheus')).text);
    const bound = samples.find((sample) => sample.name === 'tomcat_connections_config_max_connections');
    expect(bound.value).toBe(MAX_CONNECTIONS);
  });

  test('a client beyond the bound connects, waits without an error, and is served when an idle slot frees', async () => {
    held = await Promise.all(Array.from({ length: MAX_CONNECTIONS }, holdConnection));
    const waitingRequest = timedRequest();
    expect(await stillPending(waitingRequest)).toBe(true);

    const health = await request(stack.overloadManagement, '/actuator/health');
    expect(health.status).toBe(200);

    const answer = await waitingRequest;
    expect(answer.status).toBe(405);
    expect(answer.firstByteAfterMs).toBeGreaterThanOrEqual(1500);
    expect(answer.firstByteAfterMs).toBeLessThan(KEEP_ALIVE_TIMEOUT_MS + 6000);
  }, 40000);

  test('closing one held connection serves the waiting client at once', async () => {
    held = await Promise.all(Array.from({ length: MAX_CONNECTIONS }, holdConnection));
    const waitingRequest = timedRequest();
    expect(await stillPending(waitingRequest)).toBe(true);
    const freedAt = Date.now();
    held[0].destroy();
    const answer = await waitingRequest;
    expect(answer.status).toBe(405);
    expect(Date.now() - freedAt).toBeLessThan(2000);
  }, 30000);

  test('with fewer connections than the bound a request is answered immediately', async () => {
    const answer = await timedRequest();
    expect(answer.status).toBe(405);
    expect(answer.firstByteAfterMs).toBeLessThan(1000);
  });
});
