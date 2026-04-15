# Excalibase

**One schema. Two protocols.** Excalibase reads your PostgreSQL (or MySQL)
schema and exposes it through both **GraphQL** and **REST**. Each is
first-class — pick whichever fits your app, or use both from the same
project. Not sure? See [Choose Your Protocol →](choose-your-protocol.md).

## The same query, two ways

**GraphQL:**

```graphql
{
  kanbanIssues(
    where: { status: { in: [todo, in_progress] } }
    orderBy: { id: DESC }
    limit: 10
  ) {
    id title status priority
  }
}
```

**REST:**

```bash
GET /api/v1/issues?status=in.(todo,in_progress)&order=id.desc&limit=10
Accept-Profile: kanban
```

Both hit the same compiled SQL, return the same rows, and share the same
auth + multi-schema routing. **Pick the protocol that fits the call site.**

---

## What you get

- **Queries** — list, filter, paginate, sort every table
- **Mutations** — create, update, delete, bulk create (GraphQL + REST)
- **Relationships** — foreign keys become GraphQL fields automatically
- **Stored procedures** — call via GraphQL mutations
- **Computed fields** — PostgreSQL functions exposed as GraphQL fields
- **Multi-Schema** — connect to multiple schemas simultaneously with automatic prefix naming
- **Real-time subscriptions** — live table-change events via
  [excalibase-watcher](https://github.com/excalibase/excalibase-watcher) + NATS (GraphQL)
- **Row-Level Security** — per-request user context for RLS policies (PostgreSQL)
- **Typed filter inputs** — `IntFilterInput`, `FloatFilterInput`, `DateTimeFilterInput`,
  `BooleanFilterInput`, `JsonFilterInput`, per-enum `<EnumType>FilterInput`

---

## Supported Databases

| Database | Status | Version | Notes |
|----------|--------|---------|-------|
| **PostgreSQL** | ✅ Supported | 15+ | Full feature set |
| **MySQL** | ✅ Supported | 8.4+ | CRUD, ENUM/JSON, views, stored procedures |
| **MongoDB** | 🔄 Planned | — | Coming soon |

---

## Quick Start

**PostgreSQL** (port 10000):
```bash
git clone https://github.com/excalibase/excalibase-graphql.git
cd excalibase-graphql
docker-compose up -d
```

**MySQL** (port 10001):
```bash
docker-compose -f docker-compose.mysql.yml up -d
```

Then open your GraphQL client (or curl) and start querying:

```graphql
{ hanaUsers { id username email role } }
```

```bash
GET /api/v1/users?select=id,username,email,role
Accept-Profile: public
```

---

## Pick your path

- **Using GraphQL** — typed queries, nested projection, co-fetch, subscriptions, schema introspection. [Start here →](graphql/index.md)
- **Using REST** — PostgREST-compatible endpoints, HTTP-cacheable GETs, `Range` pagination, curl-friendly. [Start here →](rest/index.md)
- **Help me decide** — feature matrix, tradeoff table, scenario-by-scenario recommendations. [Decision guide →](choose-your-protocol.md)

---

## Quick feature tour

### Filtering

```graphql
{
  hanaCustomer(
    where: {
      active: { eq: true },
      last_name: { startsWith: "S" },
      story_points: { gte: 5 }
    }
    orderBy: { last_name: ASC }
    limit: 10
  ) {
    customer_id first_name last_name email
  }
}
```

Available operators: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `in`, `notIn`,
`isNull`, `isNotNull`, `contains`, `startsWith`, `endsWith`, `like`,
`ilike`, `regex`, `iregex`. JSON columns add `hasKey`, `hasKeys`,
`hasAnyKeys`, `contains`, `containedBy`. Enum columns get narrowed filter
types. See [GraphQL filtering →](graphql/filtering.md) or
[REST filtering →](rest/filtering.md).

### Full-text and vector search

Tables with a `tsvector` column get plain and Google-style search
automatically:

```graphql
{ kanbanIssues(where: { search_vec: { search: "stripe payment" } }) { id title } }
{ kanbanIssues(where: { search_vec: { webSearch: "stripe OR \"credit card\" -refund" } }) { id title } }
```

Tables with a `pgvector` column get a top-level `vector` argument for k-NN
similarity:

```graphql
{
  kanbanIssues(vector: {
    column: "embedding"
    near: [0.12, -0.34, 0.87]
    distance: "COSINE"
    limit: 5
  }) { id title }
}
```

Both are available through REST as well (`?col=plfts.term` /
`?col=wfts.term` for FTS, `?col=vector.{json}` for k-NN). See the
[Full-Text & Vector Search guide](features/search-and-vector.md).

### Mutations

```graphql
mutation {
  createHanaCustomer(input: { first_name: "Alice", last_name: "Smith", email: "alice@example.com" }) {
    customer_id
  }
}
```

```bash
POST /api/v1/customers
Content-Profile: public
Content-Type: application/json

{"first_name": "Alice", "last_name": "Smith", "email": "alice@example.com"}
```

See [GraphQL overview](graphql/index.md) and [REST mutations](rest/mutations.md).

### Aggregate queries (GraphQL only)

```graphql
{
  hanaOrdersAggregate {
    count
    sum { total_amount }
    avg { total_amount }
    min { total_amount }
    max { total_amount }
  }
}
```

REST has `Prefer: count=exact` for total row counts but no sum/avg/min/max
in one call — aggregate over the full set is a GraphQL-native feature.

### Row-Level Security (PostgreSQL)

Send a JWT — Excalibase verifies it and sets the `userId` claim as a
PostgreSQL session variable so RLS policies filter rows automatically:

```http
POST /graphql
Authorization: Bearer eyJhbGciOiJFUzI1NiJ9...
```

Both protocols honor the same JWT + RLS context. See
[RLS docs →](features/user-context-rls.md).

---

## Native Binary

Excalibase ships as a GraalVM native binary for minimal startup time and
memory footprint:

```bash
# ~50ms startup, ~80MB RAM
docker pull excalibase/excalibase-graphql:native
```

---

## Test Coverage

- **PostgreSQL unit + integration**: 304 tests
- **REST module**: 150 tests (incl. 15 JSON/array integration)
- **GraphQL JSONB filter**: 9 integration tests
- **PostgreSQL e2e** (live stack): 99+ kanban + ecommerce + clinic tests
- **MySQL e2e**: 74 tests

---

## Learn More

- [Choose Your Protocol →](choose-your-protocol.md) — when to pick GraphQL vs REST
- [GraphQL overview →](graphql/index.md) — queries, mutations, filtering, aggregates
- [REST overview →](rest/index.md) — endpoints, filters, mutations, pagination
- [Quick Start →](quick-start.md) — Docker setup, sample queries
- [MySQL Support →](features/mysql.md) — MySQL-specific guide
- [Stored Procedures →](features/stored-procedures.md) — IN/OUT params, examples
- [Full-Text & Vector Search →](features/search-and-vector.md) — FTS on `tsvector`, k-NN on pgvector
- [Real-Time Subscriptions →](features/subscriptions.md) — CDC setup
- [Row-Level Security →](features/user-context-rls.md) — Per-user data isolation
- [Enhanced PostgreSQL Types →](features/enhanced-postgresql-types.md) — JSON, arrays, network types
