# GraphQL API Documentation

Welcome to the Excalibase GraphQL API documentation. This API automatically generates a complete GraphQL schema from your PostgreSQL database, **including comprehensive Enhanced PostgreSQL Types support**.

## 🚀 Getting Started

The Excalibase GraphQL API provides instant access to your PostgreSQL data through a modern GraphQL interface. Simply point the application at your database and start querying immediately.

**Base URL:** `http://localhost:10000/graphql`

## 📊 Enhanced PostgreSQL Type Support

Excalibase now supports **Enhanced PostgreSQL Types** with full GraphQL integration, comprehensive filtering, and proper type mapping.

### Enhanced Type Coverage

| PostgreSQL Type | GraphQL Type | Description | Filter Support |
|-----------------|--------------|-------------|----------------|
| `INTEGER` | `Int` | 32-bit signed integer | ✅ Full |
| `BIGINT` | `Int` | 64-bit signed integer | ✅ Full |
| `REAL` | `Float` | Single precision float | ✅ Full |
| `DOUBLE PRECISION` | `Float` | Double precision float | ✅ Full |
| `NUMERIC(p,s)` | `Float` | Arbitrary precision decimal | ✅ Enhanced |

| `TEXT`, `VARCHAR` | `String` | UTF-8 string | ✅ Full |
| `BOOLEAN` | `Boolean` | True/false value | ✅ Full |
| `DATE` | `String` | ISO 8601 date | ✅ Full |
| `TIMESTAMP` | `String` | ISO 8601 datetime | ✅ Full |
| **`TIMESTAMPTZ`** | `String` | **Timezone-aware timestamp** | ✅ **Enhanced** |
| **`TIMETZ`** | `String` | **Time with timezone** | ✅ **Enhanced** |
| **`INTERVAL`** | `String` | **Time interval** | ✅ **Enhanced** |
| **`JSON`** | **`JSON`** | **JSON object (custom scalar)** | ✅ **Enhanced** |
| **`JSONB`** | **`JSON`** | **Binary JSON (custom scalar)** | ✅ **Enhanced** |
| **`INTEGER[]`** | **`[Int]`** | **Array of integers** | ✅ **Enhanced** |
| **`TEXT[]`** | **`[String]`** | **Array of strings** | ✅ **Enhanced** |
| **`INET`** | `String` | **IP address** | ✅ **Enhanced** |
| **`CIDR`** | `String` | **Network address** | ✅ **Enhanced** |
| **`MACADDR`** | `String` | **MAC address** | ✅ **Enhanced** |
| **`BYTEA`** | `String` | **Binary data** | ✅ **Enhanced** |
| **`XML`** | `String` | **XML document** | ✅ **Enhanced** |
| `UUID` | `ID` | UUID string | ✅ Full |

## 🔍 Schema Introspection

Use GraphQL introspection to explore your available types and fields, including enhanced types:

```graphql
query IntrospectionQuery {
  __schema {
    types {
      name
      description
      fields {
        name
        type {
          name
          ofType {
            name
          }
        }
      }
    }
  }
}
```

## Quick Example

Here's a simple example showing the power of the auto-generated API with enhanced types:

```graphql
# Query with enhanced types, filtering, sorting, and relationship resolution
query {
  users(
    where: {
      profile: { hasKey: "preferences" }           # JSON filtering
      tags: { contains: "developer" }              # Array filtering
      last_login: { gte: "2023-01-01T00:00:00Z" }  # Timezone-aware filtering
      ip_address: { like: "192.168.%" }            # Network type filtering
    }
    orderBy: [{ created_at: DESC }]
    first: 10
  ) {
    edges {
      node {
        id
        name
        email
        profile                                     # JSON field
        tags                                        # Array field
        last_login                                  # TIMESTAMPTZ field
        ip_address                                  # INET field
        posts {
          edges {
            node {
              title
              metadata                              # JSONB field
              categories                            # TEXT[] field
              published_at                          # TIMESTAMPTZ field
            }
          }
        }
      }
    }
    pageInfo {
      hasNextPage
      endCursor
    }
  }
}
```

## Enhanced Types Examples

### JSON/JSONB Operations

```graphql
# Query users with specific JSON profile settings
query {
  users(where: {
    profile: {
      hasKey: "preferences"
      contains: "{\"theme\": \"dark\"}"
    }
  }) {
    id
    name
    profile    # Returns JSON as string, validated by custom scalar
  }
}

# Complex JSON path filtering
query {
  products(where: {
    metadata: {
      path: ["specs", "processor", "cores"]
    }
  }) {
    name
    metadata
  }
}
```

