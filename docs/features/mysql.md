# MySQL Support

Excalibase GraphQL supports MySQL 8.4+ as a first-class database backend. All core features work out of the box — simply point the application at your MySQL database and get a full GraphQL API automatically.

## Quick Start

```bash
# Start the MySQL stack
docker-compose -f docker-compose.mysql.yml up -d

# GraphQL endpoint (project-scoped)
http://localhost:10001/{projectId}/graphql
```

## Configuration

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/excalibase
    username: excalibase
    password: password123
    driver-class-name: com.mysql.cj.jdbc.Driver

app:
  schemas: excalibase
  database-type: mysql

server:
  port: 10001
```

## Supported Features

### CRUD Operations

Full create, read, update, and delete on every table:

```graphql
# Query with filtering and pagination
{
  excalibaseCustomer(
    where: { active: { eq: 1 } }
    orderBy: { last_name: "ASC" }
    limit: 10
    offset: 0
  ) {
    customer_id
    first_name
    last_name
    email
  }
}

# Create
mutation {
  createExcalibaseCustomer(input: {
    first_name: "Alice"
    last_name: "Smith"
    email: "alice@example.com"
  }) {
    customer_id
    first_name
    last_name
  }
}

# Update
mutation {
  updateExcalibaseCustomer(id: 1, input: { email: "newemail@example.com" }) {
    customer_id
    email
  }
}

# Delete
mutation {
  deleteExcalibaseCustomer(id: 1) {
    customer_id
  }
}

# Bulk create
mutation {
  createManyExcalibaseCustomer(inputs: [
    { first_name: "Bob", last_name: "Jones", email: "bob@example.com" }
    { first_name: "Carol", last_name: "White", email: "carol@example.com" }
  ]) {
    customer_id
    first_name
  }
}
```

### ENUM Types

MySQL ENUM columns are reflected as GraphQL enum types:

```sql
CREATE TABLE task (
    task_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
    title    VARCHAR(200) NOT NULL,
    status   ENUM('todo', 'in_progress', 'done', 'cancelled') DEFAULT 'todo',
    priority ENUM('low', 'medium', 'high', 'critical')        DEFAULT 'medium'
);
```

```graphql
{
  excalibaseTask(where: { status: { eq: "in_progress" }, priority: { eq: "high" } }) {
    task_id
    title
    status
    priority
  }
}
```

### JSON Types

MySQL JSON columns are surfaced as GraphQL `String` (JSON-serialized):

```sql
CREATE TABLE product_detail (
    detail_id  BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    attributes JSON,
    metadata   JSON,
    tags       JSON
);
```

```graphql
{
  excalibaseProductDetail {
    detail_id
    attributes
    metadata
    tags
  }
}
```

### Views

Read-only GraphQL types are generated for MySQL views:

```graphql
{
  excalibaseActiveCustomers {
    customer_id
    first_name
    last_name
    email
  }
}

{
  excalibaseOrdersSummary {
    customer_id
    first_name
    last_name
    order_count
    total_spent
  }
}
```

### Foreign Key Relationships

Forward and reverse FK fields are automatically added to the schema:

```graphql
{
  excalibaseOrders {
    order_id
    total
    status
    excalibaseCustomer {          # forward FK: orders.customer_id -> customer
      first_name
      last_name
    }
  }
}

