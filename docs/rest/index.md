# REST API

Excalibase exposes every table and view through a PostgREST-compatible REST
API alongside the [GraphQL surface](../graphql/index.md). Both protocols
read the same Postgres schema — **pick whichever fits your app**, or use
both from the same project. See [Choose Your Protocol](../choose-your-protocol.md)
for the decision guide.

## Endpoint shape

Every route is **project-scoped** — it starts with your `{projectId}`, the single
path segment your access token is issued for (there is no unscoped route):

```
GET    /{projectId}/api/v1/{table}         # list
GET    /{projectId}/api/v1/{table}/{id}    # single row (if PK is a single column)
POST   /{projectId}/api/v1/{table}         # create
PATCH  /{projectId}/api/v1/{table}?col=eq.val   # update rows matching the filter
PUT    /{projectId}/api/v1/{table}?col=eq.val   # replace
DELETE /{projectId}/api/v1/{table}?col=eq.val   # delete rows matching the filter
```

Examples below drop the `/{projectId}` prefix for brevity — prepend it to every
path. The `/{table}` segment is the raw Postgres table name (snake_case, not the
PascalCase GraphQL type). Compare:

| GraphQL field | REST path |
|---|---|
| `kanbanIssues` | `/{projectId}/api/v1/issues` + `Accept-Profile: kanban` |
| `shopifyCustomers` | `/{projectId}/api/v1/customers` + `Accept-Profile: shopify` |
| `clinicPatients` | `/{projectId}/api/v1/patients` + `Accept-Profile: clinic` |

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
request. The token decides the role the request runs as, and that role's
[API permissions](../features/permissions.md) decide which tables, rows and
columns it reaches.

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

Names in `select` (columns, aliases, embed names and `!hints`) are read against an allowlist: a letter (any script) or
underscore, then letters, digits, `_` or `$`, up to 64 characters; an embed may also be `schema.table`. A name outside
it is written in double quotes, which also take space, `.`, `#` and `-` (never a quote, backslash, comma, parenthesis,
colon, `!` or control character): `?select="first name","p.q","2fa"`. A quoted name reaches the database only as a
column checked against the table, so it names a column that exists or is dropped.

By default a name outside the allowlist still passes, as before, and is dropped if the table has no such column; the
engine logs one warning per kind of mismatch, never the name. An embed whose named columns the table has none of is a
`400` in every mode, never the whole table.

Set `app.rest.strict-select-names=true` (`APP_REST_STRICT_SELECT_NAMES`) to refuse them with a `400` instead.
Breaking in strict mode: an unquoted name that starts with a digit or `$` (`2fa`, `$sys`) or holds a space, hyphen,
dot or any other character outside the allowlist (`x-y`, `Owner Ref` as a hint), a name over 64 characters, an empty
item (`id,,name`), and an embed written `a.b.c`. Quote the name to keep using it.

## Embedding related rows

`?select=id,users(name)` embeds the rows a foreign key leads to (or from). When more than one relationship fits
the name, the answer is `300` with code `ambiguous_relationship`, and the message lists embed names that each
pick one:

- `users!created_by` picks the key by its column.
- `tenant_b.users` names the table with its schema, for same-named tables in two schemas;
  `tenant_a.users!created_by` does both when one table has several keys.

An alias gives an embed its own key (`creator:users!created_by(name)` returns under `creator`), which is how two embeds of
the same table sit in one select; two embeds that would share a key are `400 embed_key_collision`.

A `!hint` is a column of the key, or the name of its foreign key constraint. Keys over the same columns, which no column
can tell apart, are told apart by the constraint (`gparent!gchild_by_alt`); the `300 ambiguous_relationship` answer
lists the hints that work.

A `!column` that names no key of an existing relationship is `400 unknown_relationship_hint`. An embed named with
its schema comes back under the table name (`users`), unless another embed of the same select has that name, when it
keeps the name as written (`tenant_b.users`).

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
- **[API permissions](../features/permissions.md)** — per-role tables, rows and columns
- **[Choose Your Protocol](../choose-your-protocol.md)** — when to pick REST vs GraphQL
