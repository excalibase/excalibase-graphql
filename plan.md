# Excalibase NoSQL Document Store — High Level Design (v2)

**Service:** `excalibase-graphql` (Java/Spring Boot — unified GraphQL + REST gateway)  
**Status:** Draft  
**Date:** April 2026  
**Changes from v1:** Corrected service language (Java not Go), replaced custom table/index management with DocumentDB extension calls, added compatibility verification step, clarified cluster separation.

---

## 1. Overview

Excalibase NoSQL is a document store layer built on PostgreSQL + Microsoft DocumentDB extension that gives users a MongoDB-like DX without MongoDB's infrastructure cost or licensing constraints.

The core insight: our Java service (`excalibase-graphql`) does exactly what FerretDB does in Go — translates a document-oriented API into `documentdb_api.*` SQL function calls — but since we already have Java, we skip FerretDB as a 3rd process entirely.

### Goals

- MongoDB-like DX through the Excalibase client SDK
- Zero-DDL collection creation from the user's perspective (handled by DocumentDB extension internally)
- Schema and index declarations happen once at init time, never at runtime
- FTS and vector search natively on every collection
- Disk-first, minimal RAM — OS page cache handles hot data
- Multi-tenant isolation via separate CNPG clusters per tier
- **No override of existing relational cluster** — SQL and NoSQL on separate CNPG instances

### Non-goals

- MongoDB wire protocol compatibility (not needed — users use Excalibase SDK)
- TimescaleDB on the same PostgreSQL instance as DocumentDB
- In-memory reactive queries (Convex-style)
- Replacing the existing relational/SQL cluster

---

## 2. Critical Pre-Implementation Step: Compatibility Verification

**This must be completed before any implementation work begins.**

### 2.1 What to Verify

Even though DocumentDB and TimescaleDB will run on separate CNPG clusters in production, there are edge cases where a user might want both document and time-series features. We need to understand the boundaries clearly before committing to architecture decisions.

### 2.2 Verification Test Plan

**Test 1: Same PostgreSQL instance, separate databases**
```
CNPG cluster with both extensions installed:
  shared_preload_libraries = 'timescaledb, documentdb'

Database A: CREATE EXTENSION timescaledb;
Database B: CREATE EXTENSION documentdb_api CASCADE;

Expected: Both work independently, no hook conflict
Risk: Both hook into planner — load order matters
```

**Test 2: Planner hook chain**
```sql
-- After loading both, verify hook chain is intact
-- TimescaleDB and DocumentDB both use planner_hook
-- Well-written extensions save prev_hook and chain it
-- Verify neither extension breaks the other's query plans

-- Test TS query on hypertable in Database A
SELECT time_bucket('1 hour', time), avg(value)
FROM metrics
WHERE time > NOW() - INTERVAL '1 day'
GROUP BY 1;

-- Test DocumentDB query in Database B
SELECT documentdb_api.find('mydb', 'users', '{"status": "active"}');
```

**Test 3: Same database (worst case)**
```
Single database with BOTH extensions:
  CREATE EXTENSION timescaledb;
  CREATE EXTENSION documentdb_api CASCADE;

Run queries against both simultaneously under load.
Document any errors, performance degradation, or unexpected behavior.
```

**Test 4: CNPG operator compatibility**
```yaml
# Attempt CNPG cluster with both in shared_preload_libraries
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
spec:
  imageName: 'ghcr.io/ferretdb/postgres-documentdb:17-...'
  postgresql:
    parameters:
      shared_preload_libraries: "timescaledb,pg_documentdb_core,documentdb"
```

### 2.3 Expected Outcome and Decision Tree

```
Test passes (no conflict) →
  Option A: Single CNPG cluster per tenant with both extensions
            Simpler ops, one cluster to manage
  Option B: Still split clusters (keep concerns separate)
            Recommended even if compatible

Test fails (conflict detected) →
  Confirmed separate clusters required
  Document the conflict for user-facing documentation
  "TimescaleDB workloads: use SQL collections"
  "Document workloads: use NoSQL collections (separate endpoint)"
```

### 2.4 Acceptance Criteria

- [ ] Both extensions load without error in same PostgreSQL instance
- [ ] Planner hook chain verified intact (neither corrupts the other)
- [ ] No performance regression on TimescaleDB time-bucket queries
- [ ] No correctness issues on DocumentDB BSON queries
- [ ] CNPG cluster with both extensions reaches healthy state
- [ ] Result documented: compatible / incompatible / partially compatible

### 2.5 Verdict Impact on Architecture

