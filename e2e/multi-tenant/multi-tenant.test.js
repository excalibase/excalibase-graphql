const { GraphQLClient, gql } = require('graphql-request');
const { waitForApi } = require('../client');

const GRAPHQL_URL = process.env.MT_GRAPHQL_URL || 'http://localhost:10003/graphql';
const AUTH_URL = process.env.MT_AUTH_URL || 'http://localhost:24003/auth';

const TENANT_A = { orgSlug: 'acme-corp', projectName: 'app-a' };
const TENANT_B = { orgSlug: 'beta-inc', projectName: 'app-b' };

let clientA, clientB;
let tokenA, tokenB;

async function authPost(path, body) {
  const res = await fetch(`${AUTH_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

async function waitForAuth() {
  for (let i = 0; i < 30; i++) {
    try {
      const r = await fetch(`${AUTH_URL.replace('/auth', '')}/healthz`, { signal: AbortSignal.timeout(3000) });
      if (r.ok) return;
    } catch (_) {}
    await new Promise(r => setTimeout(r, 3000));
  }
  throw new Error('Auth service not ready');
}

async function registerAndLogin(tenant, email, password, fullName) {
  const base = `/${tenant.orgSlug}/${tenant.projectName}`;

  // Register (ignore 409 if already exists)
  const reg = await authPost(`${base}/register`, { email, password, fullName });
  if (reg.status !== 200 && reg.status !== 201 && reg.status !== 409) {
    throw new Error(`Register failed (${reg.status}) for ${email}: ${JSON.stringify(reg.data)}`);
  }

  // Login
  const login = await authPost(`${base}/login`, { email, password });
  if (!login.data.accessToken) {
    throw new Error(`Login failed for ${email} on ${tenant.orgSlug}/${tenant.projectName}: ${JSON.stringify(login.data)}`);
  }
  return login.data.accessToken;
}

beforeAll(async () => {
  // Wait for both services in parallel
  await Promise.all([
    waitForAuth(),
    waitForApi(GRAPHQL_URL, { maxRetries: 30, delayMs: 3000 }),
  ]);

  // Register + login on both tenants in parallel
  [tokenA, tokenB] = await Promise.all([
    registerAndLogin(TENANT_A, 'alice@acme.com', 'Pass123!', 'Alice A'),
    registerAndLogin(TENANT_B, 'bob@beta.com', 'Pass123!', 'Bob B'),
  ]);

  clientA = new GraphQLClient(GRAPHQL_URL, {
    headers: { Authorization: `Bearer ${tokenA}` },
  });
  clientB = new GraphQLClient(GRAPHQL_URL, {
    headers: { Authorization: `Bearer ${tokenB}` },
  });
});

// ─── Tenant A Data Isolation ─────────────────────────────────────────────────

describe('Tenant A data isolation', () => {
  test('JWT A queries products from tenant-a-postgres', async () => {
    const data = await clientA.request(gql`{
      tenantProducts(orderBy: { id: ASC }) { id name price }
    }`);
    expect(data.tenantProducts).toHaveLength(3);
    expect(data.tenantProducts[0].name).toBe('Widget A');
    expect(data.tenantProducts[1].name).toBe('Gadget A');
    expect(data.tenantProducts[2].name).toBe('Gizmo A');
  });

  test('JWT A cannot query items (table does not exist in tenant A)', async () => {
    try {
      await clientA.request(gql`{ tenantItems { id title } }`);
      throw new Error('Expected query to fail but it succeeded');
    } catch (e) {
      expect(e.response?.errors).toBeDefined();
    }
  });
});

// ─── Tenant B Data Isolation ─────────────────────────────────────────────────

describe('Tenant B data isolation', () => {
  test('JWT B queries items from tenant-b-postgres', async () => {
    const data = await clientB.request(gql`{
      tenantItems(orderBy: { id: ASC }) { id title quantity }
    }`);
    expect(data.tenantItems).toHaveLength(2);
    expect(data.tenantItems[0].title).toBe('Item X');
    expect(data.tenantItems[1].title).toBe('Item Y');
  });

  test('JWT B cannot query products (table does not exist in tenant B)', async () => {
    try {
      await clientB.request(gql`{ tenantProducts { id name } }`);
      throw new Error('Expected query to fail but it succeeded');
    } catch (e) {
      expect(e.response?.errors).toBeDefined();
    }
  });
});

// ─── Schema Introspection Isolation ──────────────────────────────────────────

describe('Schema introspection isolation', () => {
  test('JWT A introspection has tenantProducts, not tenantItems', async () => {
    const data = await clientA.request(gql`{
      __schema { queryType { fields { name } } }
    }`);
    const fieldNames = data.__schema.queryType.fields.map(f => f.name);
    expect(fieldNames).toContain('tenantProducts');
    expect(fieldNames).not.toContain('tenantItems');
  });

  test('JWT B introspection has tenantItems, not tenantProducts', async () => {
    const data = await clientB.request(gql`{
      __schema { queryType { fields { name } } }
    }`);
    const fieldNames = data.__schema.queryType.fields.map(f => f.name);
    expect(fieldNames).toContain('tenantItems');
    expect(fieldNames).not.toContain('tenantProducts');
  });
});

// ─── Mutations ───────────────────────────────────────────────────────────────

describe('Mutations on tenant databases', () => {
  test('JWT A creates product on tenant A database', async () => {
    const data = await clientA.request(gql`mutation {
      createTenantProducts(input: { name: "E2E Product", price: 42.00 }) {
        id name price
      }
    }`);
    expect(data.createTenantProducts.name).toBe('E2E Product');
    expect(data.createTenantProducts.price).toBe(42.00);
  });

  test('JWT B creates item on tenant B database', async () => {
    const data = await clientB.request(gql`mutation {
      createTenantItems(input: { title: "E2E Item", quantity: 99 }) {
        id title quantity
      }
    }`);
    expect(data.createTenantItems.title).toBe('E2E Item');
    expect(data.createTenantItems.quantity).toBe(99);
  });
});

// ─── CDC subscription isolation ──────────────────────────────────────────────
//
// Stack: per-tenant watcher-go → shared NATS CDC stream → graphql NatsCDCService
// filters by subject prefix parsed from each event. The WS handler verifies the
// JWT in connection_init and uses the tenant claim (orgSlug.projectName) to
// route subscription events. Expect: JWT-A subscribers see only tenant-A events.

const WebSocket = require('ws');
const { execSync } = require('child_process');

const WS_URL = process.env.MT_GRAPHQL_WS_URL || 'ws://localhost:10003/graphql';

function psqlOn(container, db, sql) {
  const cmd = `docker exec ${container} psql -U postgres -d ${db} -c "${sql.replace(/"/g, '\\"')}"`;
  return execSync(cmd, { encoding: 'utf-8' });
}

