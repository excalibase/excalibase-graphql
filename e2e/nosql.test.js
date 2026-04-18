const NOSQL_URL = `http://${process.env.POSTGRES_API_URL || 'localhost:10000'}/api/v1/nosql`;

async function nosqlPost(path, body) {
  const res = await fetch(`${NOSQL_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

async function nosqlGet(path) {
  const res = await fetch(`${NOSQL_URL}${path}`);
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

async function nosqlPatch(path, body) {
  const res = await fetch(`${NOSQL_URL}${path}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

async function nosqlDelete(path) {
  const res = await fetch(`${NOSQL_URL}${path}`, { method: 'DELETE' });
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

async function waitForApi() {
  for (let i = 0; i < 30; i++) {
    try {
      const res = await fetch(NOSQL_URL);
      if (res.ok) return;
    } catch (_) {}
    await new Promise(r => setTimeout(r, 1000));
  }
  throw new Error('NoSQL API did not start in time');
}

beforeAll(async () => {
  await waitForApi();
});

// ─── Schema ────────────────────────────────────────────────────────────────────

describe('NoSQL — Schema sync', () => {
  test('POST /nosql syncs schema', async () => {
    const res = await nosqlPost('', {
      collections: {
        e2e_users: {
          indexes: [
            { fields: ['email'], type: 'string', unique: true },
            { fields: ['status'], type: 'string', unique: false },
            { fields: ['age'], type: 'number', unique: false },
          ],
        },
      },
    });
    expect(res.status).toBe(200);
    expect(res.data.data.created).toBe(1);
  });

  test('GET /nosql returns schema', async () => {
    const res = await nosqlGet('');
    expect(res.status).toBe(200);
    expect(res.data.data).toHaveProperty('e2e_users');
    expect(res.data.data.e2e_users.indexedFields).toContain('email');
    expect(res.data.data.e2e_users.indexedFields).toContain('status');
    expect(res.data.data.e2e_users.indexedFields).toContain('age');
  });

  test('POST /nosql is idempotent', async () => {
    const res = await nosqlPost('', {
      collections: {
        e2e_users: {
          indexes: [
            { fields: ['email'], type: 'string', unique: true },
            { fields: ['status'], type: 'string', unique: false },
            { fields: ['age'], type: 'number', unique: false },
          ],
        },
      },
    });
    expect(res.status).toBe(200);
    expect(res.data.data.updated).toBe(1);
    expect(res.data.data.created).toBe(0);
  });
});

// ─── CRUD ──────────────────────────────────────────────────────────────────────

describe('NoSQL — CRUD', () => {
  let insertedId;

  test('POST /nosql/e2e_users inserts document', async () => {
    const res = await nosqlPost('/e2e_users', {
      doc: { name: 'Vu', email: 'vu@test.com', status: 'active', age: 30 },
    });
    expect(res.status).toBe(201);
    expect(res.data.data.name).toBe('Vu');
    expect(res.data.data.id).toBeDefined();
    insertedId = res.data.data.id;
  });

  test('GET /nosql/e2e_users/{id} returns document', async () => {
    const res = await nosqlGet(`/e2e_users/${insertedId}`);
    expect(res.status).toBe(200);
    expect(res.data.data.name).toBe('Vu');
    expect(res.data.data.id).toBe(insertedId);
  });

  test('GET /nosql/e2e_users?status=eq.active finds by indexed field', async () => {
    const res = await nosqlGet('/e2e_users?status=eq.active');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(1);
    expect(res.data.data[0].status).toBe('active');
  });

  test('GET /nosql/e2e_users?email=eq.vu@test.com finds one', async () => {
    const res = await nosqlGet('/e2e_users?email=eq.vu@test.com&limit=1');
    expect(res.status).toBe(200);
    expect(res.data.data[0].name).toBe('Vu');
  });

  test('PATCH /nosql/e2e_users?email=eq.vu@test.com updates', async () => {
    const res = await nosqlPatch('/e2e_users?email=eq.vu@test.com', {
      '$set': { status: 'inactive' },
    });
    expect(res.status).toBe(200);
    expect(res.data.modified).toBe(1);
    expect(res.data.data[0].status).toBe('inactive');
  });

  test('GET after update reflects change', async () => {
    const res = await nosqlGet('/e2e_users?email=eq.vu@test.com');
    expect(res.status).toBe(200);
    expect(res.data.data[0].status).toBe('inactive');
  });

  test('DELETE /nosql/e2e_users/{id} removes by ID', async () => {
    const res = await nosqlDelete(`/e2e_users/${insertedId}`);
    expect(res.status).toBe(200);
  });

  test('GET after delete returns empty', async () => {
    const res = await nosqlGet('/e2e_users?email=eq.vu@test.com');
    expect(res.status).toBe(200);
    expect(res.data.data).toHaveLength(0);
  });

  test('POST batch insert with docs array', async () => {
    const res = await nosqlPost('/e2e_users', {
      docs: [
        { name: 'Alice', email: 'alice@test.com', status: 'active', age: 25 },
        { name: 'Bob', email: 'bob@test.com', status: 'active', age: 35 },
        { name: 'Charlie', email: 'charlie@test.com', status: 'pending', age: 28 },
      ],
    });
    expect(res.status).toBe(201);
    expect(res.data.data).toHaveLength(3);
  });

  test('GET /nosql/e2e_users?count returns count', async () => {
    const res = await nosqlGet('/e2e_users?count&status=eq.active');
    expect(res.status).toBe(200);
    expect(res.data.data.count).toBe(2);
  });

  test('GET with sort', async () => {
    const res = await nosqlGet('/e2e_users?status=eq.active&sort=age.desc');
    expect(res.status).toBe(200);
    expect(res.data.data[0].name).toBe('Bob');
    expect(res.data.data[1].name).toBe('Alice');
  });

  test('GET with limit', async () => {
    const res = await nosqlGet('/e2e_users?limit=1');
    expect(res.status).toBe(200);
    expect(res.data.data).toHaveLength(1);
  });
});

// ─── Index enforcement ─────────────────────────────────────────────────────────

describe('NoSQL — Unindexed queries (no blocking)', () => {
  test('GET on unindexed field succeeds (seq scan)', async () => {
    const res = await nosqlGet('/e2e_users?name=eq.Alice');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(1);
  });

  test('GET with X-Debug returns warnings for unindexed field', async () => {
    const res = await fetch(`${NOSQL_URL}/e2e_users?name=eq.Alice`, {
      headers: { 'X-Debug': 'true' },
    });
    expect(res.status).toBe(200);
    expect(res.headers.get('X-Warning')).toContain('not indexed');
    expect(res.headers.get('X-Query-Time')).toBeDefined();
  });

  test('GET with X-Debug and indexed field has no warnings', async () => {
    const res = await fetch(`${NOSQL_URL}/e2e_users?status=eq.active`, {
      headers: { 'X-Debug': 'true' },
    });
    expect(res.status).toBe(200);
    expect(res.headers.get('X-Warning')).toBeNull();
    expect(res.headers.get('X-Query-Time')).toBeDefined();
  });
});

describe('NoSQL — Stats', () => {
  test('GET ?stats returns index usage stats', async () => {
    const res = await nosqlGet('/e2e_users?stats');
    expect(res.status).toBe(200);
    expect(res.data.data.collection).toBe('e2e_users');
    expect(res.data.data.rowCount).toBeGreaterThanOrEqual(0);
    expect(res.data.data.indexes).toBeDefined();
    expect(Array.isArray(res.data.data.indexes)).toBe(true);
  });
});

// ─── Comparison operators ──────────────────────────────────────────────────────

describe('NoSQL — Comparison operators', () => {
  test('gt filter', async () => {
    const res = await nosqlGet('/e2e_users?age=gt.30');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(1);
    expect(res.data.data[0].name).toBe('Bob');
  });

  test('neq filter', async () => {
    const res = await nosqlGet('/e2e_users?status=neq.active');
    expect(res.status).toBe(200);
    expect(res.data.data[0].status).not.toBe('active');
  });
});

// ─── Bulk operations ───────────────────────────────────────────────────────────

describe('NoSQL — Bulk operations', () => {
  test('PATCH updates multiple', async () => {
    const res = await nosqlPatch('/e2e_users?status=eq.active', {
      '$set': { status: 'paused' },
    });
    expect(res.status).toBe(200);
    expect(res.data.modified).toBe(2);
  });

  test('DELETE removes multiple', async () => {
    const res = await nosqlDelete('/e2e_users?status=eq.paused');
    expect(res.status).toBe(200);
    expect(res.data.deleted).toBe(2);
  });
});

// ─── Error handling ────────────────────────────────────────────────────────────

describe('NoSQL — Error handling', () => {
  test('GET unknown collection returns 404', async () => {
    const res = await nosqlGet('/nonexistent?status=eq.active');
    expect(res.status).toBe(404);
  });

  test('GET unknown id returns 404', async () => {
    const res = await nosqlGet('/e2e_users/00000000-0000-0000-0000-000000000000');
    expect(res.status).toBe(404);
  });

  test('PATCH without filter returns 400', async () => {
    const res = await nosqlPatch('/e2e_users', { '$set': { status: 'x' } });
    expect(res.status).toBe(400);
  });

  test('DELETE without filter returns 400', async () => {
    const res = await nosqlDelete('/e2e_users');
    expect(res.status).toBe(400);
  });

  test('schema sync rejects > 10 indexes', async () => {
    const indexes = Array.from({ length: 11 }, (_, i) => ({
      fields: [`field${i}`], type: 'string', unique: false,
    }));
    const res = await nosqlPost('', {
      collections: { too_many: { indexes } },
    });
    expect(res.status).toBe(400);
    expect(res.data.error).toContain('max is 10');
  });
});

// ─── Full-text search ──────────────────────────────────────────────────────────

describe('NoSQL — Full-text search', () => {
  beforeAll(async () => {
    await nosqlPost('', {
      collections: {
        e2e_articles: {
          indexes: [],
          search: 'body',
        },
      },
    });
    await nosqlPost('/e2e_articles', {
      docs: [
        { title: 'Postgres FTS', body: 'PostgreSQL tsvector and tsquery power full-text search' },
        { title: 'MySQL FTS', body: 'MySQL has its own full-text search implementation' },
        { title: 'Search engines', body: 'Search engines index documents for fast retrieval' },
        { title: 'Pasta', body: 'Cooking recipes for pasta with tomatoes' },
      ],
    });
  });

  test('search ranks relevant docs first', async () => {
    const res = await nosqlGet('/e2e_articles?search=tsvector%20tsquery');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(1);
    expect(res.data.data[0].title).toBe('Postgres FTS');
  });

  test('search with no matches returns empty', async () => {
    const res = await nosqlGet('/e2e_articles?search=nonexistentwordxyz');
    expect(res.status).toBe(200);
    expect(res.data.data).toEqual([]);
  });

  test('search respects limit', async () => {
    const res = await nosqlGet('/e2e_articles?search=search%20OR%20recipes&limit=2');
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeLessThanOrEqual(2);
  });
});

// ─── Vector similarity search ──────────────────────────────────────────────────
// Notes:
//   - Populating the embedding column is outside the NoSQL REST surface today
//     (it is a non-JSONB column written by external pipelines). These tests
//     verify the search endpoint contract; ordering is exercised in NoSqlVectorIT.

describe('NoSQL — Vector search', () => {
  beforeAll(async () => {
    await nosqlPost('', {
      collections: {
        e2e_docs: {
          indexes: [],
          vector: { field: 'embedding', dimensions: 3 },
        },
      },
    });
    await nosqlPost('/e2e_docs', { doc: { title: 'doc-1' } });
    await nosqlPost('/e2e_docs', { doc: { title: 'doc-2' } });
  });

  test('POST /e2e_docs?vector=true returns 200 with array', async () => {
    const res = await fetch(`${NOSQL_URL}/e2e_docs?vector=true`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ embedding: [1, 0, 0], topK: 5 }),
    });
    const body = await res.json();
    expect(res.status).toBe(200);
    expect(Array.isArray(body.data)).toBe(true);
  });

  test('vector search requires embedding — 400 without it', async () => {
    const res = await fetch(`${NOSQL_URL}/e2e_docs?vector=true`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ topK: 3 }),
    });
    expect(res.status).toBe(400);
  });

  test('vector search honors topK limit', async () => {
    const res = await fetch(`${NOSQL_URL}/e2e_docs?vector=true`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ embedding: [1, 0, 0], topK: 1 }),
    });
    const body = await res.json();
    expect(res.status).toBe(200);
    expect(body.data.length).toBeLessThanOrEqual(1);
  });
});
