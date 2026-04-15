# API Reference

Excalibase GraphQL automatically generates a complete GraphQL schema from your database tables, views, and stored procedures. This page documents the shape of that schema.

**Default endpoints:**
- PostgreSQL: `http://localhost:10000/graphql`
- MySQL: `http://localhost:10001/graphql`

---

## Schema Introspection

Explore the full auto-generated schema:

```graphql
{
  __schema {
    types {
      name
      fields { name type { name kind ofType { name } } }
    }
  }
}
```

Or check what mutations are available:

```graphql
{
  __type(name: "Mutation") {
    fields { name args { name type { name kind } } }
  }
}
```

---

## Queries

For every table and view, three query fields are generated:

| Field | Description |
|-------|-------------|
| `{schema}{Table}` | List with filtering, sorting, pagination |
| `{schema}{Table}Connection` | Cursor-based (Relay) pagination |
| `{schema}{Table}Aggregate` | Aggregate functions (count, sum, avg, min, max) |

### List Query

```graphql
{
  hanaCustomer(
    where: { active: { eq: true } }
    orderBy: { last_name: ASC }
    limit: 20
    offset: 0
  ) {
    customer_id
    first_name
    last_name
    email
  }
}
```

**Arguments:**

| Argument | Type | Description |
|----------|------|-------------|
| `where` | `{Table}WhereInput` | Filter conditions |
| `orderBy` | `{Table}OrderByInput` | Sort direction per column |
| `limit` | `Int` | Max rows to return |
| `offset` | `Int` | Rows to skip |

### Connection Query (Cursor Pagination)

```graphql
{
  hanaCustomerConnection(first: 10, after: "cursor_value") {
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
      hasPreviousPage
      startCursor
      endCursor
    }
    totalCount
  }
}
```

### Aggregate Query

PostgreSQL aggregate fields return nested per-column results:

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

MySQL aggregate fields return flat scalars:

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

---

## Mutations

For every table, the following mutations are generated:

| Mutation | Description |
|----------|-------------|
| `create{Schema}{Table}` | Insert one row |
| `createMany{Schema}{Table}` | Bulk insert |
| `update{Schema}{Table}` | Update one row |
| `delete{Schema}{Table}` | Delete one row, returns the deleted row |
| `call{Schema}{ProcedureName}` | Call a stored procedure |

### Create

```graphql
mutation {
  createHanaCustomer(input: {
    first_name: "Alice"
    last_name: "Smith"
    email: "alice@example.com"
  }) {
    customer_id
    first_name
  }
}
```

### Bulk Create

```graphql
mutation {
  createManyHanaCustomer(inputs: [
    { first_name: "Bob", last_name: "Jones", email: "bob@example.com" }
    { first_name: "Carol", last_name: "White", email: "carol@example.com" }
  ]) {
    customer_id
    first_name
  }
}
```

### Update

**PostgreSQL** — PK is part of the input object:
```graphql
mutation {
  updateHanaCustomer(input: { customer_id: 1, email: "new@example.com" }) {
    customer_id
    email
  }
}
```

**MySQL** — PK is a separate `id` argument:
```graphql
mutation {
  updateExcalibaseCustomer(id: 1, input: { email: "new@example.com" }) {
    customer_id
    email
  }
}
```

### Delete

Delete returns the deleted row so you can update your UI without a separate fetch:

```graphql
mutation {
  deleteHanaCustomer(input: { customer_id: 1 }) {
    customer_id
    first_name
    last_name
  }
}
```

### Stored Procedure Call

Each discovered procedure becomes a `call{Schema}{ProcedureName}` mutation. IN parameters become arguments; OUT parameters are returned as a JSON string.

```graphql
mutation {
  callHanaTransferFunds(
    p_from_wallet_id: 1
    p_to_wallet_id: 2
    p_amount: 200.00
  )
}
```

```json
{ "data": { "callHanaTransferFunds": "{\"p_status\":\"SUCCESS\"}" } }
```

See [Stored Procedures →](../features/stored-procedures.md) for full documentation.

---

## Filtering

Every query accepts a `where` argument with per-column filter inputs.

### Available Operators

