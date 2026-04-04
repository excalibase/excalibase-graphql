# Multi-Schema Support

Excalibase GraphQL can expose tables from multiple database schemas in a single GraphQL API. All types are prefixed with their schema name to avoid naming conflicts and ensure consistency.

## Configuration

```yaml
app:
  schemas: public                  # single schema
  schemas: public,sales            # multiple schemas
  schemas: ALL                     # auto-discover all user schemas
  database-type: postgres          # or: mysql
```

### Schema Discovery (`ALL`)

When set to `ALL`, Excalibase queries `information_schema.schemata` and excludes system schemas (`pg_catalog`, `information_schema`, `pg_toast*`, `pg_temp_*`). Extension views like `pg_stat_statements` are also excluded.

## Naming Convention

Every table-derived GraphQL type is prefixed with its schema name:

| Schema | Table | Query Field | Type Name |
|--------|-------|-------------|-----------|
| `public` | `users` | `publicUsers` | `PublicUsers` |
| `sales` | `orders` | `salesOrders` | `SalesOrders` |
| `hana` | `customer` | `hanaCustomer` | `HanaCustomer` |

### Full naming pattern

| GraphQL element | Pattern | Example (`hana.customer`) |
|----------------|---------|---------------------------|
| List query | `{schema}{Table}` | `hanaCustomer` |
| Connection | `{schema}{Table}Connection` | `hanaCustomerConnection` |
| Aggregate | `{schema}{Table}Aggregate` | `hanaCustomerAggregate` |
| Create mutation | `create{Schema}{Table}` | `createHanaCustomer` |
| Update mutation | `update{Schema}{Table}` | `updateHanaCustomer` |
| Delete mutation | `delete{Schema}{Table}` | `deleteHanaCustomer` |
| Bulk create | `createMany{Schema}{Table}` | `createManyHanaCustomer` |
| Stored procedure | `call{Schema}{Proc}` | `callHanaTransferFunds` |
| Subscription | `{schema}{Table}Changes` | `hanaCustomerChanges` |
| FK relationship | `{schema}{ReferencedTable}` | `hanaCustomer` (on orders) |
| Enum type | `{Schema}{EnumName}` | `HanaOrderStatus` |

The prefix is always applied, even with a single schema. This prevents client code from breaking when additional schemas are added later.

## Query Examples

### List with filter

```graphql
{
  hanaCustomer(
    where: { active: { eq: true } }
    orderBy: { last_name: ASC }
    limit: 10
  ) {
    customer_id
    first_name
    last_name
  }
}
```

### Cross-Schema Relationships

Foreign keys across schemas are resolved automatically. If `sales.orders` has a FK to `public.users`:

```graphql
{
  salesOrders {
    order_id
    amount
    publicUsers {
      user_id
      name
    }
  }
}
```

### Mutations

```graphql
mutation {
  createHanaCustomer(input: {
    first_name: "Alice"
    last_name: "Smith"
    email: "alice@example.com"
  }) {
    customer_id
  }
}
```

### Connection (Cursor Pagination)

```graphql
{
  hanaCustomerConnection(first: 10, after: "cursor_value") {
    edges {
      node { customer_id first_name }
      cursor
    }
    pageInfo { hasNextPage endCursor }
    totalCount
  }
}
```

### Aggregate

```graphql
{
  hanaOrdersAggregate {
    count
    sum { total_amount }
    avg { total_amount }
  }
}
```

## Introspection

Schema introspection returns prefixed type names:

```graphql
{
  __schema {
    queryType {
      fields { name }
    }
  }
}
```

Returns fields like `hanaCustomer`, `hanaCustomerConnection`, `hanaCustomerAggregate`, `salesOrders`, etc.

Enum types include a description showing their origin:

```graphql
{
  __type(name: "HanaOrderStatus") {
    kind
    description   # "Enum order_status from schema hana"
    enumValues { name }
  }
}
```

## Performance

Excalibase loads all schema metadata in a **single SQL query** regardless of how many schemas are configured. This uses a CTE (Common Table Expression) with `UNION ALL` that fetches columns, primary keys, foreign keys, views, enums, composite types, stored procedures, and computed fields in one round-trip.

| Schemas | Queries |
|---------|---------|
| 1 | 1 |
| 5 | 1 |
| 10 | 1 |

## Docker Compose

```yaml
environment:
  APP_SCHEMAS: public,sales           # comma-separated
  # or
  APP_SCHEMAS: ALL                     # auto-discover
  APP_DATABASE_TYPE: postgres
```

## MySQL

MySQL treats database names as schemas. The same `app.schemas` config works:

```yaml
app:
  schemas: excalibase
  database-type: mysql
```

```graphql
{
  excalibaseCustomer {
    customer_id
    first_name
  }
}
```
