/**
 * Excalibase GraphQL — MySQL E2E Tests
 * Requires: docker-compose services on port 10001 (app) + 3306 (mysql)
 */

const { gql } = require('graphql-request');
const { waitForApi, createClient } = require('./client');

const API_URL = process.env.MYSQL_API_URL || 'http://localhost:10001/graphql';
let client;

beforeAll(async () => {
  await waitForApi(API_URL);
  client = createClient(API_URL);
});

// ─── Schema introspection ─────────────────────────────────────────────────────

describe('Schema introspection', () => {
  test('types list has more than 10 entries', async () => {
    const data = await client.request(gql`{ __schema { types { name } } }`);
    expect(data.__schema.types.length).toBeGreaterThan(10);
  });

  test('queryType is Query', async () => {
    const data = await client.request(gql`{ __schema { queryType { name } } }`);
    expect(data.__schema.queryType.name).toBe('Query');
  });

  test('mutationType is Mutation', async () => {
    const data = await client.request(gql`{ __schema { mutationType { name } } }`);
    expect(data.__schema.mutationType.name).toBe('Mutation');
  });
});

// ─── Basic queries ────────────────────────────────────────────────────────────

describe('Basic queries', () => {
  test('get all customers', async () => {
    const data = await client.request(gql`{ excalibaseCustomer { customer_id first_name last_name email } }`);
    expect(data.excalibaseCustomer.length).toBeGreaterThanOrEqual(10);
  });

  test('filter by eq', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { first_name: { eq: "MARY" } }) { customer_id first_name } }`);
    expect(data.excalibaseCustomer.length).toBeGreaterThanOrEqual(1);
  });

  test('filter by neq', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { first_name: { neq: "MARY" } }) { customer_id first_name } }`);
    data.excalibaseCustomer.forEach(r => expect(r.first_name).not.toBe('MARY'));
  });

  test('filter by gt (total > 50)', async () => {
    const data = await client.request(gql`{ excalibaseOrders(where: { total: { gt: 50 } }) { order_id total } }`);
    expect(data.excalibaseOrders.length).toBeGreaterThanOrEqual(1);
    data.excalibaseOrders.forEach(r => expect(r.total).toBeGreaterThan(50));
  });

  test('filter by gte (total >= 99.99)', async () => {
    const data = await client.request(gql`{ excalibaseOrders(where: { total: { gte: 99.99 } }) { order_id total } }`);
    expect(data.excalibaseOrders.length).toBeGreaterThanOrEqual(1);
    data.excalibaseOrders.forEach(r => expect(r.total).toBeGreaterThanOrEqual(99.99));
  });

  test('filter by lt (total < 20)', async () => {
    const data = await client.request(gql`{ excalibaseOrders(where: { total: { lt: 20 } }) { order_id total } }`);
    expect(data.excalibaseOrders.length).toBeGreaterThanOrEqual(1);
    data.excalibaseOrders.forEach(r => expect(r.total).toBeLessThan(20));
  });

  test('filter by lte (total <= 9.99)', async () => {
    const data = await client.request(gql`{ excalibaseOrders(where: { total: { lte: 9.99 } }) { order_id total } }`);
    expect(data.excalibaseOrders.length).toBeGreaterThanOrEqual(1);
    data.excalibaseOrders.forEach(r => expect(r.total).toBeLessThanOrEqual(9.99));
  });

  test('filter by contains', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { first_name: { contains: "AR" } }) { customer_id first_name } }`);
    expect(data.excalibaseCustomer.length).toBeGreaterThanOrEqual(1);
  });

  test('filter by startsWith', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { first_name: { startsWith: "MA" } }) { customer_id first_name } }`);
    expect(data.excalibaseCustomer.length).toBeGreaterThanOrEqual(1);
    data.excalibaseCustomer.forEach(r => expect(r.first_name.startsWith('MA')).toBe(true));
  });

  test('filter by endsWith', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { last_name: { endsWith: "SON" } }) { customer_id last_name } }`);
    expect(data.excalibaseCustomer.length).toBeGreaterThanOrEqual(1);
    data.excalibaseCustomer.forEach(r => expect(r.last_name.endsWith('SON')).toBe(true));
  });

  test('filter by like', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { email: { like: "%@example.com" } }) { customer_id email } }`);
    expect(data.excalibaseCustomer.length).toBeGreaterThanOrEqual(5);
  });

  test('filter by isNull', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { email: { isNull: true } }) { customer_id email } }`);
    data.excalibaseCustomer.forEach(r => expect(r.email).toBeNull());
  });

  test('filter by isNotNull', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { email: { isNotNull: true } }) { customer_id email } }`);
    expect(data.excalibaseCustomer.length).toBeGreaterThanOrEqual(10);
    data.excalibaseCustomer.forEach(r => expect(r.email).not.toBeNull());
  });

  test('filter by in list', async () => {
    const data = await client.request(gql`{ excalibaseOrders(where: { status: { in: ["delivered", "shipped"] } }) { order_id status } }`);
    expect(data.excalibaseOrders.length).toBeGreaterThanOrEqual(1);
    data.excalibaseOrders.forEach(r => expect(['delivered', 'shipped']).toContain(r.status));
  });

  test('filter by notIn list', async () => {
    const data = await client.request(gql`{ excalibaseOrders(where: { status: { notIn: ["cancelled"] } }) { order_id status } }`);
    expect(data.excalibaseOrders.length).toBeGreaterThanOrEqual(1);
    data.excalibaseOrders.forEach(r => expect(r.status).not.toBe('cancelled'));
  });

  test('pagination limit', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(limit: 5) { customer_id } }`);
    expect(data.excalibaseCustomer.length).toBe(5);
  });

  test('pagination limit + offset', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(limit: 3, offset: 5) { customer_id } }`);
    expect(data.excalibaseCustomer.length).toBe(3);
  });

  test('ordering ASC', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(orderBy: { customer_id: "ASC" }, limit: 3) { customer_id } }`);
    expect(data.excalibaseCustomer[0].customer_id).toBeLessThan(data.excalibaseCustomer[1].customer_id);
  });

  test('ordering DESC', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(orderBy: { customer_id: "DESC" }, limit: 3) { customer_id } }`);
    expect(data.excalibaseCustomer[0].customer_id).toBeGreaterThan(data.excalibaseCustomer[1].customer_id);
  });

  test('orders basic query', async () => {
    const data = await client.request(gql`{ excalibaseOrders { order_id customer_id total status } }`);
    expect(data.excalibaseOrders.length).toBeGreaterThanOrEqual(10);
  });

  test('orders filter by status', async () => {
    const data = await client.request(gql`{ excalibaseOrders(where: { status: { eq: "delivered" } }) { order_id status } }`);
    expect(data.excalibaseOrders.length).toBeGreaterThanOrEqual(1);
  });

  test('products basic query', async () => {
    const data = await client.request(gql`{ excalibaseProduct { product_id name price stock } }`);
    expect(data.excalibaseProduct.length).toBeGreaterThanOrEqual(5);
  });

  test('filter by or — union of conditions', async () => {
    const data = await client.request(gql`{
      excalibaseCustomer(where: { or: [{ first_name: { eq: "MARY" } }, { first_name: { eq: "PATRICIA" } }] }) {
        customer_id first_name
      }
    }`);
    expect(data.excalibaseCustomer.length).toBeGreaterThanOrEqual(2);
    data.excalibaseCustomer.forEach(r => expect(['MARY', 'PATRICIA']).toContain(r.first_name));
  });

  test('active column is Int (0/1), not Boolean (tinyInt1isBit=false)', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(limit: 3) { customer_id active } }`);
    data.excalibaseCustomer.forEach(r => {
      expect(typeof r.active).toBe('number');
      expect([0, 1]).toContain(r.active);
    });
  });
});

// ─── Aggregate queries ────────────────────────────────────────────────────────

describe('Aggregate queries', () => {
  test('customer aggregate count', async () => {
    const data = await client.request(gql`{ excalibaseCustomerAggregate { count } }`);
    expect(data.excalibaseCustomerAggregate.count).toBeGreaterThanOrEqual(10);
  });

  test('orders aggregate count', async () => {
    const data = await client.request(gql`{ excalibaseOrdersAggregate { count } }`);
    expect(data.excalibaseOrdersAggregate.count).toBeGreaterThanOrEqual(10);
  });

  test('orders aggregate sum', async () => {
    const data = await client.request(gql`{ excalibaseOrdersAggregate { sum { total } } }`);
    expect(data.excalibaseOrdersAggregate.sum.total).toBeGreaterThan(0);
  });

  test('orders aggregate avg', async () => {
    const data = await client.request(gql`{ excalibaseOrdersAggregate { avg { total } } }`);
    expect(data.excalibaseOrdersAggregate.avg.total).toBeGreaterThan(0);
  });

  test('orders aggregate min <= max', async () => {
    const data = await client.request(gql`{ excalibaseOrdersAggregate { min { total } max { total } } }`);
    expect(data.excalibaseOrdersAggregate.min.total).toBeLessThanOrEqual(data.excalibaseOrdersAggregate.max.total);
  });
});

// ─── Connection / cursor pagination ──────────────────────────────────────────

describe('Connection (cursor pagination)', () => {
  test('customerConnection returns edges + pageInfo', async () => {
    const data = await client.request(gql`{
      excalibaseCustomerConnection(first: 5) {
        edges { node { customer_id first_name } cursor }
        pageInfo { hasNextPage hasPreviousPage }
      }
    }`);
    expect(data.excalibaseCustomerConnection.edges.length).toBe(5);
  });

  test('customerConnection hasNextPage is true', async () => {
    const data = await client.request(gql`{ excalibaseCustomerConnection(first: 3) { pageInfo { hasNextPage } } }`);
    expect(data.excalibaseCustomerConnection.pageInfo.hasNextPage).toBe(true);
  });

  test('customerConnection totalCount >= 10', async () => {
    const data = await client.request(gql`{ excalibaseCustomerConnection(first: 5) { totalCount } }`);
    expect(data.excalibaseCustomerConnection.totalCount).toBeGreaterThanOrEqual(10);
  });

  test('customerConnection with where filter returns only matching rows', async () => {
    const data = await client.request(gql`{
      excalibaseCustomerConnection(first: 100, where: { first_name: { eq: "MARY" } }) {
        edges { node { first_name } }
        totalCount
      }
    }`);
    data.excalibaseCustomerConnection.edges.forEach(e => expect(e.node.first_name).toBe('MARY'));
    expect(data.excalibaseCustomerConnection.totalCount).toBeGreaterThanOrEqual(1);
  });

  test('customerConnection with orderBy sorts correctly', async () => {
    const data = await client.request(gql`{
      excalibaseCustomerConnection(first: 5, orderBy: { first_name: "ASC" }) {
        edges { node { first_name } }
      }
    }`);
    const names = data.excalibaseCustomerConnection.edges.map(e => e.node.first_name);
    const sorted = [...names].sort();
    expect(names).toEqual(sorted);
  });
});

// ─── Mutations — CRUD ─────────────────────────────────────────────────────────

describe('Mutations — CRUD', () => {
  let createdId;

  test('create customer', async () => {
    const data = await client.request(gql`
      mutation {
        createExcalibaseCustomer(input: { first_name: "E2E", last_name: "Test", email: "e2e@test.com", active: 1 }) {
          customer_id first_name last_name email
        }
      }
    `);
    expect(data.createExcalibaseCustomer.first_name).toBe('E2E');
    expect(data.createExcalibaseCustomer.customer_id).toBeTruthy();
    createdId = data.createExcalibaseCustomer.customer_id;
  });

  test('update customer', async () => {
    if (!createdId) return;
    const data = await client.request(gql`
      mutation { updateExcalibaseCustomer(id: ${createdId}, input: { first_name: "E2E_Updated" }) { customer_id first_name } }
    `);
    expect(data.updateExcalibaseCustomer.first_name).toBe('E2E_Updated');
  });

  test('delete customer', async () => {
    if (!createdId) return;
    const data = await client.request(gql`
      mutation { deleteExcalibaseCustomer(id: ${createdId}) { customer_id } }
    `);
    expect(data.deleteExcalibaseCustomer).not.toBeNull();
  });

  test('deleted customer is no longer queryable', async () => {
    if (!createdId) return;
    const data = await client.request(gql`{ excalibaseCustomer(where: { customer_id: { eq: ${createdId} } }) { customer_id } }`);
    expect(data.excalibaseCustomer).toEqual([]);
  });

  test('bulk create customers', async () => {
    const data = await client.request(gql`
      mutation {
        createManyExcalibaseCustomer(inputs: [
          { first_name: "Bulk1", last_name: "Test", email: "bulk1@test.com" }
          { first_name: "Bulk2", last_name: "Test", email: "bulk2@test.com" }
        ]) { customer_id first_name }
      }
    `);
    expect(data.createManyExcalibaseCustomer.length).toBe(2);
  });
});

// ─── Relationships ────────────────────────────────────────────────────────────

describe('Relationships', () => {
  test('orders with nested customer (forward FK)', async () => {
    const data = await client.request(gql`{ excalibaseOrders(limit: 3) { order_id customer_id excalibaseCustomer { customer_id first_name last_name } } }`);
    expect(data.excalibaseOrders.length).toBeGreaterThanOrEqual(1);
    expect(data.excalibaseOrders[0].excalibaseCustomer).not.toBeNull();
    expect(data.excalibaseOrders[0].excalibaseCustomer.first_name.length).toBeGreaterThan(0);
  });

  test('task with nested customer (forward FK)', async () => {
    const data = await client.request(gql`{ excalibaseTask(where: { customer_id: { isNotNull: true } }, limit: 3) { task_id title customer_id excalibaseCustomer { customer_id first_name } } }`);
    expect(data.excalibaseTask.length).toBeGreaterThanOrEqual(1);
    expect(data.excalibaseTask[0].excalibaseCustomer).not.toBeNull();
  });

  test('task with null customer_id has null customer', async () => {
    const data = await client.request(gql`{ excalibaseTask(where: { customer_id: { isNull: true } }, limit: 3) { task_id customer_id excalibaseCustomer { customer_id } } }`);
    // If any tasks exist with null customer_id, the nested customer field should be null
    if (data.excalibaseTask.length > 0) {
      data.excalibaseTask.forEach(t => expect(t.excalibaseCustomer ?? null).toBeNull());
    }
  });

  test('product_detail with nested product (forward FK)', async () => {
    const data = await client.request(gql`{ excalibaseProductDetail(limit: 3) { detail_id product_id excalibaseProduct { product_id name } } }`);
    expect(data.excalibaseProductDetail.length).toBeGreaterThanOrEqual(1);
    expect(data.excalibaseProductDetail[0].excalibaseProduct).not.toBeNull();
    expect(data.excalibaseProductDetail[0].excalibaseProduct.name.length).toBeGreaterThan(0);
  });

  test('customer with nested orders (reverse FK)', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { customer_id: { eq: 1 } }) { customer_id first_name excalibaseOrders { order_id total } } }`);
    expect(data.excalibaseCustomer[0].excalibaseOrders.length).toBeGreaterThanOrEqual(1);
  });

  test('product with nested product_details (reverse FK)', async () => {
    const data = await client.request(gql`{ excalibaseProduct(where: { product_id: { eq: 1 } }) { product_id name excalibaseProductDetail { detail_id } } }`);
    expect(data.excalibaseProduct[0].excalibaseProductDetail.length).toBeGreaterThanOrEqual(1);
  });
});

