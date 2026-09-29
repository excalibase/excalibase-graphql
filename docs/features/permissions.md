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
`role` in the connection payload (`connection_init` payload for GraphQL WS, the `access_token`
join payload for realtime). The same rules and errors apply.

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

**GraphQL schema.** Built per (project, role): a table appears only with a select permission; its
type holds only the permitted columns; relationship fields appear only when the role can select
the target table; `…Aggregate`/`totalCount` only with `allowAggregations`; `create…`, `update…`,
`delete…` fields only with that operation's permission; input types hold only the permitted,
non-preset columns. Introspection shows the same schema.

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

**REST.** Same permissions. A table the role cannot select answers 404; on a table it can see, a
method whose operation it has no permission for answers 403 `permission_denied`.

**Realtime** (GraphQL subscriptions and the realtime channel). A subscription is accepted only for
a table the role can select. Each change is delivered only when its row passes the select filter,
with only the select columns. An UPDATE's old and new images are judged separately. A filter that
needs another table (a relationship or `_exists`) is evaluated against the database for that row;
if that probe fails, the change is not delivered.

## 6. Functions

A database function is reachable only when a project tracks it. Tracking accepts a function only
when:

- it is a function, not a procedure;
- it is not overloaded (one function with that name in the schema);
- it returns `SETOF <table>` or `<table>` for a table or view the engine serves.

How it is exposed follows its volatility and cannot be overridden: `STABLE`/`IMMUTABLE` → a query
field (GraphQL `Query`, REST `GET`/`POST /rpc/<fn>`), `VOLATILE` → a mutation field (GraphQL
`Mutation`, REST `POST /rpc/<fn>`).

**Who may call it.**
- `STABLE`/`IMMUTABLE`: every role with a select permission on the return table, unless the
  tracked function turns inference off (`inferPermissions: false`); then only roles given an
  explicit function permission.
- `VOLATILE`: only roles given an explicit function permission, and only if they can also select
  the return table.
- `service` may call every tracked function. Untracked functions are unreachable for every role,
  `service` included.

**What it returns.** The engine runs

```sql
SELECT <permitted columns> FROM <schema>.<fn>(<args>) AS t WHERE <select filter of the return table>
```

so the caller sees only rows and columns their role may select on the return table, capped by
that permission's `limit`. What the function reads or writes inside its body is not governed by
these permissions; it runs with the database privileges of the connection (or of its owner when
it is `SECURITY DEFINER`). The engine never claims a function runs "as the caller".

**Session argument.** When a tracked function names a `json`/`jsonb` argument as its
`sessionArgument`, the engine passes the request's session variables in it; the client cannot
supply that argument.

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

## 8. Where permissions come from, and failure

The control plane serves a project's whole permission set in one response
(`GET /provision/{projectId}/permissions/`): table permissions, tracked functions and function
permissions. There is no "enforced" switch anywhere: permissions always apply.

- Unknown project (404) → the request is refused; nothing is cached for it.
- Control plane unreachable with nothing cached → 503 `permissions_unavailable`. With a cached set,
  the cached set is served until it can be refreshed.
- A change (permission, tracked function, schema DDL) publishes `policies.{projectId}.changed`; every
  engine replica drops that project's cached permissions and built schemas at once. The cache TTL
  remains the fallback.

## 9. What happens to row policies, column policies and table grants

They are folded into permission objects and the old stores are removed:

| Old | New |
|-----|-----|
| row policy, ALLOW, assigned to role R, operation O | ORed into the `filter` (select/update/delete) or `check` (insert) of R's O permission |
| row policy, DENY | ANDed as `_not` into the same expression |
| row policy assigned to `ALL` | copied to every role that has a permission on the table, and to `anon` and `user` |
| rule `{{currentUserId}}` / a custom claim `{{x}}` | `X-Excalibase-User-Id` / `X-Excalibase-X` |
| relationship rule | `_exists` |
| column policy HIDE or NULL for role R | the column is left out of R's select `columns` |
| table grant (anon/authenticated, operations) | a permission object with an empty filter for `anon`/`user` |

Not carried over, because Hasura has no equivalent: policies assigned to a single user id or a
group (groups were never populated), time variables (`{{now}}`, `{{daysAgo:N}}` — use a view or a
column default instead), and the PARTIAL/HASH/CUSTOM masks (never implemented). A NULL mask becomes
"column not selectable": the field is absent instead of returning null.

## 10. Differences from Hasura, on purpose

- Tables are served when they exist in the database; they are not tracked one by one. A table with
  no permission is still invisible to every role except `service`.
- Update and delete also require the row to pass the select filter (Postgres row-security
  semantics, pinned by the engine's differential tests against native Postgres).
- Function exposure is fixed by volatility (Hasura lets a `VOLATILE` function be exposed as a query).
- The bypass role is named `service` and comes from a secret API key, not an admin secret header.