function subscribeWithJwt(token, subscriptionQuery) {
  const events = [];
  const connectionErrors = [];
  const ws = new WebSocket(WS_URL, 'graphql-transport-ws');

  const ready = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('WS subscription timeout')), 15000);
    ws.on('open', () => {
      ws.send(JSON.stringify({
        type: 'connection_init',
        payload: { Authorization: `Bearer ${token}` },
      }));
    });
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'connection_ack') {
        ws.send(JSON.stringify({
          id: 'sub-1',
          type: 'subscribe',
          payload: { query: subscriptionQuery },
        }));
        setTimeout(() => { clearTimeout(timeout); resolve(); }, 1500);
      }
      if (msg.type === 'connection_error') {
        connectionErrors.push(msg.payload);
        clearTimeout(timeout);
        reject(new Error(`connection_error: ${JSON.stringify(msg.payload)}`));
      }
      if (msg.type === 'next') {
        const data = msg.payload?.data;
        if (data) {
          const event = data[Object.keys(data)[0]];
          if (event && event.operation !== 'HEARTBEAT') events.push(event);
        }
      }
    });
    ws.on('error', (err) => { clearTimeout(timeout); reject(err); });
  });

  return {
    events,
    connectionErrors,
    close: () => { try { ws.close(); } catch (_) {} },
    ready,
  };
}

