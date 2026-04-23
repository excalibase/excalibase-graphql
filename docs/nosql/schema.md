# NoSQL — Schema declaration

Collections are declared through a single `POST /api/v1/nosql` call. The
handler creates each missing table + its indexes; existing tables get a diff
and only changed indexes are touched. All index operations run
`CONCURRENTLY`, so schema sync never blocks writes on the collection.

## Request shape

```json
POST /api/v1/nosql
{
  "collections": {
    "users": {
      "indexes": [
        { "fields": ["email"],  "type": "string",  "unique": true },
        { "fields": ["status"], "type": "string" },
        { "fields": ["age"],    "type": "number" }
      ],
      "schema": {
        "type": "object",
        "required": ["email"],
        "properties": { "email": { "type": "string" } }
      }
    },
    "articles": {
      "indexes": [
        { "fields": ["tags"], "type": "array" }
      ],
      "search": "body"
    },
    "docs": {
      "indexes": [],
      "vector": { "field": "embedding", "dimensions": 3 }
    }
  }
}
```

## Index types

| `type`    | Postgres index | Expression                     | Use case |
|-----------|----------------|--------------------------------|----------|
| `string` *(default)* | BTree | `(data->>'field')`             | Equality, range, sort on text |
| `number`  | BTree | `((data->>'field')::numeric)`  | Numeric range / sort |
| `boolean` | BTree | `((data->>'field')::boolean)`  | `true` / `false` filters |
| `array`   | GIN   | `(data->'field')` + `jsonb_ops`| Containment + overlap on arrays / nested objects |

All BTree indexes are created as **partial** with `WHERE field IS NOT NULL` —
rows without the field don't consume index space.

`unique: true` emits a partial unique BTree. Array indexes cannot be unique
(GIN does not support uniqueness).

Each index is named `idx_{collection}_{fields}` (or `uidx_…` when unique).
Indexes missing from a re-sync are dropped; declared indexes already present
are kept.

## Changing an existing index

**Index type changes are rejected.** To change `type` on a field that already
has an index:

1. Remove the index from the declaration and `POST` the schema — the index is
   dropped concurrently.
2. Re-add the index with the new `type` and `POST` again.

This prevents silent drift between the declared and the actual index expression.

## Full-text search (`search`)

Setting `search: "body"` adds a generated column:

```sql
ALTER TABLE nosql.articles
  ADD COLUMN search_text tsvector GENERATED ALWAYS AS (
    to_tsvector('english', coalesce(data->>'body', ''))
  ) STORED;
CREATE INDEX CONCURRENTLY idx_articles_search ON nosql.articles USING gin(search_text);
```

See [search-vector.md](search-vector.md) for query endpoints.

## Vector search (`vector`)

Setting `vector: {field: "embedding", dimensions: 3}` adds a real column:

```sql
ALTER TABLE nosql.docs ADD COLUMN embedding vector(3);
CREATE INDEX CONCURRENTLY idx_docs_vector ON nosql.docs
  USING hnsw(embedding vector_cosine_ops);
```

Requires the `vector` extension on the Postgres server. See
[search-vector.md](search-vector.md) for the query + ingest path.

## Validation (`schema`)

An optional JSON Schema (Draft 2020-12) enforced on every insert. See
[validation.md](validation.md).

## Inspecting the current schema

```
GET /api/v1/nosql
```

Returns every registered collection with its indexes and indexed fields.

## Isolation

All collection tables live in the `nosql` Postgres schema and are filtered
out of the relational introspection that powers the GraphQL/REST SQL
surfaces. Document and relational worlds never see each other's tables.
