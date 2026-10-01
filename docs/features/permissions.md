# API permissions

Excalibase decides what a caller may reach through GraphQL, REST and realtime the way
[Hasura](https://hasura.io/docs/latest/auth/authorization/permissions/) does:

- Every request runs as exactly **one role**.
- A table or function a role has **no permission for does not exist** for that role: it is not in
  the role's GraphQL schema, REST answers 404, realtime refuses the subscription.
- A permission is one object per **(table, role, operation)**. It says which rows (a boolean
  expression over the row), which columns, and, for writes, what the new row must satisfy.
- A database function is reachable only when it is **tracked**, and then only by roles that are
  permitted to call it. Its result rows are filtered by the select permission of the table it
  returns.

This replaces the earlier model, where a table without a row policy was readable by everyone and
exposure was a separate on/off grant.

## 1. Roles

| Role | Who | Permissions apply? |
|------|-----|--------------------|
| `anon` | a request with no token, or a token from a publishable API key | yes |
| `user` | a signed-in end user (the default role excalibase-auth gives accounts) | yes |
| any other name | a custom role carried by the token (for example `editor`, `manager`) | yes |
| `service` | a token minted from a secret API key | **no** — full access to every table and every tracked function |

`anon` and `service` are reserved: excalibase-auth never gives either one to an account.
A role name is a lowercase identifier (`[a-z][a-z0-9_]*`, at most 63 characters).

### 1.1 Which role a request runs as

1. No `Authorization` header → `anon`.
2. A verified token → the token's `role` claim is the **default role** and `allowed_roles`
   (an array claim) lists the roles the token may act as. A token without `allowed_roles` may act
   only as its default role.
3. The optional request header **`X-Excalibase-Role`** picks one of the allowed roles. Without it
   the default role is used.
4. A `service` token may pick **any** role with `X-Excalibase-Role` and then runs under that
   role's permissions (like Hasura's admin acting as a role). Only in this case,
   `X-Excalibase-*` request headers supply session variables (§2).

| Situation | Answer | `code` |
|-----------|--------|--------|
| token fails verification | 401 | as today (`aud_mismatch`, …) |
| token has no `role` claim, or an invalid role name | 401 | `invalid_role_claim` |
| `allowed_roles` present but does not contain `role` | 401 | `invalid_role_claim` |
| `X-Excalibase-Role` names a role the token is not allowed | 403 | `role_not_allowed` |
| no token and `X-Excalibase-Role` is anything but `anon` | 403 | `role_not_allowed` |

The engine grants the `service` bypass only to a token whose `role` **and** `scope` are both
`service` (what excalibase-auth mints for secret keys). Nothing else — no header, no other claim —
turns the bypass on.

Realtime and GraphQL-over-WebSocket clients cannot set headers in a browser; they pass the role as
`role` in the `connection_init` payload (or as `X-Excalibase-Role` in its `headers` object, name
matched case-insensitively), on both the GraphQL WS and the realtime endpoint. A role sent on the
upgrade request's headers is used when the payload names none. The same rules and errors apply;
the socket is closed with a `connection_error` carrying the code.

### 1.2 Verifying tokens

The engine verifies every bearer token before it resolves a role; a token that fails is a 401 and
never falls back to `anon`. Configure one key source:

```yaml
app:
  security:
    jwt-enabled: true                 # false only together with insecure-dev-mode (local development)
    auth:
      jwks-url: https://auth.example.com/.well-known/jwks.json   # excalibase-auth or any OIDC provider
      # hmac-secret: ...              # HS256 shared secret, standalone mode
      require-aud: true               # aud must cover the project in the URL path
```

Any OIDC provider that publishes a JWKS endpoint works (excalibase-auth, Auth0, Keycloak, …).

## 2. Session variables

Permissions refer to the caller through session variables, written as strings that start with
`X-Excalibase-` (case-insensitive), exactly like Hasura's `X-Hasura-*`:

