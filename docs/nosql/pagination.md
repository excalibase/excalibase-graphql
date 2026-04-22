# NoSQL — Pagination

Two modes. Default is offset-based (no change from older clients). Cursor
mode is opt-in via `?paginate=cursor` and recommended for any collection that
can grow past a few thousand rows.

## Offset pagination (default)

```
GET /api/v1/nosql/users?limit=20&offset=40&sort=age.desc
```

- `limit` clamped at 1000 (default 30)
- `offset` starts at 0
- Sort defaults to **unordered** (Postgres decides) — pass `sort` for
  determinism

Simple, but progressively slower at large offsets and unsafe if rows are
being inserted while paging (the same row can appear on two pages or be
skipped entirely).

## Cursor pagination (opt-in)

```
GET /api/v1/nosql/users?paginate=cursor&limit=20
```

Response:

```json
{
  "data": [ { "id": "...", "createdAt": "...", ... }, ... ],
  "cursor": "eyJ0cyI6..."
}
```

Pass the returned `cursor` back to fetch the next page:

```
GET /api/v1/nosql/users?paginate=cursor&limit=20&cursor=eyJ0cyI6...
```

When the page returns fewer than `limit` rows, `cursor` is `null` — you've
reached the end.

### Semantics

- Ordering is fixed: `ORDER BY created_at DESC, id DESC`. `sort` and `offset`
  are **ignored** in cursor mode.
- Cursor encodes `(created_at, id)` of the last row in the page, base64url
  without padding. Treat it as opaque — the encoding can change.
- Keyset query shape: `WHERE (created_at, id) < (:cursorTs, :cursorId)`. This
  means concurrent inserts don't disturb ongoing pagination — the cursor
  always moves through the original snapshot.

### Errors

- `?cursor=!!!` (malformed base64 or bad payload) → 400 with a clear message.

### Notes

- The opt-in flag was chosen so this is **not a breaking change** for
  existing clients. A later major release will likely flip the default to
  cursor mode.
- `?count` and filter params compose normally with cursor mode.
