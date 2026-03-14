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
    const data = await client.request(gql`{ customer { customer_id first_name last_name email } }`);
    expect(data.customer.length).toBeGreaterThanOrEqual(10);
  });

  test('filter customers by first_name eq', async () => {
    const data = await client.request(gql`{ customer(where: { first_name: { eq: "MARY" } }) { customer_id first_name } }`);
    expect(data.customer.length).toBeGreaterThanOrEqual(1);
  });

  test('filter customers with OR', async () => {
    const data = await client.request(gql`{ customer(or: [{ first_name: { eq: "MARY" } }, { first_name: { eq: "JOHN" } }]) { customer_id first_name } }`);
    expect(data.customer.length).toBeGreaterThanOrEqual(2);
  });

  test('customer pagination limit+offset', async () => {
    const data = await client.request(gql`{ customer(limit: 3, offset: 2) { customer_id first_name } }`);
    expect(data.customer.length).toBe(3);
  });

  test('customer ordering ASC', async () => {
    const data = await client.request(gql`{ customer(orderBy: { customer_id: ASC }, limit: 5) { customer_id } }`);
    expect(data.customer[0].customer_id).toBeLessThan(data.customer[1].customer_id);
  });

  test('complex date range filter', async () => {
    const data = await client.request(gql`{ customer(where: { create_date: { gte: "2006-01-01", lt: "2008-01-01" } }) { customer_id create_date } }`);
    expect(data.customer.length).toBeGreaterThanOrEqual(1);
  });

  test('string contains filter', async () => {
    const data = await client.request(gql`{ customer(where: { email: { contains: "@example.com" } }) { customer_id email } }`);
    expect(data.customer.length).toBeGreaterThanOrEqual(5);
  });

  test('boolean filter', async () => {
    const data = await client.request(gql`{ customer(where: { active: { eq: true } }) { customer_id active } }`);
    expect(data.customer.length).toBeGreaterThanOrEqual(5);
  });

  test('IN array filter', async () => {
    const data = await client.request(gql`{ customer(where: { customer_id: { in: [1, 2, 3] } }) { customer_id } }`);
    expect(data.customer.length).toBe(3);
  });
});

// ─── Aggregate queries ────────────────────────────────────────────────────────

describe('Aggregate queries', () => {
  test('customer aggregate count', async () => {
    const data = await client.request(gql`{ customer_aggregate { count } }`);
    expect(data.customer_aggregate.count).toBeGreaterThanOrEqual(10);
  });

  test('orders aggregate count', async () => {
    const data = await client.request(gql`{ orders_aggregate { count } }`);
    expect(data.orders_aggregate.count).toBeGreaterThanOrEqual(1);
  });

  test('orders aggregate sum/avg/min/max', async () => {
    // Postgres aggregate uses nested per-column types: sum { col }, avg { col }, etc.
    const data = await client.request(gql`{ orders_aggregate { sum { total_amount } avg { total_amount } min { total_amount } max { total_amount } } }`);
    expect(Number(data.orders_aggregate.sum.total_amount)).toBeGreaterThan(0);
    expect(Number(data.orders_aggregate.avg.total_amount)).toBeGreaterThan(0);
    expect(Number(data.orders_aggregate.min.total_amount)).toBeLessThanOrEqual(Number(data.orders_aggregate.max.total_amount));
  });
});

// ─── Connection / cursor pagination ──────────────────────────────────────────

describe('Connection (cursor pagination)', () => {
  test('customerConnection returns edges and pageInfo', async () => {
    const data = await client.request(gql`{
      customerConnection(first: 3) {
        edges { node { customer_id first_name } cursor }
        pageInfo { hasNextPage hasPreviousPage }
      }
    }`);
    expect(data.customerConnection.edges.length).toBeLessThanOrEqual(3);
    expect(typeof data.customerConnection.pageInfo.hasNextPage).toBe('boolean');
  });

  test('customerConnection hasNextPage is true when more rows exist', async () => {
    const data = await client.request(gql`{ customerConnection(first: 3) { pageInfo { hasNextPage } } }`);
    expect(data.customerConnection.pageInfo.hasNextPage).toBe(true);
  });
});

