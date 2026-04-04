/**
 * CDC Subscription E2E Tests
 *
 * Tests real-time GraphQL subscriptions against the full stack:
 *   Database (WAL/Binlog) → excalibase-watcher → NATS → NatsCDCService → WebSocket
 *
 * Postgres: docker-compose up (port 10000)
 * MySQL:    docker-compose -f docker-compose.mysql.yml up (port 10001)
 */

const { gql } = require('graphql-request');
const {
  waitForApi,
  createClient,
  psql,
  mysqlExec,
  subscribeGraphQL,
  waitFor,
} = require('./client');

// ─── Postgres CDC Subscriptions ──────────────────────────────────────────────

const PG_API = process.env.POSTGRES_API_URL || 'http://localhost:10000/graphql';
const PG_WS = process.env.POSTGRES_WS_URL || 'ws://localhost:10000/graphql';

const CUSTOMER_SUBSCRIPTION = `
  subscription {
    hanaCustomerChanges {
      operation
      table
      timestamp
      data {
        customer_id
        first_name
        last_name
        email
        new {
          customer_id
          first_name
          last_name
          email
        }
      }
      error
    }
  }
`;

describe('Postgres CDC subscriptions', () => {
  let pgClient;
  let sub;

  beforeAll(async () => {
    await waitForApi(PG_API);
    pgClient = createClient(PG_API);
  }, 120000);

  afterEach(() => {
    if (sub) sub.close();
  });

  test('receives INSERT event from GraphQL mutation', async () => {
    sub = subscribeGraphQL(PG_WS, CUSTOMER_SUBSCRIPTION);
    await sub.ready;

    const data = await pgClient.request(gql`
      mutation {
        createHanaCustomer(input: {
          first_name: "CDC_PG_Insert"
          last_name: "E2E"
          email: "cdc-pg-insert@e2e.test"
        }) { customer_id first_name }
      }
    `);
    const cid = data.createHanaCustomer.customer_id;

    await waitFor(sub.events, (e) =>
      e.some((ev) => ev.operation === 'INSERT' && ev.data?.email === 'cdc-pg-insert@e2e.test')
    );

    const insert = sub.events.find(
      (e) => e.operation === 'INSERT' && e.data?.email === 'cdc-pg-insert@e2e.test'
    );
    expect(insert).toBeDefined();
    expect(insert.table).toBe('customer');
    expect(insert.data.first_name).toBe('CDC_PG_Insert');

    // Clean up
    await pgClient.request(gql`mutation { deleteHanaCustomer(input: { customer_id: ${cid} }) { customer_id } }`);
  });

  test('receives UPDATE event from GraphQL mutation', async () => {
    // Create a row to update
    const create = await pgClient.request(gql`
      mutation {
        createHanaCustomer(input: {
          first_name: "CDC_PG_Update"
          last_name: "E2E"
          email: "cdc-pg-update@e2e.test"
        }) { customer_id }
      }
    `);
    const cid = create.createHanaCustomer.customer_id;

    sub = subscribeGraphQL(PG_WS, CUSTOMER_SUBSCRIPTION);
    await sub.ready;

    await pgClient.request(gql`
      mutation {
        updateHanaCustomer(input: {
          customer_id: ${cid}
          first_name: "CDC_PG_Updated"
        }) { customer_id first_name }
      }
    `);

    await waitFor(sub.events, (e) => e.some((ev) => ev.operation === 'UPDATE'));

    const update = sub.events.find((e) => e.operation === 'UPDATE');
    expect(update).toBeDefined();
    expect(update.table).toBe('customer');
    expect(update.data.new).toBeDefined();
    expect(update.data.new.first_name).toBe('CDC_PG_Updated');

    // Clean up
    await pgClient.request(gql`mutation { deleteHanaCustomer(input: { customer_id: ${cid} }) { customer_id } }`);
  });

  test('receives DELETE event from GraphQL mutation', async () => {
    // Create a row to delete
    const create = await pgClient.request(gql`
      mutation {
        createHanaCustomer(input: {
          first_name: "CDC_PG_Delete"
          last_name: "E2E"
          email: "cdc-pg-delete@e2e.test"
        }) { customer_id }
      }
    `);
    const cid = create.createHanaCustomer.customer_id;

    sub = subscribeGraphQL(PG_WS, CUSTOMER_SUBSCRIPTION);
    await sub.ready;

    await pgClient.request(gql`
      mutation { deleteHanaCustomer(input: { customer_id: ${cid} }) { customer_id } }
    `);

    await waitFor(sub.events, (e) =>
      e.some((ev) => ev.operation === 'DELETE' && ev.data?.customer_id === cid)
    );

    const del = sub.events.find(
      (e) => e.operation === 'DELETE' && e.data?.customer_id === cid
    );
    expect(del).toBeDefined();
    expect(del.table).toBe('customer');
  });

  test('receives INSERT event from direct psql', async () => {
    sub = subscribeGraphQL(PG_WS, CUSTOMER_SUBSCRIPTION);
    await sub.ready;

    psql(
      "INSERT INTO hana.customer (first_name, last_name, email) VALUES ('CDC_PSQL_Direct', 'E2E', 'cdc-psql-direct@e2e.test')"
    );

    await waitFor(sub.events, (e) =>
      e.some((ev) => ev.operation === 'INSERT' && ev.data?.email === 'cdc-psql-direct@e2e.test')
    );

    const insert = sub.events.find(
      (e) => e.operation === 'INSERT' && e.data?.email === 'cdc-psql-direct@e2e.test'
    );
    expect(insert).toBeDefined();
    expect(insert.data.first_name).toBe('CDC_PSQL_Direct');

    // Clean up
    psql("DELETE FROM hana.customer WHERE email = 'cdc-psql-direct@e2e.test'");
  });

  test('receives UPDATE event from direct psql', async () => {
    psql(
      "INSERT INTO hana.customer (first_name, last_name, email) VALUES ('CDC_PSQL_UpdTarget', 'E2E', 'cdc-psql-upd@e2e.test')"
    );

    sub = subscribeGraphQL(PG_WS, CUSTOMER_SUBSCRIPTION);
    await sub.ready;

    psql("UPDATE hana.customer SET first_name = 'CDC_PSQL_Updated' WHERE email = 'cdc-psql-upd@e2e.test'");

    await waitFor(sub.events, (e) => e.some((ev) => ev.operation === 'UPDATE'));

    const update = sub.events.find((e) => e.operation === 'UPDATE');
    expect(update).toBeDefined();
    expect(update.data.new).toBeDefined();
    expect(update.data.new.first_name).toBe('CDC_PSQL_Updated');

    // Clean up
    psql("DELETE FROM hana.customer WHERE email = 'cdc-psql-upd@e2e.test'");
  });
});

