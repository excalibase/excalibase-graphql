# REST — Mutations

Writes go through `POST` (insert), `PATCH` (partial update), `PUT` (replace),
and `DELETE`. Every write uses the `Content-Profile: <schema>` header for
multi-schema routing (reads use `Accept-Profile`).

For the GraphQL equivalent of every mutation below, see
[GraphQL overview](../graphql/index.md#mutations).

## Insert — POST

```bash
curl -X POST https://api.example.com/api/v1/issues \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -H "Content-Profile: kanban" \
  -d '{"title": "New issue", "status": "todo", "priority": "high"}'
```

Returns the created row:

```json
[{ "id": 42, "title": "New issue", "status": "todo", "priority": "high", "created_at": "2026-04-16T01:00:00Z" }]
```

**Bulk insert** — send an array:

```bash
curl -X POST https://api.example.com/api/v1/issues \
  -H "Content-Profile: kanban" \
  -H "Content-Type: application/json" \
  -d '[
    { "title": "First",  "status": "todo" },
    { "title": "Second", "status": "todo" }
  ]'
```

**Upsert on conflict** — use the `Prefer: resolution=merge-duplicates` header:

```bash
curl -X POST https://api.example.com/api/v1/issue_labels \
  -H "Content-Profile: kanban" \
  -H "Content-Type: application/json" \
  -H "Prefer: resolution=merge-duplicates" \
  -d '{"issue_id": 1, "label_id": 5}'
```

Without the header, a conflict returns `409 Conflict`.

**Dry run** — `Prefer: tx=rollback` executes the statement and returns the
result but rolls back the transaction. Useful for testing write paths:

```bash
curl -X POST https://api.example.com/api/v1/issues \
  -H "Prefer: tx=rollback" \
  -d '{"title": "will not persist"}'
```

## Update — PATCH

Update rows matching a filter. Same filter syntax as reads.

```bash
curl -X PATCH "https://api.example.com/api/v1/issues?id=eq.42" \
  -H "Content-Profile: kanban" \
  -H "Content-Type: application/json" \
  -d '{"status": "in_progress"}'
```

Bulk update via a broader filter:

```bash
curl -X PATCH "https://api.example.com/api/v1/issues?project_id=eq.1&status=eq.todo" \
  -H "Content-Profile: kanban" \
  -d '{"priority": "medium"}'
```

## Replace — PUT

Replace the full row (all non-PK columns are required in the body).

```bash
curl -X PUT "https://api.example.com/api/v1/issues?id=eq.42" \
  -H "Content-Profile: kanban" \
  -H "Content-Type: application/json" \
  -d '{"title": "Updated", "status": "done", "priority": "low", "project_id": 1, "story_points": 3}'
```

## Delete — DELETE

```bash
curl -X DELETE "https://api.example.com/api/v1/issues?id=eq.42" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Profile: kanban"
```

Bulk delete with a broader filter:

```bash
curl -X DELETE "https://api.example.com/api/v1/issues?status=eq.done&created_at=lt.2024-01-01" \
  -H "Content-Profile: kanban"
```

## Returning a representation

By default write responses include the affected row(s). To get full
Postgres-style representation control, use the `Prefer: return=*` headers:

| Header | Effect |
|---|---|
| `Prefer: return=representation` | Return the full row (default) |
| `Prefer: return=minimal` | Return `204 No Content` with no body — faster for bulk writes |
| `Prefer: return=headers-only` | Return only the `Location` header pointing at the new row |

```bash
curl -X POST https://api.example.com/api/v1/issues \
  -H "Prefer: return=minimal" \
  -H "Content-Profile: kanban" \
  -d '[{"title":"a"},{"title":"b"},{"title":"c"}]'
# HTTP/1.1 204 No Content
```

## Transaction control

| Header | Effect |
|---|---|
| `Prefer: tx=commit` | Default — commit the statement |
| `Prefer: tx=rollback` | Execute and return result but roll back the transaction (dry-run writes) |

Nested transactions / multi-statement transactions are NOT supported via a
single REST call. Use GraphQL mutations (which compose in one document) or
a stored procedure if you need atomicity across multiple writes.

## Stored procedures

Not exposed through REST today — call them via GraphQL (`call<Proc>` mutation
fields). See [Stored Procedures](../features/stored-procedures.md).

## Error responses

| Status | Meaning |
|---|---|
| `400 Bad Request` | Malformed filter, bad URL encoding, invalid JSON body |
| `401 Unauthorized` | Missing/invalid JWT |
| `403 Forbidden` | RLS policy rejected the row |
| `404 Not Found` | Missing `Accept-Profile` / `Content-Profile` header in multi-schema mode, or unknown table |
| `409 Conflict` | PK/unique constraint violation without `Prefer: resolution=merge-duplicates` |
| `500 Internal Server Error` | SQL error, usually type coercion or missing column |

Errors return a JSON body:

```json
{
  "error": "Query execution failed",
  "details": "ERROR: duplicate key value violates unique constraint \"issues_pkey\""
}
```
