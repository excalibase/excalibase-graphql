# GraphQL Filtering Documentation

Excalibase GraphQL now supports modern, object-based GraphQL filtering syntax that provides consistency with industry standards and PostgREST-style APIs, **with comprehensive Enhanced PostgreSQL Types support**.

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

## 🎯 Enhanced PostgreSQL Types Filtering ✅ **NEW**

We now support comprehensive filtering for enhanced PostgreSQL types including JSON/JSONB, arrays, enhanced datetime, network types, and more.

### JSON/JSONB Filtering ✅ **NEW**

**Basic JSON Operations:**
```graphql
# JSON equality
where: { profile: { eq: "{\"theme\": \"dark\"}" } }

# JSON not equals
where: { profile: { neq: "{\"active\": false}" } }

# Check if JSON has a key
where: { profile: { hasKey: "preferences" } }

# Check if JSON has multiple keys
where: { profile: { hasKeys: ["theme", "notifications"] } }

# JSON contains (supports nested objects)
where: { profile: { contains: "{\"settings\": {\"theme\": \"dark\"}}" } }

# JSON path operations
where: { profile: { path: ["settings", "theme"] } }

# JSON path as text
where: { profile: { pathText: ["user", "name"] } }
```

**Complex JSON Filtering:**
```graphql
{
  users(where: {
    profile: { 
      hasKey: "preferences",
      contains: "{\"active\": true}"
    }
  }) {
    name
    profile
  }
}
```

### Array Type Filtering ✅ **NEW**

**Array Operations:**
```graphql
# Array contains element
where: { tags: { contains: "developer" } }

# Array has any of these elements
where: { categories: { hasAny: ["tech", "programming"] } }

# Array has all of these elements  
where: { skills: { hasAll: ["java", "postgresql"] } }

# Array length operations
where: { items: { length: { gt: 5 } } }

# Check if array is not null
where: { tags: { isNotNull: true } }
```

**Real-world Array Examples:**
```graphql
{
  posts(where: {
    categories: { contains: "postgresql" },
    tags: { hasAny: ["development", "database"] }
  }) {
    title
    categories
    tags
  }
}
```

### Enhanced DateTime Filtering ✅ **NEW**

**Timezone-Aware Operations:**
```graphql
# TIMESTAMPTZ filtering with timezone
where: { created_at: { gte: "2023-01-01T00:00:00Z" } }

# Time zone comparison
where: { last_login: { 
  gte: "2023-12-01T00:00:00+05:00",
  lt: "2024-01-01T00:00:00+05:00"
} }

# INTERVAL operations  
where: { session_duration: { gt: "2 hours" } }

# TIMETZ filtering
where: { daily_checkin: { eq: "09:00:00+00" } }
```

**DateTime Range Queries:**
```graphql
{
  events(where: {
    start_time: { 
      gte: "2023-12-01T00:00:00Z",
      lt: "2023-12-31T23:59:59Z"
    },
    timezone: { eq: "UTC" }
  }) {
    name
    start_time
    timezone
  }
}
```

### Network Type Filtering ✅ **NEW**

**Network Address Operations:**
```graphql
# INET filtering
where: { ip_address: { eq: "192.168.1.1" } }

# CIDR filtering
where: { network: { eq: "192.168.0.0/24" } }

# MACADDR filtering
where: { mac_address: { eq: "08:00:27:00:00:00" } }

# Pattern matching for network types
where: { ip_address: { like: "192.168.%" } }

# Network range operations
where: { server_ip: { startsWith: "10." } }
```

**Network Type Examples:**
```graphql
{
  servers(where: {
    ip_address: { like: "192.168.%" },
    status: { eq: "active" }
  }) {
    hostname
    ip_address
    mac_address
  }
}
```

### Binary and XML Type Filtering ✅ **NEW**

