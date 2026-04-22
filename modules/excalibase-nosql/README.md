# excalibase-nosql

Document-store REST API on PostgreSQL JSONB. Collections are plain Postgres
tables in the `nosql` schema; each row is
`(id UUID, data JSONB, created_at, updated_at)`. Indexes are expression
indexes managed through a declarative sync API, created concurrently so
schema changes never block writes.

## Quickstart

```bash
# 1. Declare a collection + indexes
curl -X POST http://localhost:10000/api/v1/nosql \
  -H 'Content-Type: application/json' -d '{
    "collections": {
      "users": {
        "indexes": [
          { "fields": ["email"], "unique": true },
          { "fields": ["status"] }
        ]
      }
    }
  }'

# 2. Insert
curl -X POST http://localhost:10000/api/v1/nosql/users \
  -H 'Content-Type: application/json' \
  -d '{"doc": {"email": "vu@acme.com", "status": "active"}}'

# 3. Query
curl 'http://localhost:10000/api/v1/nosql/users?status=eq.active'
```

## Documentation

| Topic | Page |
|-------|------|
| Declaring collections, index types, `search`/`vector`/`schema` fields | [schema.md](../../docs/nosql/schema.md) |
| CRUD endpoints, filter operators, `?count`/`?stats`/`X-Debug` | [crud.md](../../docs/nosql/crud.md) |
| Full-text search + vector k-NN + embedding ingest | [search-vector.md](../../docs/nosql/search-vector.md) |
| Offset (default) + cursor (opt-in) pagination | [pagination.md](../../docs/nosql/pagination.md) |
| JSON Schema validation on insert | [validation.md](../../docs/nosql/validation.md) |
| Realtime WebSocket subscriptions for REST + NoSQL | [realtime.md](../../docs/nosql/realtime.md) |

## Design notes

- Collection tables live in the `nosql` Postgres schema, filtered out of the
  relational introspection that drives the GraphQL + REST SQL surfaces.
- Declarative schema sync diffs declared vs. live indexes; adds/drops are
  concurrent; **index type changes are rejected** rather than silently drifted.
- All writes go through the app layer — JSON Schema validation and
  `updated_at` stamping live there, not in DB triggers. Raw SQL writes bypass
  both.
- Stats advisor (`?stats`) returns row count + per-index scans + size; a
  future `pg_stat_statements`-backed query advisor is tracked for the
  `excalibase-provisioning` integration.
