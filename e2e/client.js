const { GraphQLClient } = require('graphql-request');

/**
 * Polls the GraphQL endpoint until it responds, then resolves.
 */
async function waitForApi(url, { maxRetries = 15, delayMs = 5000 } = {}) {
  for (let i = 1; i <= maxRetries; i++) {
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: '{ __typename }' }),
        signal: AbortSignal.timeout(5000),
      });
      if (res.status < 500) return; // API is up (even 400 means it parsed the request)
    } catch (_) {
      // not ready yet
    }
    if (i < maxRetries) {
      console.log(`[INFO] API not ready, attempt ${i}/${maxRetries} — retrying in ${delayMs / 1000}s...`);
      await new Promise(r => setTimeout(r, delayMs));
    }
  }
  throw new Error(`API at ${url} not ready after ${maxRetries} attempts`);
}

/**
 * Creates a GraphQLClient for the given URL and optional headers.
 */
function createClient(url, headers = {}) {
  return new GraphQLClient(url, { headers });
}

/**
 * Execute SQL via docker exec psql against the Postgres container.
 */
function psql(sql) {
  const { execSync } = require('child_process');
  const container = process.env.PG_CONTAINER || 'excalibase-postgres';
  const user = process.env.PG_USER || 'hana001';
  const db = process.env.PG_DB || 'hana';
  const cmd = `docker exec ${container} psql -U ${user} -d ${db} -c "${sql.replace(/"/g, '\\"')}"`;
  return execSync(cmd, { encoding: 'utf-8' });
}

/**
 * Execute SQL via docker exec mysql against the MySQL container.
 */
function mysqlExec(sql) {
  const { execSync } = require('child_process');
  const container = process.env.MYSQL_CONTAINER || 'excalibase-mysql';
  const user = process.env.MYSQL_USER || 'app_user';
  const password = process.env.MYSQL_PASSWORD || 'password123';
  const db = process.env.MYSQL_DB || 'excalibase';
  const cmd = `docker exec ${container} mysql -u${user} -p${password} ${db} -e "${sql.replace(/"/g, '\\"')}"`;
  return execSync(cmd, { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] });
}

/**
 * Subscribe to a GraphQL subscription via WebSocket (graphql-transport-ws protocol).
 *
 * Returns { events, close, ready } where:
 *   - events: array that fills as CDC events arrive (HEARTBEAT filtered out)
 *   - close(): tear down the WebSocket
 *   - ready: Promise that resolves once the subscription is active
 */
function subscribeGraphQL(wsUrl, subscriptionQuery) {
  const WebSocket = require('ws');
  const events = [];
  const ws = new WebSocket(wsUrl, 'graphql-transport-ws');

  const ready = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('WS subscription timeout')), 15000);

    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'connection_init' }));
    });

    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString());

      if (msg.type === 'connection_ack') {
        ws.send(JSON.stringify({
          id: 'sub-1',
          type: 'subscribe',
          payload: { query: subscriptionQuery },
        }));
        // Allow a moment for the subscription to register server-side
        setTimeout(() => { clearTimeout(timeout); resolve(); }, 1500);
      }

      if (msg.type === 'next') {
        // Extract the first subscription field value (e.g. customerChanges)
        const data = msg.payload?.data;
        if (data) {
          const key = Object.keys(data)[0];
          const event = data[key];
          if (event && event.operation !== 'HEARTBEAT') {
            events.push(event);
          }
        }
      }

      if (msg.type === 'error') {
        clearTimeout(timeout);
        reject(new Error(`Subscription error: ${JSON.stringify(msg.payload)}`));
      }
    });

    ws.on('error', (err) => {
      clearTimeout(timeout);
      reject(err);
    });
  });

  const close = () => {
    try {
      ws.send(JSON.stringify({ id: 'sub-1', type: 'complete' }));
    } catch (_) {}
    ws.close();
  };

  return { events, close, ready };
}

/**
 * Wait until predicate is true on an array, polling every 200ms.
 */
async function waitFor(arr, predicate, timeoutMs = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (predicate(arr)) return;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out after ${timeoutMs}ms. Events: ${JSON.stringify(arr)}`);
}

module.exports = { waitForApi, createClient, psql, mysqlExec, subscribeGraphQL, waitFor };
