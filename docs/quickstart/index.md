# Getting Started

Excalibase GraphQL generates a full GraphQL API from your database schema — no code required.

## Choose Your Backend

**PostgreSQL** — full feature set (http://localhost:10000/graphql):
```bash
docker-compose up -d
```
JSON/JSONB, arrays, network types, composite types, domain types, views, materialized views, stored procedures, computed fields, subscriptions, Row-Level Security.

**MySQL** — (http://localhost:10001/graphql):
```bash
docker-compose -f docker-compose.mysql.yml up -d
```
CRUD, ENUM/JSON types, views, stored procedures, FK relationships.

## Key Concepts

### Everything is auto-generated

Excalibase reads your database schema at startup and builds the GraphQL schema automatically. Tables become query/mutation types, foreign keys become relationship fields, stored procedures become mutations, views become read-only query fields.

### Include FK columns for relationships

When traversing a relationship, you must include the foreign key column in your selection — the resolver reads that value to fetch the related row:

```graphql
{
  orders {
    order_id
    customer_id     # ← required for relationship to work
    customer {
      first_name
      last_name
    }
  }
}
```

### Stored procedure results are JSON strings

OUT parameters come back as a single JSON string. Parse it on the client:

```js
const raw = data.callTransferFunds;
const result = JSON.parse(raw);
// result.p_status === "SUCCESS"
```

### orderBy syntax differs between backends

- **PostgreSQL**: enum value — `orderBy: { column: ASC }`
- **MySQL**: string value — `orderBy: { column: "ASC" }`

## What's in the Sample Data

The Docker stacks initialize with tables, views, stored procedures, and seed data. See the [Installation Guide](../quick-start.md) for the full list.

## Next Steps

- [Installation Guide →](../quick-start.md) — Docker setup, native binary, local dev
- [API Reference →](../api/index.md) — full schema documentation
- [Filtering →](../filtering.md) — filter operators and examples
