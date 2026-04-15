# Choose Your Protocol — GraphQL or REST?

Excalibase exposes every table, view, and stored procedure through **two
co-equal protocols**: GraphQL and REST. Both hit the same Postgres schema,
both get the same auth + multi-schema routing, both ship in every release.
**Pick whichever fits your app, or use both** — they compose.

This page exists because the two protocols have genuinely different
strengths. The sections below help you pick with confidence instead of
guessing.

## TL;DR — by scenario

| If you're building… | Pick | Why |
|---|---|---|
| A typed React / Vue / Svelte app | **GraphQL** | Codegen → IDE autocomplete → compile-time safety |
| A dashboard loading 5+ widgets | **GraphQL** | Co-fetch: one request returns data for every panel |
| A public feed behind a CDN | **REST** | Cacheable GETs with long TTLs, no query parser on the edge |
| A CLI / curl / ad-hoc script | **REST** | No client library, URL-shareable, inspectable |
| A mobile app with offline sync | **REST** | Easier HTTP cache inspection, `Range` headers for pagination |
| Aggregate queries (count + sum + avg + min + max) | **GraphQL** | Native `<Table>Aggregate` field returns all 5 in one call |
| JSONB path queries | **Both work** | GraphQL's `JsonFilterInput` or REST's `haskey` / `jsoncontains` |
| Array (`text[]`/`int[]`) filters | **REST** | `arraycontains` / `arrayhasany` / `arrayhasall` — GraphQL array filter types are v0.2 |
| Range queries on timestamp columns | **Both work** | GraphQL `DateTimeFilterInput` or REST `gt`/`lt` chains |
| Relay cursor pagination | **GraphQL** | Native `<Table>Connection { edges { node cursor }, pageInfo, totalCount }` |
| Content-Range header pagination | **REST** | Native `Prefer: count=exact` + `Range` header support |
| Real-time subscriptions | **GraphQL** | Only surface with WebSocket subscriptions (via excalibase-watcher) |
| Server-side rendering (Next.js SSR) | **Either** | REST is slightly simpler — no GraphQL client in SSR context |
| Bulk upsert from a CSV | **REST** | `POST` + `Prefer: resolution=merge-duplicates` handles it natively |

## You'll prefer **GraphQL** when…

