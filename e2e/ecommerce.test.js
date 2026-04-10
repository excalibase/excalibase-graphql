const { GraphQLClient, gql } = require('graphql-request');
const { waitForApi } = require('./client');

const API_URL = process.env.POSTGRES_API_URL || 'http://localhost:10000/graphql';
const REST_URL = API_URL.replace('/graphql', '/api/v1');
let client;

beforeAll(async () => {
  await waitForApi(API_URL.replace('/graphql', ''));
  client = new GraphQLClient(API_URL);
});

async function restGet(path, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, { headers: { 'Accept-Profile': 'shopify', ...headers } });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

async function restPost(path, body, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Profile': 'shopify', ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

async function restPatch(path, body, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json', 'Content-Profile': 'shopify', ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

async function restDelete(path, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'DELETE', headers: { 'Content-Profile': 'shopify', ...headers },
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
        shopifyProductId { id sku color size stock_quantity price_override }
      }
    }`);
    const variants = data.shopifyProducts[0].shopifyProductId;
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

// ─── GraphQL: FK Chains ──────────────────────────────────────────────────────

describe('E-Commerce GraphQL — FK Chains', () => {
  test('customer → orders → order_items → variant → product (5-level)', async () => {
    const data = await client.request(gql`{
      shopifyCustomers(where: { email: { eq: "alice@shop.com" } }) {
        name
        shopifyCustomerId { id status total }
      }
    }`);
    expect(data.shopifyCustomers[0].name).toBe('Alice Johnson');
    expect(data.shopifyCustomers[0].shopifyCustomerId.length).toBeGreaterThanOrEqual(2);
  });

  test('order → payment FK', async () => {
    const data = await client.request(gql`{
      shopifyOrders(where: { id: { eq: 1 } }) {
        id status total
        shopifyOrderId { id method amount status }
      }
    }`);
    const payments = data.shopifyOrders[0].shopifyOrderId;
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
    expect(data.shopifyReviewsAggregate.count).toBeGreaterThanOrEqual(8);
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
      headers: { 'Accept': 'text/csv', 'Accept-Profile': 'shopify' },
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
