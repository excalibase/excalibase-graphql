# NoSQL — HLD v2 Alignment Plan

Purpose: close the gaps between `excalibase-nosql` as shipped today and the HLD v2 doc, without arbitrary defaults or silent behavioral drift.

## Scope decisions

- **Item #5 (`updated_at` trigger) — removed from scope.** Java-level manual handling in `DocumentQueryCompiler` stays (`updateOne/updateMany/setEmbedding` each set `updated_at = clock_timestamp()`). Feels "auto" from the user's perspective and works for all app paths. Only gap: raw SQL writes bypass it — acceptable.
- **Phase B (stats advisor + `pg_stat_statements`) — deferred to `excalibase-provisioning` integration.** See [Deferred — provisioning integration](#deferred--provisioning-integration) section below. Reason: provisioning already owns `pg_stat_statements` for per-tenant metrics; enabling it twice creates two sources of truth.
- **Phase F (docs) — splits README into a menu + full pages under `docs/nosql/`.** README becomes a short index that links out.

---

## Phase 1 — Files read (source of truth)

| File | What it told me |
|------|-----------------|
| `modules/excalibase-nosql/.../schema/CollectionSchemaManager.java` | Hard cap `MAX_INDEXES=10`, non-concurrent CREATE INDEX, index name `idx_/uidx_{coll}_{fields}` — type not encoded in name, `createExpressionIndex` switch over `string/number/boolean` only |
| `modules/excalibase-nosql/.../compiler/DocumentQueryCompiler.java` | `updateOne/updateMany/setEmbedding` set `updated_at` manually (3 places). `compileFind` uses `?limit=/?offset=` pagination |
| `modules/excalibase-nosql/.../controller/NoSqlController.java` | Reserved params `limit/offset/sort/search/vector/count/stats`. No subscription path |
| `modules/excalibase-nosql/.../model/CollectionSchema.java` | Record: `name, fields, indexes, indexedFields, searchField, vector`. No `jsonSchema` field |
| `modules/excalibase-nosql/.../schema/NoSqlIdentifiers.java` | `IDENT_PATTERN = ^[a-zA-Z_]\w{0,62}$`. All DDL goes through `safeIdent` |
| `modules/excalibase-nosql/pom.xml` | No JSON Schema validator dep. No standalone WebSocket dep |
| `modules/excalibase-graphql-starter/.../cdc/SubscriptionService.java:65` | Routes by `schema_table` key → schema-agnostic. `nosql.articles` already lands at key `nosql_articles` |
| `modules/excalibase-graphql-starter/.../cdc/NatsCDCService.java` | Multi-callback `schemaReloadCallbacks` list. Routes all events through `SubscriptionService` |
| `modules/excalibase-graphql-starter/.../cdc/CDCEvent.java` | `record(type, schema, table, data, timestamp)`. `type ∈ INSERT/UPDATE/DELETE/DDL/HEARTBEAT` |
| `modules/excalibase-graphql-api/.../config/ws/GraphQLWebSocketHandler.java` | graphql-transport-ws pattern. Per-session `Map<subId, Disposable>` for reactor cleanup |
| `modules/excalibase-graphql-api/.../config/ws/WebSocketConfig.java` | Custom `HandlerMapping` keyed on `Upgrade` + URI. Add new mapping for `/api/v1/realtime` |
| `e2e/subscription.test.js` | `subscribeGraphQL(PG_WS, query)` helper proves WAL→NATS→WS path works |

### MEMORY.md signals applied

- `feedback_no_silent_deferral.md` — Java IT + e2e must all exercise the runtime path
- `feedback_commit_style.md` — one-line conventional commits
- `feedback_run_dont_ask.md` — for builds/tests, just run with `-T 10`

---

## Phase 2 — Gap matrix (in-scope)

| # | HLD v2 claim | Current state | Risk | Assumption to verify |
|---|--------------|---------------|------|----------------------|
| 1 | Drop arbitrary index count warning | Uncommitted diff has `WARN_INDEX_COUNT=8` (rejected) | L | No std PG tool uses count threshold — real signals live in `?stats` (deferred with Phase B) |
| 2 | `type:"array"` → GIN on `data->'field'` | Only `string/number/boolean` branches | L | GIN `jsonb_ops` default; partial predicate `data->'field' IS NOT NULL` still valid |
| 3 | `CREATE INDEX CONCURRENTLY` | Plain CREATE INDEX (blocks writes) | **H** | Must run outside transaction. HikariCP `autoCommit=true` default; `NoSqlController.syncSchema` has no `@Transactional` |
| 4 | Reject silent index type change | Name match only → drift silently | M | Unique-toggle path already drops+creates via name change. Type-change must reject with clear error |
| 6 | Cursor pagination (opaque base64) | `?offset=N` | M | **Breaking API change if default flipped**; mitigate via `?paginate=cursor` opt-in |
| 7 | JSON Schema validation (Java level) | `fields` map accepted but ignored | L | `com.networknt:json-schema-validator` Draft 2020-12, Jackson-compatible |
| 8 | `/api/v1/realtime` WS for REST+NoSQL | Only graphql-transport-ws exists | **H** | `SubscriptionService.publish()` is schema-agnostic. Watcher WAL coverage for `nosql.*` is **UNVERIFIED** |

### Uncommitted change — decision

**Revert entirely** (`git checkout -- modules/excalibase-nosql/.../CollectionSchemaManager.java`). Reasons:
1. `WARN_INDEX_COUNT=8` was explicitly rejected
2. Couples unrelated concerns (cap removal + trigger + ensureUpdatedAtFunction) — can't bisect
3. Item #5 (trigger) is now out of scope, so the trigger work in that diff is dead code

---

## Phase 3 — Assumptions, sourced

1. **SubscriptionService routes by `schema_table` key** — `SubscriptionService.java:65`. Events on `nosql.articles` land at key `nosql_articles`.
2. **`JdbcTemplate.execute()` is autocommit** — Spring + HikariCP default. `NoSqlController.syncSchema` has no `@Transactional`.
3. **`CREATE INDEX CONCURRENTLY IF NOT EXISTS` valid on PG 9.5+** — PG docs.
4. **Existing tests don't lock offset semantics** — `e2e/nosql.test.js` has `limit` tests; `FindOptions` used only in Java IT.
5. **Watcher emits WAL for `nosql` schema** — **UNVERIFIED.** Mitigation: Phase E starts with IT that directly publishes `CDCEvent(schema="nosql", ...)`, bypassing watcher.
6. **`com.networknt:json-schema-validator` Draft 2020-12 compat with Jackson 2.16+** — lib README. Our Jackson pin (2.21.2) is newer.

---

## Phase 4 — Plan

### Phase A — Revert + index correctness (4 commits, ~1h)

**A.0: Revert uncommitted change.** `git checkout -- modules/excalibase-nosql/src/main/java/io/github/excalibase/nosql/schema/CollectionSchemaManager.java`.

**A.1: `CREATE INDEX CONCURRENTLY` everywhere.**
- Files: `CollectionSchemaManager.java` — `createExpressionIndex`, `addSearchColumn`, `addVectorColumn`, `syncIndexes` drop branch
- Change: `CREATE INDEX IF NOT EXISTS` → `CREATE INDEX CONCURRENTLY IF NOT EXISTS`
- Change: `DROP INDEX IF EXISTS` → `DROP INDEX CONCURRENTLY IF EXISTS`
- Risk: **H** — CONCURRENTLY can't run inside a transaction
- Verify: existing 16 IT + 1 new IT calling `syncSchema` inside `TransactionTemplate` asserts it errors cleanly.

**A.2: `type:"array"` → GIN.**
- File: `CollectionSchemaManager.java:createExpressionIndex` switch
- Add `case "array"` → `USING gin ((data->'{field}'))`, partial `WHERE data->'{field}' IS NOT NULL`
- Risk: L
- Test: new IT `syncSchema_arrayIndex_createsGin`.

**A.3: Reject index type change.**
- File: `CollectionSchemaManager.java:syncIndexes`
- Parse existing `indexdef` to infer current type (`::numeric` → number, `::boolean` → boolean, `USING gin` → array, else string). Declared != existing → throw `IllegalArgumentException`: *"Cannot change index type on field 'X' from 'string' to 'number'. Drop it from declaration and sync, then re-add with new type."*
- Risk: M — must round-trip without false positives
- Test: new IT `syncSchema_changeIndexType_rejects`.

### Phase C — Cursor pagination (1 commit, ~1h)

**C.1: Opt-in cursor mode.**
- Files: `DocumentQueryCompiler.java`, `FindOptions.java`, `NoSqlController.java`
- Cursor = opaque base64 of `{createdAt: iso, id: uuid}`
- Default order: `ORDER BY created_at DESC, id DESC`
- Cursor WHERE: `(created_at, id) < (:cursorTs, :cursorId)`
- Feature-flag: `?paginate=cursor` opt-in (default off — **no breaking change**)
- Response (opt-in only): `{docs: [...], cursor: "base64..." | null}`
- `?offset=N` remains for legacy path
- Risk: M — shape differs under flag
- Test: e2e walks 30-row seed in pages of 10, asserts no overlap/gap.

### Phase D — JSON Schema validation (1 commit, ~1h)

**D.1: Validate on write (Java level, Draft 2020-12).**
- Files: `modules/excalibase-nosql/pom.xml`, new `.../schema/JsonSchemaValidator.java`, `CollectionSchemaManager.java`, `NoSqlController.java`, `CollectionSchema.java` (add `jsonSchema` field)
- Dep: `com.networknt:json-schema-validator:1.5.8`
- Accept `{collections: {X: {schema: {<JSON Schema>}, indexes:[...]}}}`
- Compile once at sync, cache in `CollectionInfo`
- Validate before `compileInsertOne/InsertMany/UpdateOne/UpdateMany`
- 400 shape: `{error: "validation", issues: [{path, message}]}`
- Risk: L — additive
- Test: IT + e2e for valid pass / invalid 400.

### Phase E — Unified realtime endpoint (1 commit, ~3-4h)

**E.1: `/api/v1/realtime` WebSocket for REST + NoSQL.**
- Files: new `RealtimeHandler.java`, edit `WebSocketConfig.java`
- Protocol:
  ```
  client → {type:"subscribe", id:"s1", source:"rest"|"nosql", collection:"orders", filter:{status:"active"}}
  server → {type:"next", id:"s1", op:"insert"|"update"|"delete", doc:{...}}
  client → {type:"complete", id:"s1"}
  ```
- Key: `source=="nosql"` → `nosql_{collection}`; `source=="rest"` → `public_{collection}` (introspect schema)
- Filter: Jackson tree match on `CDCEvent.data`, `eq` on single field for v1
- Map `type`: INSERT→insert, UPDATE→update, DELETE→delete; skip DDL/HEARTBEAT
- Risk: **H** — new protocol + WS lifecycle, watcher coverage unverified
- Phased test: IT direct-publishes `CDCEvent` (skips watcher), then e2e against live stack.
- Test: IT `realtime_nosqlInsert_deliversEvent`; e2e `subscribe-insert-verify`.

### Phase F — Docs restructure (1 commit, ~1h)

**F.1:** Split `modules/excalibase-nosql/README.md` (currently full reference) into a short menu + topic pages.
- New: `docs/nosql/schema.md` — schema declaration, index types (incl. `array`), unique, `search`, `vector`
- New: `docs/nosql/crud.md` — CRUD endpoints + filter operators
- New: `docs/nosql/search-vector.md` — FTS + vector endpoints
- New: `docs/nosql/pagination.md` — offset (legacy) + cursor (opt-in)
- New: `docs/nosql/validation.md` — JSON Schema validation
- New: `docs/nosql/realtime.md` — `/api/v1/realtime` WS protocol
- Rewrite `modules/excalibase-nosql/README.md` as a **menu**: one-line intro + links to each doc page above
- Update top-level repo `README.md` (or docs index) to point here if needed

---

## Deferred — provisioning integration

Moved out of the in-scope plan above but tracked here so nothing gets lost.

### Stats advisor with `pg_stat_statements`

**Why deferred:** `excalibase-provisioning` already needs `pg_stat_statements` enabled on every tenant Postgres for its own per-tenant observability (query cost, slow-query alerts). Enabling it independently in `excalibase-graphql`'s dev stack would create two sources of truth and risk drift. The correct integration point is **provisioning**, which already owns the DB lifecycle.

**Read-only analysis (confirmed):**
- `pg_stat_statements` view — pure SELECT
- `pg_stat_user_indexes` view — pure SELECT (already used in current `?stats`)
- `pg_class.reltuples` — pure SELECT (already used)
- `EXPLAIN ANALYZE` — executes the query (NOT read-only against user data). Not needed for v1 advisor — skip.

So the **code side** (B.2) is purely read-only and could ship independently, gracefully degrading when the extension is absent. **Infra side** (B.1) is where the coupling with provisioning lives.

### Future integration shape (not yet designed)

1. Provisioning enables `pg_stat_statements` on every tenant DB at provision time (single place of truth).
2. `excalibase-graphql`'s `?stats` endpoint probes for the extension: if present, reads `pg_stat_statements` filtered to `nosql.*` queries; if absent, returns the current minimal stats only.
3. Decide whether `?stats` proxies to a provisioning API instead of reading the view directly — avoids having the GraphQL service know about cross-tenant query metrics.

**Next step (when this is picked up):** design doc in `docs/plans/` covering the provisioning-graphql split, then a cross-repo implementation plan.

---

## Tests & success criteria

Per phase:
- **A.1:** 16 existing IT + 1 new concurrency guard
- **A.2:** 16 + 1 new (GIN expression in `pg_indexes`)
- **A.3:** 16 + 1 new negative
- **C.1:** + 3 e2e (pages, empty, end)
- **D.1:** + 2 (valid/invalid)
- **E.1:** + 1 IT direct-publish + 1 e2e live
- **F.1:** manual review — all README links resolve

### Success criteria

- [ ] `mvn -pl modules/excalibase-nosql test` all green
- [ ] `make e2e` + kanban e2e all green
- [ ] `?paginate=cursor` round-trips 30 rows across 3 pages without overlap/gap
- [ ] Schema-violating insert returns 400 with `issues`
- [ ] WS subscribe receives `{op:insert,doc}` within 2s of matching insert
- [ ] `docs/nosql/*.md` files exist and are linked from `modules/excalibase-nosql/README.md`

### Rollback per phase

- **A.1:** revert commit; SQL-only change
- **C.1:** revert commit; behind flag
- **D.1:** revert commit; schema field was additive
- **E.1:** revert commit; WS clients reconnect cleanly
- **F.1:** revert commit; docs-only

---

## Execution order

**A.0 → A.1 → A.2 → A.3 → C.1 → D.1 → E.1 → F.1**

Total ~6-7 hours across 8 commits. Each phase commits independently.

---

## Phase 6 — Self-review

- [x] Every file path verified
- [x] Every assumption sourced or flagged UNVERIFIED
- [x] CONCURRENTLY risk has concrete mitigation (txn guard test)
- [x] Watcher coverage flagged UNVERIFIED with IT bypass
- [x] Breaking API change feature-flagged (cursor opt-in)
- [x] Deferred work preserved in dedicated section with integration rationale
- [x] No unsourced capability claims

Unknowns to resolve during execution:
- Watcher WAL coverage for `nosql.*` — verified at Phase E start
- Provisioning integration design for stats advisor — separate future plan
