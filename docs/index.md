# Excalibase GraphQL

**Automatic GraphQL API generation from multiple databases.** Point it at your PostgreSQL or MySQL database and get a full GraphQL API instantly — no code, no schema definitions, no configuration.

## What You Get

- **Queries** — list, filter, paginate, sort every table
- **Mutations** — create, update, delete, bulk create
- **Relationships** — foreign keys become GraphQL fields automatically
- **Stored procedures** — call via GraphQL mutations
- **Computed fields** — PostgreSQL functions exposed as GraphQL fields (PostgreSQL)
- **Multi-Schema** — connect to multiple schemas simultaneously with automatic prefix naming
- **Real-time subscriptions** — live table-change events via [excalibase-watcher](https://github.com/excalibase/excalibase-watcher) + NATS
- **Row-Level Security** — per-request user context for RLS policies (PostgreSQL)

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

Then open your GraphQL client and start querying:

```graphql
{
  hanaUsers {
    id
    username
    email
    role
  }
}
```

---

## Feature Overview

### Queries & Filtering

```graphql
{
  hanaCustomer(
    where: { active: { eq: true }, last_name: { startsWith: "S" } }
    orderBy: { last_name: ASC }
    limit: 10
    offset: 0
  ) {
    customer_id
    first_name
    last_name
    email
    full_name       # computed field
    active_label    # computed field
  }
}
```

Available filter operators: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `in`, `notIn`, `isNull`, `isNotNull`, `contains`, `startsWith`, `endsWith`, `like`, `ilike`

### Relationships

Foreign keys are automatically resolved — include the FK column to enable relationship traversal:

```graphql
{
  hanaOrders {
    order_id
    customer_id       # required for relationship
    hanaCustomer {
      first_name
      last_name
    }
    total_amount
    status
  }
}
```

### Mutations

```graphql
# Create
mutation {
  createHanaCustomer(input: { first_name: "Alice", last_name: "Smith", email: "alice@example.com" }) {
    customer_id
  }
}

# Update
mutation {
  updateHanaCustomer(input: { customer_id: 1, email: "new@example.com" }) {
    customer_id
    email
  }
}

# Bulk create
mutation {
  createManyHanaCustomer(inputs: [
    { first_name: "Bob", last_name: "Jones", email: "bob@example.com" }
    { first_name: "Carol", last_name: "White", email: "carol@example.com" }
  ]) { customer_id }
}

# Delete
mutation {
  deleteHanaCustomer(input: { customer_id: 1 }) {
    customer_id
  }
}
```

### Cursor-Based Pagination

```graphql
{
  hanaCustomerConnection(first: 10, after: "cursor_value") {
    edges {
      node { customer_id first_name last_name }
      cursor
    }
    pageInfo { hasNextPage endCursor }
    totalCount
  }
}
```

### Aggregate Queries

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

### Stored Procedures

```graphql
mutation {
  callHanaTransferFunds(
    p_from_wallet_id: 1
    p_to_wallet_id: 2
    p_amount: 200.00
  )
}
# Returns JSON string: {"p_status":"SUCCESS"}
```

### Real-Time Subscriptions

Powered by [excalibase-watcher](https://github.com/excalibase/excalibase-watcher) and NATS JetStream. Works with PostgreSQL and MySQL.

```graphql
subscription {
  hanaCustomerChanges {
    operation   # INSERT, UPDATE, DELETE
    data {
      customer_id
      first_name
      last_name
      new { customer_id first_name last_name }
    }
  }
}
```

---

## PostgreSQL Type Support

| Category | Types | GraphQL |
|----------|-------|---------|
| Basic | `INTEGER`, `BIGINT`, `REAL`, `TEXT`, `VARCHAR`, `BOOLEAN`, `DATE`, `TIMESTAMP` | `Int`, `Float`, `String`, `Boolean` |
| JSON | `JSON`, `JSONB` | Custom `JSON` scalar |
| Arrays | `INTEGER[]`, `TEXT[]`, composite/enum arrays | `[Int]`, `[String]`, etc. |
| DateTime | `TIMESTAMPTZ`, `TIMETZ`, `INTERVAL` | `String` |
| Network | `INET`, `CIDR`, `MACADDR`, `MACADDR8` | `String` |
| Binary | `BYTEA` | `String` (hex) |
| XML | `XML` | `String` |
| Bit | `BIT(n)`, `VARBIT(n)` | `String` |
| Custom Enum | User-defined `ENUM` types | GraphQL `enum` |
| Custom Composite | User-defined composite types | GraphQL object type |
| Domain | User-defined `DOMAIN` types | Mapped to base type |
| Views | `VIEW`, `MATERIALIZED VIEW` | Read-only GraphQL type |

---

## Row-Level Security (PostgreSQL)

Pass `X-User-Id` in your request headers. Excalibase sets it as a PostgreSQL session variable so RLS policies can filter rows automatically:

```http
POST /graphql
X-User-Id: alice
```

```sql
-- Your RLS policy:
CREATE POLICY user_isolation ON rls_orders
  FOR ALL USING (user_id = current_setting('request.user_id', true));
```

---

## Native Binary

Excalibase ships as a GraalVM native binary for minimal startup time and memory footprint:

```bash
# ~50ms startup, ~80MB RAM
docker pull excalibase/excalibase-graphql:native
```

---

## Test Coverage

- **PostgreSQL e2e**: 120+ tests
- **MySQL e2e**: 74 tests
- **Total**: 223+ tests passing on both JVM and native builds

---

## Learn More

- [Quick Start →](quick-start.md) — Docker setup, sample queries
- [API Reference →](api/index.md) — Full schema documentation
- [Filtering →](filtering.md) — All filter operators and examples
- [MySQL Support →](features/mysql.md) — MySQL-specific guide
- [Stored Procedures →](features/stored-procedures.md) — IN/OUT params, examples
- [Real-Time Subscriptions →](features/subscriptions.md) — CDC setup
- [Row-Level Security →](features/user-context-rls.md) — Per-user data isolation
- [Enhanced PostgreSQL Types →](features/enhanced-postgresql-types.md) — JSON, arrays, network types
