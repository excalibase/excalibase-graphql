# MySQL Support

Excalibase GraphQL supports MySQL 8.0 and 8.4 as a first-class database backend. All core features work out of the box — simply point the application at your MySQL database and get a full GraphQL API automatically.

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

MySQL JSON columns (and Postgres `json`/`jsonb`) are the GraphQL `JSON` scalar: they answer JSON values and take
them as input, inline or through a `JSON` variable:

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

### Other column types

| MySQL type | Answered as | Written from |
|---|---|---|
| `DECIMAL` | a string with every digit, e.g. `"19.90"` | a number or numeric string, kept exact |
| `BIGINT`, `BIGINT UNSIGNED` | a string, up to `"18446744073709551615"` | a number or numeric string |
| `TIME` | `"838:59:59"`, as stored (past 24 hours, negative) | the same form |
| `BINARY`, `VARBINARY`, `BLOB`, `BIT` | `\x00ff10` (`\x` and lowercase hex), as Postgres `bytea` | the same form |

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
    sum { total }
    avg { total }
    min { total }
    max { total }
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
  must supply it or let `AUTO_INCREMENT` generate it; an update that changes it is followed to the new
  key; an `onConflict` upsert is checked as an insert and as an update.
- That check runs after the write, so an insert or update check that reads, through a relationship or
  `_exists`, a table the same mutation writes (this field's table or an earlier field's) is refused with
  `400`: Postgres judges it against the database as it was before the write, which MySQL cannot.
- The engine cannot see writes made by the project's triggers or foreign-key cascades, but a check does:
  it can be satisfied by rows its own write produced that way, which Postgres would not see. Keep checks off
  tables that triggers or cascades write.
- Permission keys name a table `<database>.<table>`, the database being its schema.

## Filtering Operators

The common operators (`eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `in`, `notIn`, `isNull`, `isNotNull`, `contains`,
`startsWith`, `endsWith`, `like`, `ilike`) run as on PostgreSQL. MySQL also runs `regex` and `iregex`, JSON
`contains`, `containedBy`, `hasKey`, `hasKeys` and `hasAnyKeys`, and `search`, `phraseSearch` and `rawSearch` on
columns with their own `FULLTEXT` index. See [MySQL operators and writes](../databases/mysql.md) and the
[compatibility matrix](../database-compatibility.md). An operator MySQL cannot run is not in the schema, and is
refused with an error if sent anyway.

## Differences from PostgreSQL

| Feature | PostgreSQL | MySQL |
|---------|-----------|-------|
| `orderBy` value | Enum (`ASC` / `DESC`) | String (`"ASC"` / `"DESC"`) |
| Update mutation | `update{Table}(input: { pk, fields... })` | `update{Table}(id: X, input: { fields... })` |
| Upsert (`onConflict`) | `ON CONFLICT` | One-row insert-or-update; see [upsert](../databases/mysql.md#upsert) |
| Nested inserts, `distinctOn`, vector search | Supported | Not available (refused) |
| Subscriptions / CDC | Supported | The schema offers them; events come from the watcher's binlog stream |
| Custom composite types | Supported | N/A |
| Array types | Supported | N/A |
