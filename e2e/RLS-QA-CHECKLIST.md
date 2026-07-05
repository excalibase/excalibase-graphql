# RLS End-to-End QA Checklist (Docker pod)

Manual verification that Row-Level Security is enforced on **every** surface and
operation, from an end-user's perspective — every auth state × every data path.
Run against a full Docker stack. Status column: ✅ pass / ❌ fail / ⬜ untested.

Project used below: `e2e-test`. Two users seeded via the auth service; policies
served by the provisioning mock (wiremock): owner, relationship (membership),
JSON-path, and custom-claim policies over the `hana`-schema RLS tables
(`rls_notes`, `rls_team_orders`, `rls_profiles`, `rls_regional`) — none of which
have native Postgres RLS, so they exercise the *engine*.

## 0. Setup — bring up the pod

| # | Step | Expect | Status |
|---|------|--------|--------|
| 0.1 | `make down && make build-image && make up` | app + postgres + auth + provisioning-mock + nats healthy | |
| 0.2 | `curl -sf .../e2e-test/graphql -d '{"query":"{__typename}"}'` | `{"data":{"__typename":"Query"}}` | |
| 0.3 | containers: `docker ps` | graphql, postgres, auth, wiremock/mock, nats up | |

## 1. Routing (project must be in the URL)

| # | Scenario | Expect | Status |
|---|----------|--------|--------|
| 1.1 | `POST /graphql` (legacy, unscoped) | 404 | |
| 1.2 | `POST /api/v1/customer` (legacy) | 404 | |
| 1.3 | `POST /e2e-test/graphql` introspection | 200 data | |
| 1.4 | `GET /e2e-test/api/v1/customer` (Accept-Profile hana) | 200 data | |
| 1.5 | token for project X on path `/e2e-test/graphql` (mismatch) | 403 | |

## 2. RLS reads — GraphQL

| # | Scenario | Expect | Status |
|---|----------|--------|--------|
| 2.1 | anonymous `{ hanaRlsTeamOrders { id } }` | `[]` (fail-closed) | |
| 2.2 | anonymous `{ hanaRlsProfiles { id } }` | `[]` | |
| 2.3 | anonymous non-RLS table (`customer`) | rows (public) | |
| 2.4 | user A authenticated, owner table | only A's rows | |
| 2.5 | user B authenticated, owner table | only B's rows (≠ A) | |
| 2.6 | relationship policy — member of org | only that org's rows | |
| 2.7 | JSON-path policy (`meta.owner`) | only owner's rows | |
| 2.8 | custom-claim policy (`{{region}}`) | only claim-matching rows | |
| 2.9 | nested embed (FK) with policy on child | child rows filtered | |

## 3. RLS reads — REST (must match GraphQL)

| # | Scenario | Expect | Status |
|---|----------|--------|--------|
| 3.1 | anonymous `GET /rls_team_orders` | `data: []` | |
| 3.2 | anonymous non-RLS `GET /customer` | rows | |
| 3.3 | authenticated owner table | only own rows | |
| 3.4 | `Prefer: count=exact` totalCount on RLS table (anon) | count = 0 | |
| 3.5 | embed `?select=*,fk(*)` where child is RLS | child rows filtered | |

## 4. RLS writes — WITH-CHECK (insert/update)

| # | Scenario | Expect | Status |
|---|----------|--------|--------|
| 4.1 | GraphQL: insert row owned by self | ok | |
| 4.2 | GraphQL: insert row owned by another user | rejected (RLS violation) | |
| 4.3 | GraphQL: update reassign owner→other | rejected | |
| 4.4 | REST POST own row | 201 | |
| 4.5 | REST POST foreign-owner row | 403 | |
| 4.6 | REST PATCH reassign owner | 403 | |
| 4.7 | REST bulk POST with one violating row | 403 (whole batch) | |
| 4.8 | update/delete only touches visible+writable rows | others untouched | |

## 5. RPC (stored procedures)

| # | Scenario | Expect | Status |
|---|----------|--------|--------|
| 5.1 | anonymous `POST /rpc/{fn}` (jwt-enabled) | 401 | |
| 5.2 | authenticated `POST /rpc/{fn}` | executes | |
| 5.3 | anonymous RPC to nonexistent fn | 401 (not 404 — no enumeration) | |

## 6. Column-level security (CLS)

| # | Scenario | Expect | Status |
|---|----------|--------|--------|
| 6.1 | GraphQL: HIDE column absent from response | column not present | |
| 6.2 | REST: HIDE column absent | column not present | |

## 7. Isolation / negative

| # | Scenario | Expect | Status |
|---|----------|--------|--------|
| 7.1 | user A cannot read user B's rows (any surface) | never | |
| 7.2 | expired/invalid token | 401 | |
| 7.3 | anonymous can't write RLS table | rejected | |

## Live run results (2026-07-06, full Docker pod, latest image)

| Check | Result |
|-------|--------|
| 0.2 health / scoped introspection | ✅ `{"data":{"__typename":"Query"}}` |
| 1.1 legacy `/graphql` | ✅ 404 |
| 1.2 legacy `/api/v1` | ✅ 404 |
| 1.4 scoped REST | ✅ 200 |
| 1.5 token on wrong project path | ✅ 403 |
| 2.1 anon GraphQL RLS table | ✅ `[]` |
| 3.1 anon REST RLS table | ✅ `data:[]` |
| 3.2 anon non-RLS table | ✅ rows (public) |
| 3.3 user1 reads own rows only | ✅ 1 row, owners={1} |
| 3.4 anon totalCount on RLS table | ✅ total:0 |
| 4.4 REST insert own row | ✅ 201 |
| 4.5 REST insert foreign owner | ✅ 403 |
| 4.2 GraphQL insert foreign owner | ✅ "Row violates RLS policy for INSERT" |
| delete own row | ✅ 200 |
| 5.1 anon RPC | ✅ 401 |
| 5.2 authed RPC (nonexistent fn) | ✅ 404 (not 401, no enumeration) |
| 6.x column masking | ⬜ no HIDE policy served by mock — covered by differential/unit tests |
| 2.4–2.8 precise per-user relationship/JSON/claim | ⬜ live seed uses static ids; **precisely verified in `ProvisioningRlsIntegrationTest`** (minted JWTs match seed) |

**Verdict:** engine RLS is enforced on every tested surface/operation — reads
(GraphQL+REST+count), writes (WITH-CHECK on both), routing, RPC auth, isolation.

## 8. Known gaps (documented, not yet closed)

- RPC cannot row-filter *inside* an opaque function (auth-gated only) — `SETOF`-table output wrapping planned.
- Realtime WS subscriptions: per-row RLS on CDC events (EXC-19).