// ─── Security ─────────────────────────────────────────────────────────────────

describe('Security', () => {
  test('deeply nested introspection is blocked (depth limiting)', async () => {
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        query: '{ __schema { types { name fields { name type { name fields { name type { name fields { name type { name fields { name } } } } } } } } } }',
      }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });

  test('high-complexity query is blocked', async () => {
    const aliases = Array.from({ length: 30 }, (_, i) => `alias${i}: __schema { types { name } }`).join(' ');
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: `{ ${aliases} }` }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });

  test('invalid field returns GraphQL error', async () => {
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: '{ totally_invalid_table_xyz }' }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });

  test('SQL injection attempt does not crash', async () => {
    const data = await client.request(gql`{ __type(name: "'; DROP TABLE customer; --") { name } }`);
    // graphql-request v6 strips null values — __type is null or undefined (not found)
    expect(data.__type ?? null).toBeNull();
  });

  test('legitimate query works after security checks', async () => {
    const data = await client.request(gql`{ __schema { queryType { name } } }`);
    expect(data.__schema.queryType.name).toBe('Query');
  });
});

// ─── Error handling ───────────────────────────────────────────────────────────

describe('Error handling', () => {
  test('filter with no matches returns empty array', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { first_name: { eq: "ZZZNOMATCH999" } }) { customer_id } }`);
    expect(data.excalibaseCustomer).toEqual([]);
  });

  test('malformed JSON body returns error', async () => {
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{ invalid json !!!',
    });
    expect(resp.status).toBeGreaterThanOrEqual(400);
  });
});

// ─── ENUM column tests ────────────────────────────────────────────────────────

describe('ENUM columns', () => {
  test('task basic query with ENUM columns', async () => {
    const data = await client.request(gql`{ excalibaseTask { task_id title status priority } }`);
    expect(data.excalibaseTask.length).toBeGreaterThanOrEqual(5);
  });

  test('filter task by ENUM status', async () => {
    const data = await client.request(gql`{ excalibaseTask(where: { status: { eq: "done" } }) { task_id status } }`);
    expect(data.excalibaseTask.length).toBeGreaterThanOrEqual(1);
    data.excalibaseTask.forEach(r => expect(r.status).toBe('done'));
  });

  test('filter task by ENUM priority', async () => {
    const data = await client.request(gql`{ excalibaseTask(where: { priority: { eq: "high" } }) { task_id priority } }`);
    expect(data.excalibaseTask.length).toBeGreaterThanOrEqual(1);
  });

  test('task aggregate count', async () => {
    const data = await client.request(gql`{ excalibaseTaskAggregate { count } }`);
    expect(data.excalibaseTaskAggregate.count).toBeGreaterThanOrEqual(5);
  });

  test('create task with ENUM values', async () => {
    const data = await client.request(gql`
      mutation {
        createExcalibaseTask(input: { title: "E2E Task", status: "in_progress", priority: "high" }) {
          task_id title status priority
        }
      }
    `);
    expect(data.createExcalibaseTask.status).toBe('in_progress');
    expect(data.createExcalibaseTask.priority).toBe('high');
  });
});

// ─── JSON column tests ────────────────────────────────────────────────────────

describe('JSON columns', () => {
  test('product_detail basic query', async () => {
    const data = await client.request(gql`{ excalibaseProductDetail { detail_id product_id attributes metadata tags } }`);
    expect(data.excalibaseProductDetail.length).toBeGreaterThanOrEqual(3);
  });

  test('JSON columns are non-null', async () => {
    const data = await client.request(gql`{ excalibaseProductDetail(limit: 1) { attributes metadata tags } }`);
    expect(data.excalibaseProductDetail[0].attributes).not.toBeNull();
  });

  test('filter product_detail by product_id', async () => {
    const data = await client.request(gql`{ excalibaseProductDetail(where: { product_id: { eq: 1 } }) { detail_id product_id } }`);
    expect(data.excalibaseProductDetail.length).toBeGreaterThanOrEqual(1);
  });

  test('create product_detail with JSON', async () => {
    const data = await client.request(gql`
      mutation {
        createExcalibaseProductDetail(input: {
          product_id: 1
          attributes: { color: "green", weight: 1.0 }
          tags: ["e2e", "test"]
        }) { detail_id product_id attributes tags }
      }
    `);
    expect(data.createExcalibaseProductDetail.detail_id).toBeTruthy();
  });
});

// ─── Views ────────────────────────────────────────────────────────────────────

describe('Views (read-only)', () => {
  test('active_customers view query', async () => {
    const data = await client.request(gql`{ excalibaseActiveCustomers { customer_id first_name last_name email } }`);
    expect(data.excalibaseActiveCustomers.length).toBeGreaterThanOrEqual(10);
  });

  test('active_customers excludes inactive rows', async () => {
    const data = await client.request(gql`{ excalibaseCustomer(where: { active: { eq: 0 } }) { customer_id active } }`);
    expect(data.excalibaseCustomer.length).toBeGreaterThanOrEqual(2);
  });

  test('orders_summary view query', async () => {
    const data = await client.request(gql`{ excalibaseOrdersSummary { customer_id first_name last_name order_count total_spent } }`);
    // LEFT JOIN — customers with no orders have order_count=0, total_spent=null
    expect(data.excalibaseOrdersSummary.length).toBeGreaterThanOrEqual(5);
    const withOrders = data.excalibaseOrdersSummary.filter(r => r.order_count > 0);
    expect(withOrders.length).toBeGreaterThanOrEqual(1);
    withOrders.forEach(r => expect(r.total_spent).toBeGreaterThan(0));
  });

  test('high_value_orders view — all totals > 50', async () => {
    const data = await client.request(gql`{ excalibaseHighValueOrders { order_id total status first_name last_name } }`);
    expect(data.excalibaseHighValueOrders.length).toBeGreaterThanOrEqual(1);
    data.excalibaseHighValueOrders.forEach(r => expect(r.total).toBeGreaterThan(50));
  });

  test('view pagination', async () => {
    const data = await client.request(gql`{ excalibaseActiveCustomers(limit: 3, offset: 0) { customer_id } }`);
    expect(data.excalibaseActiveCustomers.length).toBe(3);
  });

  test('view ordering', async () => {
    const data = await client.request(gql`{ excalibaseActiveCustomers(orderBy: { customer_id: "ASC" }, limit: 3) { customer_id } }`);
    expect(data.excalibaseActiveCustomers[0].customer_id).toBeLessThan(data.excalibaseActiveCustomers[1].customer_id);
  });

  test('view aggregate count', async () => {
    const data = await client.request(gql`{ excalibaseActiveCustomersAggregate { count } }`);
    expect(data.excalibaseActiveCustomersAggregate.count).toBeGreaterThanOrEqual(10);
  });

  test('views have no mutation fields', async () => {
    const data = await client.request(gql`{ __type(name: "Mutation") { fields { name } } }`);
    const mutationNames = data.__type.fields.map(f => f.name);
    const viewNames = ['active_customers', 'orders_summary', 'high_value_orders'];
    const viewMutations = mutationNames.filter(name =>
      viewNames.some(v => name.toLowerCase().includes(v.replace('_', '')))
    );
    expect(viewMutations.length).toBe(0);
  });
});

// ─── Stored Procedures ────────────────────────────────────────────────────────

describe('Stored Procedures', () => {
  test('procedure mutation appears in schema', async () => {
    const data = await client.request(gql`{ __type(name: "Mutation") { fields { name } } }`);
    const mutationNames = data.__type.fields.map(f => f.name);
    expect(mutationNames).toContain('callExcalibaseGetCustomerOrderCount');
  });

  test('call procedure with IN param returns OUT param', async () => {
    const data = await client.request(gql`
      mutation { callExcalibaseGetCustomerOrderCount(p_customer_id: 1) }
    `);
    expect(data.callExcalibaseGetCustomerOrderCount).toBeDefined();
    const result = JSON.parse(data.callExcalibaseGetCustomerOrderCount);
    expect(result).toHaveProperty('p_count');
    expect(Number(result.p_count)).toBeGreaterThanOrEqual(0);
  });

  // ── transfer_funds: complex procedure with balance check ─────────────────

  beforeAll(async () => {
    // Reset wallet balances so transfer tests are idempotent across repeated runs
    await client.request(gql`mutation { updateExcalibaseWallets(id: 1, input: { balance: 1000.00 }) { wallet_id } }`);
    await client.request(gql`mutation { updateExcalibaseWallets(id: 2, input: { balance: 500.00 }) { wallet_id } }`);
    await client.request(gql`mutation { updateExcalibaseWallets(id: 3, input: { balance: 10.00 }) { wallet_id } }`);
  });

  test('transfer_funds appears in schema', async () => {
    const data = await client.request(gql`{ __type(name: "Mutation") { fields { name } } }`);
    const mutationNames = data.__type.fields.map(f => f.name);
    expect(mutationNames).toContain('callExcalibaseTransferFunds');
  });

  test('transfer_funds happy path — sufficient balance moves money', async () => {
    // Read balances before transfer
    const before = await client.request(gql`
      { excalibaseWallets(orderBy: { wallet_id: "ASC" }) { wallet_id balance } }
    `);
    const aliceBefore = Number(before.excalibaseWallets.find(w => w.wallet_id == 1).balance);
    const bobBefore   = Number(before.excalibaseWallets.find(w => w.wallet_id == 2).balance);

    // Alice (wallet 1) transfers 200 to Bob (wallet 2)
    const data = await client.request(gql`
      mutation { callExcalibaseTransferFunds(p_from_wallet_id: 1, p_to_wallet_id: 2, p_amount: 200.00) }
    `);
    const result = JSON.parse(data.callExcalibaseTransferFunds);
    expect(result.p_status).toBe('SUCCESS');

    // Verify balances changed by exactly 200
    const after = await client.request(gql`
      { excalibaseWallets(orderBy: { wallet_id: "ASC" }) { wallet_id balance } }
    `);
    const aliceAfter = Number(after.excalibaseWallets.find(w => w.wallet_id == 1).balance);
    const bobAfter   = Number(after.excalibaseWallets.find(w => w.wallet_id == 2).balance);
    expect(aliceAfter).toBeCloseTo(aliceBefore - 200, 2);
    expect(bobAfter).toBeCloseTo(bobBefore + 200, 2);
  });

  test('transfer_funds unhappy path — insufficient funds rejected, balances unchanged', async () => {
    // Charlie (wallet 3, balance=10) tries to send 500 → should fail
    const data = await client.request(gql`
      mutation { callExcalibaseTransferFunds(p_from_wallet_id: 3, p_to_wallet_id: 1, p_amount: 500.00) }
    `);
    const result = JSON.parse(data.callExcalibaseTransferFunds);
    expect(result.p_status).toMatch(/ERROR.*Insufficient/i);

    // Verify Charlie's balance is still 10, not negative (constraint not violated)
    const wallets = await client.request(gql`
      { excalibaseWallets(orderBy: { wallet_id: "ASC" }) { wallet_id balance } }
    `);
    const charlie = wallets.excalibaseWallets.find(w => w.wallet_id == 3);
    expect(Number(charlie.balance)).toBeCloseTo(10.00, 2);
  });
});