{
  excalibaseCustomer {
    customer_id
    first_name
    excalibaseOrders {            # reverse FK: all orders for this customer
      order_id
      total
      status
    }
  }
}
```

### Aggregate Queries

```graphql
{
  excalibaseOrdersAggregate {
    count
    sum
    avg
    min
    max
  }
}
```

### Cursor-Based Pagination

```graphql
{
  excalibaseCustomerConnection(first: 10, after: "cursor123") {
    edges {
      node {
        customer_id
        first_name
        last_name
      }
      cursor
    }
    pageInfo {
      hasNextPage
      endCursor
    }
    totalCount
  }
}
```

### Stored procedures

Not exposed. Only tracked functions are reachable, and tracking is Postgres-only: a MySQL
function returns a scalar, not rows of a table, so no table permission could apply to its result
(see [Functions](functions.md)).

### Permissions

Permissions apply to MySQL projects as they do to Postgres ones: the same document compiles to MySQL
SQL, and a differential suite checks every case against native Postgres RLS on identical data.

- `TIMESTAMP` compares in UTC whatever the session time zone; `ENUM` compares by declaration order;
  `FLOAT` compares at single precision; `NaN`/`Infinity` (which MySQL cannot store) match nothing.
- Text compares under the column's own collation, so on `utf8mb4_0900_ai_ci` `_eq: "alpha"` keeps
  "Alpha". Realtime asks the database for such rows instead of deciding in memory, so it delivers
  exactly the rows a query returns.
- MySQL cannot return the rows a write touches, so writes lock and key them first, then the permission's
  check runs on exactly the rows found again by those keys; a written row that cannot be found again
  (an `AUTO_INCREMENT` 0, a rounded key) rolls the write back. A write needs a primary key; an insert
  must supply it or let `AUTO_INCREMENT` generate it; a GraphQL update of a primary key column is
  refused; `onConflict` is refused.
- Permission keys name a table `<database>.<table>`, the database being its schema.

## Filtering Operators

| Operator | Description | Example |
|----------|-------------|---------|
| `eq` | Equal | `{ status: { eq: "active" } }` |
| `neq` | Not equal | `{ status: { neq: "cancelled" } }` |
| `gt` / `gte` | Greater than / or equal | `{ total: { gte: 50 } }` |
| `lt` / `lte` | Less than / or equal | `{ price: { lte: 100 } }` |
| `in` | In list | `{ status: { in: ["pending", "processing"] } }` |
| `notIn` | Not in list | `{ status: { notIn: ["cancelled"] } }` |
| `isNull` | Is null | `{ email: { isNull: true } }` |
| `isNotNull` | Is not null | `{ email: { isNotNull: true } }` |
| `contains` | Substring match | `{ first_name: { contains: "ali" } }` |
| `startsWith` | Prefix match | `{ last_name: { startsWith: "Sm" } }` |
| `endsWith` | Suffix match | `{ email: { endsWith: "@example.com" } }` |
| `like` | SQL LIKE pattern | `{ email: { like: "%@gmail%" } }` |

## Differences from PostgreSQL

| Feature | PostgreSQL | MySQL |
|---------|-----------|-------|
| `orderBy` value | Enum (`ASC` / `DESC`) | String (`"ASC"` / `"DESC"`) |
| Update mutation | `update{Table}(input: { pk, fields... })` | `update{Table}(id: X, input: { fields... })` |
| Aggregate result | Nested per-column (`sum { total }`) | Flat scalars (`sum`, `avg`) |
| Subscriptions / CDC | Supported | Not yet supported |
| Custom composite types | Supported | N/A |
| Array types | Supported | N/A |

## REST API

The PostgREST-compatible REST API (`/{projectId}/api/v1/{table}`) serves MySQL with the same surface as
GraphQL: `select` with to-one and to-many embeds, filters, `order` (including `nullsfirst`/`nullslast`),
`limit`/`offset`, `Prefer: count=exact`, inserts, bulk inserts, updates, deletes and
`Prefer: resolution=merge-duplicates` upserts.

MySQL has no `RETURNING`, so `Prefer: return=representation` reads the written rows back by primary key:

| Write | How the rows come back |
|-------|------------------------|
| Insert | One statement per row; read back by the key sent, or the `AUTO_INCREMENT` key generated (only when the key is that column) |
| Update | Keys of the matched rows locked (`FOR UPDATE`) before the update, then read back (a changed key is followed) |
| Delete | Rows read, locked, before they are deleted |
| Upsert | `INSERT … AS incoming ON DUPLICATE KEY UPDATE`, read back by the key sent; without every key column it is a plain insert. An existing row the update permission's filter hides is left as it is |

Filters run natively where MySQL has the operator: `match`/`imatch` (`REGEXP_LIKE`), `cs`/`cd`/`haskey`
(`JSON_CONTAINS`, `JSON_CONTAINS_PATH`) and `fts`/`plfts`/`phfts` (`MATCH … AGAINST`, the column needs its own
`FULLTEXT` index).

An upsert that collides on a unique key other than the primary key fails with `409 unique_violation`, as on
Postgres. Before `ON DUPLICATE KEY UPDATE` runs, the other unique keys are checked in the same transaction
(`SELECT … FOR UPDATE`), so the statement never updates a row the request did not address.

Refused with `400` and a message naming MySQL, never ignored:

- array, range, JSON path, web-search and vector operators (`ov`, `sl`, `arraylength`, `jsonpath`, `wfts`, `vector`, …)
- `return=representation` (or `max-affected`), or a permission check, on a table without a primary key, or an insert whose key is neither sent nor generated
- RPC (`/rpc/{function}`): functions are not tracked on MySQL

A filter on a column the table does not have is a `400` naming the column on every database, also inside
`or=(…)`: a typo never returns every row.

## Limitations

- **CDC / Subscriptions** — real-time subscriptions via Change Data Capture are PostgreSQL-only for now
- **Spatial types** — PostGIS equivalents not yet supported
