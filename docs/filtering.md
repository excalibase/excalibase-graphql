# GraphQL Filtering Documentation

Excalibase GraphQL now supports modern, object-based GraphQL filtering syntax that provides consistency with industry standards and PostgREST-style APIs.

## Filter Syntax Overview

### New Syntax (Recommended)

Instead of the old flat syntax like `customer_id_eq: 524`, we now use nested object filters:

```graphql
{
  customer(where: { customer_id: { eq: 524 } }) {
    customer_id
    first_name
    last_name
  }
}
```

### Legacy Syntax (Still Supported)

The old syntax continues to work for backward compatibility:

```graphql
{
  customer(customer_id: 524) {
    customer_id
    first_name
    last_name
  }
}
```

## Available Filter Operations

### Equality Operations

```graphql
# Equals
where: { customer_id: { eq: 524 } }

# Not equals
where: { customer_id: { neq: 524 } }
```

### Comparison Operations

```graphql
# Greater than
where: { customer_id: { gt: 500 } }

# Greater than or equal
where: { customer_id: { gte: 500 } }

# Less than
where: { customer_id: { lt: 600 } }

# Less than or equal
where: { customer_id: { lte: 600 } }

# Range query (multiple conditions on same field)
where: { customer_id: { gte: 524, lte: 526 } }
```

### String Operations

```graphql
# Contains text
where: { first_name: { contains: "John" } }

# Starts with text
where: { first_name: { startsWith: "John" } }

# Ends with text
where: { last_name: { endsWith: "son" } }

# SQL LIKE pattern
where: { first_name: { like: "J%" } }

# Case-insensitive LIKE pattern
where: { first_name: { ilike: "john" } }
```

### Null Operations

```graphql
# Is null
where: { middle_name: { isNull: true } }

# Is not null
where: { middle_name: { isNotNull: true } }
```

### List Operations

```graphql
# In list of values
where: { customer_id: { in: [524, 525, 526] } }

# Not in list of values
where: { customer_id: { notIn: [1, 2, 3] } }
```

## OR Conditions

### Simple OR

```graphql
{
  customer(or: [
    { customer_id: { eq: 524 } },
    { customer_id: { eq: 525 } }
  ]) {
    customer_id
    first_name
    last_name
  }
}
```

### Complex OR with Different Fields

```graphql
{
  customer(or: [
    { customer_id: { lt: 5 } },
    { customer_id: { gt: 615 } }
  ]) {
    customer_id
    first_name
    last_name
  }
}
```

### OR with Multiple Conditions

```graphql
{
  customer(or: [
    { first_name: { eq: "John" }, active: { eq: true } },
    { customer_id: { gte: 600 } }
  ]) {
    customer_id
    first_name
    last_name
    active
  }
}
```

## Combining WHERE and OR

You can combine both `where` and `or` conditions. They are combined with AND logic:

```graphql
{
  customer(
    where: { active: { eq: true } }
    or: [
      { customer_id: { lt: 10 } },
      { customer_id: { gt: 600 } }
    ]
  ) {
    customer_id
    first_name
    last_name
    active
  }
}
```

This translates to SQL: `WHERE active = true AND (customer_id < 10 OR customer_id > 600)`

## Pagination with Filters

### Offset-Based Pagination

```graphql
{
  customer(
    where: { active: { eq: true } }
    limit: 10
    offset: 20
    orderBy: { customer_id: ASC }
  ) {
    customer_id
    first_name
    last_name
  }
}
```

### Cursor-Based Pagination

```graphql
{
  customerConnection(
    where: { customer_id: { gte: 524 } }
    first: 3
    orderBy: { customer_id: ASC }
  ) {
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

## Filter Types by Data Type

### String Filters
- `eq`, `neq`, `contains`, `startsWith`, `endsWith`, `like`, `ilike`, `isNull`, `isNotNull`, `in`, `notIn`

### Integer/Numeric Filters
- `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `isNull`, `isNotNull`, `in`, `notIn`

### Boolean Filters
- `eq`, `neq`, `isNull`, `isNotNull`