// ─── Enhanced PostgreSQL types ────────────────────────────────────────────────

describe('Enhanced PostgreSQL types', () => {
  test('query all enhanced_types fields', async () => {
    const data = await client.request(gql`{ enhanced_types { id name json_col jsonb_col int_array text_array timestamptz_col } }`);
    expect(data.enhanced_types.length).toBeGreaterThanOrEqual(3);
  });

  test('filter enhanced_types by name', async () => {
    const data = await client.request(gql`{ enhanced_types(where: { name: { eq: "Test Record 1" } }) { id name json_col } }`);
    expect(data.enhanced_types[0].json_col).not.toBeNull();
  });

  test('array fields are non-null', async () => {
    const data = await client.request(gql`{ enhanced_types { id int_array text_array } }`);
    expect(data.enhanced_types[0].int_array).not.toBeNull();
    expect(data.enhanced_types[0].text_array).not.toBeNull();
  });

  test('network type fields', async () => {
    const data = await client.request(gql`{ enhanced_types { id inet_col cidr_col macaddr_col } }`);
    expect(data.enhanced_types[0].inet_col).not.toBeNull();
  });

  test('datetime fields', async () => {
    const data = await client.request(gql`{ enhanced_types { id timestamptz_col timetz_col interval_col } }`);
    expect(data.enhanced_types[0].timestamptz_col).not.toBeNull();
  });

  test('BIT/VARBIT fields', async () => {
    const data = await client.request(gql`{ enhanced_types { id name bit_col varbit_col } }`);
    expect(data.enhanced_types[0].bit_col).not.toBeNull();
    expect(data.enhanced_types[0].varbit_col).not.toBeNull();
  });

  test('create enhanced_types with direct JSON objects', async () => {
    const data = await client.request(gql`
      mutation {
        createEnhancedTypes(input: {
          name: "E2E JSON Direct Object Test"
          json_col: { user: { name: "Alice", age: 28 }, tags: ["premium"] }
          jsonb_col: { profile: { id: 12345, email: "alice@e2e.com" } }
          numeric_col: 1234.56
        }) { id name json_col jsonb_col numeric_col }
      }
    `);
    expect(data.createEnhancedTypes.name).toBe('E2E JSON Direct Object Test');
    expect(data.createEnhancedTypes.json_col).not.toBeNull();
  });

  test('create enhanced_types with BIT values', async () => {
    const data = await client.request(gql`
      mutation {
        createEnhancedTypes(input: {
          name: "E2E BIT Test"
          bit_col: "10101010"
          varbit_col: "1100110011"
        }) { id name bit_col varbit_col }
      }
    `);
    expect(data.createEnhancedTypes.name).toBe('E2E BIT Test');
    expect(data.createEnhancedTypes.bit_col).not.toBeNull();
  });

  test('update enhanced_types BIT fields', async () => {
    const data = await client.request(gql`
      mutation {
        updateEnhancedTypes(input: { id: 1, bit_col: "11110000", varbit_col: "0011001100" }) {
          id bit_col varbit_col
        }
      }
    `);
    expect(data.updateEnhancedTypes.id).toBe(1);
    expect(data.updateEnhancedTypes.bit_col).not.toBeNull();
  });
});

// ─── Custom types (enums & composite) ────────────────────────────────────────