| Operator | Types | Example |
|----------|-------|---------|
| `eq` | All | `{ status: { eq: "active" } }` |
| `neq` | All | `{ status: { neq: "cancelled" } }` |
| `gt` / `gte` | Numeric, Date | `{ total_amount: { gte: 100 } }` |
| `lt` / `lte` | Numeric, Date | `{ price: { lte: 50 } }` |
| `in` | All | `{ status: { in: ["pending", "shipped"] } }` |
| `notIn` | All | `{ status: { notIn: ["cancelled"] } }` |
| `isNull` | All | `{ email: { isNull: true } }` |
| `isNotNull` | All | `{ email: { isNotNull: true } }` |
| `contains` | String | `{ last_name: { contains: "smith" } }` |
| `startsWith` | String | `{ email: { startsWith: "alice" } }` |
| `endsWith` | String | `{ email: { endsWith: "@example.com" } }` |
| `like` | String | `{ email: { like: "%@gmail%" } }` |
| `ilike` | String (PG) | `{ name: { ilike: "%alice%" } }` |

### JSON Operators (PostgreSQL)

| Operator | Description |
|----------|-------------|
| `hasKey` | JSON object contains this key |
| `hasKeys` | JSON object contains all these keys |
| `contains` | JSON is a superset of this value |
| `containedBy` | JSON is a subset of this value |
| `path` | JSON path exists |
| `pathText` | JSON path value equals text |

```graphql
{
  hanaEnhancedTypes(where: {
    jsonb_col: {
      hasKey: "score"
      contains: "{\"active\": true}"
    }
  }) {
    id
    name
    jsonb_col
  }
}
```

### Array Operators (PostgreSQL)

| Operator | Description |
|----------|-------------|
| `contains` | Array contains this element |
| `hasAny` | Array overlaps with these values |
| `hasAll` | Array contains all these values |

```graphql
{
  hanaEnhancedTypes(where: {
    text_array: { hasAny: ["postgresql", "graphql"] }
  }) {
    id
    name
    text_array
  }
}
```

### OR Conditions

```graphql
{
  hanaCustomer(or: [
    { first_name: { eq: "MARY" } }
    { first_name: { eq: "JOHN" } }
  ]) {
    customer_id
    first_name
    last_name
  }
}
```

---

## Relationships

### Forward (Many-to-One)

When a table has a FK column, a relationship field is added. **Include the FK column** in your selection — the resolver needs it to fetch the related row:

```graphql
{
  hanaOrders {
    order_id
    customer_id       # required — resolver reads this value
    hanaCustomer {
      first_name
      last_name
    }
    total_amount
  }
}
```

### Reverse (One-to-Many)

The referenced table gets a list field for all rows that point to it:

```graphql
{
  hanaCustomer {
    customer_id
    first_name
    hanaOrders {
      order_id
      total_amount
      status
    }
  }
}
```

### Composite Foreign Keys (PostgreSQL)

Multi-column FKs work the same way — include all FK columns:

```graphql
{
  hanaChildTable {
    child_id
    parent_id1
    parent_id2
    description
    hanaParentTable {
      name
    }
  }
}
```

---

## Computed Fields (PostgreSQL)

PostgreSQL functions matching the pattern `{tablename}_{fieldname}(row {tablename})` are auto-discovered and exposed as read-only GraphQL fields:

```sql
-- Function in the database:
CREATE FUNCTION customer_full_name(c customer) RETURNS TEXT ...
CREATE FUNCTION customer_active_label(c customer) RETURNS TEXT ...
CREATE FUNCTION orders_total_with_tax(o orders) RETURNS NUMERIC ...
CREATE FUNCTION orders_is_high_value(o orders) RETURNS BOOLEAN ...
```

```graphql
{
  hanaCustomer {
    customer_id
    first_name
    last_name
    full_name       # computed: first_name || ' ' || last_name
    active_label    # computed: 'Active' or 'Inactive'
  }
}

{
  hanaOrders {
    order_id
    total_amount
    total_with_tax  # computed: total_amount * 1.10
    is_high_value   # computed: total_amount > 200
  }
}
```

---

## Views (Read-Only)

Views and materialized views appear as read-only query fields (no mutations generated):