// ─── MySQL CDC Subscriptions ─────────────────────────────────────────────────

const MYSQL_API = process.env.MYSQL_API_URL || 'http://localhost:10001/graphql';
const MYSQL_WS = process.env.MYSQL_WS_URL || 'ws://localhost:10001/graphql';

const MYSQL_CUSTOMER_SUBSCRIPTION = `
  subscription {
    excalibaseCustomerChanges {
      operation
      table
      timestamp
      data {
        customer_id
        first_name
        last_name
        email
        new {
          customer_id
          first_name
          last_name
          email
        }
      }
      error
    }
  }
`;

// MySQL schema generator does not yet generate subscription types.
// These tests are ready for when that feature is added.
// To enable: remove the .skip and ensure docker-compose.mysql.yml has NATS + watcher.
describe.skip('MySQL CDC subscriptions', () => {
  let myClient;
  let sub;

  beforeAll(async () => {
    await waitForApi(MYSQL_API);
    myClient = createClient(MYSQL_API);
  }, 120000);

  afterEach(() => {
    if (sub) sub.close();
  });

  test('receives INSERT event from GraphQL mutation', async () => {
    sub = subscribeGraphQL(MYSQL_WS, MYSQL_CUSTOMER_SUBSCRIPTION);
    await sub.ready;

    const data = await myClient.request(gql`
      mutation {
        createExcalibaseCustomer(input: {
          first_name: "CDC_MY_Insert"
          last_name: "E2E"
          email: "cdc-my-insert@e2e.test"
        }) { customer_id first_name }
      }
    `);
    const cid = data.createExcalibaseCustomer.customer_id;

    await waitFor(sub.events, (e) =>
      e.some((ev) => ev.operation === 'INSERT' && ev.data?.email === 'cdc-my-insert@e2e.test')
    );

    const insert = sub.events.find(
      (e) => e.operation === 'INSERT' && e.data?.email === 'cdc-my-insert@e2e.test'
    );
    expect(insert).toBeDefined();
    expect(insert.table).toBe('customer');
    expect(insert.data.first_name).toBe('CDC_MY_Insert');

    // Clean up
    await myClient.request(gql`mutation { deleteExcalibaseCustomer(input: { customer_id: ${cid} }) { customer_id } }`);
  });

  test('receives UPDATE event from GraphQL mutation', async () => {
    const create = await myClient.request(gql`
      mutation {
        createExcalibaseCustomer(input: {
          first_name: "CDC_MY_Update"
          last_name: "E2E"
          email: "cdc-my-update@e2e.test"
        }) { customer_id }
      }
    `);
    const cid = create.createExcalibaseCustomer.customer_id;

    sub = subscribeGraphQL(MYSQL_WS, MYSQL_CUSTOMER_SUBSCRIPTION);
    await sub.ready;

    await myClient.request(gql`
      mutation {
        updateExcalibaseCustomer(input: {
          customer_id: ${cid}
          first_name: "CDC_MY_Updated"
        }) { customer_id first_name }
      }
    `);

    await waitFor(sub.events, (e) => e.some((ev) => ev.operation === 'UPDATE'));

    const update = sub.events.find((e) => e.operation === 'UPDATE');
    expect(update).toBeDefined();
    expect(update.table).toBe('customer');
    expect(update.data.new).toBeDefined();
    expect(update.data.new.first_name).toBe('CDC_MY_Updated');

    // Clean up
    await myClient.request(gql`mutation { deleteExcalibaseCustomer(input: { customer_id: ${cid} }) { customer_id } }`);
  });

  test('receives DELETE event from GraphQL mutation', async () => {
    const create = await myClient.request(gql`
      mutation {
        createExcalibaseCustomer(input: {
          first_name: "CDC_MY_Delete"
          last_name: "E2E"
          email: "cdc-my-delete@e2e.test"
        }) { customer_id }
      }
    `);
    const cid = create.createExcalibaseCustomer.customer_id;

    sub = subscribeGraphQL(MYSQL_WS, MYSQL_CUSTOMER_SUBSCRIPTION);
    await sub.ready;

    await myClient.request(gql`
      mutation { deleteExcalibaseCustomer(input: { customer_id: ${cid} }) { customer_id } }
    `);

    await waitFor(sub.events, (e) => e.some((ev) => ev.operation === 'DELETE'));

    const del = sub.events.find((e) => e.operation === 'DELETE');
    expect(del).toBeDefined();
    expect(del.table).toBe('customer');
  });

  test('receives INSERT event from direct mysql', async () => {
    sub = subscribeGraphQL(MYSQL_WS, MYSQL_CUSTOMER_SUBSCRIPTION);
    await sub.ready;

    mysqlExec(
      "INSERT INTO customer (first_name, last_name, email) VALUES ('CDC_MYSQL_Direct', 'E2E', 'cdc-mysql-direct@e2e.test')"
    );

    await waitFor(sub.events, (e) =>
      e.some((ev) => ev.operation === 'INSERT' && ev.data?.email === 'cdc-mysql-direct@e2e.test')
    );

    const insert = sub.events.find(
      (e) => e.operation === 'INSERT' && e.data?.email === 'cdc-mysql-direct@e2e.test'
    );
    expect(insert).toBeDefined();
    expect(insert.data.first_name).toBe('CDC_MYSQL_Direct');

    // Clean up
    mysqlExec("DELETE FROM customer WHERE email = 'cdc-mysql-direct@e2e.test'");
  });

  test('receives UPDATE event from direct mysql', async () => {
    mysqlExec(
      "INSERT INTO customer (first_name, last_name, email) VALUES ('CDC_MYSQL_UpdTarget', 'E2E', 'cdc-mysql-upd@e2e.test')"
    );

    sub = subscribeGraphQL(MYSQL_WS, MYSQL_CUSTOMER_SUBSCRIPTION);
    await sub.ready;

    mysqlExec("UPDATE customer SET first_name = 'CDC_MYSQL_Updated' WHERE email = 'cdc-mysql-upd@e2e.test'");

    await waitFor(sub.events, (e) => e.some((ev) => ev.operation === 'UPDATE'));

    const update = sub.events.find((e) => e.operation === 'UPDATE');
    expect(update).toBeDefined();
    expect(update.data.new).toBeDefined();
    expect(update.data.new.first_name).toBe('CDC_MYSQL_Updated');

    // Clean up
    mysqlExec("DELETE FROM customer WHERE email = 'cdc-mysql-upd@e2e.test'");
  });
});