Regardless of test outcome, the production architecture remains:

```
Current CNPG cluster (SQL)    → TimescaleDB, pgvector, VectorChord-BM25
                                  Relational data, time-series, existing features
                                  NO DocumentDB extension installed here

New CNPG cluster (NoSQL)      → DocumentDB extension only
                                  Document collections, BSON indexes
                                  NO TimescaleDB installed here
```

The verification test determines whether we can offer a "combined" tier in future, not whether we need split clusters today. **Split clusters is the default and non-negotiable for v1.**

---

## 3. Architecture

### 3.1 Cluster Separation (Non-negotiable)

```
Tenant (org_id: abc)
│
├── CNPG Cluster: excalibase-sql-abc
│   Purpose: Relational SQL workloads (existing)
│   Extensions: TimescaleDB, pgvector, VectorChord-BM25, pg_stat_statements
│   Used by: excalibase-rest (existing), excalibase-graphql (existing SQL features)
│   NOT changed by this feature
│
└── CNPG Cluster: excalibase-nosql-abc          ← NEW
    Purpose: NoSQL document workloads
    Image: ghcr.io/ferretdb/postgres-documentdb:17-...
    Extensions: pg_documentdb_core, documentdb (pg_documentdb_api)
    Used by: excalibase-graphql (new NoSQL features)
```

### 3.2 Service Architecture

```
┌─────────────────────────────────────────────────────┐
│              Client SDK                              │
│  TypeScript / Java / Go / Python                     │
│                                                      │
│  // SQL path (existing)                              │
│  db.query("SELECT * FROM users WHERE ...")           │
│                                                      │
│  // NoSQL path (new)                                 │
│  db.collection("users").find({ status: "active" })  │
└──────────┬────────────────────────┬─────────────────┘
           │ HTTP/WebSocket          │ HTTP/WebSocket
           ▼                        ▼
┌─────────────────────────────────────────────────────┐
│         excalibase-graphql (Java/Spring Boot)        │
│                                                      │
│  ┌──────────────────┐   ┌─────────────────────────┐ │
│  │  GraphQL Engine  │   │  REST Router            │ │
│  │  (existing SQL + │   │  /v1/sql/...  (existing)│ │
│  │   new NoSQL)     │   │  /v1/nosql/... (new)    │ │
│  └────────┬─────────┘   └────────────┬────────────┘ │
│           └────────────┬─────────────┘              │
│                        ▼                             │
│  ┌─────────────────────────────────────────────────┐│
│  │           Request Router                        ││
│  │  if nosql → DocumentDB Query Translator         ││
│  │  if sql   → existing SQL path (unchanged)       ││
│  └──────────┬──────────────────┬───────────────────┘│
│             │ SQL path          │ NoSQL path          │
│             ▼                  ▼                     │
│  ┌──────────────┐  ┌───────────────────────────────┐│
│  │ Existing SQL │  │  DocumentDB Query Translator  ││
│  │ data source  │  │                               ││
│  │ (unchanged)  │  │  filter  → BSON query         ││
│  └──────┬───────┘  │  insert  → insert_one()       ││
│         │          │  find    → find()             ││
│         │          │  search  → $text search       ││
│         │          │  vector  → $vectorSearch      ││
│         │          └──────────────┬────────────────┘│
└─────────┼────────────────────────┼─────────────────┘
          │                        │
          ▼                        ▼
┌──────────────────┐    ┌──────────────────────────────┐
│ CNPG SQL cluster │    │ CNPG NoSQL cluster           │
│ (existing)       │    │ (new, DocumentDB image)      │
│                  │    │                              │
│ TimescaleDB      │    │ pg_documentdb_core           │
│ pgvector         │    │ documentdb (api)             │
│ VectorChord-BM25 │    │ pgvector (via documentdb)    │
└──────────────────┘    └──────────────────────────────┘
```

### 3.3 Why Java Calls DocumentDB Directly (No FerretDB)

FerretDB's job is to translate **MongoDB wire protocol** (BSON over TCP port 27017) into `documentdb_api.*` SQL calls. We don't need this because:

- Our users use the Excalibase SDK, not MongoDB drivers
- The SDK speaks HTTP/GraphQL to `excalibase-graphql`, not MongoDB wire protocol
- Our Java service translates SDK calls → `documentdb_api.*` SQL directly via JDBC

```
FerretDB flow:
MongoDB driver → [MongoDB wire protocol] → FerretDB (Go) → documentdb_api SQL → PostgreSQL

Excalibase flow:
SDK            → [HTTP/GraphQL]          → excalibase-graphql (Java) → documentdb_api SQL → PostgreSQL
```

