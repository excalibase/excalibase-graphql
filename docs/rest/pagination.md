# REST — Pagination

Two complementary pagination models:

1. **Offset-based** — `?limit=N&offset=M`. Simple, allows random access, good
   for small-to-medium result sets.
2. **Range-based** — `Range: 0-9` header. PostgREST-compatible, no URL changes
   between pages.

Both return `Content-Range` headers so the client knows the total row count
(when `Prefer: count=exact` is set).

## Offset-based

```bash
GET /api/v1/issues?limit=10&offset=0
GET /api/v1/issues?limit=10&offset=10
GET /api/v1/issues?limit=10&offset=20
```

Response:

```json
{
  "data": [ ... 10 rows ... ],
  "pagination": { "limit": 10, "offset": 0, "total": null }
}
```

The `total` field is `null` unless the request includes the count header
(see below). Compute `hasNextPage` client-side: `offset + data.length < total`.

## Range header

```bash
GET /api/v1/issues -H "Range: 0-9"    # first 10 rows
GET /api/v1/issues -H "Range: 10-19"  # next 10
```

Response:

```
HTTP/1.1 206 Partial Content
Content-Range: 0-9/15
```

Body is the same envelope as offset queries. The `Content-Range` header is
always set on `Range`-based requests. For offset/limit-based requests you
must opt in with `Prefer: count=exact`.

## Total row count — `Prefer: count=exact`

```bash
curl "https://api.example.com/api/v1/issues?limit=10&offset=0" \
  -H "Accept-Profile: kanban" \
  -H "Prefer: count=exact"
```

Returns:

```
HTTP/1.1 200 OK
Content-Range: 0-9/342
Preference-Applied: count=exact
```

```json
{
  "data": [ ... 10 rows ... ],
  "pagination": { "limit": 10, "offset": 0, "total": 342 }
}
```

**When to use:**

- `Prefer: count=exact` runs a `COUNT(*)` on the filter — accurate but slow
  on large tables with complex WHERE clauses.
- Default (no header) skips the count entirely — fastest, client has no
  total and can only check `hasNextPage` by fetching one extra row.

## Ordering for stable pagination

Always pair `limit` / `offset` with an explicit `order` — Postgres row order
is non-deterministic without one, so subsequent pages may overlap or skip
rows:

```bash
GET /api/v1/issues?limit=10&offset=0&order=id.asc
```

For infinite-scroll / forward-only pagination, prefer **cursor pagination**
(simulate with a `gt`-based filter on a sortable column):

```bash
# Page 1
GET /api/v1/issues?limit=10&order=id.asc

# Page 2 — fetch rows after the last id from page 1
GET /api/v1/issues?limit=10&order=id.asc&id=gt.10

# Page 3
GET /api/v1/issues?limit=10&order=id.asc&id=gt.20
```

Cursor pagination is O(1) per page regardless of table size — no `OFFSET`
scan. Use it for large tables.

For strict Relay-style cursor pagination with `edges { node, cursor }`
shapes, use the GraphQL `<Table>Connection` field instead.

## Worked example

Fetch the first 20 high-priority open issues in a kanban project, with
total count for UI pagination:

```bash
curl "https://api.example.com/api/v1/issues\
?select=id,title,status,priority\
&project_id=eq.1\
&status=in.(todo,in_progress)\
&priority=eq.high\
&order=created_at.desc\
&limit=20&offset=0" \
  -H "Authorization: Bearer $JWT" \
  -H "Accept-Profile: kanban" \
  -H "Prefer: count=exact"
```

```json
{
  "data": [ ... 20 rows ... ],
  "pagination": { "limit": 20, "offset": 0, "total": 87 }
}
```

Client logic:

```ts
const pageSize = 20;
const totalPages = Math.ceil(response.pagination.total / pageSize);
const currentPage = Math.floor(response.pagination.offset / pageSize) + 1;
const hasNext = response.pagination.offset + response.data.length < response.pagination.total;
```

## GraphQL equivalent

For the GraphQL way of paginating — with `<Table>Connection(first, after)`
returning `{ edges { node, cursor }, pageInfo, totalCount }` — see the
[GraphQL overview](../graphql/index.md#pagination).

## Limits

- `limit` is clamped to `app.max-rows` in the server config (default 30).
  Passing `limit=100` when the cap is 30 silently caps at 30.
- `offset` is uncapped but beware: `OFFSET 1000000` reads and discards a
  million rows before returning the next page. Prefer cursor-style
  pagination for deep offsets.
