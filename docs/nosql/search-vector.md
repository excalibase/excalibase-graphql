# NoSQL — Full-text search & vector search

Both surfaces are driven off columns added during [schema sync](schema.md) —
`search_text tsvector` for FTS, `embedding vector(N)` for k-NN.

## Full-text search

Requires `search: "<field>"` in the collection's schema declaration.

```
GET /api/v1/nosql/articles?search=postgres%20tsvector&limit=10
```

Ranked by
`ts_rank(search_text, websearch_to_tsquery(:query))`. The query string is
interpreted as PostgreSQL's `websearch_to_tsquery`, so these all work:

| Query | Matches |
|-------|---------|
| `postgres tsvector`   | Docs containing both tokens |
| `postgres OR mysql`   | Docs containing either |
| `payment -stripe`     | Docs containing `payment` but not `stripe` |
| `"webhook handler"`   | Exact phrase (adjacent tokens) |

Results come back as the standard `{"data": [doc, ...]}`. `limit` is clamped
at 1000.

## Vector search

Requires `vector: {field, dimensions}` in the collection's schema declaration.

### Query

```
POST /api/v1/nosql/docs?vector=true
Content-Type: application/json

{ "embedding": [0.1, 0.2, 0.3], "topK": 5 }
```

Ordered by cosine distance (`embedding <=> :q::vector`). `topK` is clamped at
1000. A dimension mismatch between the query and the declared column dimension
is rejected by Postgres at query time.

### Ingesting embeddings

Embeddings are real `vector(N)` columns — **not** part of the JSONB `data`
blob. Write them with the dedicated endpoint:

```
PUT /api/v1/nosql/docs/{id}/embedding
Content-Type: application/json

{ "embedding": [0.1, 0.2, 0.3] }
```

Returns the updated document. Rejects:

- Empty / non-numeric `embedding` → 400
- Collection without `vector` configured → 400
- Unknown id → 404

### Where embeddings come from

Embeddings are model-generated. Typical flow:

1. Your app takes text → calls an embedding model (OpenAI, Cohere, local
   sentence-transformers, etc.) → gets a float array.
2. Insert the document (text + metadata) via `POST /api/v1/nosql/{coll}`.
3. Send the generated embedding via `PUT /{coll}/{id}/embedding`.

Automatic embedding generation is out of scope for this module — pick your
model + rate limits + secrets outside the document store.
