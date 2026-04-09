const { GraphQLClient, gql } = require('graphql-request');
const { waitForApi } = require('../client');

const GRAPHQL_URL = process.env.MT_GRAPHQL_URL || 'http://localhost:10003/graphql';
const AUTH_URL = process.env.MT_AUTH_URL || 'http://localhost:24003/auth';

const TENANT_A = { orgSlug: 'acme-corp', projectName: 'app-a' };
const TENANT_B = { orgSlug: 'beta-inc', projectName: 'app-b' };

let clientA, clientB;

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
  const [tokenA, tokenB] = await Promise.all([
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