Same translation, one less process, no extra network hop.

---

## 4. Schema Management

### 4.1 Schema Declaration (SDK Side)

Schema declared once in a dedicated file, applied at application startup via `init()`. Never called at runtime alongside queries.

```typescript
// excalibase.schema.ts — committed to version control
import { defineSchema, defineCollection } from "@excalibase/sdk"

export default defineSchema({
  users: defineCollection({
    indexes: [
      { fields: ["email"],             unique: true  },
      { fields: ["orgId", "status"]                  },
      { fields: ["createdAt"],         type: "brin"  },
    ],
    search: { field: "bio" },
    vector: { field: "embedding", dimensions: 1536 },
  }),

  orders: defineCollection({
    indexes: [
      { fields: ["userId"] },
      { fields: ["status", "createdAt"] },
    ]
  }),
})
```

```typescript
// app.ts — init ONCE at startup
const db = await ExcalibaseClient.init({
  url: process.env.EXCALIBASE_NOSQL_URL,
  schema,   // synced once here
})

export { db }  // use everywhere, no schema at runtime
```

```typescript
// Application code — zero schema, zero DDL
await db.collection("users").find({ status: "active" })
await db.collection("users").vectorSearch(embedding, { topK: 5 })
```

### 4.2 Schema Sync Flow (Service Side — Java)

On `init()`, `excalibase-graphql` receives the schema declaration and calls DocumentDB extension functions directly:

```java
// SchemaService.java
@Service
public class SchemaService {

    @Autowired
    private JdbcTemplate jdbc;  // connected to NoSQL CNPG cluster

    public void syncSchema(SchemaDeclaration schema, String dbName) {
        for (CollectionDeclaration col : schema.getCollections()) {

            // 1. Create collection (fast on empty, DocumentDB manages internally)
            jdbc.execute(String.format(
                "SELECT documentdb_api.create_collection('%s', '%s')",
                dbName, col.getName()
            ));

            // 2. Declare indexes (DocumentDB handles background build)
            if (!col.getIndexes().isEmpty()) {
                String indexSpec = buildIndexSpec(col.getIndexes());
                jdbc.execute(String.format(
                    "SELECT documentdb_api.create_indexes('%s', '%s', '%s')",
                    dbName, col.getName(), indexSpec
                ));
            }
        }
    }

    // DocumentDB handles:
    //   - Whether collection already exists (idempotent)
    //   - Whether index already exists (idempotent)
    //   - Background index build for populated collections
    //   - Index state tracking internally
}
```

**No custom `excali_collections` or `excali_index_state` tables needed** — DocumentDB manages all of this internally via its own catalog.

### 4.3 Index Build Behavior (Delegated to DocumentDB)

| Scenario | DocumentDB behavior |
|---|---|
| Collection empty, index declared | Index created instantly |
| Collection populated, index declared | Background build, queries use seq scan until ready |
| Collection already has index | Idempotent, no-op |
| Two indexes on same collection | Sequential background build (DocumentDB limit) |

```java
// Check index status — query DocumentDB's own catalog
public IndexStatus getIndexStatus(String dbName, String collection, String indexName) {
    String result = jdbc.queryForObject(
        "SELECT documentdb_api.list_indexes_cursor_first_page(?, ?)",
        String.class, dbName, collection
    );
    // parse BSON result for the specific index name and status
    return parseIndexStatus(result, indexName);
}
```

---

## 5. DocumentDB Query Translator (Java)

This is the core of the new feature — translating Excalibase SDK filter syntax into `documentdb_api.*` SQL function calls.

### 5.1 CRUD Operations