### Array Type Operations

```graphql
# Query posts with specific categories
query {
  posts(where: {
    categories: { hasAny: ["postgresql", "graphql"] }
    tags: { contains: "tutorial" }
  }) {
    title
    categories   # Returns as GraphQL list: ["tech", "database"]
    tags         # Returns as GraphQL list: ["tutorial", "beginner"]
  }
}

# Array length filtering
query {
  users(where: {
    skills: { length: { gte: 3 } }
  }) {
    name
    skills
  }
}
```

### Enhanced DateTime Operations

```graphql
# Timezone-aware datetime filtering
query {
  events(where: {
    start_time: {
      gte: "2023-12-01T00:00:00Z"
      lt: "2024-01-01T00:00:00Z"
    }
    timezone: { eq: "UTC" }
  }) {
    name
    start_time     # TIMESTAMPTZ with timezone info
    duration       # INTERVAL type
  }
}
```

### Network Type Operations

```graphql
# Network address filtering
query {
  servers(where: {
    ip_address: { like: "192.168.%" }
    status: { eq: "active" }
  }) {
    hostname
    ip_address     # INET type
    mac_address    # MACADDR type
    network        # CIDR type
  }
}
```

## Type System

Excalibase GraphQL automatically generates GraphQL types from your PostgreSQL tables with **Enhanced Types Support**:

### Basic Types
| PostgreSQL Type | GraphQL Type | Description |
|-----------------|--------------|-------------|
| `INTEGER` | `Int` | 32-bit signed integer |
| `BIGINT` | `Int` | 64-bit signed integer |
| `REAL` | `Float` | Single precision float |
| `DOUBLE PRECISION` | `Float` | Double precision float |
| `TEXT`, `VARCHAR` | `String` | UTF-8 string |
| `BOOLEAN` | `Boolean` | True/false value |
| `DATE` | `String` | ISO 8601 date |
| `TIMESTAMP` | `String` | ISO 8601 datetime |
| `UUID` | `ID` | UUID string |

### Enhanced Types ✅ **NEW**

| PostgreSQL Type | GraphQL Type | Description | Example |
|-----------------|--------------|-------------|---------|
| **`JSON`** | **`JSON`** | **JSON object (custom scalar)** | `{"name": "John", "age": 30}` |
| **`JSONB`** | **`JSON`** | **Binary JSON (custom scalar)** | `{"score": 95, "active": true}` |
| **`INTEGER[]`** | **`[Int]`** | **Array of integers** | `[1, 2, 3, 4, 5]` |
| **`TEXT[]`** | **`[String]`** | **Array of strings** | `["apple", "banana", "cherry"]` |
| **`TIMESTAMPTZ`** | `String` | **Timezone-aware timestamp** | `"2023-12-25T14:30:00Z"` |
| **`TIMETZ`** | `String` | **Time with timezone** | `"14:30:00+05:00"` |
| **`INTERVAL`** | `String` | **Time interval** | `"2 days 3 hours"` |
| **`NUMERIC(p,s)`** | `Float` | **Precision decimal** | `1234.56` |

| **`INET`** | `String` | **IP address** | `"192.168.1.1"` |
| **`CIDR`** | `String` | **Network address** | `"192.168.0.0/24"` |
| **`MACADDR`** | `String` | **MAC address** | `"08:00:27:00:00:00"` |
| **`BYTEA`** | `String` | **Binary data (hex)** | `"48656c6c6f"` |
| **`XML`** | `String` | **XML document** | `"<user><name>John</name></user>"` |

## API Sections

Explore the different aspects of the API:

- **[Advanced Filtering →](../filtering.md)** - Modern object-based filtering system with enhanced types
- **[Testing Coverage →](../testing.md)** - Comprehensive test documentation (42+ tests)
- **[Contributing →](../CONTRIBUTING.md)** - How to contribute to the project

## Enhanced Filtering Examples

### JSON/JSONB Filtering
```graphql
{
  users(where: {
    profile: {
      hasKey: "preferences"
      contains: "{\"notifications\": true}"
      path: ["settings", "theme"]
    }
  }) {
    name
    profile
  }
}
```

### Array Filtering
```graphql
{
  posts(where: {
    categories: { hasAny: ["tech", "programming"] }
    tags: { contains: "postgresql" }
  }) {
    title
    categories
    tags
  }
}
```

### Network Type Filtering
```graphql
{
  servers(where: {
    ip_address: { like: "192.168.%" }
    mac_address: { startsWith: "08:00" }
  }) {
    hostname
    ip_address
    mac_address
  }
}
```

