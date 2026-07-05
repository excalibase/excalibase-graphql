# RLS/CLS Security Audit — findings & status

Adversarial line-by-line audit of every data path (2026-07-06). Verified-correct
items: identifier safety, bind-param namespacing, anonymous fail-closed, project
path/token binding, ThreadLocal lifecycle, top-level write WITH-CHECK, alias
correlation on compiled paths. Gaps below, severity-first.

| ID | Severity | Gap | Status |
|----|----------|-----|--------|
| C1 | CRITICAL | REST reads apply **no column masking** — HIDE/NULL columns returned in full over `GET /{p}/api/v1/{table}` (list/singular/CSV/cursor/embed) | ✅ |
| C2 | CRITICAL | GraphQL WS subscriptions apply **neither row-filter nor column-mask** — subscriber gets every user's events | ✅ |
| H3 | HIGH | Realtime WS subscriptions mask columns but **don't filter rows** | ✅ |
| H4 | HIGH | Hidden/masked columns remain **filterable & orderable** (inference oracle) — GraphQL + REST | ✅ |
| H5 | HIGH | Nested-FK **child inserts bypass WITH-CHECK** (GraphQL `buildChildInsertCte`) | ✅ |
| H6 | HIGH | **Upsert / ON CONFLICT DO UPDATE has no USING** — can overwrite another owner's row (REST + GraphQL) | ✅ |
| M7 | MEDIUM | GraphQL nested-embed **relationship/EXISTS correlation broken under aliasing** (`appendNestedRls` passes alias=null) | ✅ |
| M8 | MEDIUM | Default `jwt-enabled=false` disables ALL RLS — no loud warning when policies exist | ⬜ |
| L9 | LOW | GROUP-assigned policies never match (`groupIds()` always empty) | ⬜ |
| L10 | LOW | Empty-rule ALLOW: DENY_ALL on read vs permit on WITH-CHECK (inconsistent) | ⬜ |
| L11 | LOW | RPC no in-body RLS (auth-gated only — documented, PostgREST/Hasura parity) | ✅ accepted |

Priority order to fix: **C1 → C2/H3 → H4 → H5 → H6 → M7 → M8**.

### Adjacent (non-security) issue surfaced while fixing H5

Nested-FK **child inserts don't cast UUID/typed columns** — the child CTE binds
data-column params as `varchar`, so a nested insert into a child table with a
`uuid` (or enum) column fails with *"column X is of type uuid but expression is
of type character varying"*. Pre-existing, not a security gap; the top-level and
bulk insert paths apply `getEnumCastForMutation` but `buildChildInsertCte` does
not. Fix: thread the same enum/type cast into the child SELECT value list.