describe('Custom types — enums & composite', () => {
  test('OrderStatus enum in schema', async () => {
    const data = await client.request(gql`{ __type(name: "OrderStatus") { name kind enumValues { name } } }`);
    expect(data.__type.kind).toBe('ENUM');
    expect(data.__type.enumValues.length).toBe(5);
  });

  test('UserRole enum in schema', async () => {
    const data = await client.request(gql`{ __type(name: "UserRole") { name kind enumValues { name } } }`);
    expect(data.__type.kind).toBe('ENUM');
    expect(data.__type.enumValues.length).toBeGreaterThanOrEqual(3);
  });

  test('Address composite type in schema', async () => {
    const data = await client.request(gql`{ __type(name: "Address") { name kind fields { name } } }`);
    expect(data.__type.kind).toBe('OBJECT');
    expect(data.__type.fields.length).toBe(5);
  });

  test('query orders with composite Address field', async () => {
    const data = await client.request(gql`{
      orders(where: { order_id: { eq: 1 } }) {
        order_id shipping_address { street city state postal_code country }
      }
    }`);
    expect(data.orders[0].shipping_address.street).toBe('123 Delivery St');
    expect(data.orders[0].shipping_address.city).toBe('New York');
  });

  test('create order with OrderStatus enum', async () => {
    const data = await client.request(gql`
      mutation {
        createOrders(input: { customer_id: 1, status: PENDING, total_amount: 99.99 }) {
          order_id status total_amount
        }
      }
    `);
    expect(data.createOrders.status).toBe('PENDING');
    expect(data.createOrders.total_amount).toBe(99.99);
  });

  test('update order status enum', async () => {
    const data = await client.request(gql`
      mutation { updateOrders(input: { order_id: 1, status: SHIPPED }) { order_id status } }
    `);
    expect(data.updateOrders.status).toBe('SHIPPED');
  });

  test('create order with composite Address input', async () => {
    const data = await client.request(gql`
      mutation {
        createOrders(input: {
          customer_id: 4, status: PENDING, total_amount: 199.99
          shipping_address: { street: "123 Main St", city: "New York", state: "NY", postal_code: "10001", country: "USA" }
        }) { order_id shipping_address { street city } }
      }
    `);
    expect(data.createOrders.shipping_address.street).toBe('123 Main St');
  });

  test('create custom_types_test with multiple enums', async () => {
    const data = await client.request(gql`
      mutation {
        createCustomTypesTest(input: { name: "Mixed Test", status: PENDING, role: USER, priority: MEDIUM }) {
          id status role priority
        }
      }
    `);
    expect(data.createCustomTypesTest.status).toBe('PENDING');
    expect(data.createCustomTypesTest.role).toBe('USER');
    expect(data.createCustomTypesTest.priority).toBe('MEDIUM');
  });

  test('invalid enum value is rejected', async () => {
    const rawClient = createClient(API_URL);
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { createOrders(input: { customer_id: 1, status: INVALID_STATUS, total_amount: 50 }) { order_id } }' }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });
});

// ─── Domain types ─────────────────────────────────────────────────────────────

describe('Domain types', () => {
  test('query domain_types_test table', async () => {
    const data = await client.request(gql`{ domain_types_test { id email quantity price username tags rating description is_active } }`);
    expect(data.domain_types_test.length).toBeGreaterThanOrEqual(4);
  });

  test('email domain value', async () => {
    const data = await client.request(gql`{ domain_types_test(where: { username: { eq: "john_doe" } }) { email username } }`);
    expect(data.domain_types_test[0].email).toBe('john.doe@example.com');
  });

  test('positive integer domain (quantity > 0)', async () => {
    const data = await client.request(gql`{ domain_types_test { quantity } }`);
    data.domain_types_test.forEach(row => expect(row.quantity).toBeGreaterThan(0));
  });

  test('price domain (price >= 0)', async () => {
    const data = await client.request(gql`{ domain_types_test { price } }`);
    data.domain_types_test.forEach(row => expect(Number(row.price)).toBeGreaterThanOrEqual(0));
  });

  test('text array domain tags', async () => {
    const data = await client.request(gql`{ domain_types_test(where: { username: { eq: "jane_smith" } }) { tags } }`);
    expect(data.domain_types_test[0].tags.length).toBeGreaterThanOrEqual(2);
  });

  test('rating domain 1..5', async () => {
    const data = await client.request(gql`{ domain_types_test { rating } }`);
    data.domain_types_test.forEach(row => {
      expect(row.rating).toBeGreaterThanOrEqual(1);
      expect(row.rating).toBeLessThanOrEqual(5);
    });
  });
});

// ─── Relationships ────────────────────────────────────────────────────────────

describe('Relationships', () => {
  test('orders with nested customer', async () => {
    const data = await client.request(gql`{ orders { order_id customer { first_name last_name } } }`);
    expect(data.orders.length).toBeGreaterThanOrEqual(1);
    expect(data.orders[0].customer.first_name.length).toBeGreaterThan(0);
  });

  test('customer with nested orders', async () => {
    const data = await client.request(gql`{ customer(where: { customer_id: { eq: 1 } }) { customer_id orders { order_id total_amount } } }`);
    expect(data.customer[0].orders.length).toBeGreaterThanOrEqual(1);
  });
});

// ─── Composite key tables ─────────────────────────────────────────────────────

describe('Composite key tables', () => {
  test('query order_items', async () => {
    const data = await client.request(gql`{ order_items { order_id product_id quantity price } }`);
    expect(data.order_items.length).toBeGreaterThanOrEqual(3);
  });

  test('filter order_items by one part of composite key', async () => {
    const data = await client.request(gql`{ order_items(where: { order_id: { eq: 1 } }) { order_id product_id } }`);
    expect(data.order_items.length).toBeGreaterThanOrEqual(2);
    data.order_items.forEach(r => expect(r.order_id).toBe(1));
  });

  test('filter order_items by full composite key', async () => {
    const data = await client.request(gql`{ order_items(where: { order_id: { eq: 1 }, product_id: { eq: 1 } }) { order_id product_id } }`);
    expect(data.order_items.length).toBe(1);
    expect(data.order_items[0].order_id).toBe(1);
    expect(data.order_items[0].product_id).toBe(1);
  });

  test('query parent_table', async () => {
    const data = await client.request(gql`{ parent_table { parent_id1 parent_id2 name } }`);
    expect(data.parent_table.length).toBeGreaterThanOrEqual(3);
  });

  test('query child_table with parent relationship', async () => {
    const data = await client.request(gql`{ child_table { child_id description parent_table { parent_id1 parent_id2 name } } }`);
    expect(data.child_table.length).toBeGreaterThanOrEqual(3);
    data.child_table.forEach(r => expect(r.parent_table).toBeDefined());
  });

  test('create order_items with composite key', async () => {
    const data = await client.request(gql`
      mutation {
        createOrderItems(input: { order_id: 4, product_id: 3, quantity: 5, price: 199.99 }) {
          order_id product_id quantity price
        }
      }
    `);
    expect(data.createOrderItems.order_id).toBe(4);
    expect(data.createOrderItems.product_id).toBe(3);
    expect(data.createOrderItems.quantity).toBe(5);
  });

  test('update order_items with composite key', async () => {
    const data = await client.request(gql`
      mutation {
        updateOrderItems(input: { order_id: 1, product_id: 1, quantity: 10, price: 349.98 }) {
          order_id product_id quantity
        }
      }
    `);
    expect(data.updateOrderItems.order_id).toBe(1);
    expect(data.updateOrderItems.quantity).toBe(10);
  });

  test('delete order_items with composite key', async () => {
    const data = await client.request(gql`
      mutation {
        deleteOrderItems(input: { order_id: 4, product_id: 3 }) {
          order_id product_id
        }
      }
    `);
    expect(data.deleteOrderItems.order_id).toBe(4);
    expect(data.deleteOrderItems.product_id).toBe(3);
  });

  test('create parent_table with composite key', async () => {
    // Delete first for idempotency across test runs
    await fetch(API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { deleteParentTable(input: { parent_id1: 9001, parent_id2: 9001 }) { parent_id1 } }' }) });
    const data = await client.request(gql`
      mutation {
        createParentTable(input: { parent_id1: 9001, parent_id2: 9001, name: "New Parent 9001-9001" }) {
          parent_id1 parent_id2 name
        }
      }
    `);
    expect(data.createParentTable.parent_id1).toBe(9001);
    expect(data.createParentTable.name).toBe('New Parent 9001-9001');
  });

  test('create child_table with composite FK', async () => {
    // Delete first for idempotency across test runs
    await fetch(API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { deleteChildTable(input: { child_id: 9001 }) { child_id } }' }) });
    const data = await client.request(gql`
      mutation {
        createChildTable(input: { child_id: 9001, parent_id1: 1, parent_id2: 2, description: "New child for parent 1-2" }) {
          child_id parent_id1 parent_id2 description
        }
      }
    `);
    expect(data.createChildTable.child_id).toBe(9001);
    expect(data.createChildTable.parent_id1).toBe(1);
  });

  test('bulk create order_items', async () => {
    // Use order 5 (no items) with products 1,2 — delete first for idempotency
    await fetch(API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { deleteOrderItems(input: { order_id: 5, product_id: 1 }) { order_id } }' }) });
    await fetch(API_URL, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { deleteOrderItems(input: { order_id: 5, product_id: 2 }) { order_id } }' }) });
    const data = await client.request(gql`
      mutation {
        createManyOrderItemss(inputs: [
          { order_id: 5, product_id: 1, quantity: 2, price: 99.98 }
          { order_id: 5, product_id: 2, quantity: 1, price: 79.99 }
        ]) { order_id product_id quantity }
      }
    `);
    expect(data.createManyOrderItemss.length).toBe(2);
  });

  test('incomplete composite key is rejected', async () => {
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { updateOrderItems(input: { order_id: 1, quantity: 15 }) { order_id } }' }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });

  test('duplicate composite key is rejected', async () => {
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { createOrderItems(input: { order_id: 1, product_id: 2, quantity: 999, price: 999.99 }) { order_id } }' }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });

  test('composite FK violation is rejected', async () => {
    const resp = await fetch(API_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'mutation { createChildTable(input: { parent_id1: 999, parent_id2: 999, description: "Orphaned" }) { child_id } }' }),
    });
    const json = await resp.json();
    expect(json.errors).toBeDefined();
  });
});

// ─── Views ────────────────────────────────────────────────────────────────────

describe('Views (read-only)', () => {
  test('query active_customers view', async () => {
    const data = await client.request(gql`{ active_customers { customer_id first_name last_name email } }`);
    expect(data.active_customers.length).toBeGreaterThanOrEqual(5);
  });

  test('query enhanced_types_summary view', async () => {
    const data = await client.request(gql`{ enhanced_types_summary { id name json_name array_size } }`);
    expect(data.enhanced_types_summary.length).toBeGreaterThanOrEqual(3);
  });

  test('view pagination', async () => {
    const data = await client.request(gql`{ active_customers(limit: 3, offset: 0) { customer_id } }`);
    expect(data.active_customers.length).toBe(3);
  });

  test('view aggregate', async () => {
    const data = await client.request(gql`{ active_customers_aggregate { count } }`);
    expect(data.active_customers_aggregate.count).toBeGreaterThanOrEqual(10);
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
        createCustomer(input: { first_name: "TEST", last_name: "USER", email: "test@example.com", active: true }) {
          customer_id first_name last_name email
        }
      }
    `);
    expect(data.createCustomer.first_name).toBe('TEST');
    expect(data.createCustomer.customer_id).toBeTruthy();
    createdCustomerId = data.createCustomer.customer_id;
  });

  test('update customer', async () => {
    const data = await client.request(gql`
      mutation { updateCustomer(input: { customer_id: 1, email: "updated@example.com" }) { customer_id email } }
    `);
    expect(data.updateCustomer.customer_id).toBe(1);
    expect(data.updateCustomer.email).toBe('updated@example.com');
  });

  test('bulk create customers', async () => {
    const data = await client.request(gql`
      mutation {
        createManyCustomers(inputs: [
          { first_name: "Bulk1", last_name: "Test", email: "bulk1@test.com" }
          { first_name: "Bulk2", last_name: "Test", email: "bulk2@test.com" }
        ]) { customer_id first_name }
      }
    `);
    expect(data.createManyCustomers.length).toBe(2);
  });

  test('create task with PriorityLevel enum', async () => {
    const data = await client.request(gql`
      mutation {
        createTasks(input: { title: "Test Task", priority: HIGH, assigned_user_id: 1 }) { id title priority }
      }
    `);
    expect(data.createTasks.priority).toBe('HIGH');
  });
});

// ─── Computed fields (PostgreSQL functions) ───────────────────────────────────

describe('Computed fields', () => {
  test('full_name field exists and is non-null', async () => {
    const data = await client.request(gql`{ customer(limit: 1) { customer_id first_name last_name full_name } }`);
    expect(data.customer[0].full_name).not.toBeNull();
  });

  test('full_name equals first_name + space + last_name', async () => {
    const data = await client.request(gql`{ customer(limit: 1) { first_name last_name full_name } }`);
    const { first_name, last_name, full_name } = data.customer[0];
    expect(full_name).toBe(`${first_name} ${last_name}`);
  });

  test('active_label is "Active" for active customers', async () => {
    const data = await client.request(gql`{ customer(where: { active: { eq: true } }, limit: 1) { active active_label } }`);
    expect(data.customer[0].active_label).toBe('Active');
  });

  test('active_label is "Inactive" for inactive customers', async () => {
    const data = await client.request(gql`{ customer(where: { active: { eq: false } }, limit: 1) { active active_label } }`);
    expect(data.customer[0].active_label).toBe('Inactive');
  });

  test('total_with_tax exists and is non-null', async () => {
    const data = await client.request(gql`{ orders(limit: 1) { order_id total_amount total_with_tax } }`);
    expect(data.orders[0].total_with_tax).not.toBeNull();
  });

  test('total_with_tax is 10% more than total_amount', async () => {
    const data = await client.request(gql`{ orders(limit: 1) { total_amount total_with_tax } }`);
    const amount = Number(data.orders[0].total_amount);
    const withTax = Number(data.orders[0].total_with_tax);
    expect(withTax).toBeCloseTo(Math.round(amount * 1.1 * 100) / 100, 1);
  });

  test('is_high_value is true for high-value orders', async () => {
    const data = await client.request(gql`{ orders(where: { total_amount: { gt: 200 } }, limit: 1) { total_amount is_high_value } }`);
    expect(data.orders[0].is_high_value).toBe(true);
  });

  test('is_high_value is false for low-value orders', async () => {
    const data = await client.request(gql`{ orders(where: { total_amount: { lt: 200 } }, limit: 1) { total_amount is_high_value } }`);
    expect(data.orders[0].is_high_value).toBe(false);
  });

  test('computed fields work with pagination', async () => {
    const data = await client.request(gql`{ customer(limit: 3) { customer_id full_name active_label } }`);
    expect(data.customer.length).toBe(3);
    data.customer.forEach(c => expect(c.full_name).not.toBeNull());
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
    rlsAvailable = data.__type.fields.some(f => f.name === 'rls_orders');
    if (!rlsAvailable) {
      console.warn('[WARN] rls_orders not in schema — RLS tests will be skipped');
    }
  });

  test('no user context blocks all rows', async () => {
    if (!rlsAvailable) return;
    const data = await client.request(gql`{ rls_orders { id user_id product } }`);
    expect(data.rls_orders.length).toBe(0);
  });

  test('X-User-Id: alice sees only her rows', async () => {
    if (!rlsAvailable) return;
    const aliceClient = createClient(API_URL, { 'X-User-Id': 'alice' });
    const data = await aliceClient.request(gql`{ rls_orders { id user_id product } }`);
    expect(data.rls_orders.length).toBeGreaterThan(0);
    data.rls_orders.forEach(r => expect(r.user_id).toBe('alice'));
  });

  test('X-User-Id: bob sees only his rows', async () => {
    if (!rlsAvailable) return;
    const bobClient = createClient(API_URL, { 'X-User-Id': 'bob' });
    const data = await bobClient.request(gql`{ rls_orders { id user_id product } }`);
    expect(data.rls_orders.length).toBeGreaterThan(0);
    data.rls_orders.forEach(r => expect(r.user_id).toBe('bob'));
  });

  test('alice and bob see different rows (isolation)', async () => {
    if (!rlsAvailable) return;
    const aliceClient = createClient(API_URL, { 'X-User-Id': 'alice' });
    const bobClient = createClient(API_URL, { 'X-User-Id': 'bob' });
    const [aliceData, bobData] = await Promise.all([
      aliceClient.request(gql`{ rls_orders { id } }`),
      bobClient.request(gql`{ rls_orders { id } }`),
    ]);
    const aliceIds = aliceData.rls_orders.map(r => r.id).sort();
    const bobIds = bobData.rls_orders.map(r => r.id).sort();
    expect(aliceIds).not.toEqual(bobIds);
  });
});