- **Type safety matters.** Generate TypeScript types from the schema via
  [graphql-codegen](https://the-guild.dev/graphql/codegen) and get full
  IDE autocomplete + compile-time errors on bad queries.
- **You need nested projection.** `{ issue { project { name } assignee { email } } }`
  returns nested shapes in one request. REST returns flat rows — nested
  reads need N+1 calls.
- **You're calling multiple tables in one view.** GraphQL compiles the
  whole document to a single SQL statement with nested `jsonb_build_object`
  subqueries — one network round trip, one query plan.
- **You want aggregate functions in the same response as rows.** `<Table>`
  and `<Table>Aggregate` co-exist in one document. REST needs
  `Prefer: count=exact` for total only — no sum/avg/min/max.
- **Column types should be narrowed.** GraphQL filter inputs use typed
  operators: `status: KanbanIssueStatusFilterInput` only accepts valid
  enum members, caught at query-parse time. REST values are stringly-typed.
- **You're using Relay-style cursors.** `<Table>Connection(first, after)` is
  the standard Relay spec shape with `{ edges, pageInfo, totalCount }`.
- **Subscriptions.** Real-time change feeds only ship as GraphQL
  subscriptions (see [Real-Time Subscriptions](features/subscriptions.md)).

## You'll prefer **REST** when…

- **HTTP caching is worth more than shape control.** A GET with a fixed
  URL can sit behind a CDN, honor `Cache-Control` headers, and survive
  without a query parser. POSTs (which GraphQL uses) can't.
- **You want language-agnostic access.** Every HTTP client in every
  language can hit REST. GraphQL requires a client library or hand-rolled
  `POST /graphql` with JSON.
- **curl / inspection is part of your workflow.** REST URLs are shareable,
  debuggable, and tell the full story in the browser address bar.
- **You're paginating through a huge table.** `Range` headers +
  `Content-Range` responses are the PostgREST-native way to page through
  results without URL changes, and cursor-style `id=gt.N` is O(1) per page.
- **You need bulk upsert.** `POST /api/v1/table` with `Prefer:
  resolution=merge-duplicates` handles bulk insert-with-conflict in one
  request. GraphQL mutations are per-row.
- **You want small-surface server-side rendering.** Next.js `getStaticProps`
  / Astro / server components can `fetch()` REST directly without adding
  a GraphQL client to the bundle.

## Neither — it's a tradeoff

| Concern | GraphQL | REST |
|---|---|---|
| Over-fetch | Avoided (explicit projection) | Default unless `?select=cols` |
| Under-fetch / N+1 | Avoided (nested projection) | Common — fetch related tables separately |
| Request size | Large query doc POST | Small URL GET |
| Response shape | Nested | Flat |
| Runtime discovery | `__schema` introspection | No standard spec |
| HTTP caching | Hard (POST, varying body) | Easy (GET, URL-keyed) |
| Client library required? | Usually (graphql-request, Apollo, urql) | No |

## Via the SDK

The [`@excalibase/sdk`](https://github.com/excalibase/excalibase-sdk-js)
exposes **both protocols from one client** with shared auth, session, and
error handling. You don't have to pick project-wide — pick per-call:

```ts
import { createClient } from "@excalibase/sdk";

const db = createClient({ url, projectId, publishableKey });

// GraphQL — shape control, typed, aggregates
const dashboard = await db.graphql.query(`
  { 
    issues(where: { status: { eq: todo } }, limit: 10) { id title priority }
    projects { id name }
    issuesAggregate { count sum { story_points } }
  }
`);

// REST — cacheable, simple, fast
const issues = await db.rest.get("/issues?status=eq.todo&limit=10");
```

Both calls go through the same auth lifecycle (auto-refresh, session
persistence, header folding). You're not locked in either direction.

## Mixed strategies — use both

The cleanest apps use GraphQL and REST side by side, each for what it's good at:

- **REST** for public, cacheable reads (blog posts, product catalogs,
  unauthenticated landing pages)
- **GraphQL** for the authenticated app (dashboards, admin panels, mobile
  after login)
- **REST** for CSV exports, bulk imports, webhooks, and server-to-server
- **GraphQL** for anything that needs nested data or typed codegen

## Feature compatibility matrix

| Feature | GraphQL | REST | Notes |
|---|---|---|---|
| Read tables + views | ✅ | ✅ | |
| `eq`/`neq`/`gt`/`gte`/`lt`/`lte` | ✅ | ✅ | Identical semantics |
| `in` / `notIn` | ✅ | ✅ | REST uses lowercase `notin` |
| `isNull` / `isNotNull` | ✅ | ✅ (`is.null` / `is.notnull`) | Different syntax |
| `like` / `ilike` | ✅ (`%` wildcard) | ✅ (`*` wildcard) | Spelling differs |
| `contains` / `startsWith` / `endsWith` | ✅ | ✅ (`startswith` / `endswith`, lowercase) | |
| Regex (`regex` / `iregex`) | ✅ | ✅ (`match` / `imatch`) | |
| Enum typed filter narrowing | ✅ | ❌ | GraphQL knows enum members at parse time |
| JSONB containment (`@>` / `<@`) | ✅ | ✅ | `JsonFilterInput.contains` ↔ REST `jsoncontains` |
| JSONB `hasKey` / `hasKeys` / `hasAnyKeys` | ✅ | ✅ | |
| JSONB path existence | ✅ (v0.2) | ✅ (`jsonpath`) | |
| Array contains / has-any / has-all | v0.2 | ✅ (`arraycontains`, `arrayhasany`, `arrayhasall`) | |
| Full-text `search` / `webSearch` / `phrase` / `raw` | ✅ | ✅ (`plfts`, `wfts`, `phfts`, `fts`) | Different names, same semantics |
| Vector k-NN | ✅ (top-level `vector` arg) | ✅ (`vector.{json}`) | |
| Insert / Update / Delete | ✅ (typed mutations) | ✅ (POST / PATCH / DELETE) | |
| Bulk upsert | ✅ (`createMany<Table>`) | ✅ (`Prefer: resolution=merge-duplicates`) | |
| Returning rows from writes | ✅ (mutation return type) | ✅ (`Prefer: return=representation`) | |
| Transaction rollback (dry run) | ❌ | ✅ (`Prefer: tx=rollback`) | |
| Stored procedures | ✅ (`call<Proc>` mutations) | ❌ | Call via GraphQL |
| Aggregate (count / sum / avg / min / max in one call) | ✅ (`<Table>Aggregate`) | ❌ (only `count=exact`) | |
| Relay cursor pagination | ✅ (`<Table>Connection`) | ❌ (simulate with `id=gt.N`) | |
| `Range` / `Content-Range` header pagination | ❌ | ✅ | |
| Schema introspection | ✅ (`__schema`) | ❌ (no OpenAPI today) | |
| Real-time subscriptions | ✅ (WebSocket) | ❌ | |
| FK traversal (nested projection) | ✅ | ❌ | |
| Co-fetch multiple tables | ✅ | ❌ (N requests) | |
| HTTP caching | ❌ (POST) | ✅ (GET with URL keys) | |

## Migration between protocols

Both protocols read the same schema. **You can start with one and add the
other later without rewriting anything** — just point new code at the
other surface. The SDK exposes both namespaces, so a single app can use
REST for the public landing page and GraphQL for the admin panel without
any configuration change.

## Still not sure?

- **Default to GraphQL** if your frontend is a typed SPA and you care about
  IDE autocomplete + schema validation.
- **Default to REST** if you care more about HTTP caching, URL sharing, or
  language-agnostic access.
- **Use both** if you have an authenticated app (GraphQL) with public
  read endpoints (REST).

Neither choice is permanent. Switch per-call in the SDK, per-route in your
backend, or mix freely. The schema doesn't change underneath you.
