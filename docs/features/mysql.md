# MySQL Support

Excalibase GraphQL supports MySQL 8.4+ as a first-class database backend. All core features work out of the box — simply point the application at your MySQL database and get a full GraphQL API automatically.

## Quick Start

```bash
# Start the MySQL stack
docker-compose -f docker-compose.mysql.yml up -d

# GraphQL endpoint
http://localhost:10001/graphql
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

### Stored Procedures

See [Stored Procedures](stored-procedures.md) for full details. MySQL example:

```graphql
# Call a stored procedure
mutation {
  callExcalibaseGetCustomerOrderCount(p_customer_id: 1)
}

# Transfer funds (IN/OUT params)
mutation {
  callExcalibaseTransferFunds(
    p_from_wallet_id: 1
    p_to_wallet_id: 2
    p_amount: 200.00
  )
}
```

Results are returned as a JSON string. Parse on the client:

```js
const result = JSON.parse(data.callExcalibaseTransferFunds);
console.log(result.p_status); // "SUCCESS"
```

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

## Limitations

- **CDC / Subscriptions** — real-time subscriptions via Change Data Capture are PostgreSQL-only for now
- **Spatial types** — PostGIS equivalents not yet supported
