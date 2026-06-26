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

The SDK (`createClient({ url, projectId })`, `excalibase-sdk-js`) already
project-scopes most surfaces in the URL — **GraphQL is the current exception**:

| Surface       | URL the SDK builds                          | Project in URL? |
|---------------|---------------------------------------------|-----------------|
| Auth          | `{url}/auth/{orgSlug}/{projectName}/token`   | yes |
| Functions     | `{url}/functions/v1/{projectId}/{mod}.{fn}`  | yes |
| Storage       | uses `{projectId}`                           | yes |
| Provisioning  | `{base}/provision/{projectId}/rls-policies/` | yes |
| REST          | `{url}/api/v1/{path}`                        | no  |
| **GraphQL**   | `{url}/graphql` (`graphqlEndpoint()`)        | **no — to fix** |

**Target:** GraphQL becomes project-scoped, mirroring functions —
`/graphql/v1/{projectId}` (or `/{projectId}/graphql`). This is a **two-repo**
change: the app exposes the project-scoped route, and the SDK's
`graphqlEndpoint()` includes `${this.projectId}` (a one-line change; the SDK
already holds the projectId).

**There is no bare, project-less `localhost:.../graphql`.** A project-less route
cannot determine which policies apply, so it cannot enforce RLS. *How* a request
is routed to the app (ingress, host, gateway) is an infrastructure concern; the
app's job is only to read `projectId` from the path and enforce.

The token (when present) also carries `projectId`. When both the path and a
token are present, they **must match** — defence in depth.

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

- **Anonymous enforcement** depends on the project-in-URL routing above: until
  GraphQL is project-scoped, a token-less request has no project and engine RLS
  is skipped (tables with engine-only policies are exposed). Closing this is the
  motivation for `/{projectId}/graphql`.
- **Missing custom claim**: currently throws (typo protection). Postgres
  `current_setting(x, true)` returns NULL (fail-closed). Decide whether an absent
  *custom* claim should resolve to null to match Postgres.
- **Realtime relationship predicates**: the in-memory matcher can't probe a
  second table; needs a DB lookup (or claim-based modelling) — see subscriptions.