```java
// DocumentDbQueryTranslator.java
@Component
public class DocumentDbQueryTranslator {

    private final JdbcTemplate jdbc;
    private final ObjectMapper bson = new ObjectMapper(); // BSON-compatible

    // INSERT
    public InsertResult insertOne(String db, String collection, Map<String, Object> doc) {
        String json = bson.writeValueAsString(doc);
        return jdbc.queryForObject(
            "SELECT documentdb_api.insert_one(?, ?, ?::bson)",
            InsertResult.class, db, collection, json
        );
    }

    // FIND
    public List<Map<String, Object>> find(
            String db, String collection,
            Map<String, Object> filter,
            FindOptions opts) {

        String filterJson = buildFilter(filter);
        String optionsJson = buildOptions(opts); // limit, skip, sort, projection

        String result = jdbc.queryForObject(
            "SELECT documentdb_api.find(?, ?, ?::bson, ?::bson)",
            String.class, db, collection, filterJson, optionsJson
        );
        return parseBsonArray(result);
    }

    // UPDATE
    public UpdateResult updateOne(
            String db, String collection,
            Map<String, Object> filter,
            Map<String, Object> update) {

        return jdbc.queryForObject(
            "SELECT documentdb_api.update(?, ?, ?::bson, ?::bson, ?::bson)",
            UpdateResult.class,
            db, collection,
            bson.writeValueAsString(filter),
            bson.writeValueAsString(update),
            "{\"isMany\": false}"
        );
    }

    // DELETE
    public DeleteResult deleteOne(String db, String collection, Map<String, Object> filter) {
        return jdbc.queryForObject(
            "SELECT documentdb_api.delete(?, ?, ?::bson, ?::bson)",
            DeleteResult.class,
            db, collection,
            bson.writeValueAsString(filter),
            "{\"isMany\": false}"
        );
    }
}
```

### 5.2 Filter Translation

SDK filter syntax maps to MongoDB-compatible BSON that DocumentDB understands natively:

```java
private String buildFilter(Map<String, Object> filter) {
    // SDK filter:  { "status": "active", "age": { "$gt": 25 } }
    // Maps to BSON: {"status": "active", "age": {"$gt": 25}}
    // DocumentDB handles $gt, $lt, $in, $or, $and, $regex, etc.
    return bson.writeValueAsString(filter);  // direct pass-through
}
```

Most filter operators pass through directly since our SDK uses MongoDB-compatible filter syntax. No translation layer needed for standard operators — DocumentDB handles them natively.

### 5.3 Full-Text Search

```java
public List<SearchResult> search(String db, String collection, String query, int limit) {
    // DocumentDB $text search via find with $text operator
    String filter = String.format(
        "{\"$text\": {\"$search\": \"%s\"}}",
        query.replace("\"", "\\\"")
    );
    String options = String.format("{\"limit\": %d}", limit);

    String result = jdbc.queryForObject(
        "SELECT documentdb_api.find(?, ?, ?::bson, ?::bson)",
        String.class, db, collection, filter, options
    );
    return parseSearchResults(result);
}
```

### 5.4 Vector Search

```java
public List<VectorResult> vectorSearch(
        String db, String collection,
        float[] embedding, int topK,
        Map<String, Object> filter) {

    // DocumentDB $vectorSearch via aggregate
    String pipeline = buildVectorPipeline(embedding, topK, filter);

    String result = jdbc.queryForObject(
        "SELECT documentdb_api.aggregate(?, ?, ?::bson)",
        String.class, db, collection, pipeline
    );
    return parseVectorResults(result);
}

private String buildVectorPipeline(float[] embedding, int topK, Map<String, Object> filter) {
    return String.format("""
        [{"$vectorSearch": {
            "vector": %s,
            "path": "embedding",
            "k": %d,
            "index": "vector_hnsw_index",
            "filter": %s
        }}]
        """,
        Arrays.toString(embedding),
        topK,
        filter != null ? bson.writeValueAsString(filter) : "{}"
    );
}
```

### 5.5 Hybrid Search (RRF)

```java
public List<HybridResult> hybridSearch(
        String db, String collection,
        String textQuery, float[] embedding, int topK) {

    // Run both searches, merge with Reciprocal Rank Fusion in Java layer
    // (DocumentDB does not natively support RRF — we implement it above)
    List<SearchResult> textResults = search(db, collection, textQuery, topK * 2);
    List<VectorResult> vectorResults = vectorSearch(db, collection, embedding, topK * 2, null);
    return reciprocalRankFusion(textResults, vectorResults, topK);
}
```

---

## 6. API Surface

### 6.1 REST API (new routes in excalibase-graphql)

All NoSQL routes mounted under `/v1/nosql/` — completely separate from existing `/v1/` SQL routes:

```
POST   /v1/nosql/:db/:collection              → insertOne
POST   /v1/nosql/:db/:collection/batch        → insertMany
GET    /v1/nosql/:db/:collection/:id          → findOne by _id
POST   /v1/nosql/:db/:collection/find         → find (filter in body)
PUT    /v1/nosql/:db/:collection/:id          → replaceOne
PATCH  /v1/nosql/:db/:collection/:id          → updateOne (partial)
DELETE /v1/nosql/:db/:collection/:id          → deleteOne
POST   /v1/nosql/:db/:collection/delete-many  → deleteMany
POST   /v1/nosql/:db/:collection/search       → FTS
POST   /v1/nosql/:db/:collection/vector       → ANN vector search
POST   /v1/nosql/:db/:collection/hybrid       → RRF hybrid search
POST   /v1/nosql/:db/:collection/aggregate    → aggregation pipeline

POST   /v1/nosql/schema/sync                  → schema push (init)
GET    /v1/nosql/schema/status                → index build status
```