async function waitFor(arr, predicate, timeoutMs = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (predicate(arr)) return;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out. Events: ${JSON.stringify(arr)}`);
}

describe('CDC subscription isolation (per-tenant watcher-go → NATS → WS)', () => {
  let subA;
  let subB;

  afterEach(() => {
    if (subA) { subA.close(); subA = null; }
    if (subB) { subB.close(); subB = null; }
  });

  test('WS connection_init without token is rejected (jwt-enabled=true)', async () => {
    const ws = new WebSocket(WS_URL, 'graphql-transport-ws');
    const outcome = await new Promise((resolve) => {
      const events = [];
      ws.on('open', () => ws.send(JSON.stringify({ type: 'connection_init' })));
      ws.on('message', (raw) => events.push(JSON.parse(raw.toString())));
      ws.on('close', () => resolve(events));
      setTimeout(() => { try { ws.close(); } catch (_) {} }, 5000);
    });
    const err = outcome.find((m) => m.type === 'connection_error');
    expect(err).toBeDefined();
  });

  test('tenant-A subscription receives INSERT from tenant-A DB only', async () => {
    subA = subscribeWithJwt(
      await getTokenA(),
      'subscription { tenantProductsChanges { operation table data } }'
    );
    await subA.ready;

    psqlOn('mt-tenant-a-postgres', 'tenant_a_db',
      "INSERT INTO tenant.products (name, price) VALUES ('MT_CDC_Iso_A', 7.77)");

    await waitFor(subA.events, (e) =>
      e.some((ev) => ev.operation === 'INSERT' && ev.table === 'products'
        && ev.data && JSON.stringify(ev.data).includes('MT_CDC_Iso_A'))
    );
    const hit = subA.events.find((ev) => ev.operation === 'INSERT' && ev.table === 'products');
    expect(hit).toBeDefined();
  });

  test('tenant-B subscription receives INSERT from tenant-B DB only', async () => {
    subB = subscribeWithJwt(
      await getTokenB(),
      'subscription { tenantItemsChanges { operation table data } }'
    );
    await subB.ready;

    psqlOn('mt-tenant-b-postgres', 'tenant_b_db',
      "INSERT INTO tenant.items (title, quantity) VALUES ('MT_CDC_Iso_B', 13)");

    await waitFor(subB.events, (e) =>
      e.some((ev) => ev.operation === 'INSERT' && ev.table === 'items'
        && ev.data && JSON.stringify(ev.data).includes('MT_CDC_Iso_B'))
    );
    const hit = subB.events.find((ev) => ev.operation === 'INSERT' && ev.table === 'items');
    expect(hit).toBeDefined();
  });

  test('cross-tenant leak: both tenants write, each WS only sees its own tenant', async () => {
    subA = subscribeWithJwt(
      await getTokenA(),
      'subscription { tenantProductsChanges { operation table data } }'
    );
    subB = subscribeWithJwt(
      await getTokenB(),
      'subscription { tenantItemsChanges { operation table data } }'
    );
    await Promise.all([subA.ready, subB.ready]);

    psqlOn('mt-tenant-a-postgres', 'tenant_a_db',
      "INSERT INTO tenant.products (name, price) VALUES ('MT_CDC_Cross_A', 1.11)");
    psqlOn('mt-tenant-b-postgres', 'tenant_b_db',
      "INSERT INTO tenant.items (title, quantity) VALUES ('MT_CDC_Cross_B', 2)");

    await Promise.all([
      waitFor(subA.events, (e) => e.some((ev) => ev.operation === 'INSERT')),
      waitFor(subB.events, (e) => e.some((ev) => ev.operation === 'INSERT')),
    ]);

    // Tenant-A stream: only products
    expect(subA.events.every((ev) => ev.table === 'products')).toBe(true);
    expect(subA.events.find((ev) => ev.table === 'items')).toBeUndefined();

    // Tenant-B stream: only items
    expect(subB.events.every((ev) => ev.table === 'items')).toBe(true);
    expect(subB.events.find((ev) => ev.table === 'products')).toBeUndefined();
  }, 30000);

  test('tenant routing (same table name on both tenants) — proves tenantId-in-sink-key, not table name, isolates', async () => {
    // Both tenants have `tenant.shared_counters` with identical shape. If
    // SubscriptionService keyed sinks by table name alone, both subscribers
    // would see both tenants' events. The tenant dimension must do the filtering.
    subA = subscribeWithJwt(
      await getTokenA(),
      'subscription { tenantSharedCountersChanges { operation table data } }'
    );
    subB = subscribeWithJwt(
      await getTokenB(),
      'subscription { tenantSharedCountersChanges { operation table data } }'
    );
    await Promise.all([subA.ready, subB.ready]);

    psqlOn('mt-tenant-a-postgres', 'tenant_a_db',
      "INSERT INTO tenant.shared_counters (label, value) VALUES ('A_SIDE', 111)");
    psqlOn('mt-tenant-b-postgres', 'tenant_b_db',
      "INSERT INTO tenant.shared_counters (label, value) VALUES ('B_SIDE', 222)");

    // Each side must see its own label — and only its own.
    await waitFor(subA.events, (e) =>
      e.some((ev) => ev.operation === 'INSERT' && JSON.stringify(ev.data).includes('A_SIDE'))
    );
    await waitFor(subB.events, (e) =>
      e.some((ev) => ev.operation === 'INSERT' && JSON.stringify(ev.data).includes('B_SIDE'))
    );

    // Give a grace window for any cross-leak to arrive if the routing is broken.
    await new Promise((r) => setTimeout(r, 2000));

    const aSawB = subA.events.find((ev) => JSON.stringify(ev.data).includes('B_SIDE'));
    const bSawA = subB.events.find((ev) => JSON.stringify(ev.data).includes('A_SIDE'));
    expect(aSawB).toBeUndefined();
    expect(bSawA).toBeUndefined();
  }, 30000);
});

// tokenA / tokenB are hoisted to module scope (assigned in beforeAll above).
async function getTokenA() { return tokenA; }
async function getTokenB() { return tokenB; }
