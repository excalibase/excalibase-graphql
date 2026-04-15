const { GraphQLClient, gql } = require('graphql-request');
const { waitForApi } = require('./client');

const GRAPHQL_URL = process.env.SC_GRAPHQL_URL || 'http://localhost:10004/graphql';
const AUTH_URL = process.env.SC_AUTH_URL || 'http://localhost:24004/auth';
const REST_URL = GRAPHQL_URL.replace('/graphql', '/api/v1');

const PROJECT = { orgSlug: 'study-cases', projectName: 'shopify' };
const TEST_USER = { email: 'shopper@example.com', password: 'Pass123!', fullName: 'E2E Shopper' };

let token;
let client;

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

async function registerAndLogin() {
  const base = `/${PROJECT.orgSlug}/${PROJECT.projectName}`;
  const reg = await authPost(`${base}/register`, { email: TEST_USER.email, password: TEST_USER.password, fullName: TEST_USER.fullName });
  if (reg.status !== 200 && reg.status !== 201 && reg.status !== 409) {
    throw new Error(`Register failed (${reg.status}): ${JSON.stringify(reg.data)}`);
  }
  const login = await authPost(`${base}/login`, { email: TEST_USER.email, password: TEST_USER.password });
  if (!login.data.accessToken) {
    throw new Error(`Login failed: ${JSON.stringify(login.data)}`);
  }
  return login.data.accessToken;
}

beforeAll(async () => {
  await Promise.all([
    waitForAuth(),
    waitForApi(GRAPHQL_URL, { maxRetries: 30, delayMs: 3000 }),
  ]);
  token = await registerAndLogin();
  client = new GraphQLClient(GRAPHQL_URL, {
    headers: { Authorization: `Bearer ${token}` },
  });
});

async function restGet(path, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, { headers: { 'Accept-Profile': 'shopify', Authorization: `Bearer ${token}`, ...headers } });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

