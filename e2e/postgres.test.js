/**
 * Excalibase GraphQL — Postgres E2E Tests
 * Requires: docker-compose services on port 10000 (app) + 5432 (postgres)
 */

const { gql } = require('graphql-request');
const { waitForApi, createClient } = require('./client');

const API_URL = process.env.POSTGRES_API_URL || 'http://localhost:10000/graphql';
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
    const data = await client.request(gql`{ hanaCustomer { customer_id first_name last_name email } }`);
    expect(data.hanaCustomer.length).toBeGreaterThanOrEqual(10);
  });

  test('filter customers by first_name eq', async () => {
    const data = await client.request(gql`{ hanaCustomer(where: { first_name: { eq: "MARY" } }) { customer_id first_name } }`);
    expect(data.hanaCustomer.length).toBeGreaterThanOrEqual(1);
  });

  test('filter customers with OR', async () => {
    const data = await client.request(gql`{ hanaCustomer(or: [{ first_name: { eq: "MARY" } }, { first_name: { eq: "JOHN" } }]) { customer_id first_name } }`);
    expect(data.hanaCustomer.length).toBeGreaterThanOrEqual(2);
  });

  test('customer pagination limit+offset', async () => {
    const data = await client.request(gql`{ hanaCustomer(limit: 3, offset: 2) { customer_id first_name } }`);
    expect(data.hanaCustomer.length).toBe(3);
  });

  test('limit above MAX_ROWS (30) is capped — returns at most 30 rows', async () => {
    const data = await client.request(gql`{ hanaCustomer(limit: 1000) { customer_id } }`);
    expect(data.hanaCustomer.length).toBeLessThanOrEqual(30);
  });

  test('no limit specified returns at most MAX_ROWS (30) rows', async () => {
    const data = await client.request(gql`{ hanaCustomer { customer_id } }`);
    expect(data.hanaCustomer.length).toBeLessThanOrEqual(30);
  });

  test('customer ordering ASC', async () => {
    const data = await client.request(gql`{ hanaCustomer(orderBy: { customer_id: ASC }, limit: 5) { customer_id } }`);
    expect(data.hanaCustomer[0].customer_id).toBeLessThan(data.hanaCustomer[1].customer_id);
  });

  test('complex date range filter', async () => {
    const data = await client.request(gql`{ hanaCustomer(where: { create_date: { gte: "2006-01-01", lt: "2008-01-01" } }) { customer_id create_date } }`);
    expect(data.hanaCustomer.length).toBeGreaterThanOrEqual(1);
  });

  test('string contains filter', async () => {
    const data = await client.request(gql`{ hanaCustomer(where: { email: { contains: "@example.com" } }) { customer_id email } }`);
    expect(data.hanaCustomer.length).toBeGreaterThanOrEqual(5);
  });

  test('boolean filter', async () => {
    const data = await client.request(gql`{ hanaCustomer(where: { active: { eq: true } }) { customer_id active } }`);
    expect(data.hanaCustomer.length).toBeGreaterThanOrEqual(5);
  });

  test('IN array filter', async () => {
    const data = await client.request(gql`{ hanaCustomer(where: { customer_id: { in: [1, 2, 3] } }) { customer_id } }`);
    expect(data.hanaCustomer.length).toBe(3);
  });
});

// ─── Aggregate queries ────────────────────────────────────────────────────────

describe('Aggregate queries', () => {
  test('customer aggregate count', async () => {
    const data = await client.request(gql`{ hanaCustomerAggregate { count } }`);
    expect(data.hanaCustomerAggregate.count).toBeGreaterThanOrEqual(10);
  });

  test('orders aggregate count', async () => {
    const data = await client.request(gql`{ hanaOrdersAggregate { count } }`);
    expect(data.hanaOrdersAggregate.count).toBeGreaterThanOrEqual(1);
  });

  test('orders aggregate sum/avg/min/max', async () => {
    // Postgres aggregate uses nested per-column types: sum { col }, avg { col }, etc.
    const data = await client.request(gql`{ hanaOrdersAggregate { sum { total_amount } avg { total_amount } min { total_amount } max { total_amount } } }`);
    expect(Number(data.hanaOrdersAggregate.sum.total_amount)).toBeGreaterThan(0);
    expect(Number(data.hanaOrdersAggregate.avg.total_amount)).toBeGreaterThan(0);
    expect(Number(data.hanaOrdersAggregate.min.total_amount)).toBeLessThanOrEqual(Number(data.hanaOrdersAggregate.max.total_amount));
  });
});

// ─── Connection / cursor pagination ──────────────────────────────────────────

describe('Connection (cursor pagination)', () => {
  test('customerConnection returns edges and pageInfo', async () => {
    const data = await client.request(gql`{
      hanaCustomerConnection(first: 3) {
        edges { node { customer_id first_name } cursor }
        pageInfo { hasNextPage hasPreviousPage }
      }
    }`);
    expect(data.hanaCustomerConnection.edges.length).toBeLessThanOrEqual(3);
    expect(typeof data.hanaCustomerConnection.pageInfo.hasNextPage).toBe('boolean');
  });

  test('customerConnection hasNextPage is true when more rows exist', async () => {
    const data = await client.request(gql`{ hanaCustomerConnection(first: 3) { pageInfo { hasNextPage } } }`);
    expect(data.hanaCustomerConnection.pageInfo.hasNextPage).toBe(true);
  });
});

// ─── Enhanced PostgreSQL types ────────────────────────────────────────────────

describe('Enhanced PostgreSQL types', () => {
  test('query all enhanced_types fields', async () => {
    const data = await client.request(gql`{ hanaEnhancedTypes { id name json_col jsonb_col int_array text_array timestamptz_col } }`);
    expect(data.hanaEnhancedTypes.length).toBeGreaterThanOrEqual(3);
  });

  test('filter enhanced_types by name', async () => {
    const data = await client.request(gql`{ hanaEnhancedTypes(where: { name: { eq: "Test Record 1" } }) { id name json_col } }`);
    expect(data.hanaEnhancedTypes[0].json_col).not.toBeNull();
  });

  test('array fields are non-null', async () => {
    const data = await client.request(gql`{ hanaEnhancedTypes { id int_array text_array } }`);
    expect(data.hanaEnhancedTypes[0].int_array).not.toBeNull();
    expect(data.hanaEnhancedTypes[0].text_array).not.toBeNull();
  });

  test('network type fields', async () => {
    const data = await client.request(gql`{ hanaEnhancedTypes { id inet_col cidr_col macaddr_col } }`);
    expect(data.hanaEnhancedTypes[0].inet_col).not.toBeNull();
  });

  test('datetime fields', async () => {
    const data = await client.request(gql`{ hanaEnhancedTypes { id timestamptz_col timetz_col interval_col } }`);
    expect(data.hanaEnhancedTypes[0].timestamptz_col).not.toBeNull();
  });

  test('BIT/VARBIT fields', async () => {
    const data = await client.request(gql`{ hanaEnhancedTypes { id name bit_col varbit_col } }`);
    expect(data.hanaEnhancedTypes[0].bit_col).not.toBeNull();
    expect(data.hanaEnhancedTypes[0].varbit_col).not.toBeNull();
  });

  test('create enhanced_types with direct JSON objects', async () => {
    const data = await client.request(gql`
      mutation {
        createHanaEnhancedTypes(input: {
          name: "E2E JSON Direct Object Test"
          json_col: { user: { name: "Alice", age: 28 }, tags: ["premium"] }
          jsonb_col: { profile: { id: 12345, email: "alice@e2e.com" } }
          numeric_col: 1234.56
        }) { id name json_col jsonb_col numeric_col }
      }
    `);
    expect(data.createHanaEnhancedTypes.name).toBe('E2E JSON Direct Object Test');
    expect(data.createHanaEnhancedTypes.json_col).not.toBeNull();
  });

  test('create enhanced_types with BIT values', async () => {
    const data = await client.request(gql`
      mutation {
        createHanaEnhancedTypes(input: {
          name: "E2E BIT Test"
          bit_col: "10101010"
          varbit_col: "1100110011"
        }) { id name bit_col varbit_col }
      }
    `);
    expect(data.createHanaEnhancedTypes.name).toBe('E2E BIT Test');
    expect(data.createHanaEnhancedTypes.bit_col).not.toBeNull();
  });

  test('update enhanced_types BIT fields', async () => {
    const data = await client.request(gql`
      mutation {
        updateHanaEnhancedTypes(where: { id: { eq: 1 } }, input: { bit_col: "11110000", varbit_col: "0011001100" }) {
          id bit_col varbit_col
        }
      }
    `);
    expect(data.updateHanaEnhancedTypes[0].id).toBe(1);
    expect(data.updateHanaEnhancedTypes[0].bit_col).not.toBeNull();
  });
});

