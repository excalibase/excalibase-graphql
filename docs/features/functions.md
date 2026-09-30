# Functions

A Postgres function is reachable through the API only when the project **tracks** it, and then
only by roles that may call it. Its result rows are filtered by the select permission of the table
it returns, exactly like a read of that table. Untracked functions are unreachable for every role,
the `service` role included. Procedures (`CREATE PROCEDURE`) are never exposed.

The rules are specified in [API permissions §6](permissions.md#6-functions); this page shows how to
use them.

## What can be tracked

A function can be tracked when it:

- is a function, not a procedure;
- is not overloaded (only one function of that name in its schema);
- returns `SETOF <table>` or `<table>` for a table or view the engine serves;
- has only named `IN` arguments whose names are not `where`, `filter`, `orderBy`, `limit`,
  `offset`, `distinctOn`, `vector`, `select`, `order`, `or`, `and`, `not`, `first` or `after`
  (those already mean something on a list of rows).

Its volatility decides how it is served, and the tracked `exposedAs` must match it:

| Volatility | `exposedAs` | GraphQL | REST |
|------------|-------------|---------|------|
| `STABLE` / `IMMUTABLE` | `QUERY` | a `Query` field | `GET` or `POST /rpc/<fn>` |
| `VOLATILE` | `MUTATION` | a `Mutation` field | `POST /rpc/<fn>` only (`GET` answers 405) |

A tracked function that does not (or no longer) meet these rules is not exposed to anyone and the
engine logs `function_invalid function=<name> reason=<why>`. It is never exposed in a wider form.

## Who may call it

- A `QUERY` function tracked with `inferPermissions: true`: every role that may select its return
  table.
- Otherwise (a `QUERY` function with inference off, or any `MUTATION` function): a role with an
  explicit function permission, and only if it may also select the return table.
- `service`: every valid tracked function.

Tracking and function permissions are part of the project's permission document
([§8.1](permissions.md#81-wire-format)):

```json
{
  "functions": [
    { "function": "public.search_notes", "exposedAs": "QUERY",    "inferPermissions": true,  "sessionArgument": null },
    { "function": "public.add_note",     "exposedAs": "MUTATION", "inferPermissions": false, "sessionArgument": "session" }
  ],
  "functionPermissions": [
    { "function": "public.add_note", "role": "user" }
  ]
}
```

## What it returns

The engine calls the function as the source of a read of its return table:

```sql
SELECT <columns the role may select, nested relationships>
FROM "public"."search_notes"("p_query" => CAST($1 AS text)) AS t
WHERE <select filter of public.notes for the role> [AND the request's where]
ORDER BY … LIMIT …
```

So the caller gets only rows and columns their role may select on the return table, capped by that
permission's `limit` and the server's `app.max-rows`. A single-row function (`RETURNS notes`)
answers one object, or `null` when it returns no row or its row is not visible.

What the function body reads or writes is **not** governed by the API permissions. It runs with the
database privileges of the engine's connection, or of the function's owner when it is
`SECURITY DEFINER`; the engine never runs it "as the caller" and never changes `EXECUTE` privileges.
Write the function with that in mind: filter by the session argument when the body must only touch
the caller's rows.

A `VOLATILE` function runs inside the request's single statement (and transaction). Rows it writes
are returned only if they pass the select filter; a filter that reaches another table (`_exists`, a
relationship) sees the database as it was when the statement started, so it does not see rows the
function itself wrote.

## Session argument

When a tracked function names a `json`/`jsonb` argument as its `sessionArgument`, the engine passes
the request's session variables in it, as an object keyed by lower-cased variable name:

```json
{ "x-excalibase-role": "user", "x-excalibase-user-id": "u-1", "x-excalibase-project-id": "proj-abc" }
```

The client cannot supply that argument; it is not part of the GraphQL field or the REST call.

```sql
CREATE FUNCTION my_notes(session jsonb) RETURNS SETOF notes
LANGUAGE sql STABLE AS $$
    SELECT * FROM notes WHERE owner_id = session ->> 'x-excalibase-user-id'
$$;
```

## GraphQL

The field is named like a table field: schema prefix + camelCase name (`public.search_notes` →
`publicSearchNotes`). A function whose field name would collide with a table's field is not
exposed (`function_invalid`). Arguments are typed from their Postgres types and required unless
they have a default. A set-returning function also takes `where`, `orderBy`, `limit` and `offset`,
and its rows expose the return table's relationships.

```graphql
query {
  publicSearchNotes(p_query: "alpha%", where: { id: { gt: 1 } }, limit: 10) {
    id
    body
    publicAuthorId { name }
  }
}

mutation {
  publicAddNote(p_id: 10, p_body: "hello") { id owner_id body }
}
```

## REST

```bash
# QUERY function: arguments as query parameters; the other parameters read the rows
curl "$BASE/proj-abc/api/v1/rpc/search_notes?p_query=alpha%25&select=id,body&order=id.desc&limit=5" \
  -H "Authorization: Bearer $TOKEN"

# Any tracked function: arguments as a JSON body; filters in the query string
curl -X POST "$BASE/proj-abc/api/v1/rpc/add_note?select=id" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"p_id": 10, "p_body": "hello"}'
```

- The schema is the default one, or the `Accept-Profile` (GET) / `Content-Profile` (POST) header.
- The answer is `{"data": [...]}` for a set-returning function and `{"data": {...} | null}` for a
  single-row one.
- REST filters (`?col=eq.x`), `select=` (with embeds), `order=`, `limit` and `offset` apply to the
  returned rows; `Prefer: count=…` is not supported on functions.
- A function that is not tracked, or that the role may not call, answers 404; `GET` on a
  `MUTATION` function answers 405.

## Computed fields

Functions of one table row (`customer_full_name(c customer)`) are reflected but not exposed for GA,
to any role, `service` included: they are untracked functions, so a request selecting one answers
`Unknown field(s): <name>`. A later step may let a function be tracked as a computed field.

## Procedures and MySQL

Procedures are reflected but never exposed, to any role. The earlier `call<Procedure>` GraphQL
mutations and the untyped `POST /rpc/<fn>` that returned a bare value were removed; wrap the logic
in a function that returns rows of a table and track it. Tracked functions, like permissions, are
Postgres-only: a MySQL deployment exposes no routines.