### DateTime Filters
- `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `isNull`, `isNotNull`, `in`, `notIn`

**Supported Date Formats:**
- `"2023-12-25"` (yyyy-MM-dd)
- `"2023-12-25 14:30:00"` (yyyy-MM-dd HH:mm:ss)
- `"2023-12-25 14:30:00.123"` (with milliseconds)
- `"2023-12-25T14:30:00Z"` (ISO 8601)

## Examples by Use Case

### Find Customers in a City

```graphql
{
  customer(where: { address: { city: { eq: "New York" } } }) {
    customer_id
    first_name
    last_name
  }
}
```

### Find Recent Customers

```graphql
{
  customer(where: { create_date: { gte: "2023-01-01" } }) {
    customer_id
    first_name
    last_name
    create_date
  }
}
```

### Search by Name Pattern

```graphql
{
  customer(where: { first_name: { ilike: "mar%" } }) {
    customer_id
    first_name
    last_name
  }
}
```

### Complex Business Logic

```graphql
{
  customer(
    where: { active: { eq: true } }
    or: [
      { store_id: { eq: 1 }, customer_id: { lt: 100 } },
      { store_id: { eq: 2 }, customer_id: { gte: 500 } }
    ]
    orderBy: { customer_id: DESC }
    limit: 20
  ) {
    customer_id
    first_name
    last_name
    store_id
    active
  }
}
```

## Performance Tips

1. **Use Indexes**: Ensure database columns used in filters have appropriate indexes
2. **Limit Results**: Always use `limit` or pagination to avoid large result sets
3. **Specific Filters**: Use specific filters (like `eq`, `in`) when possible instead of pattern matching
4. **Order By**: Include `orderBy` for consistent pagination results

## Security Features

### SQL Injection Prevention

All filter operations are parameterized to prevent SQL injection:

```graphql
# This is safe - parameters are properly escaped
{
  users(where: { name: { eq: "'; DROP TABLE users; --" } }) {
    id name
  }
}
```

### Input Validation

- **Type validation** for all filter inputs
- **Length validation** for string inputs
- **Character encoding** validation
- **JSON structure** validation

## Migration from Legacy Syntax

### Before (Legacy)
```graphql
{
  customer(customer_id_gte: 524, customer_id_lte: 526, active: true) {
    customer_id
    first_name
    last_name
  }
}
```

### After (New Syntax)
```graphql
{
  customer(where: { 
    customer_id: { gte: 524, lte: 526 }, 
    active: { eq: true } 
  }) {
    customer_id
    first_name
    last_name
  }
}
```

### Migration Benefits

The new syntax provides:
- **Better IDE support** with GraphQL tooling
- **Type safety** with proper GraphQL types
- **Consistent API** following GraphQL best practices
- **Enhanced readability** with nested object structure
- **Future extensibility** for more complex filter operations

## Error Handling

### Invalid Filter Values

```graphql
# Invalid date format
{
  users(where: { created_at: { eq: "invalid-date" } }) {
    id name
  }
}
```

Response:
```json
{
  "errors": [
    {
      "message": "Invalid date format: 'invalid-date'",
      "extensions": {
        "code": "INVALID_DATE_FORMAT"
      }
    }
  ]
}
```

### Type Mismatches

```graphql
# String value for integer field
{
  users(where: { id: { eq: "not-a-number" } }) {
    id name
  }
}
```

Response:
```json
{
  "errors": [
    {
      "message": "Invalid value for integer field: 'not-a-number'",
      "extensions": {
        "code": "TYPE_MISMATCH"
      }
    }
  ]
}
```

## Testing and Quality Assurance

Our filtering system includes comprehensive testing:

- **41+ test methods** covering all filter operations
- **Performance testing** with 1000+ record datasets
- **Security testing** for SQL injection prevention
- **Edge case testing** for boundary conditions
- **Integration testing** with real PostgreSQL databases

For detailed testing documentation, see [Testing Documentation](testing.md).

---

The new filtering syntax is more expressive, follows GraphQL best practices, and provides better tooling support in GraphQL IDEs. 