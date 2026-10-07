# Permission Enforcement Architecture & Project Routing

Design reference for how the engine enforces a project's [API permissions](permissions.md): how a
request is mapped to a project and a role, where the rules are compiled, and the error contract.
Permissions apply to **every request** — with or without a token.

> This is the source of truth for the request model. Update it when the behaviour changes.

## Project routing — the project is in the URL

Every surface carries the project in its URL; there is no unscoped route. `projectId` is a single
opaque segment that provisioning emits (e.g. `proj-237qoqksdb`).

| Surface        | Route                                     |
|----------------|-------------------------------------------|
| Auth           | `/auth/{projectId}/token`                 |
| Functions      | `/functions/v1/{projectId}/{mod}.{fn}`    |
| Permissions    | `/provision/{projectId}/permissions/`     |
| **GraphQL**    | `/{projectId}/graphql`                    |
| **REST**       | `/{projectId}/api/v1/{table}`             |
| **GraphQL WS** | `/{projectId}/graphql` (upgrade)          |
| **Realtime WS**| `/{projectId}/api/v1/realtime` (upgrade)  |

A token that names another project is refused (`project_mismatch`); an unknown project is `404`.

## One access plan per (project, role)

Each project's database is reflected once. For every role that calls it, the engine builds one
immutable **access plan** (`io.github.excalibase.access.AccessPlan`) from that reflection and the
role's slice of the project's permission document. The plan is built in exactly one place — where the
schema manager assembles a role's engine — and everything else takes it from there:

| From the plan | Used by |
|---|---|
| the role's schema view (`view()`) and rights (`access()`) | the SQL compiler, GraphQL introspection, REST |
| `guard(sessionVariables)` — select/update/delete filters, presets, checks | registered per request by `JwtAuthFilter` through `RlsContext`; the compilers AND it into every read and write |
| `changes(table, sessionVariables)` — row matcher and column projection | both websocket handlers |

`service` gets an all-access plan (every table and function, no guards). A deployment without a
permission source serves `service` only (every other role gets an empty schema); with authentication
off, the whole schema is served.

A permission that names a table, column or relationship the database lacks, or a literal its column
cannot hold, is dropped when the plan is built and logged as `permission_invalid` — dropping denies.

No controller, compiler or websocket handler reads the permission source; `PermissionBoundaryTest`
fails the build if one does.

## What each surface applies

- **Reads**: the select filter is ANDed into every read of the table: lists, connections, counts,
  aggregates, nested relationships and REST embeds. The role's limit caps each select, nested lists
  included. A column outside the role's view cannot be selected, filtered, ordered or paged by.
- **Writes**: only the permission's columns may be set; presets are written from literals or session
  variables and cannot be set by the client. Update and delete reach rows passing their own filter
  **and** the select filter. The permission's `check` is evaluated in the same SQL statement on the
  written rows; if any row fails, the statement raises and the whole mutation rolls back. What a
  mutation returns is limited to rows passing the select filter, with the select columns.
- **Upsert** needs insert and update permission; the update half reaches only rows the update filter
  passes, and every written row owes both checks.
- **Realtime**: a subscription is accepted only for a table the role can select. Each change image is
  judged in memory; one that needs the database (a relationship or `_exists`) is probed with
  `SELECT EXISTS (...)` for an INSERT or an UPDATE's new image, and withheld for a DELETE or an
  UPDATE's old image. Only the select columns are delivered.
- **Functions**: only tracked functions are reachable, by roles permitted to call them (explicitly,
  or inferred from the return table's select for a query function); their rows are read through the
  return table's select filter, columns and limit ([Functions](functions.md)). Procedures are never
  exposed, and neither are computed fields (untracked functions), for any role.

## Where permissions come from, and failure

The engine reads `GET {policy-url}/provision/{projectId}/permissions/` with its service token and
caches it for `app.security.rls.policy-ttl-ms`. A `policies.{projectId}.changed` NATS message drops
the project's cached permissions, reflection and engines at once.

- Unknown project: refused, nothing cached.
- Control plane unreachable: the cached document is served while it lasts; with none, `503`.
- A document the grammar refuses: never applied; the last good copy stands in for at most one TTL
  from the first refusal, then the project answers `503 permissions_unavailable` for every role but
  `service`. Each refusal logs `permissions_document_refused` and increments
  `excalibase_permissions_document_refused_total{project}`.

Permissions compile to Postgres SQL only: a MySQL deployment with a permission source refuses to start.

## Error contract

| Condition | GraphQL | REST | WebSocket |
|---|---|---|---|
| a written row fails its check | `200`, `errors[0].extensions.code = permission_check_failed`, no `data` | `403`, `{ "code": "permission_check_failed", ... }` | — |
| a method the role holds no permission for, on a table it can see | the field does not exist | `403`, `code: permission_denied` | — |
| `Prefer: count=` without `allowAggregations` | `totalCount` / `…Aggregate` do not exist | `403`, `code: permission_denied` | — |
| a table the role cannot select | `Unknown field` | `404` | subscription refused |
| a session variable is missing or does not fit | `200`, `extensions.code = missing_session_variable` / `invalid_session_variable` | `400` with that `code` | subscription refused with that code |
| permissions cannot be read | `503`, `code: permissions_unavailable` | `503` | session closed (`permissions_unavailable`) |
| a write names a column the role can read but may not set, or one the permission presets | `200`, `extensions.code = permission_denied` | `403`, `code: permission_denied` | — |
| a write names a column outside the role's view | `200`, `Unknown column` | `400`, `Unknown column` | — |
| the database refuses the write (unique, foreign key, check, not-null, exclusion, RAISE) | `200`, `extensions.code` = `unique_violation` / `foreign_key_violation` / … with `constraint` or `column` | `409` for `unique_violation`, else `400`, same `code` | — |
| a filter value the column's type cannot hold | `200`, `extensions.code = invalid_value`, `extensions.column` | `400`, `code: invalid_value`, `column` | — |
| a fault on the server's side | `500`, `extensions.code = internal_error` | `500`, `code: internal_error` | — |

Messages never contain SQL, SQLSTATE, PL/pgSQL context or stored row values; a
value appears only when the client sent it (a malformed filter value) or a
`RAISE` message includes it.
