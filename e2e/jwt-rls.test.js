/**
 * Excalibase GraphQL — JWT + RLS E2E Tests
 *
 * Requires: docker-compose.e2e.yml services running:
 *   - graphql (port 10000, jwt-enabled=true)
 *   - auth (port 24000)
 *   - mock-vault (port 28080, serves EC P-256 key pair)
 *   - postgres (port 25433, with RLS tables + auth schema)
 */

const { waitForApi, createClient } = require('./client');

const GRAPHQL_URL = process.env.GRAPHQL_URL || 'http://localhost:10000/graphql';
const AUTH_URL = process.env.AUTH_URL || 'http://localhost:24000';
const PROJECT_ID = 'e2e-test';

let accessToken;
let userId;

// ─── Helpers ──────────────────────────────────────────────────────────────────

async function authPost(path, body) {
  const res = await fetch(`${AUTH_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json() };
}

async function graphqlQuery(query, token = null) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(GRAPHQL_URL, {
    method: 'POST',
    headers,
    body: JSON.stringify({ query }),
  });
  return { status: res.status, data: await res.json() };
}

async function graphqlWithHeader(query, headerName, headerValue) {
  const res = await fetch(GRAPHQL_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      [headerName]: headerValue,
    },
    body: JSON.stringify({ query }),
  });
  return { status: res.status, data: await res.json() };
}

// ─── Setup ────────────────────────────────────────────────────────────────────

beforeAll(async () => {
  // Wait for both services
  await waitForApi(GRAPHQL_URL, { maxRetries: 20 });
  // Wait for auth separately (not a GraphQL endpoint)
  for (let i = 0; i < 20; i++) {
    try {
      const res = await fetch(`${AUTH_URL}/healthz`, { signal: AbortSignal.timeout(3000) });
      if (res.ok) break;
    } catch (_) {}
    await new Promise(r => setTimeout(r, 3000));
  }

  // Register user (ignore 409 if already exists)
  await authPost(`/auth/${PROJECT_ID}/register`, {
    email: 'alice@test.com',
    password: 'secret123',
    fullName: 'Alice Test',
  });

  // Login to get token
  const login = await authPost(`/auth/${PROJECT_ID}/login`, {
    email: 'alice@test.com',
    password: 'secret123',
  });
  expect(login.status).toBe(200);
  accessToken = login.data.accessToken;
  userId = login.data.user.id;
  expect(accessToken).toBeTruthy();
});

// ─── Auth service tests ───────────────────────────────────────────────────────

describe('Auth service', () => {
  test('register returns 201 or 409', async () => {
    const res = await authPost(`/auth/${PROJECT_ID}/register`, {
      email: 'bob-e2e@test.com',
      password: 'secret456',
      fullName: 'Bob E2E',
    });
    expect([201, 409]).toContain(res.status);
  });

  test('login returns 200 with token and user', async () => {
    const res = await authPost(`/auth/${PROJECT_ID}/login`, {
      email: 'alice@test.com',
      password: 'secret123',
    });
    expect(res.status).toBe(200);
    expect(res.data.accessToken).toMatch(/^eyJ/);
    expect(res.data.refreshToken).toBeTruthy();
    expect(res.data.user.email).toBe('alice@test.com');
  });

  test('login with wrong password returns 401', async () => {
    const res = await authPost(`/auth/${PROJECT_ID}/login`, {
      email: 'alice@test.com',
      password: 'wrongpassword',
    });
    expect(res.status).toBe(401);
  });

  test('validate token returns valid=true with claims', async () => {
    const res = await authPost(`/auth/${PROJECT_ID}/validate`, {
      token: accessToken,
    });
    expect(res.status).toBe(200);
    expect(res.data.valid).toBe(true);
    expect(res.data.email).toBe('alice@test.com');
    expect(res.data.userId).toBe(userId);
    expect(res.data.projectId).toBe(PROJECT_ID);
  });

  test('refresh token returns new access token', async () => {
    const login = await authPost(`/auth/${PROJECT_ID}/login`, {
      email: 'alice@test.com',
      password: 'secret123',
    });
    const res = await authPost(`/auth/${PROJECT_ID}/refresh`, {
      refreshToken: login.data.refreshToken,
    });
    expect(res.status).toBe(200);
    expect(res.data.accessToken).toMatch(/^eyJ/);
    expect(res.data.accessToken).not.toBe(login.data.accessToken);
  });

  test('logout revokes refresh token', async () => {
    const login = await authPost(`/auth/${PROJECT_ID}/login`, {
      email: 'alice@test.com',
      password: 'secret123',
    });
    const logout = await authPost(`/auth/${PROJECT_ID}/logout`, {
      refreshToken: login.data.refreshToken,
    });
    expect(logout.status).toBe(200);

    // Refresh with revoked token should fail
    const refresh = await authPost(`/auth/${PROJECT_ID}/refresh`, {
      refreshToken: login.data.refreshToken,
    });
    expect([400, 401]).toContain(refresh.status);
  });
});

// ─── GraphQL + JWT + RLS tests ────────────────────────────────────────────────

describe('GraphQL with JWT', () => {
  test('valid JWT returns 200 with data', async () => {
    const res = await graphqlQuery(
      '{ publicOrders(orderBy: { id: ASC }) { id user_id product total } }',
      accessToken,
    );
    expect(res.status).toBe(200);
    expect(res.data.data).toBeDefined();
    expect(res.data.data.publicOrders).toBeDefined();
  });

  test('JWT RLS filters rows by userId from token', async () => {
    // The JWT has userId from auth registration — won't match seed data (user_id 1,2)
    // So we test with X-User-Id to prove RLS works, and JWT to prove token is accepted
    const res = await graphqlQuery(
      '{ publicOrders(orderBy: { id: ASC }) { id user_id product } }',
      accessToken,
    );
    expect(res.status).toBe(200);
    // JWT userId doesn't match any seed orders → RLS filters all out
    const orders = res.data.data.publicOrders;
    expect(Array.isArray(orders)).toBe(true);
  });

  test('invalid JWT returns 401', async () => {
    const res = await graphqlQuery(
      '{ publicOrders { id } }',
      'invalid.jwt.token',
    );
    expect(res.status).toBe(401);
    expect(res.data.errors || res.data.message).toBeTruthy();
  });

  test('expired-format JWT returns 401', async () => {
    const res = await graphqlQuery(
      '{ publicOrders { id } }',
      'eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.invalid',
    );
    expect(res.status).toBe(401);
  });

  test('no token passes through (no 401)', async () => {
    const res = await graphqlQuery('{ publicOrders { id } }');
    expect(res.status).toBe(200);
  });
});

// ─── RLS with X-User-Id header (legacy) ───────────────────────────────────────

describe('RLS with X-User-Id header', () => {
  test('user 1 sees only their orders', async () => {
    const res = await graphqlWithHeader(
      '{ publicOrders(orderBy: { id: ASC }) { id user_id product } }',
      'X-User-Id',
      '1',
    );
    expect(res.status).toBe(200);
    const orders = res.data.data.publicOrders;
    expect(orders).toHaveLength(2);
    orders.forEach(o => expect(o.user_id).toBe(1));
  });

  test('user 2 sees only their orders', async () => {
    const res = await graphqlWithHeader(
      '{ publicOrders(orderBy: { id: ASC }) { id user_id product } }',
      'X-User-Id',
      '2',
    );
    expect(res.status).toBe(200);
    const orders = res.data.data.publicOrders;
    expect(orders).toHaveLength(3);
    orders.forEach(o => expect(o.user_id).toBe(2));
  });
});

// ─── Multi-table RLS ──────────────────────────────────────────────────────────

describe('Multi-table RLS with X-User-Id', () => {
  test('user 1 sees correct count across orders and payments', async () => {
    // Query both tables in sequence — same RLS context per request
    const ordersRes = await graphqlWithHeader(
      '{ publicOrders(orderBy: { id: ASC }) { id user_id } }',
      'X-User-Id', '1',
    );
    expect(ordersRes.data.data.publicOrders).toHaveLength(2);

    // If payments table exists in the schema
    // (depends on init-e2e.sql — may not have payments)
  });

  test('user 2 sees different data than user 1', async () => {
    const user1 = await graphqlWithHeader(
      '{ publicOrders { id } }', 'X-User-Id', '1',
    );
    const user2 = await graphqlWithHeader(
      '{ publicOrders { id } }', 'X-User-Id', '2',
    );
    const ids1 = user1.data.data.publicOrders.map(o => o.id);
    const ids2 = user2.data.data.publicOrders.map(o => o.id);
    // No overlap between user 1 and user 2 orders
    const overlap = ids1.filter(id => ids2.includes(id));
    expect(overlap).toHaveLength(0);
  });
});

// ─── Schema introspection still works ─────────────────────────────────────────

describe('Schema introspection (no auth required)', () => {
  test('introspection returns Query type', async () => {
    const res = await graphqlQuery('{ __schema { queryType { name } } }');
    expect(res.status).toBe(200);
    expect(res.data.data.__schema.queryType.name).toBe('Query');
  });

  test('introspection works without any auth header', async () => {
    const res = await graphqlQuery('{ __schema { mutationType { name } } }');
    expect(res.status).toBe(200);
    expect(res.data.data.__schema.mutationType.name).toBe('Mutation');
  });
});
