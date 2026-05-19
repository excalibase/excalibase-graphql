# Postgres Role Switching

## Overview

When enabled, excalibase issues `SET LOCAL ROLE "<role>"` inside the per-request
transaction, mapping the verified JWT to a Postgres role. Every query runs as
a least-privileged role, and forgetting to write an RLS policy on a table
becomes "permission denied" instead of a silent leak.

This layers **on top of** the existing JWT-based RLS context (the
`request.user_id` / `request.project_id` / `request.role` `set_config` flow at
`/features/user-context-rls`). Nothing about that flow changes — role switching
plus session vars run inside the same transaction.

## How it works

```
HTTP request  →  JwtAuthFilter (verify JWT)
                ↓
              GraphqlController
                ↓
              QueryExecutionService.executeInContext
                  conn = dataSource.getConnection()
                  setAutoCommit(false)
                  set_config('request.user_id',    claims.userId,    true)
                  set_config('request.project_id', claims.projectId, true)
                  set_config('request.role',       claims.role,      true)
                  SET LOCAL ROLE "<resolved_role>"        ← NEW
                  <run user query>
                  COMMIT  (resets the role and config)
```

The role is resolved from the JWT's `scope` claim, populated by
`excalibase-auth` based on which credential the client exchanged:

| JWT `scope` | Issued by                                      | Maps to Postgres role           |
|-------------|------------------------------------------------|---------------------------------|
| `public`    | `publishable` API key (`esk_pub_live_…`)       | `anon-role`                     |
| `authenticated` | password / refresh-token grant             | `authenticated-default-role` (or `claims.role` if in allowlist) |
| `service`   | `secret` API key (`esk_sec_live_…`)            | `service-role` (use `BYPASSRLS`) |
| absent / `null` | legacy tokens minted before the scope claim was added | treated as `authenticated`      |

## Configuration

```yaml
app:
  security:
    postgres:
      role-switching:
        # Required — presence enables the feature.
        anon-role: app_anon

        # Required when scope=authenticated reaches the resolver.
        authenticated-default-role: app_authenticated

        # Required when scope=service reaches the resolver.
        service-role: app_service

        # Optional — allows JWTs to claim a custom Postgres role beyond the
        # default authenticated role (e.g. app_admin, app_analytics). Roles not
        # in the allowlist produce a 403 before any SQL runs.
        allowed-roles:
          - app_admin
          - app_analytics
```

The feature is **off by default** — leave `anon-role` unset and the data plane
behaves exactly as before.

## Bootstrap SQL

A template lives at
`modules/excalibase-graphql-api/src/main/resources/sql/role-switching-bootstrap.sql`.
The minimum setup is:

```sql
CREATE ROLE app_anon          NOLOGIN;
CREATE ROLE app_authenticated NOLOGIN;
CREATE ROLE app_service       NOLOGIN BYPASSRLS;
GRANT app_anon, app_authenticated, app_service TO :pool_user;
GRANT USAGE ON SCHEMA public TO app_anon, app_authenticated, app_service;
```

The login user must be GRANTed every role you want excalibase to be able to
switch to — Postgres `SET ROLE` only works between roles the connecting user
has membership in.

## Wallet pattern (anon + authenticated overlap)

Common RLS shape — anon sees public rows, authenticated user sees own
+ public:

```sql
GRANT SELECT ON public.wallet TO app_anon, app_authenticated;

ALTER TABLE public.wallet ENABLE ROW LEVEL SECURITY;

CREATE POLICY wallet_public_read ON public.wallet
    FOR SELECT TO app_anon, app_authenticated
    USING (is_public = true);

CREATE POLICY wallet_owner_read ON public.wallet
    FOR SELECT TO app_authenticated
    USING (user_id = (select current_setting('request.user_id', true)));
```

| Caller                  | Sees                                          |
|-------------------------|-----------------------------------------------|
| anon (`scope=public`)   | rows where `is_public = true`                 |
| user 'duc' (`authenticated`) | own rows AND `is_public = true` (OR-merged) |
| `service_role` JWT      | every row (`BYPASSRLS`)                       |

Note the `(select current_setting(...))` wrap — Postgres caches the function
call once per query instead of per row, a established Postgres performance
trick.

## SDK contract

The TypeScript SDK already supports this model — no client changes required:

