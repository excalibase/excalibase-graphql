# RLS Engine Architecture & Project Routing

Design reference for the application-layer Row-Level-Security (RLS) engine: how
policies are loaded, how a request is mapped to a project, and the rule that
**RLS applies to every request** — authenticated or not.

> This is the source of truth for the RLS request model. Update it when the
> behaviour changes.

## Why an app-layer engine (not native DB RLS)

The engine composes the `WHERE` filter (and column projection) in the
application, from policies fetched over HTTP from provisioning. It does **not**
rely on native Postgres `ROW LEVEL SECURITY`. That keeps enforcement identical
across any backing database (Postgres today, MySQL proven, Mongo later) — the
same policy compiles to portable SQL. See `excalibase-rls` (engine) and the
vendored `modules/excalibase-rls-*` copies the app runs.

## Project routing — the project is in the URL

A request must declare **which project** it targets, so the engine knows which
policy set to load. `projectId` is a single **opaque id** that provisioning
emits (e.g. `proj-237qoqksdb`). (`{org}/{project}` slash-form is legacy.)

Every platform surface carries the project in its URL — there is **no unscoped
route**. A request without a project in the path cannot resolve which policies
apply, so it could not enforce RLS; such routes simply don't exist.

| Surface       | Route                                   |
|---------------|-----------------------------------------|
| Auth          | `/auth/{projectId}/token`               |
| Functions     | `/functions/v1/{projectId}/{mod}.{fn}`  |
| Provisioning  | `/provision/{projectId}/rls-policies/`  |
| **GraphQL**   | `/{projectId}/graphql`                  |
| **REST**      | `/{projectId}/api/v1/{table}`           |

`projectId` is a single opaque segment (e.g. `proj-237qoqksdb`).

`JwtAuthFilter.extractProjectId` reads it from the path; the controllers bind it
only to make the route match. *How* a request reaches the app (ingress, host,
gateway) is infra; the app's only job is to read `projectId` from the path and
enforce. There is intentionally no fallback to a token-derived project — the
path is the single source of truth.

The token (when present) also carries `projectId`. When both the path and a
token are present and **disagree, the request is rejected (403)** — a token
cannot reach another project.

**SDK alignment (separate repo, `excalibase-sdk-js`):** `graphqlEndpoint()` /
`restEndpoint()` should include `${this.projectId}` to call the project-scoped
routes (the SDK already holds the projectId; auth/functions already do this).

## RLS applies on every request

RLS is a property of the **resource**, not of the token. The user context only
decides *which rows match*; absence of a user is not absence of RLS.

| Request                         | User context        | Result for a policied table |
|---------------------------------|---------------------|------------------------------|
| Valid token                     | token's user/claims | rows the user is allowed to see |
| No token (anonymous)            | empty context       | owner/claim predicates match nothing → **0 rows (fail-closed)** |
| Token for the wrong project     | —                   | **rejected** (path project ≠ token project) |
| Table with **no** policy        | any                 | unrestricted (like Postgres: no policy = public) |

So a token-less or unauthorised caller never sees protected rows, and tables
without a policy stay public.

## Enforcement coverage per surface

RLS must apply on **every** data path, not just GraphQL. Status:

| Path                                   | Read (visibility) | Write (WITH-CHECK) |
|----------------------------------------|-------------------|--------------------|
| GraphQL query                          | ✅                | n/a |
| GraphQL mutation (insert/update/delete)| ✅ (coupling)     | ✅ `RlsContext.rowCheck()` |
| REST `GET /{table}` + `totalCount`     | ✅                | n/a |
| REST embeds `?select=*,fk(*)`          | ✅                | n/a |
| REST `PATCH`/`DELETE`                  | ✅ (where + coupling) | ❌ new-image not validated |
| REST `POST` (insert)                   | n/a               | ❌ no WITH-CHECK — can insert policy-violating rows |
| REST `POST /rpc/{fn}` (stored proc)    | ❌ bypasses RLS (EXC-25) | ❌ |
| Realtime WS subscriptions              | ❌ no per-row RLS (EXC-19) | n/a |

The **read** leaks are closed (REST previously skipped engine RLS entirely —
`RestQueryCompiler` now splices the predicate into selects, counts, and embeds,
exactly like GraphQL's `FilterBuilder`). **Remaining gaps** are writes/RPC/
realtime — fix order: REST insert/update WITH-CHECK → RPC → realtime.

## Engine capabilities (Postgres-RLS parity)

The engine matches native Postgres RLS for the policy classes it supports,
pinned by a differential test harness (`DifferentialPostgresRlsTest`) that runs
each case under native `CREATE POLICY` and compares row sets:

- Scalar predicates: `EQ/NEQ/GT/GTE/LT/LTE/IN/NOT_IN/LIKE/NOT_LIKE/IS_NULL/IS_NOT_NULL`, NULL three-valued logic.
- Composition: multiple ALLOW (OR / DNF), DENY (`AND NOT`), per role/user/group assignment.
- `ALLOW`-write coupling: UPDATE/DELETE require SELECT-visibility.
- Column-level: HIDE (= Postgres column GRANT), plus NULL/mask supersets.
- **Relationship / `EXISTS`** subqueries (membership policies) → portable `EXISTS (SELECT 1 FROM rel WHERE rel.fk = outer.pk …)`.
- **Custom claims**: any JWT claim as `{{claim}}` (e.g. `{{region}}`).
- **JSON path**: `meta.field` → `meta->>'field'` (Postgres) / `JSON_EXTRACT` (MySQL).

### Relationship correlation needs the query alias

The SQL compiler aliases every table (`FROM t <randAlias>`). A relationship
`EXISTS` must correlate back to that alias, not the table name (`"orders".col`
fails with *missing FROM-clause entry*). The alias is threaded
`RlsWhereContributor.contribute(table, alias, op)` →
`RlsPolicyEnforcer.filterFor(…, alias)` → `JdbcEvaluator.compile(…, outerAlias)`.

## Open items

- **SDK alignment** (`excalibase-sdk-js`, separate repo): `graphqlEndpoint()` /
  `restEndpoint()` must include the project segment (use `this.projectName`,
  which equals the token's `projectId`) — the unscoped routes are gone, so a
  client that calls `/graphql` or `/api/v1` now gets a 404. Auth/functions
  already build project-scoped URLs.
- **WebSocket subscriptions** still upgrade at `/graphql` (separate handler;
  realtime per-row RLS is its own epic) — scope + enforce there too later.
- **OpenAPI** `servers[].url` still emits `/api/v1` (cosmetic) — should reflect
  the scoped base.
- **Missing custom claim**: currently throws (typo protection). Postgres
  `current_setting(x, true)` returns NULL (fail-closed). Decide whether an absent
  *custom* claim should resolve to null to match Postgres.
- **Realtime relationship predicates**: the in-memory matcher can't probe a
  second table; needs a DB lookup (or claim-based modelling) — see subscriptions.