### 6.2 GraphQL (new types added to existing schema)

```graphql
# New types added alongside existing SQL types — no override
type Query {
  # existing SQL queries remain unchanged...

  # new NoSQL queries
  nosqlFind(db: String!, collection: String!, filter: JSON, opts: FindOptions): [JSON!]!
  nosqlFindOne(db: String!, collection: String!, filter: JSON!): JSON
  nosqlSearch(db: String!, collection: String!, query: String!, limit: Int): [SearchResult!]!
  nosqlVectorSearch(db: String!, collection: String!, vector: [Float!]!, topK: Int): [VectorResult!]!
  nosqlSchemaStatus: SchemaStatus!
}

type Mutation {
  # existing SQL mutations remain unchanged...

  # new NoSQL mutations
  nosqlInsertOne(db: String!, collection: String!, doc: JSON!): InsertResult!
  nosqlInsertMany(db: String!, collection: String!, docs: [JSON!]!): BulkResult!
  nosqlUpdateOne(db: String!, collection: String!, filter: JSON!, update: JSON!): UpdateResult!
  nosqlDeleteOne(db: String!, collection: String!, filter: JSON!): DeleteResult!
  nosqlSyncSchema(schema: SchemaInput!): SchemaSyncResult!
}

type Subscription {
  # existing subscriptions remain unchanged...

  # new NoSQL change stream (backed by CDC watcher)
  nosqlWatch(db: String!, collection: String!, filter: JSON): ChangeEvent!
}
```

---

## 7. Multi-Tenancy

### 7.1 CNPG Cluster Provisioning

The Provisioning Service (Go) already handles CNPG cluster creation. New cluster type added:

```go
// provisioning-service/internal/cluster/nosql.go
func ProvisionNoSQLCluster(orgID string, tier Tier) error {
    cluster := &cnpgv1.Cluster{
        Spec: cnpgv1.ClusterSpec{
            // Use FerretDB's pre-built DocumentDB image
            ImageName: "ghcr.io/ferretdb/postgres-documentdb:17-0.102.0-ferretdb-2.1.0",
            PostgreSQL: cnpgv1.PostgreSQLConfiguration{
                Parameters: map[string]string{
                    "shared_preload_libraries": "pg_cron,pg_documentdb_core,documentdb",
                    "cron.database_name":       "postgres",
                },
            },
            Bootstrap: &cnpgv1.BootstrapConfiguration{
                InitDB: &cnpgv1.BootstrapInitDB{
                    PostInitSQL: []string{
                        "CREATE EXTENSION IF NOT EXISTS documentdb CASCADE",
                        "CREATE EXTENSION IF NOT EXISTS vector",
                    },
                },
            },
        },
    }
    return cnpgClient.Create(ctx, cluster)
}
```

### 7.2 Tenant Isolation

```
Free tier:
  Shared NoSQL CNPG cluster
  Separate database per org: documentdb_api calls use orgId as db name
  DocumentDB's own auth via SCRAM

Pro tier:
  Dedicated CNPG namespace per org
  Own PostgreSQL instance, own DocumentDB installation

Enterprise:
  Dedicated CNPG cluster, own region
  GDPR/PDPA compliant data residency
```

---

## 8. Data Source Configuration (Spring Boot)

Two separate data sources in `excalibase-graphql` — SQL and NoSQL never share a connection pool:

```yaml
# application.yml
datasource:
  sql:
    url: ${SQL_POSTGRES_URL}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      pool-name: sql-pool

  nosql:
    url: ${NOSQL_POSTGRES_URL}        # points to DocumentDB CNPG cluster
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      pool-name: nosql-pool
```

```java
// DataSourceConfig.java
@Configuration
public class DataSourceConfig {

    @Bean("sqlDataSource")
    @ConfigurationProperties("datasource.sql")
    public DataSource sqlDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean("nosqlDataSource")
    @ConfigurationProperties("datasource.nosql")
    public DataSource nosqlDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean("sqlJdbc")
    public JdbcTemplate sqlJdbc(@Qualifier("sqlDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean("nosqlJdbc")
    public JdbcTemplate nosqlJdbc(@Qualifier("nosqlDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
```

