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

async function nosqlDelete(path) {
  const res = await fetch(`${NOSQL_URL}${path}`, { method: 'DELETE' });
  return { status: res.status, data: await res.json().catch(() => ({})) };
}

async function waitForApi() {
  for (let i = 0; i < 30; i++) {
    try {
      const res = await fetch(`${NOSQL_URL}/_schema`);
      if (res.ok) return;
    } catch (_) {}
    await new Promise(r => setTimeout(r, 1000));
  }
  throw new Error('NoSQL API did not start in time');
}

beforeAll(async () => {
  await waitForApi();
});

describe('NoSQL — Schema sync', () => {
  test('POST /_schema creates collection with indexes', async () => {
    const res = await nosqlPost('/_schema', {
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

  test('GET /_schema returns created collection', async () => {
    const res = await nosqlGet('/_schema');
    expect(res.status).toBe(200);
    expect(res.data.data).toHaveProperty('e2e_users');
    expect(res.data.data.e2e_users.indexedFields).toContain('email');
    expect(res.data.data.e2e_users.indexedFields).toContain('status');
    expect(res.data.data.e2e_users.indexedFields).toContain('age');
  });

  test('POST /_schema is idempotent', async () => {
    const res = await nosqlPost('/_schema', {
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

describe('NoSQL — CRUD', () => {
  let insertedId;

  test('insertOne creates document', async () => {
    const res = await nosqlPost('/e2e_users/insertOne', {
      doc: { name: 'Vu', email: 'vu@test.com', status: 'active', age: 30 },
    });
    expect(res.status).toBe(201);
    expect(res.data.data.name).toBe('Vu');
    expect(res.data.data.email).toBe('vu@test.com');
    expect(res.data.data.id).toBeDefined();
    expect(res.data.data.createdAt).toBeDefined();
    insertedId = res.data.data.id;
  });

  test('getById returns document', async () => {
    const res = await nosqlGet(`/e2e_users/${insertedId}`);
    expect(res.status).toBe(200);
    expect(res.data.data.name).toBe('Vu');
    expect(res.data.data.id).toBe(insertedId);
  });

  test('find by indexed field returns results', async () => {
    const res = await nosqlPost('/e2e_users/find', {
      filter: { status: 'active' },
    });
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(1);
    expect(res.data.data[0].status).toBe('active');
  });

  test('findOne returns single document', async () => {
    const res = await nosqlPost('/e2e_users/findOne', {
      filter: { email: 'vu@test.com' },
    });
    expect(res.status).toBe(200);
    expect(res.data.data.name).toBe('Vu');
  });

  test('updateOne with $set', async () => {
    const res = await nosqlPost('/e2e_users/updateOne', {
      filter: { email: 'vu@test.com' },
      update: { '$set': { status: 'inactive' } },
    });
    expect(res.status).toBe(200);
    expect(res.data.data.status).toBe('inactive');
    expect(res.data.data.name).toBe('Vu');
  });

  test('find after update reflects change', async () => {
    const res = await nosqlPost('/e2e_users/find', {
      filter: { email: 'vu@test.com' },
    });
    expect(res.status).toBe(200);
    expect(res.data.data[0].status).toBe('inactive');
  });

  test('deleteOne removes document', async () => {
    const res = await nosqlPost('/e2e_users/deleteOne', {
      filter: { email: 'vu@test.com' },
    });
    expect(res.status).toBe(200);
    expect(res.data.data.name).toBe('Vu');
  });

  test('findOne after delete returns 404', async () => {
    const res = await nosqlPost('/e2e_users/findOne', {
      filter: { email: 'vu@test.com' },
    });
    expect(res.status).toBe(404);
  });

  test('insertMany inserts multiple documents', async () => {
    const res = await nosqlPost('/e2e_users/insertMany', {
      docs: [
        { name: 'Alice', email: 'alice@test.com', status: 'active', age: 25 },
        { name: 'Bob', email: 'bob@test.com', status: 'active', age: 35 },
        { name: 'Charlie', email: 'charlie@test.com', status: 'pending', age: 28 },
      ],
    });
    expect(res.status).toBe(201);
    expect(res.data.data.length).toBe(3);
  });

  test('count returns correct number', async () => {
    const res = await nosqlPost('/e2e_users/count', {
      filter: { status: 'active' },
    });
    expect(res.status).toBe(200);
    expect(res.data.data.count).toBe(2);
  });

  test('find with sort', async () => {
    const res = await nosqlPost('/e2e_users/find', {
      filter: { status: 'active' },
      sort: { age: -1 },
    });
    expect(res.status).toBe(200);
    expect(res.data.data[0].name).toBe('Bob');
    expect(res.data.data[1].name).toBe('Alice');
  });

  test('find with limit', async () => {
    const res = await nosqlPost('/e2e_users/find', {
      filter: {},
      limit: 1,
      allowScan: true,
    });
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBe(1);
  });
});

describe('NoSQL — Index enforcement', () => {
  test('find on unindexed field returns 400', async () => {
    const res = await nosqlPost('/e2e_users/find', {
      filter: { name: 'Vu' },
    });
    expect(res.status).toBe(400);
    expect(res.data.error).toContain('not indexed');
  });

  test('find on unindexed field with allowScan succeeds', async () => {
    const res = await nosqlPost('/e2e_users/find', {
      filter: { name: 'Alice' },
      allowScan: true,
    });
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(1);
  });
});

describe('NoSQL — Comparison operators', () => {
  test('$gt filter', async () => {
    const res = await nosqlPost('/e2e_users/find', {
      filter: { age: { '$gt': 30 } },
    });
    expect(res.status).toBe(200);
    expect(res.data.data.length).toBeGreaterThanOrEqual(1);
    expect(res.data.data[0].name).toBe('Bob');
  });

  test('$ne filter', async () => {
    const res = await nosqlPost('/e2e_users/find', {
      filter: { status: { '$ne': 'active' } },
    });
    expect(res.status).toBe(200);
    expect(res.data.data[0].status).not.toBe('active');
  });
});

describe('NoSQL — Bulk operations', () => {
  test('updateMany updates multiple documents', async () => {
    const res = await nosqlPost('/e2e_users/updateMany', {
      filter: { status: 'active' },
      update: { '$set': { status: 'paused' } },
    });
    expect(res.status).toBe(200);
    expect(res.data.modified).toBe(2);
  });

  test('deleteMany removes multiple documents', async () => {
    const res = await nosqlPost('/e2e_users/deleteMany', {
      filter: { status: 'paused' },
    });
    expect(res.status).toBe(200);
    expect(res.data.deleted).toBe(2);
  });
});

describe('NoSQL — Error handling', () => {
  test('unknown collection returns 404', async () => {
    const res = await nosqlPost('/nonexistent/find', { filter: {} });
    expect(res.status).toBe(404);
  });

  test('getById with unknown id returns 404', async () => {
    const res = await nosqlGet('/e2e_users/00000000-0000-0000-0000-000000000000');
    expect(res.status).toBe(404);
  });

  test('updateOne without filter returns 400', async () => {
    const res = await nosqlPost('/e2e_users/updateOne', {
      update: { '$set': { status: 'x' } },
    });
    expect(res.status).toBe(400);
  });

  test('deleteOne without filter returns 400', async () => {
    const res = await nosqlPost('/e2e_users/deleteOne', {});
    expect(res.status).toBe(400);
  });

  test('schema sync rejects > 10 indexes', async () => {
    const indexes = Array.from({ length: 11 }, (_, i) => ({
      fields: [`field${i}`], type: 'string', unique: false,
    }));
    const res = await nosqlPost('/_schema', {
      collections: { too_many: { indexes } },
    });
    expect(res.status).toBe(400);
    expect(res.data.error).toContain('max is 10');
  });
});
