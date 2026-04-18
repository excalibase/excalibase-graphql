# excalibase-nosql

Document-store REST API layered over PostgreSQL JSONB. Collections are plain
Postgres tables in the `nosql` schema; each row is `(id, data JSONB, created_at,
updated_at)`. Indexes are expression indexes on `data->>'field'`, created
concurrently so schema sync never blocks the collection.

## Schema

```json
POST /api/v1/nosql
{
  "collections": {
    "users": {
      "indexes": [
        { "fields": ["email"],  "type": "string", "unique": true },
        { "fields": ["status"], "type": "string" },
        { "fields": ["age"],    "type": "number" }
      ]
    },
    "articles": {
      "indexes": [],
      "search": "body"
    },
    "docs": {
      "indexes": [],
      "vector": { "field": "embedding", "dimensions": 3 }
    }
  }
}
```

- `indexes`: up to 10 per collection. `type` is `string` (default), `number`, or `boolean`.
  `unique: true` becomes a partial unique index (`WHERE field IS NOT NULL`).
- `search`: adds a generated `tsvector` column (English stemmer) backed by a GIN
  index. Only one search field per collection.
- `vector`: adds a real `vector(N)` column backed by an HNSW index using
  `vector_cosine_ops`. Requires the `vector` extension.

## CRUD

| Verb | Path | Purpose |
|------|------|---------|
| GET  | `/api/v1/nosql/{coll}` | List/filter (query params are filters + `limit`/`offset`/`sort`) |
| GET  | `/api/v1/nosql/{coll}/{id}` | Fetch by id |
| POST | `/api/v1/nosql/{coll}` | Insert one (`{doc: {...}}`) or many (`{docs: [...]}`) |
| PATCH | `/api/v1/nosql/{coll}?filter...` | Partial update (body is a `$set` patch) |
| DELETE | `/api/v1/nosql/{coll}?filter...` | Delete by filter |
| DELETE | `/api/v1/nosql/{coll}/{id}` | Delete by id |

### Filter operators

Query-string form, PostgREST-style: `?field=op.value`.

- `eq` (default — bare `?status=active` is `eq`)
- `neq`, `gt`, `gte`, `lt`, `lte`
- `in` (comma-separated values: `?status=in.active,pending`)

### Sort

`?sort=field.desc,other.asc` — omit direction for ascending.

## Search (FTS)

```
GET /api/v1/nosql/articles?search=postgres%20tsvector&limit=10
```

Ranked by `ts_rank(search_text, websearch_to_tsquery(query))`. The query is
interpreted as a PostgreSQL `websearch_to_tsquery` string, so `OR`, `-term`,
and `"exact phrase"` are all supported.

## Vector search (k-NN)

```
POST /api/v1/nosql/docs?vector=true
{ "embedding": [0.1, 0.2, 0.3], "topK": 5 }
```

Ordered by cosine distance (`embedding <=> :embedding::vector`). `topK` is
clamped at 1000. Writing to the `embedding` column is out of scope for the
NoSQL REST API — feed it through a dedicated embedding pipeline that writes
directly to Postgres.

## Query-plan hints

- Filters on indexed fields use the expression index; unindexed fields fall back
  to a sequential scan (allowed, but reported via the `X-Warning` header when
  the request sends `X-Debug: true`).
- `?count` returns a filtered count without fetching rows.
- `?stats` returns the collection's row count estimate (from `pg_class.reltuples`)
  plus per-index scan counts and size, with suggestions when an index is unused.

## Isolation

All collection tables live in the `nosql` schema and are filtered out of the
relational introspection that powers the GraphQL/REST SQL surfaces. The two
surfaces never see each other's tables.

## Change data capture

When `excalibase-watcher` is wired in, its NATS schema-reload callback triggers
`CollectionSchemaManager.reload()`, which re-reads `pg_tables` and `pg_indexes`
and swaps the in-memory `CollectionInfo` atomically.