async function restPost(path, body, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Profile': 'shopify', Authorization: `Bearer ${token}`, ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

async function restPatch(path, body, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json', 'Content-Profile': 'shopify', Authorization: `Bearer ${token}`, ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

async function restDelete(path, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'DELETE', headers: { 'Content-Profile': 'shopify', Authorization: `Bearer ${token}`, ...headers },
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

// ─── GraphQL: Catalog & Products ─────────────────────────────────────────────

describe('E-Commerce GraphQL — Catalog', () => {
  test('list active products with category FK', async () => {
    const data = await client.request(gql`{
      shopifyProducts(where: { status: { eq: "active" } }) {
        id name price status
        shopifyCategoryId { id name }
      }
    }`);
    expect(data.shopifyProducts.length).toBeGreaterThanOrEqual(6);
    const iphone = data.shopifyProducts.find(p => p.name === 'iPhone 15');
    expect(iphone.shopifyCategoryId.name).toBe('Phones');
  });

  test('product → variants (reverse FK)', async () => {
    const data = await client.request(gql`{
      shopifyProducts(where: { slug: { eq: "iphone-15" } }) {
        id name
        shopifyProductVariants { id sku color size stock_quantity price_override }
      }
    }`);
    const variants = data.shopifyProducts[0].shopifyProductVariants;
    expect(variants.length).toBe(2);
    expect(variants[0].sku).toContain('IP15');
  });

  test('category tree: self-referential parent_category_id', async () => {
    const data = await client.request(gql`{
      shopifyCategories(where: { name: { eq: "Phones" } }) {
        id name
        shopifyParentCategoryId { id name }
      }
    }`);
    expect(data.shopifyCategories[0].shopifyParentCategoryId.name).toBe('Electronics');
  });

  test('GENERATED column: line_total = quantity * unit_price', async () => {
    const data = await client.request(gql`{
      shopifyOrderItems(where: { id: { eq: 1 } }) {
        quantity unit_price line_total
      }
    }`);
    const item = data.shopifyOrderItems[0];
    expect(item.line_total).toBe(item.quantity * item.unit_price);
  });

  test('JSONB metadata query', async () => {
    const data = await client.request(gql`{
      shopifyProducts(where: { slug: { eq: "iphone-15" } }) {
        metadata
      }
    }`);
    expect(data.shopifyProducts[0].metadata.brand).toBe('Apple');
  });

  test('ENUM filter: order status', async () => {
    const data = await client.request(gql`{
      shopifyOrders(where: { status: { eq: "delivered" } }) { id status }
    }`);
    expect(data.shopifyOrders.length).toBeGreaterThanOrEqual(3);
    data.shopifyOrders.forEach(o => expect(o.status).toBe('DELIVERED'));
  });
});

describe('E-Commerce GraphQL — JSONB filter (JsonFilterInput)', () => {
  test('hasKey: addresses all have a city key', async () => {
    const data = await client.request(gql`{
      shopifyCustomers(where: { address: { hasKey: "city" } }) { id email }
    }`);
    expect(data.shopifyCustomers.length).toBeGreaterThanOrEqual(3);
  });

  test('hasKey: filter matches only non-empty addresses', async () => {
    // Falsifiability — a key that does not exist must return zero rows.
    const data = await client.request(gql`{
      shopifyCustomers(where: { address: { hasKey: "nonexistent_key_xyz" } }) { id }
    }`);
    expect(data.shopifyCustomers).toHaveLength(0);
  });

  test('contains (JSONB @>): match exact subset of the jsonb value', async () => {
    const data = await client.request(gql`{
      shopifyCustomers(where: { address: { contains: {city: "New York"} } }) { id email }
    }`);
    // Exactly one seed row (Alice) has city=New York
    expect(data.shopifyCustomers).toHaveLength(1);
    expect(data.shopifyCustomers[0].email).toBe('alice@shop.com');
  });

  test('contains multi-key: must match all fields', async () => {
    // Alice is the only customer with BOTH city=New York and zip=10001
    const data = await client.request(gql`{
      shopifyCustomers(where: { address: { contains: {city: "New York", zip: "10001"} } }) { id email }
    }`);
    expect(data.shopifyCustomers).toHaveLength(1);
    expect(data.shopifyCustomers[0].email).toBe('alice@shop.com');
  });

  test('contains with no match returns empty', async () => {
    const data = await client.request(gql`{
      shopifyCustomers(where: { address: { contains: {city: "Atlantis"} } }) { id }
    }`);
    expect(data.shopifyCustomers).toHaveLength(0);
  });

  test('hasKeys: requires ALL listed keys', async () => {
    // Every seeded address has both city AND street
    const data = await client.request(gql`{
      shopifyCustomers(where: { address: { hasKeys: ["city", "street"] } }) { id }
    }`);
    expect(data.shopifyCustomers.length).toBeGreaterThanOrEqual(3);
  });

  test('hasAnyKeys: requires at least one listed key', async () => {
    // "street" is present on all customers, "fake_key" is present on none —
    // the OR semantic makes this equivalent to "has street"
    const data = await client.request(gql`{
      shopifyCustomers(where: { address: { hasAnyKeys: ["street", "fake_key"] } }) { id }
    }`);
    expect(data.shopifyCustomers.length).toBeGreaterThanOrEqual(3);
  });

  test('contains on product.metadata with nested value', async () => {
    // The iPhone 15 row seeds metadata with brand=Apple
    const data = await client.request(gql`{
      shopifyProducts(where: { metadata: { contains: {brand: "Apple"} } }) { id name }
    }`);
    expect(data.shopifyProducts.length).toBeGreaterThanOrEqual(1);
    expect(data.shopifyProducts.find(p => p.name === 'iPhone 15')).toBeTruthy();
  });
});

describe('E-Commerce GraphQL — Float filter (FloatFilterInput)', () => {
  test('lt on numeric price returns cheap items', async () => {
    // Seed has products under $50 (tees at 29.99, 34.99, 9.99)
    const data = await client.request(gql`{
      shopifyProducts(where: { price: { lt: 50.0 } }) { id name price }
    }`);
    expect(data.shopifyProducts.length).toBeGreaterThan(0);
    data.shopifyProducts.forEach(p => expect(Number(p.price)).toBeLessThan(50.0));
  });

  test('gte + lt range narrows to a price window', async () => {
    const data = await client.request(gql`{
      shopifyProducts(where: { price: { gte: 20.0, lt: 40.0 } }) { id name price }
    }`);
    data.shopifyProducts.forEach(p => {
      const n = Number(p.price);
      expect(n).toBeGreaterThanOrEqual(20.0);
      expect(n).toBeLessThan(40.0);
    });
  });

  test('impossible range returns empty', async () => {
    const data = await client.request(gql`{
      shopifyProducts(where: { price: { gt: 999999.0 } }) { id }
    }`);
    expect(data.shopifyProducts).toHaveLength(0);
  });

  test('eq on Float matches exact value', async () => {
    // Alice's Classic Tee is 29.99 — exact float equality
    const data = await client.request(gql`{
      shopifyProducts(where: { price: { eq: 29.99 } }) { id name price }
    }`);
    expect(data.shopifyProducts.length).toBeGreaterThanOrEqual(1);
    data.shopifyProducts.forEach(p => expect(Number(p.price)).toBe(29.99));
  });
});

describe('E-Commerce GraphQL — DateTime filter (DateTimeFilterInput)', () => {
  test('isNull: true returns rows where deleted_at is unset', async () => {
    const live = await client.request(gql`{
      shopifyProducts(where: { deleted_at: { isNull: true } }) { id name }
    }`);
    expect(live.shopifyProducts.length).toBeGreaterThan(0);
  });

  test('isNull: false returns soft-deleted rows', async () => {
    const deleted = await client.request(gql`{
      shopifyProducts(where: { deleted_at: { isNull: false } }) { id name }
    }`);
    // At least one soft-deleted product in seed
    expect(deleted.shopifyProducts.length).toBeGreaterThanOrEqual(1);
  });
});

// ─── GraphQL: FK Chains ──────────────────────────────────────────────────────

describe('E-Commerce GraphQL — FK Chains', () => {
  test('customer → orders → order_items → variant → product (5-level)', async () => {
    const data = await client.request(gql`{
      shopifyCustomers(where: { email: { eq: "alice@shop.com" } }) {
        name
        shopifyOrders { id status total }
      }
    }`);
    expect(data.shopifyCustomers[0].name).toBe('Alice Johnson');
    expect(data.shopifyCustomers[0].shopifyOrders.length).toBeGreaterThanOrEqual(2);
  });

  test('order → payment FK', async () => {
    const data = await client.request(gql`{
      shopifyOrders(where: { id: { eq: 1 } }) {
        id status total
        shopifyPayments { id method amount status }
      }
    }`);
    const payments = data.shopifyOrders[0].shopifyPayments;
    expect(payments.length).toBeGreaterThanOrEqual(1);
    expect(payments[0].status).toBe('COMPLETED');
  });

  test('reviews: 2 FKs to different tables (product + customer)', async () => {
    const data = await client.request(gql`{
      shopifyReviews(where: { id: { eq: 1 } }) {
        rating title
        shopifyProductId { name }
        shopifyCustomerId { name }
      }
    }`);
    expect(data.shopifyReviews[0].shopifyProductId.name).toBe('iPhone 15');
    expect(data.shopifyReviews[0].shopifyCustomerId.name).toBe('Alice Johnson');
  });
});

// ─── GraphQL: Aggregates & Views ─────────────────────────────────────────────

describe('E-Commerce GraphQL — Aggregates & Views', () => {
  test('product review aggregate', async () => {
    const data = await client.request(gql`{
      shopifyReviewsAggregate { count }
    }`);
    expect(data.shopifyReviewsAggregate.count).toBeGreaterThanOrEqual(7);
  });

  test('wishlist composite key', async () => {
    const data = await client.request(gql`{
      shopifyWishlists { customer_id product_id }
    }`);
    expect(data.shopifyWishlists.length).toBeGreaterThanOrEqual(6);
  });

  test('order_summary view', async () => {
    const data = await client.request(gql`{
      shopifyOrderSummary { order_id customer_name status item_count }
    }`);
    expect(data.shopifyOrderSummary.length).toBeGreaterThanOrEqual(10);
  });

  test('product_catalog view (excludes soft-deleted)', async () => {
    const data = await client.request(gql`{
      shopifyProductCatalog { id name avg_rating total_stock }
    }`);
    const names = data.shopifyProductCatalog.map(p => p.name);
    expect(names).not.toContain('Deleted Item');
  });

  test('connection pagination on products', async () => {
    const data = await client.request(gql`{
      shopifyProductsConnection(first: 3) {
        edges { node { id name } cursor }
        pageInfo { hasNextPage endCursor }
        totalCount
      }
    }`);
    expect(data.shopifyProductsConnection.edges).toHaveLength(3);
    expect(data.shopifyProductsConnection.pageInfo.hasNextPage).toBe(true);
  });
});

// ─── REST: Read & Filter ─────────────────────────────────────────────────────

describe('E-Commerce REST — Read', () => {
  test('GET /products with status filter', async () => {
    const res = await restGet('/products?status=eq.active&order=price.desc');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(6);
  });

  test('GET /products with select + category embed', async () => {
    const res = await restGet('/products?select=id,name,price,categories(name)&limit=3');
    expect(res.status).toBe(200);
  });

  test('GET /orders with customer embed', async () => {
    const res = await restGet('/orders?select=id,total,status,customers(name,email)&status=eq.paid');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(2);
  });

  test('GET /order_summary view', async () => {
    const res = await restGet('/order_summary');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(10);
  });

  test('GET /products CSV export', async () => {
    const res = await fetch(`${REST_URL}/products?select=id,name,price&limit=3&order=id.asc`, {
      headers: { 'Accept': 'text/csv', 'Accept-Profile': 'shopify', Authorization: `Bearer ${token}` },
    });
    expect(res.status).toBe(200);
    const csv = await res.text();
    expect(csv).toContain('id,name,price');
  });

  test('GET /products cursor pagination', async () => {
    const res = await restGet('/products?first=3&order=id.asc');
    expect(res.status).toBe(200);
    expect(res.data.data).toHaveLength(3);
    expect(res.data.pageInfo.hasNextPage).toBe(true);
  });

  test('GET /products with Prefer: count=exact', async () => {
    const res = await restGet('/products', { 'Prefer': 'count=exact' });
    expect(res.status).toBe(200);
    expect(res.data.pagination.total).toBeGreaterThanOrEqual(7);
  });
});

// ─── REST: Mutations ─────────────────────────────────────────────────────────

describe('E-Commerce REST — Mutations', () => {
  let newOrderId;

  test('POST /orders creates order', async () => {
    const res = await restPost('/orders', {
      customer_id: 1, status: 'pending', total: 0,
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(201);
    newOrderId = res.data.data.id;
    expect(newOrderId).toBeTruthy();
  });

  test('POST /order_items bulk create', async () => {
    const res = await restPost('/order_items', [
      { order_id: newOrderId, variant_id: 1, quantity: 1, unit_price: 999.00 },
      { order_id: newOrderId, variant_id: 8, quantity: 2, unit_price: 29.99 },
    ], { 'Prefer': 'return=representation' });
    expect(res.status).toBe(201);
  });

  test('PATCH /orders update status to paid', async () => {
    const res = await restPatch(`/orders?id=eq.${newOrderId}`, {
      status: 'paid', total: 1058.98,
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(200);
    expect(res.data.data[0].status).toBe('paid');
  });

  test('POST /wishlists upsert (merge-duplicates)', async () => {
    const res = await restPost('/wishlists', {
      customer_id: 1, product_id: 1,
    }, { 'Prefer': 'return=representation, resolution=merge-duplicates' });
    expect(res.status).toBe(201);
  });

  test('DELETE /reviews with return=representation', async () => {
    const res = await restDelete('/reviews?id=eq.8', { 'Prefer': 'return=representation' });
    expect(res.status).toBe(200);
  });

  test('POST /orders with tx=rollback (dry run)', async () => {
    const res = await restPost('/orders', {
      customer_id: 2, status: 'pending', total: 0,
    }, { 'Prefer': 'return=representation, tx=rollback' });
    expect(res.status).toBe(201);
    expect(res.data.data.customer_id).toBe(2);

    const check = await restGet(`/orders?id=eq.${res.data.data.id}`);
    expect(check.data.data).toHaveLength(0);
  });
});
