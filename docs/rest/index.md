# REST API

Excalibase exposes every table and view through a PostgREST-compatible REST
API alongside the [GraphQL surface](../graphql/index.md). Both protocols
read the same Postgres schema — **pick whichever fits your app**, or use
both from the same project. See [Choose Your Protocol](../choose-your-protocol.md)
for the decision guide.

## Endpoint shape

```
GET    /api/v1/{table}         # list
GET    /api/v1/{table}/{id}    # single row (if PK is a single column)
POST   /api/v1/{table}         # create
PATCH  /api/v1/{table}?col=eq.val   # update rows matching the filter
PUT    /api/v1/{table}?col=eq.val   # replace
DELETE /api/v1/{table}?col=eq.val   # delete rows matching the filter
```

The `/{table}` segment is the raw Postgres table name (snake_case, not the
PascalCase GraphQL type). Compare:

| GraphQL field | REST path |
|---|---|
| `kanbanIssues` | `/api/v1/issues` + `Accept-Profile: kanban` |
| `shopifyCustomers` | `/api/v1/customers` + `Accept-Profile: shopify` |
| `clinicPatients` | `/api/v1/patients` + `Accept-Profile: clinic` |

## Multi-schema routing

Every request must set the **schema profile header**:

| Header | Used for | Required |
|---|---|---|
| `Accept-Profile: <schema>` | `GET`, `HEAD` — reads | yes in multi-schema mode |
| `Content-Profile: <schema>` | `POST`, `PATCH`, `PUT`, `DELETE` — writes | yes in multi-schema mode |

Without the header you'll get `404 Not Found` because the server can't route
the table name to a specific schema. This matches
[PostgREST's schema-switching convention](https://postgrest.org/en/stable/api.html#switching-schemas).

## Authentication

Same JWT flow as GraphQL — attach `Authorization: Bearer <jwt>` to every
request. The JWT carries the project claim used for Row-Level Security and
per-tenant routing. See [Row-Level Security](../features/user-context-rls.md)
for the full auth model.

```bash
curl https://api.example.com/api/v1/issues?limit=10 \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Accept-Profile: kanban"
```

## Response envelope

Reads return a `data` array plus a `pagination` object:

```json
{
  "data": [
    { "id": 1, "title": "Setup JWT auth", "status": "done" },
    { "id": 2, "title": "User CRUD endpoints", "status": "done" }
  ],
  "pagination": { "limit": 10, "offset": 0, "total": 15 }
}
```

The `pagination.total` field is only populated when the request includes
`Prefer: count=exact`. See [Pagination](pagination.md) for the full story.

Writes return the affected row (or array) directly:

```json
[{ "id": 42, "title": "New issue", "status": "todo" }]
```

## Column projection

Use `?select=col1,col2,col3` to fetch only specific columns — same semantic
as GraphQL's selection set:

```bash
GET /api/v1/issues?select=id,title,status&limit=5
```

## Worked example

A complete read-path query — filter by enum + numeric range, order, paginate,
project specific columns, and include the total row count in the response
envelope:

```bash
curl "https://api.example.com/api/v1/issues\
?select=id,title,status,story_points\
&status=in.(todo,in_progress)\
&story_points=gte.5\
&order=story_points.desc\
&limit=10" \
  -H "Authorization: Bearer $JWT" \
  -H "Accept-Profile: kanban" \
  -H "Prefer: count=exact"
```

Same query as GraphQL:

```graphql
{
  kanbanIssues(
    where: {
      status: { in: [todo, in_progress] },
      story_points: { gte: 5 }
    }
    orderBy: { story_points: DESC }
    limit: 10
  ) {
    id title status story_points
  }
  kanbanIssuesAggregate(where: {
    status: { in: [todo, in_progress] },
    story_points: { gte: 5 }
  }) { count }
}
```

## Reference

- **[Filtering](filtering.md)** — every operator with runnable examples
- **[Mutations](mutations.md)** — POST / PATCH / PUT / DELETE with returning
- **[Pagination](pagination.md)** — `limit` / `offset` / `Range` / `count=exact`
- **[Full-Text & Vector Search](../features/search-and-vector.md)** — `plfts`, `phfts`, `wfts`, `fts`, `vector`
- **[Row-Level Security](../features/user-context-rls.md)** — per-user data isolation
- **[Choose Your Protocol](../choose-your-protocol.md)** — when to pick REST vs GraphQL