### Enhanced DateTime Filtering
```graphql
{
  events(where: {
    start_time: { gte: "2023-01-01T00:00:00Z" }
    duration: { gt: "1 hour" }
  }) {
    name
    start_time
    duration
  }
}
```

## Rate Limiting

The API includes built-in rate limiting to ensure fair usage:

- **Query complexity**: Maximum depth of 15 levels
- **Rate limit**: 1000 requests per minute per IP
- **Timeout**: 30 seconds per query

### Rate Limiting Headers

Every response includes rate limiting information:

```http
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 999
X-RateLimit-Reset: 1640995200
```

## Error Handling

The API follows GraphQL error conventions with enhanced type-specific error handling:

### Standard Errors

```json
{
  "errors": [
    {
      "message": "Cannot query field 'invalidField' on type 'User'",
      "extensions": {
        "code": "GRAPHQL_VALIDATION_FAILED"
      }
    }
  ]
}
```

### Enhanced Types Errors ✅ **NEW**

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

## Performance Characteristics

### Response Time Targets
- **Simple queries**: < 200ms
- **Complex filtering**: < 800ms
- **Large result sets**: < 1000ms
- **Enhanced types queries**: < 300ms ✅ **NEW**

### Enhanced Types Performance ✅ **NEW**
- **JSON/JSONB operations**: < 250ms
- **Array filtering**: < 200ms
- **Network type queries**: < 150ms
- **Mixed enhanced types**: < 400ms

## Schema Generation

The API automatically generates GraphQL schemas with the following features:

### Basic Schema Features
- **Query types** for all tables
- **Mutation types** for CRUD operations
- **Filter input types** for advanced filtering
- **Connection types** for pagination
- **Relationship resolution** for foreign keys

### Enhanced Schema Features ✅ **NEW**
- **JSON scalar types** for JSON/JSONB columns
- **Array list types** for PostgreSQL arrays
- **Enhanced filter types** for JSON, arrays, network types
- **Timezone-aware datetime handling**
- **Custom scalar validation** for enhanced types

## Mutations

### Basic Mutations

```graphql
# Create a new user
mutation {
  createUser(input: {
    name: "John Doe"
    email: "john@example.com"
  }) {
    id
    name
    email
    created_at
  }
}
```

### Enhanced Types Mutations ✅ **NEW**

```graphql
# Create user with enhanced types
mutation {
  createUser(input: {
    name: "Alice Johnson"
    email: "alice@example.com"
    profile: "{\"theme\": \"dark\", \"notifications\": true}"
    tags: ["developer", "postgresql", "graphql"]
    ip_address: "192.168.1.100"
  }) {
    id
    name
    profile       # JSON field
    tags          # Array field
    ip_address    # Network field
    created_at    # TIMESTAMPTZ field
  }
}

# Update with JSON operations
mutation {
  updateUser(input: {
    id: 1
    profile: "{\"preferences\": {\"theme\": \"light\", \"lang\": \"en\"}}"
    tags: ["senior-developer", "team-lead"]
  }) {
    id
    profile
    tags
  }
}
```

## Subscriptions

*Coming soon: Real-time GraphQL subscriptions for live data updates*

## Security

### SQL Injection Prevention
All queries are parameterized and validated, including enhanced types:

- ✅ **Standard SQL injection protection**
- ✅ **JSON injection prevention** 
- ✅ **Array parameter validation**
- ✅ **Network address validation**
- ✅ **Input sanitization** for all enhanced types

### Authentication
*Coming soon: JWT and API key authentication*

## Supported PostgreSQL Features

### ✅ Currently Supported
- **Basic data types** (integer, text, boolean, date, UUID)
- **Enhanced data types** (JSON/JSONB, arrays, enhanced datetime, network, binary, XML) ✅ **NEW**
- **Primary keys and foreign keys**
- **Table relationships** (one-to-one, one-to-many)
- **Advanced filtering** with enhanced type support
- **Cursor-based pagination**
- **CRUD operations** with enhanced type validation

### 🔄 Coming Soon
- **Views and materialized views**
- **Check constraints and unique constraints**
- **PostGIS spatial types**
- **Multi-schema support**
- **Stored procedures as mutations**

## Limits and Quotas

- **Max query depth**: 15 levels
- **Max query complexity**: 1000 points
- **Max result set**: 10,000 records per query
- **Max mutation batch**: 100 operations
- **Enhanced types payload**: 10MB JSON/XML, 50MB binary ✅ **NEW**

---

The Excalibase GraphQL API provides a powerful, type-safe interface to your PostgreSQL database with comprehensive enhanced types support, making it easy to work with modern PostgreSQL features through GraphQL. 