---

## 9. SDK Design

### 9.1 Init Contract

```typescript
const db = await ExcalibaseClient.init({
  sqlUrl:   process.env.EXCALIBASE_SQL_URL,    // existing
  nosqlUrl: process.env.EXCALIBASE_NOSQL_URL,  // new
  schema,   // NoSQL schema only — SQL schema unchanged
})

// SQL path (existing, unchanged)
const result = await db.sql.query("SELECT * FROM users")

// NoSQL path (new)
const users = await db.collection("users").find({ status: "active" })
```

### 9.2 Collection API

```typescript
const users = db.collection("users")

// CRUD
await users.insertOne({ name: "Vu", email: "vu@acme.com", embedding: [...] })
await users.insertMany([...])
await users.findOne({ email: "vu@acme.com" })
await users.find({ status: "active" }, { limit: 20, skip: 0, sort: { createdAt: -1 } })
await users.updateOne({ _id: "..." }, { $set: { status: "inactive" } })
await users.replaceOne({ _id: "..." }, newDoc)
await users.deleteOne({ _id: "..." })
await users.deleteMany({ status: "deleted" })

// Search
await users.search("software engineer", { limit: 10 })

// Vector
await users.vectorSearch(embedding, { topK: 5, filter: { orgId: "abc" } })

// Hybrid
await users.hybridSearch({ text: "software engineer", vector: embedding, topK: 10 })

// Aggregation
await users.aggregate([
  { $match: { status: "active" } },
  { $group: { _id: "$orgId", count: { $sum: 1 } } },
  { $sort: { count: -1 } },
])

// Watch (CDC)
const sub = users.watch()
sub.on("insert", (doc) => console.log(doc))
sub.on("update", (doc) => console.log(doc))
```

---

## 10. Phased Rollout

### Phase 0 — Compatibility Verification (BLOCKER)

- [ ] Spin up test CNPG cluster with DocumentDB image
- [ ] Test DocumentDB + TimescaleDB on same PostgreSQL instance
- [ ] Document result: compatible / incompatible / separate only
- [ ] Decision: confirm split-cluster architecture for v1
- [ ] No implementation starts until this is done

### Phase 1 — Core Infrastructure

- [ ] Provisioning Service: add `ProvisionNoSQLCluster()` function
- [ ] CNPG cluster template for DocumentDB image
- [ ] Spring Boot dual data source configuration
- [ ] Schema sync service (`documentdb_api.create_collection` + `create_indexes`)
- [ ] Basic CRUD translator (insertOne, find, updateOne, deleteOne)
- [ ] REST API: `/v1/nosql/:db/:collection` endpoints

### Phase 2 — Search and Vector

- [ ] FTS via DocumentDB `$text` operator
- [ ] Vector search via DocumentDB `$vectorSearch` aggregate stage
- [ ] Hybrid search (RRF in Java layer)
- [ ] GraphQL types and resolvers for NoSQL operations
- [ ] SDK: TypeScript client with `collection()` API

### Phase 3 — Advanced Features

- [ ] Aggregation pipeline subset (`$match`, `$group`, `$sort`, `$limit`, `$project`)
- [ ] Change streams via existing CDC watcher + `nosqlWatch()` SDK
- [ ] Index build status API
- [ ] Dashboard: schema editor UI
- [ ] Dashboard: index build progress visualization
- [ ] SDK: Java and Go clients

### Phase 4 — Scale and Polish

- [ ] Per-tenant NoSQL cluster provisioning automation
- [ ] Write batching for free tier
- [ ] Index recommendations from query patterns
- [ ] If compatibility verified in Phase 0: evaluate combined cluster tier

---

## 11. Open Questions

| Question | Context | Decision needed by |
|---|---|---|
| DocumentDB + TimescaleDB compatibility | Phase 0 must answer this | Before Phase 1 |
| Schema file format in SDK | `.ts` / `.json` / dashboard-only | Phase 1 planning |
| Free tier collection limit | 10 / 20 / 50 per org | Phase 1 planning |
| Free tier document size limit | 1MB / 4MB per document | Phase 1 planning |
| Aggregation pipeline scope | Which stages for MVP | Phase 2 planning |
| Auto-embedding generation | Integrate in service layer or client-side only | Phase 2 planning |
| RRF in Java vs SQL | Current: Java layer. Consider: SQL CTE for better perf | Phase 2 planning |