**Binary Data (BYTEA):**
```graphql
# Binary data existence
where: { file_data: { isNotNull: true } }

# Binary data pattern (as hex string)
where: { signature: { startsWith: "48656c6c6f" } }
```

**XML Operations:**
```graphql
# XML content filtering
where: { metadata: { contains: "<status>active</status>" } }

# XML structure validation
where: { config: { isNotNull: true } }
```

### Precision Numeric Filtering ✅ **NEW**

**NUMERIC Types:**
```graphql
# Precision numeric operations
where: { price: { gte: 99.99, lte: 999.99 } }


where: { salary: { gt: 50000.00 } }

# Decimal precision handling
where: { tax_rate: { eq: 8.25 } }
```

## Available Filter Operations

### All Data Types
- `eq`, `neq`, `isNull`, `isNotNull`, `in`, `notIn`

### String Operations
- `contains`, `startsWith`, `endsWith`, `like`, `ilike`

### Numeric Operations
- `gt`, `gte`, `lt`, `lte`

### JSON Operations ✅ **NEW**
- `hasKey`, `hasKeys`, `contains`, `containedBy`, `path`, `pathText`

### Array Operations ✅ **NEW**
- `contains`, `hasAny`, `hasAll`, `length`

### Network Operations ✅ **NEW**
- `eq`, `neq`, `like`, `startsWith`, `endsWith`

### Date/Time Operations
- Supports multiple formats: `"2023-12-25"`, `"2023-12-25T14:30:00Z"`, ISO 8601 with timezones

## Equality Operations

```graphql
# Equals
where: { customer_id: { eq: 524 } }

# Not equals
where: { customer_id: { neq: 524 } }
```

## Comparison Operations

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

## String Operations

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

## Null Operations

```graphql
# Is null
where: { middle_name: { isNull: true } }

# Is not null
where: { middle_name: { isNotNull: true } }
```

## List Operations

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

### OR with Enhanced Types ✅ **NEW**