```graphql
# PostgreSQL views from initdb.sql
{ hanaActiveCustomers { customer_id first_name last_name email } }
{ hanaPostsWithAuthors { id title author_username published } }
{ hanaEnhancedTypesSummary { id name json_name array_size } }

# MySQL views
{ excalibaseActiveCustomers { customer_id first_name last_name email } }
{ excalibaseOrdersSummary { customer_id first_name order_count total_spent } }
{ excalibaseHighValueOrders { order_id total status first_name last_name } }
```

---

## Composite Primary Keys (PostgreSQL)

Tables with composite PKs work with all CRUD operations. All PK columns are required for update/delete:

```graphql
# Create
mutation {
  createHanaOrderItems(input: { order_id: 1, product_id: 2, quantity: 3, price: 59.99 }) {
    order_id product_id quantity price
  }
}

# Update — all PK columns required
mutation {
  updateHanaOrderItems(input: { order_id: 1, product_id: 2, quantity: 5 }) {
    order_id product_id quantity
  }
}

# Delete — all PK columns required
mutation {
  deleteHanaOrderItems(input: { order_id: 1, product_id: 2 }) {
    order_id product_id
  }
}
```

---

## Row-Level Security (PostgreSQL)

Send a JWT in the `Authorization` header. Excalibase verifies the token and sets the `userId` claim as the `request.user_id` PostgreSQL session variable before executing the query:

```http
POST /graphql HTTP/1.1
Authorization: Bearer eyJhbGciOiJFUzI1NiJ9...
Content-Type: application/json
```

With this RLS policy in place:

```sql
CREATE POLICY user_isolation ON rls_orders
  FOR ALL USING (user_id = current_setting('request.user_id', true));
```

Each user sees only their own rows — no application-level filtering needed. Requires `jwt-enabled: true` and a configured `auth.jwks-url` or public key.

See [Row-Level Security →](../features/user-context-rls.md) for full documentation.

---

## Real-Time Subscriptions (PostgreSQL)

Excalibase uses PostgreSQL logical replication (CDC) to push live changes over WebSocket:

```graphql
subscription {
  hanaCustomerChanges {
    operation
    data { customer_id first_name last_name }
  }
}
```

See [Subscriptions →](../features/subscriptions.md) for setup and configuration.

---

## Type Mappings

### PostgreSQL

| DB Type | GraphQL Type |
|---------|-------------|
| `INTEGER`, `BIGINT`, `SMALLINT` | `Int` |
| `REAL`, `DOUBLE PRECISION`, `NUMERIC` | `Float` |
| `TEXT`, `VARCHAR`, `CHAR` | `String` |
| `BOOLEAN` | `Boolean` |
| `DATE`, `TIMESTAMP`, `TIMESTAMPTZ` | `String` |
| `TIMETZ`, `INTERVAL` | `String` |
| `JSON`, `JSONB` | `JSON` (custom scalar) |
| `INTEGER[]`, `TEXT[]`, etc. | `[Int]`, `[String]`, etc. |
| `INET`, `CIDR`, `MACADDR` | `String` |
| `BYTEA` | `String` (hex-encoded) |
| `XML` | `String` |
| `BIT(n)`, `VARBIT(n)` | `String` |
| `UUID` | `ID` |
| Custom `ENUM` | GraphQL enum type |
| Custom composite type | GraphQL object type |
| Domain type | Mapped to base type |

### MySQL

| DB Type | GraphQL Type |
|---------|-------------|
| `INT`, `BIGINT`, `SMALLINT`, `TINYINT` | `Int` |
| `FLOAT`, `DOUBLE`, `DECIMAL` | `Float` |
| `VARCHAR`, `TEXT`, `CHAR` | `String` |
| `TINYINT(1)` | `Boolean` |
| `DATE`, `DATETIME`, `TIMESTAMP` | `String` |
| `JSON` | `String` (JSON-serialized) |
| `ENUM(...)` | GraphQL enum type |

---

## Security

All queries use parameterized statements — SQL injection is not possible through the GraphQL interface. Input is validated against the generated type schema before execution.

Query depth is limited to prevent overly complex nested queries from reaching the database.