| State                                  | SDK action                                     | Server resolves to                                    |
|----------------------------------------|------------------------------------------------|--------------------------------------------------------|
| `createClient({ url, projectId, publishableKey })` | (lazy)                          | —                                                      |
| First query — exchanges `publishableKey` for `scope=public` JWT | `Authorization: Bearer <jwt>` | `app_anon`                                             |
| `db.auth.signInWithPassword(...)` succeeds         | new JWT with `scope=authenticated`             | `app_authenticated` (or claimed role from allowlist)  |
| `db.auth.signOut()`                                | reverts to public-scope JWT                    | `app_anon`                                             |

Anon authentication uses a per-project `publishable` API key minted by
`excalibase-auth` — short-lived JWTs with revocation/audit/rate-limit
properties.

## Validation

Two layers of defense:

1. **App-side allowlist** — JWT `role` claims are checked against
   `allowed-roles`. Unknown roles produce HTTP 403 before any SQL runs.
2. **Postgres GRANT** — even an allowlisted role is only effective if the pool
   user has been GRANTed it. Misconfiguration produces "permission denied"
   from Postgres rather than a privilege escalation.

The role identifier is also matched against
`^[A-Za-z_][A-Za-z0-9_]{0,62}$` to defend `SET ROLE` (which cannot use
parameter placeholders) against injection in case an operator pastes a hostile
value into config or a JWT issuer is compromised.

## How REST + GraphQL share the resolved role

`JwtAuthFilter` calls the resolver **once per request** after JWT
verification and stores the result in a `RoleContext` ThreadLocal
(`io.github.excalibase.security.RoleContext` in the starter module). Both
data-plane consumers read from this single source of truth:

- `QueryExecutionService.executeInContext` (GraphQL) — emits
  `SET LOCAL ROLE` inside its manual transaction.
- `RestApiController.setRlsContext` (REST) — emits `SET LOCAL ROLE` inside
  the `TransactionTemplate` after the existing `set_config` calls.

This avoids a Maven dependency cycle (rest-api → graphql-api) and means new
data-plane surfaces only need to read `RoleContext.getRole()` to participate.

403 handling: when the resolver throws `RoleNotAllowedException`, the filter
writes 403 directly (because `@RestControllerAdvice` doesn't reach servlet
filters). Both REST and GraphQL clients see consistent 403 status.

## Limitations / known gaps

- **REST `/rpc/{function}`** still runs without `request.*` session vars
  (pre-existing gap, separate from this feature). Role switching does fire
  for it because the resolved role is set globally per request via
  `RoleContext`, but the RLS user-id session variable is not.
- **Schema introspection cache is per-tenant, not per-role.** Anon users still
  see the same GraphQL type set as authenticated users — empty results, but
  the type name is exposed. Adding per-role cache keys is deferred.

## Replaces

This feature supersedes the aspirational `role-based-schema` flag and
`X-Database-Role` header described in the now-archived
`/archive/role-based-security` doc. Those concepts were never implemented;
role switching is the implemented replacement.

## Live demos

Two polished React + TypeScript + Tailwind apps in the SDK repo,
both driven by the same role-switching infra and demonstrating
different product-shaped UIs:

- **[`excalibase-sdk-js/examples/jira-board`](https://github.com/excalibase/excalibase-sdk-js/tree/main/examples/jira-board)**
  — Jira/Linear-style kanban board on the `kanban` schema. Drag-drop
  cards across columns trigger REST mutations whose row-level
  visibility depends on the active identity.
- **[`excalibase-sdk-js/examples/storefront`](https://github.com/excalibase/excalibase-sdk-js/tree/main/examples/storefront)**
  — Shopify-style storefront on the `shopify` schema. Anon browses,
  customers cart and checkout (RLS-scoped to their own `customer_id`),
  admin role unlocks an inventory editor.

Schema, RLS policies, docker stack, and JWT signing key live in this
repo under `e2e/study-cases/`. Quick start (with `excalibase-sdk-js`
checked out as a sibling repo):

```bash
make demo-jira    # → http://localhost:5175
make demo-shop    # → http://localhost:5176
```

Both share the same identity switcher (anon / alice / carol / admin)
and live footer pill that proves which Postgres role the data plane
is actually running queries under via `SELECT current_user FROM <schema>.whoami_view`.