```graphql
{
  users(or: [
    { profile: { hasKey: "admin" } },
    { tags: { contains: "moderator" } },
    { ip_address: { like: "10.%" } }
  ]) {
    id
    name
    profile
    tags
    ip_address
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

### Enhanced Types with WHERE and OR ✅ **NEW**

```graphql
{
  posts(
    where: { 
      published: { eq: true },
      categories: { hasAny: ["tech", "programming"] }
    }
    or: [
      { metadata: { hasKey: "featured" } },
      { author_id: { in: [1, 2, 3] } }
    ]
  ) {
    title
    categories
    metadata
    published
  }
}
```

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

### Enhanced Types Pagination ✅ **NEW**

```graphql
{
  posts(
    where: { 
      categories: { contains: "postgresql" },
      created_at: { gte: "2023-01-01T00:00:00Z" }
    }
    limit: 10
    orderBy: { created_at: DESC }
  ) {
    title
    categories
    created_at
    metadata
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

### JSON Filters ✅ **NEW**
- `eq`, `neq`, `hasKey`, `hasKeys`, `contains`, `containedBy`, `path`, `pathText`, `isNull`, `isNotNull`

### Array Filters ✅ **NEW**
- `contains`, `hasAny`, `hasAll`, `length`, `isNull`, `isNotNull`

### Network Filters ✅ **NEW**
- `eq`, `neq`, `like`, `startsWith`, `endsWith`, `isNull`, `isNotNull`

**Supported Date Formats:**
- `"2023-12-25"` (yyyy-MM-dd)
- `"2023-12-25 14:30:00"` (yyyy-MM-dd HH:mm:ss)
- `"2023-12-25 14:30:00.123"` (with milliseconds)
- `"2023-12-25T14:30:00Z"` (ISO 8601)
- `"2023-12-25T14:30:00+05:00"` (ISO 8601 with timezone) ✅ **NEW**

## Examples by Use Case

### Find Users with Specific Profile Settings ✅ **NEW**

```graphql
{
  users(where: { 
    profile: { 
      hasKey: "preferences",
      contains: "{\"notifications\": true}"
    }
  }) {
    name
    email
    profile
  }
}
```

### Find Posts with Specific Categories ✅ **NEW**

```graphql
{
  posts(where: { 
    categories: { hasAny: ["postgresql", "graphql"] },
    published: { eq: true }
  }) {
    title
    categories
    published_at
  }
}
```

### Find Servers in Network Range ✅ **NEW**

```graphql
{
  servers(where: { 
    ip_address: { like: "192.168.%" },
    last_ping: { gte: "2023-12-01T00:00:00Z" }
  }) {
    hostname
    ip_address
    last_ping
  }
}
```

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

### Complex Business Logic with Enhanced Types ✅ **NEW**

```graphql
{
  users(
    where: { 
      active: { eq: true },
      profile: { hasKey: "subscription" }
    }
    or: [
      { 
        tags: { contains: "premium" },
        last_login: { gte: "2023-12-01T00:00:00Z" }
      },
      { 
        metadata: { hasKey: "admin" },
        ip_address: { like: "10.%" }
      }
    ]
    orderBy: { created_at: DESC }
    limit: 20
  ) {
    id
    name
    profile
    tags
    metadata
    last_login
    ip_address
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
   - **JSON/JSONB**: Use GIN indexes for JSON operations
   - **Arrays**: Use GIN indexes for array operations
   - **Network Types**: Use GIST indexes for network operations
2. **Limit Results**: Always use `limit` or pagination to avoid large result sets
3. **Specific Filters**: Use specific filters (like `eq`, `in`) when possible instead of pattern matching
4. **Order By**: Include `orderBy` for consistent pagination results
5. **Enhanced Types**: Use appropriate operators for each type (JSON path operations vs simple equality)

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

### Enhanced Types Security ✅ **NEW**

```graphql
# JSON injection attempts are safely handled
{
  users(where: { 
    profile: { 
      contains: "'; DROP TABLE users; --" 
    }
  }) {
    id name profile
  }
}

# Array injection attempts are parameterized
{
  posts(where: { 
    categories: { 
      hasAny: ["'; DELETE FROM posts; --", "tech"] 
    }
  }) {
    title categories
  }
}
```

### Input Validation

- **Type validation** for all filter inputs
- **Length validation** for string inputs
- **Character encoding** validation
- **JSON structure** validation ✅ **NEW**
- **Array format** validation ✅ **NEW**
- **Network address** validation ✅ **NEW**

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

### Enhanced Types Migration ✅ **NEW**

**Before (Not Supported):**
```graphql
# JSON/Array filtering was not available
```

**After (Enhanced Support):**
```graphql
{
  users(where: {
    profile: { hasKey: "preferences" },
    tags: { contains: "developer" },
    ip_address: { like: "192.168.%" }
  }) {
    name
    profile
    tags
    ip_address
  }
}
```

### Migration Benefits

The new syntax with enhanced types provides:
- **Better IDE support** with GraphQL tooling
- **Type safety** with proper GraphQL types for enhanced PostgreSQL types
- **Consistent API** following GraphQL best practices
- **Enhanced readability** with nested object structure
- **Future extensibility** for more complex filter operations
- **JSON/JSONB support** with path operations and key checking ✅ **NEW**
- **Array operations** with element and subset matching ✅ **NEW**
- **Network type filtering** with pattern matching ✅ **NEW**

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

### Enhanced Types Error Handling ✅ **NEW**

```graphql
# Invalid JSON format
{
  users(where: { profile: { eq: "invalid-json{" } }) {
    id name
  }
}
```

Response:
```json
{
  "errors": [
    {
      "message": "Invalid JSON format: 'invalid-json{'",
      "extensions": {
        "code": "INVALID_JSON_FORMAT"
      }
    }
  ]
}
```

```graphql
# Invalid network address
{
  servers(where: { ip_address: { eq: "invalid-ip" } }) {
    hostname ip_address
  }
}
```

Response:
```json
{
  "errors": [
    {
      "message": "Invalid network address format: 'invalid-ip'",
      "extensions": {
        "code": "INVALID_NETWORK_ADDRESS"
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

## 🔑 Composite Key Filtering ✅ **NEW**

Excalibase GraphQL provides comprehensive filtering support for tables with composite primary keys and composite foreign keys, allowing you to filter by multiple key components simultaneously.

### Composite Primary Key Filtering

**Filter by specific composite key:**
```graphql
{
  order_items(where: {
    order_id: { eq: 3 }
    product_id: { eq: 2 }
  }) {
    order_id
    product_id
    quantity
    price
  }
}
```

**Filter by single component of composite key:**
```graphql
{
  order_items(where: {
    order_id: { eq: 1 }
  }) {
    order_id
    product_id
    quantity
    price
  }
}
```

**Filter with ranges on composite keys:**
```graphql
{
  order_items(where: {
    order_id: { gte: 1, lte: 3 }
    product_id: { in: [1, 2, 3] }
  }) {
    order_id
    product_id
    quantity
    price
  }
}
```

### Composite Foreign Key Filtering

**Filter child table by composite foreign key:**
```graphql
{
  child_table(where: {
    parent_id1: { eq: 1 }
    parent_id2: { eq: 2 }
  }) {
    child_id
    parent_id1
    parent_id2
    description
    parent_table {        # Automatic relationship resolution
      parent_id1
      parent_id2
      name
    }
  }
}
```

### Complex OR Operations with Composite Keys

**Multiple composite key combinations:**
```graphql
{
  order_items(where: {
    or: [
      { order_id: { eq: 1 }, product_id: { eq: 1 } },
      { order_id: { eq: 2 }, product_id: { eq: 3 } },
      { order_id: { eq: 3 }, product_id: { eq: 2 } }
    ]
  }) {
    order_id
    product_id
    quantity
    price
  }
}
```

**Mixed individual and composite conditions:**
```graphql
{
  order_items(where: {
    or: [
      { order_id: { eq: 1 } },                    # All items for order 1
      { product_id: { eq: 5 } },                  # All instances of product 5
      { order_id: { eq: 3 }, product_id: { eq: 2 } }  # Specific composite key
    ]
  }) {
    order_id
    product_id
    quantity
    price
  }
}
```

### Advanced Composite Key Filtering

**Combine with other field filters:**
```graphql
{
  order_items(where: {
    order_id: { eq: 2 }
    product_id: { gte: 1 }
    quantity: { gt: 5 }
    price: { lt: 200.00 }
  }) {
    order_id
    product_id
    quantity
    price
  }
}
```

**Relationship filtering through composite keys:**
```graphql
{
  child_table(where: {
    parent_table: {
      name: { startsWith: "Parent" }
    }
  }) {
    child_id
    parent_id1
    parent_id2
    description
    parent_table {
      parent_id1
      parent_id2
      name
    }
  }
}
```

### Composite Key Mutations with Filtering

**Update specific composite key record:**
```graphql
mutation {
  updateOrder_items(input: {
    order_id: 3          # Required: part of composite PK
    product_id: 2        # Required: part of composite PK
    quantity: 10         # Updated field
    price: 299.99        # Updated field
  }) {
    order_id
    product_id
    quantity
    price
  }
}
```

**Delete using composite key:**
```graphql
mutation {
  deleteOrder_items(input: {
    order_id: 3
    product_id: 2
  }) {
    order_id
    product_id
    quantity
    price
  }
}
```

### Pagination with Composite Keys

**Ordered by composite primary key:**
```graphql
{
  order_items(
    orderBy: { order_id: ASC, product_id: ASC }
    limit: 10
    offset: 20
  ) {
    order_id
    product_id
    quantity
    price
  }
}
```

**Cursor-based pagination with composite keys:**
```graphql
{
  order_itemsConnection(
    first: 10
    after: "cursor123"
    orderBy: { order_id: ASC }
  ) {
    edges {
      node {
        order_id
        product_id
        quantity
        price
      }
      cursor
    }
    pageInfo {
      hasNextPage
      hasPreviousPage
      startCursor
      endCursor
    }
  }
}
```

### Performance Considerations

For optimal performance with composite key filtering:

1. **Index Strategy**: Ensure composite indexes exist for your composite keys:
   ```sql
   CREATE INDEX idx_order_items_composite ON order_items (order_id, product_id);
   ```

2. **Query Optimization**: Filter by leading columns of composite indexes when possible:
   ```graphql
   # Optimal - uses composite index
   where: { order_id: { eq: 1 }, product_id: { eq: 2 } }
   
   # Less optimal - may not use full index
   where: { product_id: { eq: 2 } }
   ```

3. **Relationship Performance**: When filtering through relationships, include foreign key fields in your selection:
   ```graphql
   {
     child_table {
       child_id
       parent_id1     # Include FK fields for performance
       parent_id2     # Include FK fields for performance
       parent_table {
         name
       }
     }
   }
   ```

## Testing and Quality Assurance

Our filtering system includes comprehensive testing with enhanced types support:

- **42+ test methods** covering all filter operations including enhanced types
- **Performance testing** with 1000+ record datasets including enhanced types
- **Security testing** for SQL injection prevention across all types including JSON/Array
- **Edge case testing** for boundary conditions and enhanced type validations
- **Integration testing** with real PostgreSQL databases and enhanced types

For detailed testing documentation, see [Testing Documentation](testing.md).

## Enhanced Types Support Summary ✅ **NEW**

| PostgreSQL Type | GraphQL Mapping | Filter Operations | Status |
|-----------------|-----------------|-------------------|---------|
| **JSON** | Custom `JSON` scalar | `eq`, `neq`, `hasKey`, `contains`, `path` | ✅ Complete |
| **JSONB** | Custom `JSON` scalar | `eq`, `neq`, `hasKey`, `contains`, `path` | ✅ Complete |
| **INTEGER[]** | `[Int]` list | `contains`, `hasAny`, `hasAll`, `length` | ✅ Complete |
| **TEXT[]** | `[String]` list | `contains`, `hasAny`, `hasAll`, `length` | ✅ Complete |
| **TIMESTAMPTZ** | `String` | `eq`, `neq`, `gt`, `gte`, `lt`, `lte` | ✅ Complete |
| **TIMETZ** | `String` | `eq`, `neq`, `gt`, `gte`, `lt`, `lte` | ✅ Complete |
| **INTERVAL** | `String` | `eq`, `neq`, `gt`, `gte`, `lt`, `lte` | ✅ Complete |
| **INET** | `String` | `eq`, `neq`, `like`, `startsWith` | ✅ Complete |
| **CIDR** | `String` | `eq`, `neq`, `like`, `startsWith` | ✅ Complete |
| **MACADDR** | `String` | `eq`, `neq`, `like`, `startsWith` | ✅ Complete |
| **BYTEA** | `String` | `eq`, `neq`, `isNull`, `isNotNull` | ✅ Complete |
| **XML** | `String` | `eq`, `neq`, `contains`, `like` | ✅ Complete |
| **NUMERIC(p,s)** | `Float` | `eq`, `neq`, `gt`, `gte`, `lt`, `lte` | ✅ Complete |
| **Composite Keys** | Multiple types | All operations + OR/AND logic | ✅ Complete |


---

The new filtering syntax with enhanced PostgreSQL types support and comprehensive composite key functionality is more expressive, follows GraphQL best practices, and provides better tooling support in GraphQL IDEs while enabling powerful operations on JSON, arrays, network types, composite keys, and more advanced PostgreSQL data structures. 