| Variable | Value |
|----------|-------|
| `X-Excalibase-Role` | the role the request runs as |
| `X-Excalibase-User-Id` | the token's `userId` claim (absent for API-key tokens) |
| `X-Excalibase-Project-Id` | the project in the URL |
| `X-Excalibase-Email` | the token's `email` claim, else `sub` for password tokens |
| `X-Excalibase-<Claim>` | every other top-level claim of the token (scalar or array) |

Names are compared lower-cased. An array claim becomes a Postgres array literal with every element
quoted (`{"a","b"}`). The registered claims `iss`, `aud`, `exp`, `iat`, `nbf`, `jti` and `token_use`
are not session variables. A request running as `anon` has no user, even when its token (a
publishable key's) carries a subject. When a `service` token acts as another role, the request's
variables are only `X-Excalibase-Role`, `X-Excalibase-Project-Id` and the `X-Excalibase-*` headers
it sent — never the service token's own claims.

A permission that refers to a variable the request does not carry fails the request with
`missing_session_variable` (Hasura's behaviour) — it never evaluates to "true" and never to a
guessed value. A value that cannot be converted to the column's type fails with
`invalid_session_variable`.

## 3. Permission objects

One object per table, role and operation; a missing object means the operation does not exist for
that role. Table keys are schema-qualified (`public.orders`).

```json
{
  "table": "public.orders",
  "role": "user",
  "select": {
    "filter": { "owner_id": { "_eq": "X-Excalibase-User-Id" } },
    "columns": ["id", "owner_id", "total", "status"],
    "limit": 100,
    "allowAggregations": false
  },
  "insert": {
    "check": { "owner_id": { "_eq": "X-Excalibase-User-Id" } },
    "columns": ["total", "status"],
    "set": { "owner_id": "X-Excalibase-User-Id" }
  },
  "update": {
    "filter": { "owner_id": { "_eq": "X-Excalibase-User-Id" } },
    "check": { "status": { "_neq": "shipped" } },
    "columns": ["status"],
    "set": {}
  },
  "delete": {
    "filter": { "owner_id": { "_eq": "X-Excalibase-User-Id" } }
  }
}
```

- `columns` is a list or `"*"` (every column, including ones added later).
- `filter`/`check` of `{}` means every row.
- `set` (presets) fills a column from a literal or a session variable. A preset column is not
  settable by the client even when it is also listed in `columns`.
- `limit` caps the rows one select returns for this role (the smaller of this, the request's own
  limit and the server's `app.max-rows`).
- `allowAggregations` (default `false`) exposes `…Aggregate` fields, `totalCount` and REST
  `Prefer: count=…`.

## 4. Boolean expressions

The Hasura boolean expression grammar:

```
exp      := {}                                  -- true
          | { "_and": [exp, ...] } | { "_or": [exp, ...] } | { "_not": exp }
          | { "<column>": { "<op>": value } }
          | { "<relationship>": exp }           -- a row exists in the related table matching exp
          | { "_exists": { "_table": "<schema.table>", "_where": exp } }
op       := _eq _neq _gt _gte _lt _lte _in _nin _like _nlike _is_null
value    := JSON literal | session variable | [values] for _in/_nin | true/false for _is_null
```

- Several keys in one object are ANDed.
- A relationship name is the field name the unfiltered schema gives the foreign key (both
  directions). The related table's own permissions do not apply inside a permission expression —
  the expression is the table owner's rule, as in Hasura.
- Values bind to the column's type. SQL NULL semantics hold: `_eq` against NULL is false,
  `_neq` against NULL is false, only `_is_null` matches NULL.

## 5. How each surface applies them

**GraphQL schema.** Built per (project, role): a table appears only with a select permission (for insert without
select, see below); its
type holds only the permitted columns; relationship fields appear only when the role can select
the target table; `…Aggregate`/`totalCount` only with `allowAggregations`; `create…`, `update…`,
`delete…` fields only with that operation's permission; input types hold only the permitted,
non-preset columns. Introspection shows the same schema. A field the role's schema does not have — root query or
mutation field, or a selection — fails the request with `Unknown field(s): <name>` and no `data`;
a mutation field the role has but whose arguments cannot compile fails with
`Invalid arguments for <name>`.

**Reads.** The select filter is ANDed into every read of the table, including nested relationship
reads, connection cursors, counts and the embedded reads REST performs. Columns the role may not
select cannot be used in `where`, `orderBy` or cursors either.

**Writes.**
- Insert: the row is written with presets applied, then `check` is evaluated on the new row inside
  the same transaction; a failure rolls back with `permission_check_failed`.
- Update: rows are chosen by the update `filter` **and** the select filter (a role never modifies a
  row it cannot read — Postgres semantics, stricter than Hasura); only permitted columns may be set;
  `check` is evaluated on the updated row.
- Delete: `filter` **and** the select filter.
- Upsert (on conflict update) needs both insert and update permission; the update half is guarded
  by the update filter.
- What a mutation returns (`returning`, REST `return=representation`) is limited to rows that pass
  the select filter and to the select columns. A role with insert but no select permission gets the
  affected-row count only. `affected_rows` counts every row written.

**Insert without select** (a public contact form: `anon` may insert into `messages`, never read it).
The table is write-only for that role:
- GraphQL: no `Query` field (list, connection, aggregate), no type for its rows, and no relationship
  field to or from it on any read type; nested inserts into or from it work as for any table (below).
  `Mutation` has `create<T>(input:)` and
  `createMany<T>(inputs:)`, both returning `<T>_InsertResult { affected_rows: Int! }` (the underscore
  keeps the name clear of every table-derived type). The input holds the permitted, non-preset
  insert columns; presets and `check` apply as for any insert, and a failed check rolls the whole
  statement back. A nested insert answers the count of every row it wrote, nested rows included.
  `onConflict` needs update permission, so it is not offered; update and delete need
  select (their filter includes it), so a document giving them without select has them dropped and
  logged as `permission_invalid`. Selecting anything but `affected_rows`/`__typename` answers
  `Unknown field(s)`.
- REST: `POST /<table>` (object or array) answers 201 with no body; with
  `Prefer: return=representation` 201 `{"data": []}`, the same answer as for any written row the role
  may not read. There is no count header on writes. A failed check is 403 `permission_check_failed`.
  `Prefer: resolution=merge-duplicates` (upsert) is 403 `permission_denied`. `GET`, `PATCH`, `PUT`
  and `DELETE` answer 404, as for a table that does not exist. The OpenAPI document lists only
  `post` for it, typed by the settable columns.
- Realtime: no subscription. Tracked functions returning the table: not callable.

**Nested inserts** (GraphQL `create<T>` and `createMany<T>`; Postgres only, MySQL offers none), as in Hasura:
- A row's insert input has one field per relationship the role may insert through: an array
  relationship (`<child>: { data: [<Child>CreateInput!]! }`, the reverse foreign key) or an object
  relationship (`<fk field>: { data: <Parent>CreateInput! }`, the foreign key on this table). The role
  needs **insert** permission on both tables, never select. Field names are the ones the unfiltered
  schema gives the foreign key. A relationship the role cannot insert through is not in the input, and
  sending it fails the request before anything is written.
- Every nested row obeys its own table's insert permission: its `columns`, its `set` presets and its
  `check`. The key a row takes from the row it hangs off is filled by the engine and is not the client's
  to send (`cannot insert "<col>" columns as their values are already being determined by parent
  insert`); neither is a foreign key beside the object relationship that fills it (`cannot insert object
  relationship "<rel>" as "<col>" column values are already determined`). A preset on that column wins.
- Order (Hasura's): the row an object relationship points at, then the row, then the rows of each array
  relationship, each as its own statement, in input order. Rows that nest others are inserted one at a
  time; the rows of one relationship that nest nothing share one statement. A `null` relationship or an
  empty `data` list inserts nothing.
- Each statement evaluates its own rows' `check`. A check that reaches another table (a relationship or
  `_exists`) sees every row written by earlier statements of the mutation and none written later: a
  child's check can rely on its parent row, a parent's check cannot rely on its children (Hasura fails
  that case the same way). Rows of one statement do not see each other.
- The whole mutation (every root field, every nested row) runs in one transaction. Any failed check,
  constraint or validation rolls all of it back; `permission_check_failed` is the answer for a check.
- What it answers: `create<T>` the row and `createMany<T>` the rows, limited to the select filter and
  columns, evaluated after every insert, so a relationship field shows the nested rows the role may
  select; a relationship to a table the role cannot select is not a field (`Unknown field(s)`). A role
  that cannot select the root table gets `affected_rows`, counting every row written, nested ones
  included.
- When an operation holds a nested insert, all its root fields run as separate statements in order in
  that one transaction, so a later field sees what an earlier one wrote.

**REST.** Same permissions. A table the role cannot select answers 404 (a POST to a table it may
insert into but not select excepted, above); on a table it can see, a method whose operation it has
no permission for answers 403 `permission_denied`. The OpenAPI document lists only the methods the
role holds.

**Realtime** (GraphQL subscriptions and the realtime channel). A subscription is accepted only for
a table the role can select. Each change is delivered only when its row passes the select filter,
with only the select columns. An UPDATE's old and new images are judged separately. A filter that
needs another table (a relationship or `_exists`) is evaluated against the database for that row;
if that probe fails, the change is not delivered. A DELETE (and an UPDATE's old image) is judged by
the old row's own values, so it reaches everyone who could see the row, even after the row is gone.
That needs every column the filter reads and the select columns in the image: enabling realtime on
a table sets `REPLICA IDENTITY FULL`. An image missing any of them is not delivered, and the engine
logs `realtime_image_incomplete table=…` at most once a minute per table.

## 6. Functions

A database function is reachable only when a project tracks it. Tracking accepts a function only
when:

- it is a function, not a procedure;
- it is not overloaded (one function with that name in the schema);
- it returns `SETOF <table>` or `<table>` for a table or view the engine serves.

How it is exposed follows its volatility and cannot be overridden: `STABLE`/`IMMUTABLE` → a query
field (GraphQL `Query`, REST `GET`/`POST /rpc/<fn>`), `VOLATILE` → a mutation field (GraphQL
`Mutation`, REST `POST /rpc/<fn>`; `GET` answers 405).

The engine re-checks every tracked function against the live catalog each time it builds a role's
schema. A tracked function is **not exposed to anyone** (and the engine logs
`function_invalid function=<name> reason=<why>`) when:

- it no longer exists, is a procedure, is overloaded, or does not return rows of a served table or
  view;
- its `exposedAs` does not match its volatility (`QUERY` needs `STABLE`/`IMMUTABLE`, `MUTATION`
  needs `VOLATILE`), including after the function is redefined;
- an argument is not a named `IN` argument, is named like a row argument (`where`, `filter`,
  `orderBy`, `limit`, `offset`, `distinctOn`, `vector`, `select`, `order`, `or`, `and`, `not`,
  `first`, `after`), or has a type name the engine does not cast to;
- its `sessionArgument` is not one of its `json`/`jsonb` arguments;
- its GraphQL field name (schema prefix + camelCase, like a table: `public.search_notes` →
  `publicSearchNotes`) collides with a field a table gives the schema, or with an earlier tracked
  function's.

An invalid function is dropped, never exposed in a wider or guessed form.

**Who may call it.**
- `STABLE`/`IMMUTABLE`: every role with a select permission on the return table, unless the
  tracked function turns inference off (`inferPermissions: false`); then only roles given an
  explicit function permission.
- `VOLATILE`: only roles given an explicit function permission, and only if they can also select
  the return table.
- `service` may call every valid tracked function. Untracked functions are unreachable for every
  role, `service` included. `service` reads the permission document only for the tracked functions:
  when it cannot be read, `service` is still served every table, and no function.

**What it returns.** The engine runs

```sql
SELECT <permitted columns> FROM <schema>.<fn>(<args>) AS t WHERE <select filter of the return table>
```

so the caller sees only rows and columns their role may select on the return table, capped by
that permission's `limit`. What the function reads or writes inside its body is not governed by
these permissions; it runs with the database privileges of the connection (or of its owner when
it is `SECURITY DEFINER`). The engine never claims a function runs "as the caller".

- Arguments are passed by name (`"arg" => CAST(:value AS <type>)`); an unknown argument or a missing
  one without a default fails the request.
- A set-returning function answers a list and also takes the list arguments of its return table
  (GraphQL `where`, `orderBy`, `limit`, `offset`; REST filters, `select=`, `order=`, `limit`,
  `offset`) and exposes its relationships. A single-row function answers one object, or `null` when
  it returns no row, a NULL row, or a row the role may not select.
- A `VOLATILE` function runs in the request's one statement and transaction; its writes stand even
  when the rows it returns are filtered out. A filter that reaches another table sees the database
  as of the start of the statement, not the function's own writes.
- REST answers `{"data": [...]}` (set-returning) or `{"data": {...}|null}` (single row); counting
  (`Prefer: count=…`) is not supported on functions.

**Session argument.** When a tracked function names a `json`/`jsonb` argument as its
`sessionArgument`, the engine passes the request's session variables in it, as one object keyed by
lower-cased variable name (`{"x-excalibase-role": "user", "x-excalibase-user-id": "…", …}`); the
client cannot supply that argument.

Procedures are reflected but never exposed; the earlier `call<Procedure>` mutations and the untyped
`/rpc` call were removed. Computed fields (functions of one table row) are reflected but not exposed
for GA either. Tracked functions, like permissions, are Postgres-only.

The engine never changes Postgres `EXECUTE` privileges.

## 7. Where the engine enforces this (one place)

All of the above is derived from one immutable object per (project, role), the **access plan**,
built in one place: the step that assembles a role's engine state from the reflected schema and
the project's permission set. It holds, per table, the permitted operations, columns, presets and
compiled filter/check templates, and, per tracked function, whether the role may call it. From it
come, and only from it:

- the role's filtered schema, so the compiler and introspection never see what the role cannot
  reach;
- the request-scoped SQL guards (read filter, write filter, check, column list, limit), which bind
  the request's session variables into the plan's templates;
- the realtime row matcher and column projection;
- the function call guard.

No controller, compiler or websocket handler reads permissions from the permission source
directly; a structural test fails the build if one does. The `service` role gets a plan that
permits everything on every table and every tracked function, so the bypass is also decided in
that one place.

How the engine applies this today (see [Permission Enforcement](rls-architecture.md)):

- Rows written by an upsert owe both the insert and the update `check`.
- A `check` or filter that reaches another table sees the database as of the start of its statement.
  A nested insert runs one statement per table and level (§5), so a child's check sees its parent row;
  rows written by the same statement are not seen.
- Computed fields (functions of one table row) are reflected but not exposed for GA, to any role,
  `service` included: they are untracked functions. A later step may let a function be tracked as a
  computed field.

## 8. Where permissions come from, and failure

The control plane serves a project's whole permission set in one response
(`GET /provision/{projectId}/permissions/`): table permissions, tracked functions and function
permissions. There is no "enforced" switch anywhere: permissions always apply.

- Unknown project (404) → the request is refused; nothing is cached for it.
- Control plane cannot be read (connection refused, timeout, a non-200 answer other than 404, or no
  service token) with nothing cached → 503 `permissions_unavailable`. With a cached set, the cached set
  is served for at most `app.security.permissions.max-stale-ms` (env
  `APP_SECURITY_PERMISSIONS_MAX_STALE_MS`, default 300000 = 5 minutes, must be greater than 0 or the
  engine refuses to start), counted per project from the first failed refresh. After that every role
  except `service` gets 503 `permissions_unavailable` (GraphQL and REST; a WebSocket is closed) until a
  refresh succeeds, which ends the outage and restarts the count for the next one. `service` never reads
  the document, so it is served throughout. A cached set is refreshed only after the cache TTL
  (`app.security.rls.policy-ttl-ms`), so the longest a set can be served stale is TTL + max-stale.
- An outage does not multiply control-plane calls: concurrent reads of a project share one fetch, a
  request reads the document at most once, and during an outage a project's refresh is attempted at
  most once per `app.security.permissions.retry-interval-ms` (env
  `APP_SECURITY_PERMISSIONS_RETRY_INTERVAL_MS`, default 2000, must be greater than 0). Requests between
  attempts are answered from the cached set while the window is open, else with 503 at once.
- An outage is loud: every failed attempt logs ERROR `permissions_fetch_failed project=… stale_for_ms=…`
  and increments `excalibase_permissions_fetch_failed_total{project}`; the window closing logs ERROR
  `permissions_stale_expired` once per outage; `excalibase_permissions_stale_seconds{project}` shows
  how long refreshes have been failing (0 when fresh).
- A change (permission, tracked function, schema DDL) publishes `policies.{projectId}.changed`; every
  engine replica drops that project's cached permissions and built schemas at once. The cache TTL
  remains the fallback.

### 8.1 Wire format

The engine authenticates with its service token (capability `policies:read`) and reads:

```json
{
  "projectId": "proj-abc",
  "version": 42,
  "tables": [
    {
      "table": "public.orders",
      "role": "user",
      "select": { "filter": {"owner_id": {"_eq": "X-Excalibase-User-Id"}}, "columns": ["id","owner_id","total"], "limit": 100, "allowAggregations": false },
      "insert": { "check": {}, "columns": "*", "set": {"owner_id": "X-Excalibase-User-Id"} },
      "update": { "filter": {}, "check": {}, "columns": ["status"], "set": {} },
      "delete": { "filter": {} }
    }
  ],
  "functions": [
    { "function": "public.search_orders", "exposedAs": "QUERY", "inferPermissions": true, "sessionArgument": null }
  ],
  "functionPermissions": [
    { "function": "public.search_orders", "role": "editor" }
  ]
}
```

One `tables` entry per (table, role); an operation key is present only when that permission exists.
The three arrays are always present. `table` and `function` are schema-qualified lower-case names,
roles follow §1 and are never `service`, `version` grows with every write, and expressions follow §4
(depth at most 16, at most 200 nodes). The engine reads the document strictly: an unknown key, a
repeated key, a missing `filter`/`check`, a function permission for an untracked function or any
other deviation refuses the **whole** document — it is never partly applied and never cached, and the
last good copy (else 503 `permissions_unavailable`) is served instead.

## 9. Row policies, column policies and table grants are gone

Permissions are the only access model. The earlier row policies, column policies, table grants and
the platform-wide switch that could turn enforcement off no longer exist, in the engine or in the
control plane. What each of them said is written as a permission:

| Old | Permission |
|-----|-----|
| row policy, ALLOW, for role R, operation O | ORed into the `filter` (select/update/delete) or `check` (insert) of R's O permission |
| row policy, DENY | ANDed as `_not` into the same expression |
| row policy for every role | repeated in each role's permission |
| rule `{{currentUserId}}` / a custom claim `{{x}}` | `X-Excalibase-User-Id` / `X-Excalibase-X` |
| relationship rule | `_exists` |
| column policy HIDE or NULL for role R | the column is left out of R's select `columns` |
| table grant (role, operations) | a permission object with an empty filter for that role |

There is no equivalent for a rule on a single user id or a group, for time variables (`{{now}}`,
`{{daysAgo:N}}` — use a view or a column default instead) or for PARTIAL/HASH/CUSTOM masks. A column
a role may not select is absent from its schema rather than returned as null.

## 10. Differences from Hasura, on purpose

- Tables are served when they exist in the database; they are not tracked one by one. A table with
  no permission is still invisible to every role except `service`.
- Update and delete also require the row to pass the select filter (Postgres row-security
  semantics, pinned by the engine's differential tests against native Postgres).
- Function exposure is fixed by volatility (Hasura lets a `VOLATILE` function be exposed as a query).
- `create<T>` of a table the role may insert into but not select answers `<T>_InsertResult` rather than
  Hasura's `<t>_mutation_response`, and has no `returning`; a role that can select the table gets the
  row (`create<T>`) or rows (`createMany<T>`) directly instead of `returning`.
- Nested inserts have no `on_conflict` inside a relationship (Hasura offers it); only the root
  `create<T>` takes `onConflict`. A relationship is a foreign key, so there is no `insertion_order` for
  manually defined relationships.
- The bypass role is named `service` and comes from a secret API key, not an admin secret header.
