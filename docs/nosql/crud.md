# NoSQL — CRUD

All endpoints are under `/api/v1/nosql/{collection}`. Request/response bodies
are JSON. Response shape is always `{"data": ...}` (with `cursor` added under
[cursor pagination](pagination.md)).

## Endpoints

| Verb   | Path                     | Purpose |
|--------|--------------------------|---------|
| GET    | `/{coll}`                | List + filter (see below) |
| GET    | `/{coll}/{id}`           | Fetch one by id |
| POST   | `/{coll}`                | Insert one (`{doc: {...}}`) or many (`{docs: [...]}`) |
| PATCH  | `/{coll}?filter...`      | Partial update (body is a `$set` patch) |
| DELETE | `/{coll}?filter...`      | Delete by filter |
| DELETE | `/{coll}/{id}`           | Delete by id |
| PUT    | `/{coll}/{id}/embedding` | Write a pre-computed vector (see [search-vector.md](search-vector.md)) |

## Filter operators

Query-string form, PostgREST-style: `?field=op.value`.

| Operator | Example                                | Notes |
|----------|----------------------------------------|-------|
| `eq`     | `?status=active` or `?status=eq.active`| Default when no op prefix |
| `neq`    | `?status=neq.archived`                 | |
| `gt`     | `?age=gt.18`                           | Numeric or lexicographic |
| `gte`    | `?age=gte.18`                          | |
| `lt`     | `?age=lt.65`                           | |
| `lte`    | `?age=lte.65`                          | |
| `in`     | `?status=in.active,pending`            | Comma-separated list |

Unindexed fields are allowed (sequential scan). If you want visibility on
which filters fell back to a scan, send `X-Debug: true` and the response
includes per-field warnings in the `X-Warning` header.

## Sort

```
?sort=field.desc,other.asc
```

Omit the suffix for ascending. Ignored under `?paginate=cursor` (cursor mode
is always ordered by `created_at DESC, id DESC`).

## Pagination

Default: `?limit=N&offset=M`. See [pagination.md](pagination.md) for the
opt-in cursor mode that handles large collections correctly.

## Special query params

| Param      | Behavior |
|------------|----------|
| `?count`   | Returns `{count: N}` for the filter; no rows fetched |
| `?stats`   | Returns row count estimate, per-index scan counts + sizes, suggestions |
| `?search=` | FTS query — see [search-vector.md](search-vector.md) |
| `?vector=true` (POST) | Vector search request — see [search-vector.md](search-vector.md) |
| `X-Debug: true` header | Adds `X-Query-Time` and per-filter `X-Warning` headers |

## Response shapes

- **List**: `{"data": [doc, doc, ...]}` — or `{"data": [...], "cursor": "..."}` under cursor mode
- **Single**: `{"data": doc}`
- **Bulk write** (PATCH/DELETE): `{"data": [doc...], "modified": N}` or `{"deleted": N}`
- **Insert**: `{"data": doc}` (single) or `{"data": [doc, ...]}` (bulk), HTTP 201
- **Error**: `{"error": "message"}` or `{"error": "validation", "issues": [...]}` (see [validation.md](validation.md))
