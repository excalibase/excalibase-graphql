# API Reference

Excalibase GraphQL provides a powerful, auto-generated GraphQL API that mirrors your PostgreSQL database schema. This reference covers all the capabilities and features available through the GraphQL interface.

## Overview

<div class="feature-grid">
<div class="feature-card">
<h3>🔍 Queries</h3>
<p>Flexible data retrieval with filtering, sorting, and pagination. Supports complex nested queries and relationship traversal.</p>
</div>

<div class="feature-card">
<h3>✏️ Mutations</h3>
<p>Create, update, and delete operations with full CRUD support. Batch operations and optimistic updates included.</p>
</div>

<div class="feature-card">
<h3>🎯 Filtering</h3>
<p>Advanced filtering system with 15+ operators including string matching, numeric comparisons, and date ranges.</p>
</div>

<div class="feature-card">
<h3>📄 Pagination</h3>
<p>Cursor-based pagination following Relay specifications. Supports both forward and backward pagination.</p>
</div>

<div class="feature-card">
<h3>🔗 Relationships</h3>
<p>Automatic relationship resolution based on foreign keys. Supports one-to-one, one-to-many, and many-to-many relationships.</p>
</div>

<div class="feature-card">
<h3>⚡ Performance</h3>
<p>Optimized for performance with N+1 query prevention, connection pooling, and intelligent batching.</p>
</div>
</div>

## GraphQL Endpoint

<div class="api-endpoint">
<span class="method">POST</span>
<code>http://localhost:10000/graphql</code>
</div>

## Performance Metrics

Our API is designed for high performance with the following benchmarks:

- <span class="perf-metric">Simple queries: < 200ms</span>
- <span class="perf-metric">Complex filtering: < 800ms</span>
- <span class="perf-metric">Large result sets: < 1000ms</span>
- <span class="perf-metric">Concurrent requests: 20+ simultaneous</span>

## Schema Introspection

Excalibase GraphQL supports full schema introspection, allowing you to explore your API structure:

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
        }
      }
    }
  }
}
```

## Quick Example

Here's a simple example showing the power of the auto-generated API:

```graphql
# Query with filtering, sorting, and relationship resolution
query {
  users(
    where: {
      name: { contains: "john" }
      active: { eq: true }
    }
    orderBy: [{ created_at: DESC }]
    first: 10
  ) {
    edges {
      node {
        id
        name
        email
        posts {
          edges {
            node {
              title
              published_at
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

## Type System

Excalibase GraphQL automatically generates GraphQL types from your PostgreSQL tables:

| PostgreSQL Type | GraphQL Type | Description |
|-----------------|--------------|-------------|
| `INTEGER` | `Int` | 32-bit signed integer |
| `BIGINT` | `BigInt` | 64-bit signed integer (as string) |
| `REAL` | `Float` | Single precision float |
| `DOUBLE PRECISION` | `Float` | Double precision float |
| `NUMERIC` | `BigFloat` | Arbitrary precision decimal (as string) |
| `TEXT`, `VARCHAR` | `String` | UTF-8 string |
| `BOOLEAN` | `Boolean` | True/false value |
| `DATE` | `Date` | ISO 8601 date |
| `TIMESTAMP` | `DateTime` | ISO 8601 datetime |
| `JSON`, `JSONB` | `JSON` | JSON object (as string) |
| `UUID` | `UUID` | UUID string |

## API Sections

Explore the different aspects of the API:

- **[Advanced Filtering →](../filtering.md)** - Modern object-based filtering system
- **[Testing Coverage →](../testing.md)** - Comprehensive test documentation
- **[Contributing →](../CONTRIBUTING.md)** - How to contribute to the project

## Rate Limiting

The API includes built-in rate limiting to ensure fair usage:

- **Query complexity**: Maximum depth of 15 levels
- **Rate limit**: 1000 requests per minute per IP
- **Timeout**: 30 seconds per query

!!! note "Authentication"
    Authentication and authorization features are currently in development. The current version provides full access to configured database schemas.

## Error Handling

Excalibase GraphQL provides detailed error information following GraphQL specifications:

```json
{
  "errors": [
    {
      "message": "Field 'nonExistentField' not found on type 'User'",
      "locations": [
        {
          "line": 3,
          "column": 5
        }
      ],
      "path": ["users", 0, "nonExistentField"]
    }
  ]
}
```

## Next Steps

- Learn about [Advanced Filtering](../filtering.md)
- Check out [Testing Coverage](../testing.md)
- Read [Contributing Guidelines](../CONTRIBUTING.md)
- Visit the [GitHub Repository](https://github.com/excalibase/excalibase-graphql) 