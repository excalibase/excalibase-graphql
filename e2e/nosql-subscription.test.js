/**
 * NoSQL Collection CDC Subscription E2E
 *
 * Validates that a newly-auto-created NoSQL collection (created by POST
 * /api/v1/nosql syncSchema) is added to cdc_watcher_pub (via Java's
 * CollectionSchemaManager.addToRealtimePublication), and that subsequent
 * document writes flow through the watcher → NATS → graphql WS chain to
 * a subscriber.
 *
 * Stack: docker-compose up — postgres + watcher-go + NATS + graphql.
 * graphql is configured with APP_REALTIME_PUBLICATION_NAME matching the
 * watcher's publication_name (cdc_publication_go in this e2e setup).
 *
 * Requires Postgres logical decoding to be enabled on the test DB
 * (the docker-compose stack already runs with wal_level=logical).
 */

const { waitForApi, subscribeGraphQL, waitFor } = require('./client');

const PG_API = process.env.POSTGRES_API_URL || 'http://localhost:10000/graphql';
const PG_WS = process.env.POSTGRES_WS_URL || 'ws://localhost:10000/graphql';
const NOSQL_BASE = (process.env.POSTGRES_API_URL || 'http://localhost:10000') + '/api/v1/nosql';

const COLLECTION = 'e2e_realtime_collection';

async function nosqlPost(path, body) {
  const res = await fetch(`${NOSQL_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

async function nosqlDelete(path) {
  const res = await fetch(`${NOSQL_BASE}${path}`, { method: 'DELETE' });
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

// Subscription field naming follows the same camelCase rule as SQL tables:
// schema_table → schemaTableChanges. For our collection in the "nosql"
// schema named "e2e_realtime_collection", the field is
// "nosqlE2eRealtimeCollectionChanges". Event payload for NoSQL collections
// flattens the new row directly into data (no nested `new` like the SQL
// customer subscription) — that matches schema-on-write semantics.
const COLLECTION_SUBSCRIPTION = `
  subscription {
    nosqlE2eRealtimeCollectionChanges {
      operation
      table
      timestamp
      data
      error
    }
  }
`;

describe('NoSQL Realtime auto-enable + CDC subscription', () => {
  let sub;

  beforeAll(async () => {
    await waitForApi(PG_API);

    // Sync the collection — this triggers CollectionSchemaManager.createTable
    // which then auto-runs ALTER PUBLICATION cdc_publication_go ADD TABLE
    // nosql.<collection> (because APP_REALTIME_PUBLICATION_NAME matches
    // the watcher's publication name in docker-compose).
    const sync = await nosqlPost('', {
      collections: { [COLLECTION]: { indexes: [] } },
    });
    if (sync.status !== 200 && sync.status !== 201) {
      throw new Error(`syncSchema failed: ${sync.status} ${JSON.stringify(sync.data)}`);
    }
  }, 60000);

  afterEach(() => {
    if (sub) sub.close();
  });

  test('receives INSERT event when document is written to auto-created collection', async () => {
    sub = subscribeGraphQL(PG_WS, COLLECTION_SUBSCRIPTION);
    await sub.ready;

    // Insert a document via the NoSQL endpoint — should generate an
    // INSERT WAL record on the (now-replicated) nosql.e2e_realtime_collection
    // table, which the watcher relays to NATS.
    const insertRes = await nosqlPost(`/${COLLECTION}`, {
      doc: { kind: 'realtime-test', payload: 'hello-cdc' },
    });
    expect([200, 201]).toContain(insertRes.status);
    // NoSQL insert response wraps the row under .data.{...}
    const insertedId = insertRes.data?.data?.id;
    expect(insertedId).toBeDefined();

    await waitFor(
      sub.events,
      (events) =>
        events.some(
          (ev) =>
            ev.operation === 'INSERT' && ev.data?.id === insertedId,
        ),
      20000,
    );

    const insert = sub.events.find(
      (ev) => ev.operation === 'INSERT' && ev.data?.id === insertedId,
    );
    expect(insert).toBeDefined();
    expect(insert.table).toBe(COLLECTION);
    // The full document body is in event.data.data (NoSQL stores the user
    // doc under a JSONB column called "data" — same name as the wrapper).
    expect(insert.data?.data?.kind).toBe('realtime-test');
    expect(insert.data?.data?.payload).toBe('hello-cdc');

    // Cleanup the document so re-runs are idempotent.
    await nosqlDelete(`/${COLLECTION}/${insertedId}`);
  }, 30000);
});