// ─── Custom types (enums & composite) ────────────────────────────────────────

describe('Custom types — enums & composite', () => {
  test('OrderStatus enum in schema', async () => {
    const data = await client.request(gql`{ __type(name: "HanaOrderStatus") { name kind enumValues { name } } }`);
    expect(data.__type.kind).toBe('ENUM');
    expect(data.__type.enumValues.length).toBe(5);
  });

  test('UserRole enum in schema', async () => {
    const data = await client.request(gql`{ __type(name: "HanaUserRole") { name kind enumValues { name } } }`);
    expect(data.__type.kind).toBe('ENUM');
    expect(data.__type.enumValues.length).toBeGreaterThanOrEqual(3);
  });

  test('Address composite type in schema', async () => {
    const data = await client.request(gql`{ __type(name: "HanaAddress") { name kind fields { name } } }`);
    expect(data.__type.kind).toBe('OBJECT');
    expect(data.__type.fields.length).toBe(5);
  });

  test('query orders with composite Address field', async () => {
    const data = await client.request(gql`{
      hanaOrders(where: { order_id: { eq: 1 } }) {
        order_id shipping_address { street city state postal_code country }
      }
    }`);
    expect(data.hanaOrders[0].shipping_address.street).toBe('123 Delivery St');
    expect(data.hanaOrders[0].shipping_address.city).toBe('New York');
  });

  test('create order with OrderStatus enum', async () => {
    const data = await client.request(gql`
      mutation {
        createHanaOrders(input: { customer_id: 1, status: PENDING, total_amount: 99.99 }) {
          order_id status total_amount
        }
      }
    `);
    expect(data.createHanaOrders.status).toBe('PENDING');
    expect(data.createHanaOrders.total_amount).toBe(99.99);
  });

  test('update order status enum', async () => {
    const data = await client.request(gql`
      mutation { updateHanaOrders(where: { order_id: { eq: 1 } }, input: { status: SHIPPED }) { order_id status } }
    `);
    expect(data.updateHanaOrders[0].status).toBe('SHIPPED');
  });

  test('create order with composite Address input', async () => {
    const data = await client.request(gql`
      mutation {
        createHanaOrders(input: {
          customer_id: 4, status: PENDING, total_amount: 199.99
          shipping_address: { street: "123 Main St", city: "New York", state: "NY", postal_code: "10001", country: "USA" }
        }) { order_id shipping_address { street city } }
      }
    `);
    expect(data.createHanaOrders.shipping_address.street).toBe('123 Main St');
  });

  test('create custom_types_test with multiple enums', async () => {
    const data = await client.request(gql`
      mutation {
        createHanaCustomTypesTest(input: { name: "Mixed Test", status: PENDING, role: USER, priority: MEDIUM }) {
          id status role priority
        }
      }
    `);
    expect(data.createHanaCustomTypesTest.status).toBe('PENDING');
    expect(data.createHanaCustomTypesTest.role).toBe('USER');
    expect(data.createHanaCustomTypesTest.priority).toBe('MEDIUM');
  });

  test('invalid enum value is rejected', async () => {
    const rawClient = createClient(API_URL);
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { createHanaOrders(input: { customer_id: 1, status: INVALID_STATUS, total_amount: 50 }) { order_id } }' }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });
});

// ─── Domain types ─────────────────────────────────────────────────────────────

describe('Domain types', () => {
  test('query domainTypesTest table', async () => {
    const data = await client.request(gql`{ hanaDomainTypesTest { id email quantity price username tags rating description is_active } }`);
    expect(data.hanaDomainTypesTest.length).toBeGreaterThanOrEqual(4);
  });

  test('email domain value', async () => {
    const data = await client.request(gql`{ hanaDomainTypesTest(where: { username: { eq: "john_doe" } }) { email username } }`);
    expect(data.hanaDomainTypesTest[0].email).toBe('john.doe@example.com');
  });

  test('positive integer domain (quantity > 0)', async () => {
    const data = await client.request(gql`{ hanaDomainTypesTest { quantity } }`);
    data.hanaDomainTypesTest.forEach(row => expect(row.quantity).toBeGreaterThan(0));
  });

  test('price domain (price >= 0)', async () => {
    const data = await client.request(gql`{ hanaDomainTypesTest { price } }`);
    data.hanaDomainTypesTest.forEach(row => expect(Number(row.price)).toBeGreaterThanOrEqual(0));
  });

  test('text array domain tags', async () => {
    const data = await client.request(gql`{ hanaDomainTypesTest(where: { username: { eq: "jane_smith" } }) { tags } }`);
    expect(data.hanaDomainTypesTest[0].tags.length).toBeGreaterThanOrEqual(2);
  });

  test('rating domain 1..5', async () => {
    const data = await client.request(gql`{ hanaDomainTypesTest { rating } }`);
    data.hanaDomainTypesTest.forEach(row => {
      expect(row.rating).toBeGreaterThanOrEqual(1);
      expect(row.rating).toBeLessThanOrEqual(5);
    });
  });
});

// ─── Relationships ────────────────────────────────────────────────────────────

describe('Relationships', () => {
  test('orders with nested customer', async () => {
    const data = await client.request(gql`{ hanaOrders { order_id hanaCustomerId { first_name last_name } } }`);
    expect(data.hanaOrders.length).toBeGreaterThanOrEqual(1);
    expect(data.hanaOrders[0].hanaCustomerId.first_name.length).toBeGreaterThan(0);
  });

  test('customer with nested orders', async () => {
    const data = await client.request(gql`{ hanaCustomer(where: { customer_id: { eq: 1 } }) { customer_id hanaOrders { order_id total_amount } } }`);
    expect(data.hanaCustomer[0].hanaOrders.length).toBeGreaterThanOrEqual(1);
  });

  // ── Circular / multi-level relationship tests (N+1 fix) ─────────────────────
  // These exercise visitedTables guard, root-batchContext storage, and SELECT *
  // on forward FK preloads. Before the fix, "Batch context not found" warnings
  // appear and individual fallback queries run per row.

  test('circular: customer → orders → customer returns correct data', async () => {
    // customer(1) has orders [1,2]; order(1).customer_id=1 → must resolve to customer 1
    const data = await client.request(gql`{
      hanaCustomer(where: { customer_id: { eq: 1 } }) {
        customer_id
        first_name
        hanaOrders {
          order_id
          hanaCustomerId { customer_id first_name }
        }
      }
    }`);
    expect(data.hanaCustomer.length).toBe(1);
    const c = data.hanaCustomer[0];
    expect(c.customer_id).toBe(1);
    expect(c.hanaOrders.length).toBeGreaterThanOrEqual(1);
    // Each order's customer must resolve back to customer 1
    c.hanaOrders.forEach(o => {
      expect(o.hanaCustomerId).toBeDefined();
      expect(o.hanaCustomerId.customer_id).toBe(1);
      expect(o.hanaCustomerId.first_name).toBe('MARY');
    });
  });

  test('3-level: users → posts → users resolves author correctly', async () => {
    // user 1 (john_doe) wrote posts 1 & 3; posts[].hanaAuthorId must resolve back to john_doe
    const data = await client.request(gql`{
      hanaUsers(where: { id: { eq: 1 } }) {
        id
        username
        hanaPosts {
          id
          hanaAuthorId { id username }
        }
      }
    }`);
    expect(data.hanaUsers.length).toBe(1);
    const u = data.hanaUsers[0];
    expect(u.username).toBe('john_doe');
    expect(u.hanaPosts.length).toBeGreaterThanOrEqual(1);
    u.hanaPosts.forEach(p => {
      expect(p.hanaAuthorId).toBeDefined();
      expect(p.hanaAuthorId.id).toBe(1);
      expect(p.hanaAuthorId.username).toBe('john_doe');
    });
  });

  test('4-level circular: users → posts → users → posts returns data without error', async () => {
    // Deepest circular test: level 0 users → level 1 posts (reverse FK) →
    // level 2 users (forward FK, requestedColumns={} triggers the N+1 bug) →
    // level 3 posts (reverse FK); visitedTables prevents infinite recursion.
    const data = await client.request(gql`{
      hanaUsers(where: { id: { eq: 1 } }) {
        id
        hanaPosts {
          id
          hanaAuthorId {
            hanaPosts { id }
          }
        }
      }
    }`);
    expect(data.hanaUsers.length).toBe(1);
    const u = data.hanaUsers[0];
    expect(u.hanaPosts.length).toBeGreaterThanOrEqual(1);
    u.hanaPosts.forEach(p => {
      expect(p.hanaAuthorId).toBeDefined();
      // level-2 user's posts must be present (not null/undefined)
      expect(Array.isArray(p.hanaAuthorId.hanaPosts)).toBe(true);
    });
  });

  test('3-level: users → posts → comments loads all levels', async () => {
    // user 1's posts have comments; verify all 3 levels resolve
    const data = await client.request(gql`{
      hanaUsers(where: { id: { eq: 1 } }) {
        id
        hanaPosts {
          id
          hanaComments { id }
        }
      }
    }`);
    expect(data.hanaUsers.length).toBe(1);
    // user 1 authored posts 1 and 3; post 1 has 2 comments, post 3 has 0
    const allPosts = data.hanaUsers[0].hanaPosts;
    expect(allPosts.length).toBeGreaterThanOrEqual(1);
    const post1 = allPosts.find(p => p.id === 1);
    if (post1) {
      expect(Array.isArray(post1.hanaComments)).toBe(true);
      expect(post1.hanaComments.length).toBeGreaterThanOrEqual(2);
    }
  });
});

// ─── Composite key tables ─────────────────────────────────────────────────────

describe('Composite key tables', () => {
  test('query order_items', async () => {
    const data = await client.request(gql`{ hanaOrderItems { order_id product_id quantity price } }`);
    expect(data.hanaOrderItems.length).toBeGreaterThanOrEqual(3);
  });

  test('filter order_items by one part of composite key', async () => {
    const data = await client.request(gql`{ hanaOrderItems(where: { order_id: { eq: 1 } }) { order_id product_id } }`);
    expect(data.hanaOrderItems.length).toBeGreaterThanOrEqual(2);
    data.hanaOrderItems.forEach(r => expect(r.order_id).toBe(1));
  });

  test('filter order_items by full composite key', async () => {
    const data = await client.request(gql`{ hanaOrderItems(where: { order_id: { eq: 1 }, product_id: { eq: 1 } }) { order_id product_id } }`);
    expect(data.hanaOrderItems.length).toBe(1);
    expect(data.hanaOrderItems[0].order_id).toBe(1);
    expect(data.hanaOrderItems[0].product_id).toBe(1);
  });

  test('query parent_table', async () => {
    const data = await client.request(gql`{ hanaParentTable { parent_id1 parent_id2 name } }`);
    expect(data.hanaParentTable.length).toBeGreaterThanOrEqual(3);
  });

  test('query child_table with parent relationship', async () => {
    const data = await client.request(gql`{ hanaChildTable { child_id description hanaParentTable { parent_id1 parent_id2 name } } }`);
    expect(data.hanaChildTable.length).toBeGreaterThanOrEqual(3);
    data.hanaChildTable.forEach(r => expect(r.hanaParentTable).toBeDefined());
  });


  test('query parent_table with reverse childTable (composite FK)', async () => {
    const data = await client.request(gql`{ hanaParentTable { parent_id1 parent_id2 name hanaChildTable { child_id description } } }`);
    expect(data.hanaParentTable.length).toBeGreaterThanOrEqual(3);

    // Core fix assertion: each parent sees only its own children (not cross-parent leakage)
    // Before fix: parent(1,1) and parent(1,2) both saw ALL children with parent_id1=1
    const p11 = data.hanaParentTable.find(p => p.parent_id1 === 1 && p.parent_id2 === 1);
    const p12 = data.hanaParentTable.find(p => p.parent_id1 === 1 && p.parent_id2 === 2);
    const p21 = data.hanaParentTable.find(p => p.parent_id1 === 2 && p.parent_id2 === 1);
    expect(p11).toBeDefined();
    expect(p12).toBeDefined();
    expect(p21).toBeDefined();

    // child_id=1 belongs to parent(1,1) — p11 must contain it, p12 and p21 must not
    expect(p11.hanaChildTable.map(c => c.child_id)).toContain(1);
    expect(p12.hanaChildTable.map(c => c.child_id)).not.toContain(1);
    expect(p21.hanaChildTable.map(c => c.child_id)).not.toContain(1);

    // child_id=2 belongs to parent(1,2) — p11 and p21 must not contain it
    expect(p11.hanaChildTable.map(c => c.child_id)).not.toContain(2);
    expect(p12.hanaChildTable.map(c => c.child_id)).toContain(2);
    expect(p21.hanaChildTable.map(c => c.child_id)).not.toContain(2);

    // child_id=3 belongs to parent(2,1) — p11 and p12 must not contain it
    expect(p11.hanaChildTable.map(c => c.child_id)).not.toContain(3);
    expect(p12.hanaChildTable.map(c => c.child_id)).not.toContain(3);
    expect(p21.hanaChildTable.map(c => c.child_id)).toContain(3);
  });

  test('create order_items with composite key', async () => {
    // Delete first for idempotency across test runs
    await fetch(API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { deleteHanaOrderItems(where: { order_id: { eq: 4 }, product_id: { eq: 3 } }) { order_id } }' }) });
    const data = await client.request(gql`
      mutation {
        createHanaOrderItems(input: { order_id: 4, product_id: 3, quantity: 5, price: 199.99 }) {
          order_id product_id quantity price
        }
      }
    `);
    expect(data.createHanaOrderItems.order_id).toBe(4);
    expect(data.createHanaOrderItems.product_id).toBe(3);
    expect(data.createHanaOrderItems.quantity).toBe(5);
  });

  test('update order_items with composite key', async () => {
    const data = await client.request(gql`
      mutation {
        updateHanaOrderItems(where: { order_id: { eq: 1 }, product_id: { eq: 1 } }, input: { quantity: 10, price: 349.98 }) {
          order_id product_id quantity
        }
      }
    `);
    expect(data.updateHanaOrderItems[0].order_id).toBe(1);
    expect(data.updateHanaOrderItems[0].quantity).toBe(10);
  });

  test('delete order_items with composite key', async () => {
    const data = await client.request(gql`
      mutation {
        deleteHanaOrderItems(where: { order_id: { eq: 4 }, product_id: { eq: 3 } }) {
          order_id product_id
        }
      }
    `);
    expect(data.deleteHanaOrderItems[0].order_id).toBe(4);
    expect(data.deleteHanaOrderItems[0].product_id).toBe(3);
  });

  test('create parent_table with composite key', async () => {
    // Delete first for idempotency across test runs
    await fetch(API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { deleteHanaParentTable(where: { parent_id1: { eq: 9001 }, parent_id2: { eq: 9001 } }) { parent_id1 } }' }) });
    const data = await client.request(gql`
      mutation {
        createHanaParentTable(input: { parent_id1: 9001, parent_id2: 9001, name: "New Parent 9001-9001" }) {
          parent_id1 parent_id2 name
        }
      }
    `);
    expect(data.createHanaParentTable.parent_id1).toBe(9001);
    expect(data.createHanaParentTable.name).toBe('New Parent 9001-9001');
  });

  test('create child_table with composite FK', async () => {
    // Delete first for idempotency across test runs
    await fetch(API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { deleteHanaChildTable(where: { child_id: { eq: 9001 } }) { child_id } }' }) });
    const data = await client.request(gql`
      mutation {
        createHanaChildTable(input: { child_id: 9001, parent_id1: 1, parent_id2: 2, description: "New child for parent 1-2" }) {
          child_id parent_id1 parent_id2 description
        }
      }
    `);
    expect(data.createHanaChildTable.child_id).toBe(9001);
    expect(data.createHanaChildTable.parent_id1).toBe(1);
  });

  test('bulk create order_items', async () => {
    // Use order 5 (no items) with products 1,2 — delete first for idempotency
    await fetch(API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { deleteHanaOrderItems(where: { order_id: { eq: 5 }, product_id: { eq: 1 } }) { order_id } }' }) });
    await fetch(API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { deleteHanaOrderItems(where: { order_id: { eq: 5 }, product_id: { eq: 2 } }) { order_id } }' }) });
    const data = await client.request(gql`
      mutation {
        createManyHanaOrderItems(inputs: [
          { order_id: 5, product_id: 1, quantity: 2, price: 99.98 }
          { order_id: 5, product_id: 2, quantity: 1, price: 79.99 }
        ]) { order_id product_id quantity }
      }
    `);
    expect(data.createManyHanaOrderItems.length).toBe(2);
  });

  test('non-existent FK reference is rejected on create', async () => {
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { createHanaOrderItems(input: { order_id: 99999, product_id: 99999, quantity: 1, price: 1.00 }) { order_id } }' }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });

  test('duplicate composite key is rejected', async () => {
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { createHanaOrderItems(input: { order_id: 1, product_id: 2, quantity: 999, price: 999.99 }) { order_id } }' }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });

  test('composite FK violation is rejected', async () => {
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { createHanaChildTable(input: { parent_id1: 999, parent_id2: 999, description: "Orphaned" }) { child_id } }' }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });
});

// ─── Views ────────────────────────────────────────────────────────────────────

describe('Views (read-only)', () => {
  test('query active_customers view', async () => {
    const data = await client.request(gql`{ hanaActiveCustomers { customer_id first_name last_name email } }`);
    expect(data.hanaActiveCustomers.length).toBeGreaterThanOrEqual(5);
  });

  test('query enhanced_types_summary view', async () => {
    const data = await client.request(gql`{ hanaEnhancedTypesSummary { id name json_name array_size } }`);
    expect(data.hanaEnhancedTypesSummary.length).toBeGreaterThanOrEqual(3);
  });

  test('view pagination', async () => {
    const data = await client.request(gql`{ hanaActiveCustomers(limit: 3, offset: 0) { customer_id } }`);
    expect(data.hanaActiveCustomers.length).toBe(3);
  });

  test('view aggregate', async () => {
    const data = await client.request(gql`{ hanaActiveCustomersAggregate { count } }`);
    expect(data.hanaActiveCustomersAggregate.count).toBeGreaterThanOrEqual(9);
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

// ─── Mutations (CRUD) ─────────────────────────────────────────────────────────

describe('Mutations — CRUD', () => {
  let createdCustomerId;

  test('create customer', async () => {
    const data = await client.request(gql`
      mutation {
        createHanaCustomer(input: { first_name: "TEST", last_name: "USER", email: "test@example.com", active: true }) {
          customer_id first_name last_name email
        }
      }
    `);
    expect(data.createHanaCustomer.first_name).toBe('TEST');
    expect(data.createHanaCustomer.customer_id).toBeTruthy();
    createdCustomerId = data.createHanaCustomer.customer_id;
  });

  test('update customer', async () => {
    const data = await client.request(gql`
      mutation { updateHanaCustomer(where: { customer_id: { eq: 1 } }, input: { email: "updated@example.com" }) { customer_id email } }
    `);
    expect(data.updateHanaCustomer[0].customer_id).toBe(1);
    expect(data.updateHanaCustomer[0].email).toBe('updated@example.com');
  });

  test('bulk create customers', async () => {
    const data = await client.request(gql`
      mutation {
        createManyHanaCustomer(inputs: [
          { first_name: "Bulk1", last_name: "Test", email: "bulk1@test.com" }
          { first_name: "Bulk2", last_name: "Test", email: "bulk2@test.com" }
        ]) { customer_id first_name }
      }
    `);
    expect(data.createManyHanaCustomer.length).toBe(2);
  });

  test('create task with PriorityLevel enum', async () => {
    const data = await client.request(gql`
      mutation {
        createHanaTasks(input: { title: "Test Task", priority: HIGH, assigned_user_id: 1 }) { id title priority }
      }
    `);
    expect(data.createHanaTasks.priority).toBe('HIGH');
  });
});

// ─── Computed fields (PostgreSQL functions) ───────────────────────────────────

describe('Computed fields', () => {
  test('full_name field exists and is non-null', async () => {
    const data = await client.request(gql`{ hanaCustomer(limit: 1) { customer_id first_name last_name full_name } }`);
    expect(data.hanaCustomer[0].full_name).not.toBeNull();
  });

  test('full_name equals first_name + space + last_name', async () => {
    const data = await client.request(gql`{ hanaCustomer(limit: 1) { first_name last_name full_name } }`);
    const { first_name, last_name, full_name } = data.hanaCustomer[0];
    expect(full_name).toBe(`${first_name} ${last_name}`);
  });

  test('active_label is "Active" for active customers', async () => {
    const data = await client.request(gql`{ hanaCustomer(where: { active: { eq: true } }, limit: 1) { active active_label } }`);
    expect(data.hanaCustomer[0].active_label).toBe('Active');
  });

  test('active_label is "Inactive" for inactive customers', async () => {
    const data = await client.request(gql`{ hanaCustomer(where: { active: { eq: false } }, limit: 1) { active active_label } }`);
    expect(data.hanaCustomer[0].active_label).toBe('Inactive');
  });

  test('total_with_tax exists and is non-null', async () => {
    const data = await client.request(gql`{ hanaOrders(limit: 1) { order_id total_amount total_with_tax } }`);
    expect(data.hanaOrders[0].total_with_tax).not.toBeNull();
  });

  test('total_with_tax is 10% more than total_amount', async () => {
    const data = await client.request(gql`{ hanaOrders(limit: 1) { total_amount total_with_tax } }`);
    const amount = Number(data.hanaOrders[0].total_amount);
    const withTax = Number(data.hanaOrders[0].total_with_tax);
    expect(withTax).toBeCloseTo(Math.round(amount * 1.1 * 100) / 100, 1);
  });

  test('is_high_value is true for high-value orders', async () => {
    const data = await client.request(gql`{ hanaOrders(where: { total_amount: { gt: 200 } }, limit: 1) { total_amount is_high_value } }`);
    expect(data.hanaOrders[0].is_high_value).toBe(true);
  });

  test('is_high_value is false for low-value orders', async () => {
    const data = await client.request(gql`{ hanaOrders(where: { total_amount: { lt: 200 } }, limit: 1) { total_amount is_high_value } }`);
    expect(data.hanaOrders[0].is_high_value).toBe(false);
  });

  test('computed fields work with pagination', async () => {
    const data = await client.request(gql`{ hanaCustomer(limit: 3) { customer_id full_name active_label } }`);
    expect(data.hanaCustomer.length).toBe(3);
    data.hanaCustomer.forEach(c => expect(c.full_name).not.toBeNull());
  });
});

// ─── Security tests ───────────────────────────────────────────────────────────

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

  test('high-complexity introspection is blocked', async () => {
    const aliases = Array.from({ length: 30 }, (_, i) => `alias${i}: __schema { types { name } }`).join(' ');
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: `{ ${aliases} }` }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });

  test('SQL injection attempt does not crash — returns null or empty', async () => {
    const data = await client.request(gql`{ __type(name: "'; DROP TABLE users; --") { name } }`);
    // GraphQL returns undefined (omits the key) for non-existent types, which is falsy
    expect(data.__type).toBeFalsy();
  });

  test('legitimate simple query still works', async () => {
    const data = await client.request(gql`{ __schema { types { name } } }`);
    expect(data.__schema.types.length).toBeGreaterThan(5);
  });

  test('invalid field returns GraphQL error', async () => {
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: '{ invalid_field }' }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });

  test('response time under 2 seconds', async () => {
    const start = Date.now();
    await client.request(gql`{ __schema { types { name } } }`);
    expect(Date.now() - start).toBeLessThan(2000);
  });
});

// ─── RLS (Row Level Security) ─────────────────────────────────────────────────

describe('RLS (Row Level Security)', () => {
  let rlsAvailable = false;

  beforeAll(async () => {
    const data = await client.request(gql`{ __type(name: "Query") { fields { name } } }`);
    rlsAvailable = data.__type.fields.some(f => f.name === 'hanaRlsOrders');
    if (!rlsAvailable) {
      console.warn('[WARN] hanaRlsOrders not in schema — RLS tests will be skipped');
    }
  });

  test('no user context blocks all rows', async () => {
    if (!rlsAvailable) return;
    const data = await client.request(gql`{ hanaRlsOrders { id user_id product } }`);
    expect(data.hanaRlsOrders.length).toBe(0);
  });

  test('X-User-Id: alice sees only her rows', async () => {
    if (!rlsAvailable) return;
    const aliceClient = createClient(API_URL, { 'X-User-Id': 'alice' });
    const data = await aliceClient.request(gql`{ hanaRlsOrders { id user_id product } }`);
    expect(data.hanaRlsOrders.length).toBeGreaterThan(0);
    data.hanaRlsOrders.forEach(r => expect(r.user_id).toBe('alice'));
  });

  test('X-User-Id: bob sees only his rows', async () => {
    if (!rlsAvailable) return;
    const bobClient = createClient(API_URL, { 'X-User-Id': 'bob' });
    const data = await bobClient.request(gql`{ hanaRlsOrders { id user_id product } }`);
    expect(data.hanaRlsOrders.length).toBeGreaterThan(0);
    data.hanaRlsOrders.forEach(r => expect(r.user_id).toBe('bob'));
  });

  test('alice and bob see different rows (isolation)', async () => {
    if (!rlsAvailable) return;
    const aliceClient = createClient(API_URL, { 'X-User-Id': 'alice' });
    const bobClient = createClient(API_URL, { 'X-User-Id': 'bob' });
    // Sequential (not concurrent) to avoid race on session-scoped SET variables
    const aliceData = await aliceClient.request(gql`{ hanaRlsOrders { id } }`);
    const bobData = await bobClient.request(gql`{ hanaRlsOrders { id } }`);
    const aliceIds = aliceData.hanaRlsOrders.map(r => r.id).sort();
    const bobIds = bobData.hanaRlsOrders.map(r => r.id).sort();
    expect(aliceIds).not.toEqual(bobIds);
  });
});

// ─── Stored Procedures ────────────────────────────────────────────────────────

describe('Stored Procedures', () => {
  test('procedure mutation appears in schema', async () => {
    const data = await client.request(gql`{ __type(name: "Mutation") { fields { name } } }`);
    const mutationNames = data.__type.fields.map(f => f.name);
    expect(mutationNames).toContain('callHanaGetCustomerOrderCount');
  });

  test('call procedure with IN param returns OUT param', async () => {
    const data = await client.request(gql`
      mutation { callHanaGetCustomerOrderCount(p_customer_id: 1) }
    `);
    expect(data.callHanaGetCustomerOrderCount).toBeDefined();
    const result = JSON.parse(data.callHanaGetCustomerOrderCount);
    expect(result).toHaveProperty('p_count');
    expect(Number(result.p_count)).toBeGreaterThanOrEqual(0);
  });

  // ── transfer_funds: complex procedure with balance check ─────────────────

  beforeAll(async () => {
    // Reset wallet balances so transfer tests are idempotent across repeated runs
    await client.request(gql`mutation { updateHanaWallets(where: { wallet_id: { eq: 1 } }, input: { balance: 1000.00 }) { wallet_id } }`);
    await client.request(gql`mutation { updateHanaWallets(where: { wallet_id: { eq: 2 } }, input: { balance: 500.00 }) { wallet_id } }`);
    await client.request(gql`mutation { updateHanaWallets(where: { wallet_id: { eq: 3 } }, input: { balance: 10.00 }) { wallet_id } }`);
  });

  test('transfer_funds appears in schema', async () => {
    const data = await client.request(gql`{ __type(name: "Mutation") { fields { name } } }`);
    const mutationNames = data.__type.fields.map(f => f.name);
    expect(mutationNames).toContain('callHanaTransferFunds');
  });

  test('transfer_funds happy path — sufficient balance moves money', async () => {
    // Read balances before transfer
    const before = await client.request(gql`
      { hanaWallets(orderBy: { wallet_id: ASC }) { wallet_id balance } }
    `);
    const aliceBefore = Number(before.hanaWallets.find(w => w.wallet_id == 1).balance);
    const bobBefore   = Number(before.hanaWallets.find(w => w.wallet_id == 2).balance);

    // Alice (wallet 1) transfers 200 to Bob (wallet 2)
    const data = await client.request(gql`
      mutation { callHanaTransferFunds(p_from_wallet_id: 1, p_to_wallet_id: 2, p_amount: 200.00) }
    `);
    const result = JSON.parse(data.callHanaTransferFunds);
    expect(result.p_status).toBe('SUCCESS');

    // Verify balances changed by exactly 200
    const after = await client.request(gql`
      { hanaWallets(orderBy: { wallet_id: ASC }) { wallet_id balance } }
    `);
    const aliceAfter = Number(after.hanaWallets.find(w => w.wallet_id == 1).balance);
    const bobAfter   = Number(after.hanaWallets.find(w => w.wallet_id == 2).balance);
    expect(aliceAfter).toBeCloseTo(aliceBefore - 200, 2);
    expect(bobAfter).toBeCloseTo(bobBefore + 200, 2);
  });

  test('transfer_funds unhappy path — insufficient funds rejected, balances unchanged', async () => {
    // Charlie (wallet 3, balance=10) tries to send 500 → should fail
    const data = await client.request(gql`
      mutation { callHanaTransferFunds(p_from_wallet_id: 3, p_to_wallet_id: 1, p_amount: 500.00) }
    `);
    const result = JSON.parse(data.callHanaTransferFunds);
    expect(result.p_status).toMatch(/ERROR.*Insufficient/i);

    // Verify Charlie's balance is still 10, not negative (constraint not violated)
    const wallets = await client.request(gql`
      { hanaWallets(orderBy: { wallet_id: ASC }) { wallet_id balance } }
    `);
    const charlie = wallets.hanaWallets.find(w => w.wallet_id == 3);
    expect(Number(charlie.balance)).toBeCloseTo(10.00, 2);
  });
});

// ─── JWT + RLS Integration Tests ──────────────────────────────────────────────

const AUTH_URL = process.env.AUTH_URL || 'http://localhost:24000';
const PROJECT_ID = 'e2e-org/e2e-test';

async function authPost(path, body) {
  const res = await fetch(`${AUTH_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

async function rawGraphql(query, headers = {}) {
  const res = await fetch(API_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify({ query }),
  });
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

describe('JWT Authentication (via excalibase-auth)', () => {
  let accessToken;
  let authAvailable = false;

  beforeAll(async () => {
    // Wait for auth service — skip suite if unavailable (CI may not run auth service)
    for (let i = 0; i < 15; i++) {
      try {
        const r = await fetch(`${AUTH_URL}/healthz`, { signal: AbortSignal.timeout(3000) });
        if (r.ok) { authAvailable = true; break; }
      } catch (_) {}
      await new Promise(r => setTimeout(r, 3000));
    }
    if (!authAvailable) return;

    // Register (ignore 409)
    await authPost(`/auth/${PROJECT_ID}/register`, {
      email: 'alice-e2e@test.com', password: 'secret123', fullName: 'Alice E2E',
    });

    // Login
    const login = await authPost(`/auth/${PROJECT_ID}/login`, {
      email: 'alice-e2e@test.com', password: 'secret123',
    });
    accessToken = login.data.accessToken;
  });

  test('login returns valid JWT', () => {
    if (!authAvailable) return;
    expect(accessToken).toBeTruthy();
    expect(accessToken).toMatch(/^eyJ/);
  });

  test('validate token via auth service', async () => {
    if (!authAvailable) return;
    const res = await authPost(`/auth/${PROJECT_ID}/validate`, { token: accessToken });
    expect(res.status).toBe(200);
    expect(res.data.valid).toBe(true);
    expect(res.data.email).toBe('alice-e2e@test.com');
  });

  test('graphql accepts valid JWT', async () => {
    if (!authAvailable) return;
    const res = await rawGraphql(
      '{ hanaRlsOrders { id product } }',
      { Authorization: `Bearer ${accessToken}` },
    );
    expect(res.status).toBe(200);
    expect(res.data.data).toBeDefined();
  });

  test('graphql rejects invalid JWT with 401', async () => {
    if (!authAvailable) return;
    const res = await rawGraphql(
      '{ hanaRlsOrders { id } }',
      { Authorization: 'Bearer invalid.jwt.token' },
    );
    expect(res.status).toBe(401);
  });

  test('graphql without token still works (no 401)', async () => {
    if (!authAvailable) return;
    const res = await rawGraphql('{ __schema { queryType { name } } }');
    expect(res.status).toBe(200);
    expect(res.data.data.__schema.queryType.name).toBe('Query');
  });

  test('refresh token returns new access token', async () => {
    if (!authAvailable) return;
    const login = await authPost(`/auth/${PROJECT_ID}/login`, {
      email: 'alice-e2e@test.com', password: 'secret123',
    });
    const res = await authPost(`/auth/${PROJECT_ID}/refresh`, {
      refreshToken: login.data.refreshToken,
    });
    expect(res.status).toBe(200);
    expect(res.data.accessToken).toMatch(/^eyJ/);
  });
});

describe('RLS with X-User-Id header', () => {
  test('alice sees only her RLS orders', async () => {
    const res = await rawGraphql(
      '{ hanaRlsOrders(orderBy: { id: ASC }) { id user_id product } }',
      { 'X-User-Id': 'alice' },
    );
    expect(res.status).toBe(200);
    const orders = res.data.data.hanaRlsOrders;
    expect(orders).toHaveLength(2);
    orders.forEach(o => expect(o.user_id).toBe('alice'));
  });

  test('bob sees only his RLS orders', async () => {
    const res = await rawGraphql(
      '{ hanaRlsOrders(orderBy: { id: ASC }) { id user_id product } }',
      { 'X-User-Id': 'bob' },
    );
    expect(res.status).toBe(200);
    const orders = res.data.data.hanaRlsOrders;
    expect(orders).toHaveLength(2);
    orders.forEach(o => expect(o.user_id).toBe('bob'));
  });

  test('alice and bob see different rows', async () => {
    const alice = await rawGraphql('{ hanaRlsOrders { id } }', { 'X-User-Id': 'alice' });
    const bob = await rawGraphql('{ hanaRlsOrders { id } }', { 'X-User-Id': 'bob' });
    const aliceIds = alice.data.data.hanaRlsOrders.map(o => o.id);
    const bobIds = bob.data.data.hanaRlsOrders.map(o => o.id);
    const overlap = aliceIds.filter(id => bobIds.includes(id));
    expect(overlap).toHaveLength(0);
  });
});

// ─── REST API (PostgREST-compatible) ─────────────────────────────────────────

const REST_URL = process.env.POSTGRES_API_URL
  ? process.env.POSTGRES_API_URL.replace('/graphql', '/api/v1')
  : 'http://localhost:10000/api/v1';

async function restGet(path, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    headers: { 'Content-Type': 'application/json', ...headers },
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

async function restPost(path, body, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

async function restPatch(path, body, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

async function restDelete(path, headers = {}) {
  const res = await fetch(`${REST_URL}${path}`, {
    method: 'DELETE',
    headers: { ...headers },
  });
  return { status: res.status, data: await res.json().catch(() => ({})), headers: res.headers };
}

describe('REST API — Read operations', () => {
  test('GET /customers returns all customers', async () => {
    const res = await restGet('/customer');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(1);
  });

  test('GET with select returns only specified columns', async () => {
    const res = await restGet('/customer?select=id,first_name');
    expect(res.status).toBe(200);
    expect(res.data.data[0].first_name).toBeTruthy();
    expect(res.data.data[0].last_name).toBeUndefined();
  });

  test('GET with eq filter', async () => {
    const res = await restGet('/customer?first_name=eq.MARY');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(1);
    res.data.data.forEach(r => expect(r.first_name).toBe('MARY'));
  });

  test('GET with order=first_name.asc', async () => {
    const res = await restGet('/customer?order=first_name.asc&limit=3');
    expect(res.status).toBe(200);
    const names = res.data.data.map(r => r.first_name);
    expect(names).toEqual([...names].sort());
  });

  test('GET with limit and offset', async () => {
    const res = await restGet('/customer?limit=5&offset=2');
    expect(res.status).toBe(200);
    expect(res.data.data).toHaveLength(5);
  });

  test('GET with Prefer: count=exact returns Content-Range', async () => {
    const res = await restGet('/customer?limit=5', { 'Prefer': 'count=exact' });
    expect(res.status).toBe(200);
    expect(res.data.pagination).toBeDefined();
    expect(res.data.pagination.total).toBeGreaterThan(0);
    expect(res.headers.get('content-range')).toBeTruthy();
  });

  test('GET nonexistent table returns 404', async () => {
    const res = await restGet('/nonexistent_table');
    expect(res.status).toBe(404);
  });

  test('GET with is.null filter', async () => {
    const res = await restGet('/customer?email=is.null');
    expect(res.status).toBe(200);
  });

  test('GET with neq filter', async () => {
    const res = await restGet('/customer?first_name=neq.MARY&limit=5');
    expect(res.status).toBe(200);
    res.data.data.forEach(r => expect(r.first_name).not.toBe('MARY'));
  });

  test('GET with gt filter on numeric column', async () => {
    const res = await restGet('/customer?customer_id=gt.590&limit=5');
    expect(res.status).toBe(200);
    res.data.data.forEach(r => expect(r.customer_id).toBeGreaterThan(590));
  });
});

describe('REST API — Cursor pagination', () => {
  test('GET with first=3 returns 3 records with hasNextPage', async () => {
    const res = await restGet('/customer?first=3&order=customer_id.asc');
    expect(res.status).toBe(200);
    expect(res.data.data).toHaveLength(3);
    expect(res.data.pageInfo.hasNextPage).toBe(true);
  });
});

describe('REST API — Singular object', () => {
  test('GET with vnd.pgrst.object+json returns single object', async () => {
    const res = await restGet('/customer?customer_id=eq.1', {
      'Accept': 'application/vnd.pgrst.object+json',
    });
    expect(res.status).toBe(200);
    expect(res.data.customer_id).toBe(1);
  });

  test('GET singular with 0 rows returns 406', async () => {
    const res = await restGet('/customer?customer_id=eq.99999', {
      'Accept': 'application/vnd.pgrst.object+json',
    });
    expect(res.status).toBe(406);
  });
});

describe('REST API — CSV response', () => {
  test('GET with Accept: text/csv returns CSV', async () => {
    const res = await fetch(`${REST_URL}/customer?select=customer_id,first_name&limit=3&order=customer_id.asc`, {
      headers: { 'Accept': 'text/csv' },
    });
    expect(res.status).toBe(200);
    const csv = await res.text();
    expect(csv).toContain('customer_id,first_name');
    const lines = csv.trim().split('\n');
    expect(lines.length).toBe(4); // header + 3 data rows
  });
});

describe('REST API — Mutations', () => {
  let createdId;

  test('POST creates a record with Prefer: return=representation', async () => {
    const res = await restPost('/customer', {
      first_name: 'REST_TEST',
      last_name: 'USER',
      store_id: 1,
      address_id: 1,
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(201);
    expect(res.data.data.first_name).toBe('REST_TEST');
    createdId = res.data.data.customer_id;
  });

  test('PATCH updates the created record', async () => {
    const res = await restPatch(`/customer?customer_id=eq.${createdId}`, {
      last_name: 'UPDATED',
    }, { 'Prefer': 'return=representation' });
    expect(res.status).toBe(200);
    expect(res.data.data[0].last_name).toBe('UPDATED');
  });

  test('DELETE removes the created record', async () => {
    const res = await restDelete(`/customer?customer_id=eq.${createdId}`, {
      'Prefer': 'return=representation',
    });
    expect(res.status).toBe(200);
    expect(res.data.data[0].customer_id).toBe(createdId);
  });

  test('DELETE without filter returns 400', async () => {
    const res = await restDelete('/customer');
    expect(res.status).toBe(400);
  });

  test('PATCH without filter returns 400', async () => {
    const res = await restPatch('/customer', { first_name: 'X' });
    expect(res.status).toBe(400);
  });
});

describe('REST API — Prefer: tx=rollback', () => {
  test('POST with tx=rollback does not persist', async () => {
    const res = await restPost('/customer', {
      first_name: 'ROLLBACK_TEST',
      last_name: 'SHOULD_NOT_EXIST',
      store_id: 1,
      address_id: 1,
    }, { 'Prefer': 'return=representation, tx=rollback' });
    expect(res.status).toBe(201);
    expect(res.data.data.first_name).toBe('ROLLBACK_TEST');

    const check = await restGet('/customer?first_name=eq.ROLLBACK_TEST');
    expect(check.data.data).toHaveLength(0);
  });
});

describe('REST API — OpenAPI spec', () => {
  test('GET /api/v1 returns OpenAPI spec', async () => {
    const res = await fetch(`${REST_URL}`, {
      headers: { 'Accept': 'application/openapi+json' },
    });
    expect(res.status).toBe(200);
    const spec = await res.json();
    expect(spec.openapi).toBe('3.0.3');
    expect(spec.paths).toBeDefined();
    expect(Object.keys(spec.paths).length).toBeGreaterThan(0);
  });
});

describe('REST API — Accept-Profile', () => {
  test('Accept-Profile selects schema', async () => {
    const res = await restGet('/customer', { 'Accept-Profile': 'hana' });
    expect(res.status).toBe(200);
  });

  test('unknown Accept-Profile returns 404', async () => {
    const res = await restGet('/customer', { 'Accept-Profile': 'nonexistent' });
    expect(res.status).toBe(404);
  });
});

// ─── Phase 2: Multi-Mutation Transaction ─────────────────────────────────────

describe('GraphQL — Multi-Mutation Transaction', () => {
  test('two creates in single request both execute and return distinct IDs', async () => {
    const data = await client.request(gql`
      mutation {
        c1: createHanaCustomer(input: { first_name: "MultiMut1", last_name: "E2E", email: "multimut1_e2e@test.com", active: true }) { customer_id first_name }
        c2: createHanaCustomer(input: { first_name: "MultiMut2", last_name: "E2E", email: "multimut2_e2e@test.com", active: true }) { customer_id first_name }
      }
    `);
    expect(data.c1.first_name).toBe('MultiMut1');
    expect(data.c2.first_name).toBe('MultiMut2');
    expect(data.c1.customer_id).toBeGreaterThan(0);
    expect(data.c2.customer_id).toBeGreaterThan(0);
    expect(data.c1.customer_id).not.toBe(data.c2.customer_id);
  });

  test('create and update in single request — both execute atomically', async () => {
    // Create a target customer to update
    const created = await client.request(gql`
      mutation {
        createHanaCustomer(input: { first_name: "MutTarget", last_name: "E2E", email: "muttarget_e2e@test.com", active: true }) { customer_id }
      }
    `);
    const id = created.createHanaCustomer.customer_id;

    const data = await client.request(gql`
      mutation {
        newRow: createHanaCustomer(input: { first_name: "MutNew", last_name: "E2E", email: "mutnew_e2e@test.com", active: true }) { customer_id first_name }
        updated: updateHanaCustomer(where: { customer_id: { eq: ${id} } }, input: { last_name: "MultiUpdated" }) { customer_id last_name }
      }
    `);
    expect(data.newRow.first_name).toBe('MutNew');
    expect(Array.isArray(data.updated)).toBe(true);
    expect(data.updated[0].last_name).toBe('MultiUpdated');
  });

  test('aliases are used as response keys in multi-mutation response', async () => {
    const data = await client.request(gql`
      mutation {
        first: createHanaCustomer(input: { first_name: "AliasA", last_name: "E2E", email: "aliasa_e2e@test.com", active: true }) { customer_id first_name }
        second: createHanaCustomer(input: { first_name: "AliasB", last_name: "E2E", email: "aliasb_e2e@test.com", active: true }) { customer_id first_name }
      }
    `);
    // Aliases become keys in response
    expect(data.first).toBeDefined();
    expect(data.second).toBeDefined();
    expect(data.first.first_name).toBe('AliasA');
    expect(data.second.first_name).toBe('AliasB');
  });
});

// ─── Phase 3: Nested FK Insert ────────────────────────────────────────────────

describe('GraphQL — Nested FK Insert', () => {
  test('create order with nested order_items in single mutation', async () => {
    const data = await client.request(gql`
      mutation {
        createHanaOrders(input: {
          customer_id: 1
          total_amount: 199.99
          hanaOrderItems: {
            data: [
              { product_id: 1, quantity: 1, price: 99.99 }
              { product_id: 2, quantity: 2, price: 49.99 }
            ]
          }
        }) { order_id total_amount }
      }
    `);
    expect(data.createHanaOrders.order_id).toBeGreaterThan(0);
    expect(Number(data.createHanaOrders.total_amount)).toBeCloseTo(199.99, 1);
  });

  test('nested order_items are persisted and queryable after creation', async () => {
    const created = await client.request(gql`
      mutation {
        createHanaOrders(input: {
          customer_id: 1
          total_amount: 59.99
          hanaOrderItems: {
            data: [
              { product_id: 3, quantity: 1, price: 59.99 }
            ]
          }
        }) { order_id }
      }
    `);
    const orderId = created.createHanaOrders.order_id;

    const data = await client.request(gql`
      { hanaOrderItems(where: { order_id: { eq: ${orderId} } }) { order_id product_id quantity } }
    `);
    expect(data.hanaOrderItems.length).toBe(1);
    expect(data.hanaOrderItems[0].product_id).toBe(3);
    expect(data.hanaOrderItems[0].order_id).toBe(orderId);
  });
});

// ─── Phase 1: Deep REST FK Embedding ─────────────────────────────────────────

describe('REST API — Deep FK Embedding', () => {
  test('2-level embed: orders with customer (forward FK)', async () => {
    const res = await restGet('/orders?select=order_id,total_amount,customer(customer_id,first_name)&order=order_id.asc&limit=3');
    expect(res.status).toBe(200);
    const rows = res.data.data;
    expect(rows.length).toBeGreaterThan(0);
    expect(rows[0].customer).toBeDefined();
    expect(rows[0].customer.first_name).toBeDefined();
  });

  test('2-level embed: customer with orders (reverse FK)', async () => {
    const res = await restGet('/customer?select=customer_id,first_name,orders(order_id,total_amount)&customer_id=lte.3&order=customer_id.asc');
    expect(res.status).toBe(200);
    const rows = res.data.data;
    expect(rows.length).toBeGreaterThan(0);
    const withOrders = rows.filter(r => r.orders && r.orders.length > 0);
    expect(withOrders.length).toBeGreaterThan(0);
    expect(withOrders[0].orders[0].total_amount).toBeDefined();
  });

  test('3-level deep embed: customer -> orders -> order_items', async () => {
    const res = await restGet('/customer?select=customer_id,first_name,orders(order_id,total_amount,order_items(order_id,product_id,quantity))&customer_id=lte.3&order=customer_id.asc');
    expect(res.status).toBe(200);
    const rows = res.data.data;
    expect(rows.length).toBeGreaterThan(0);
    // Find a customer that has orders with order_items
    const customersWithOrders = rows.filter(r => r.orders && r.orders.length > 0);
    expect(customersWithOrders.length).toBeGreaterThan(0);
    const ordersWithItems = customersWithOrders
      .flatMap(c => c.orders)
      .filter(o => o.order_items && o.order_items.length > 0);
    expect(ordersWithItems.length).toBeGreaterThan(0);
    expect(ordersWithItems[0].order_items[0].product_id).toBeGreaterThan(0);
  });
});
