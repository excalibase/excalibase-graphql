# Excalibase GraphQL

<div style="text-align: center; margin: 2rem 0;">
  <h2 style="color: #2196f3; margin-bottom: 1rem;">Automatic GraphQL API generation from PostgreSQL database schemas</h2>
  <p style="font-size: 1.2em; color: #666;">Transform your PostgreSQL database into a powerful GraphQL API in minutes</p>
</div>

## Overview

Excalibase GraphQL is a Spring Boot application that automatically generates a complete GraphQL API from your existing PostgreSQL database. Simply point it at your database and get instant GraphQL queries and mutations with built-in pagination, filtering, and relationship resolution.

<div class="feature-grid">
<div class="feature-card">
<h3>🚀 Zero Configuration</h3>
<p>Auto-generates GraphQL schema from your database structure. No manual type definitions needed.</p>
</div>

<div class="feature-card">
<h3>🔍 Advanced Filtering</h3>
<p>Modern object-based filtering with 15+ operators. Supports complex nested conditions and type safety.</p>
</div>

<div class="feature-card">
<h3>⚡ High Performance</h3>
<p>Optimized for production with <span class="perf-metric">sub-1s</span> response times and built-in N+1 query prevention.</p>
</div>

<div class="feature-card">
<h3>🛡️ Security First</h3>
<p>Comprehensive security testing with SQL injection prevention and input validation.</p>
</div>

<div class="feature-card">
<h3>📈 Production Ready</h3>
<p>Docker support, CI/CD integration, and extensive test coverage for enterprise deployment.</p>
</div>

<div class="feature-card">
<h3>🔗 Relationship Magic</h3>
<p>Foreign keys automatically become GraphQL relationships. Supports one-to-one, one-to-many, and many-to-many.</p>
</div>
</div>

## Quick Start

<div class="quickstart-grid">
<div class="quickstart-step">
<h3>📦 Install</h3>
<p>Get started with Docker in under 2 minutes.</p>

```bash
git clone https://github.com/excalibase/excalibase-graphql.git
cd excalibase-graphql
```
</div>

<div class="quickstart-step">
<h3>⚙️ Configure</h3>
<p>Set your database connection details.</p>

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/hana
export SPRING_DATASOURCE_USERNAME=hana001
export SPRING_DATASOURCE_PASSWORD=password123
export APP_ALLOWED_SCHEMA=hana
```
</div>

<div class="quickstart-step">
<h3>🚀 Launch</h3>
<p>Start the GraphQL API server.</p>

```bash
docker-compose up -d
```
</div>

<div class="quickstart-step">
<h3>🎯 Query</h3>
<p>Access your GraphQL endpoint.</p>

```
http://localhost:10000/graphql
```
</div>
</div>

### Prerequisites

- **Java 21+** - Required for running the application
- **PostgreSQL 15+** - Supported database version  
- **Docker** - Recommended for easy deployment
- **Maven 3.8+** - For local development builds

#### Option 2: Local Development

1. **Clone the repository:**
   ```bash
   git clone https://github.com/excalibase/excalibase-graphql.git
   cd excalibase-graphql
   ```

2. **Configure your database** in `src/main/resources/application.yaml`:
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://localhost:5432/your_database
       username: your_username
       password: your_password
   
   app:
     allowed-schema: hana  # Your database schema
     database-type: postgres
   ```

3. **Build and run:**
   ```bash
   mvn clean compile
   mvn spring-boot:run
   ```

4. **Access GraphQL endpoint:**
   ```
   http://localhost:10000/graphql
   ```

## Example Usage

Excalibase GraphQL includes comprehensive sample data in the `hana` schema for both demos and testing:

### **Demo Schema (Great for Learning)**
```sql
-- Blog-style schema with relationships
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE posts (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    author_id INTEGER REFERENCES users(id),
    published BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE comments (
    id SERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    post_id INTEGER REFERENCES posts(id),
    author_id INTEGER REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### **Test Schema (Advanced PostgreSQL Features)**
```sql
-- Comprehensive type testing
CREATE TABLE enhanced_types (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    json_col JSON,                    -- JSON support
    jsonb_col JSONB,                  -- JSONB support
    int_array INTEGER[],              -- Array support  
    text_array TEXT[],                -- Text arrays
    timestamptz_col TIMESTAMPTZ,      -- Timezone-aware timestamps
    inet_col INET,                    -- Network types
    cidr_col CIDR,                    -- Network ranges
    macaddr_col MACADDR,              -- MAC addresses
    xml_col XML                       -- XML support
);
```

You can immediately start querying both demo and advanced test data:

### **Demo Queries (Perfect for Learning)**

```graphql
# Query blog users
{
  users {
    id
    username
    email
    first_name
    last_name
    created_at
  }
}

# Query posts with author relationships
{
  posts {
    id
    title
    content
    published
    author_id
    users {           # Automatic relationship resolution
      username
      first_name
    }
  }
}

# Query comments with nested relationships
{
  comments {
    id
    content
    post_id
    posts {
      title
      users {
        username
      }
    }
  }
}
```

### **Advanced Queries (PostgreSQL Features)**

```graphql
# Query enhanced PostgreSQL types
{
  enhanced_types {
    id
    name
    json_col                   # JSON field - returns as JSON
    jsonb_col                  # JSONB field - returns as JSON  
    int_array                  # Array field - returns as GraphQL list
    text_array                 # Text array support
    timestamptz_col            # Timezone-aware timestamps
    inet_col                   # Network type support
    cidr_col                   # Network ranges
    macaddr_col                # MAC addresses
  }
}

# Advanced JSON filtering
{
  enhanced_types(where: { 
    jsonb_col: { 
      hasKey: "settings",
      path: ["preferences", "theme"],
      contains: "{\"auto_save\": true}"
    }
  }) {
    name
    jsonb_col
  }
}

# Array operations
{
  enhanced_types(where: {
    text_array: { contains: "postgresql" }
  }) {
    name
    text_array
    int_array
  }
}
```

### **Mutations**

```graphql
# Create a new user
mutation {
  createUsers(input: {
    username: "alice_dev"
    email: "alice@example.com"
    first_name: "Alice"
    last_name: "Johnson"
  }) {
    id
    username
    email
    first_name
    last_name
    created_at
  }
}

# Create a post with relationship
mutation {
  createPosts(input: {
    title: "Getting Started with Excalibase GraphQL"
    content: "This post explains how to use Excalibase GraphQL..."
    author_id: 1
    published: true
  }) {
    id
    title
    published
    author_id
    users {
      username
      first_name
    }
  }
}

# Create with enhanced PostgreSQL types
mutation {
  createEnhanced_types(input: {
    name: "New Record"
    json_col: "{\"theme\": \"dark\", \"notifications\": true}"
    jsonb_col: "{\"settings\": {\"auto_save\": true}}"
    int_array: [1, 2, 3, 4, 5]
    text_array: ["postgresql", "graphql", "spring-boot"]
    inet_col: "192.168.1.100"
    cidr_col: "192.168.0.0/24"
  }) {
    id
    name
    json_col
    jsonb_col
    int_array
    text_array
    inet_col
    cidr_col
  }
}
```

## 🎯 Enhanced PostgreSQL Support (60% Complete)

We've significantly enhanced PostgreSQL support from ~25% to ~60% with comprehensive type coverage:

### <span class="status-available">✅ Enhanced Types Now Supported</span>

<div class="feature-grid">
<div class="feature-card">
<h3>📄 JSON/JSONB Support</h3>
<p>Custom JSON GraphQL scalar with operators like <code>hasKey</code>, <code>contains</code>, and <code>path</code> for advanced JSON querying.</p>
</div>

<div class="feature-card">
<h3>📝 Array Types</h3>
<p>Full support for PostgreSQL arrays (<code>INTEGER[]</code>, <code>TEXT[]</code>) with GraphQL list types and array-specific filtering.</p>
</div>

<div class="feature-card">
<h3>🕒 Enhanced DateTime</h3>
<p>Timezone-aware types: <code>TIMESTAMPTZ</code>, <code>TIMETZ</code>, <code>INTERVAL</code> with proper timezone handling.</p>
</div>

<div class="feature-card">
<h3>🔢 Precision Numerics</h3>
<p>Enhanced numeric support: <code>NUMERIC(precision,scale)</code>, <code>BIT</code>, <code>VARBIT</code> types.</p>
</div>

<div class="feature-card">
<h3>🌐 Network Types</h3>
<p>Network address support: <code>INET</code>, <code>CIDR</code>, <code>MACADDR</code>, <code>MACADDR8</code> for network data.</p>
</div>

<div class="feature-card">
<h3>💾 Binary & XML</h3>
<p>Binary data (<code>BYTEA</code>) and XML type support for storing complex data structures.</p>
</div>
</div>

### PostgreSQL Type Coverage

| Category | Types | Status | GraphQL Mapping |
|----------|-------|---------|-----------------|
| **Basic Types** | `INTEGER`, `TEXT`, `BOOLEAN`, `DATE` | ✅ Complete | `Int`, `String`, `Boolean`, `String` |
| **JSON Types** | `JSON`, `JSONB` | ✅ Complete | Custom `JSON` scalar |
| **Array Types** | `INTEGER[]`, `TEXT[]`, etc. | ✅ Complete | `[GraphQLType]` lists |
| **DateTime Enhanced** | `TIMESTAMPTZ`, `TIMETZ`, `INTERVAL` | ✅ Complete | `String` with timezone support |
| **Numeric Enhanced** | `NUMERIC(p,s)`, `BIT` | ✅ Complete | `Float`, `String` |
| **Network Types** | `INET`, `CIDR`, `MACADDR` | ✅ Complete | `String` |
| **Binary/XML** | `BYTEA`, `XML` | ✅ Complete | `String` |
| **PostGIS Spatial** | `GEOMETRY`, `GEOGRAPHY` | 🔴 Planned | Future enhancement |
| **Advanced Features** | Views, Constraints, Indexes | 🔴 In Progress | Schema reflection |

## Key Features

### <span class="status-available">✅ Currently Available</span>

<div class="feature-grid">
<div class="feature-card">
<h3>🎯 Advanced Filtering</h3>
<p>Modern object-based filtering with <span class="test-badge functional">42+ tests</span> and 15+ operators including JSON path operations and array filtering.</p>
</div>

<div class="feature-card">
<h3>⚡ High Performance</h3>
<p>Optimized for large datasets with <span class="perf-metric">sub-1s</span> response times, N+1 query prevention, and intelligent batching.</p>
</div>

<div class="feature-card">
<h3>🛡️ Security Tested</h3>
<p>Comprehensive security testing with <span class="test-badge security">13+ security tests</span> covering SQL injection prevention and input validation.</p>
</div>

<div class="feature-card">
<h3>📊 Performance Tested</h3>
<p><span class="test-badge performance">6+ performance tests</span> ensuring scalability with 1000+ records and 20+ concurrent requests.</p>
</div>

<div class="feature-card">
<h3>🔗 Smart Relationships</h3>
<p>Foreign keys automatically become GraphQL relationships with support for one-to-one, one-to-many, and many-to-many patterns.</p>
</div>

<div class="feature-card">
<h3>🐳 Production Ready</h3>
<p>Docker support, CI/CD integration, and comprehensive test coverage for enterprise deployment.</p>
</div>
</div>

### <span class="status-development">🚧 In Development</span>

- **Authentication & Authorization** - Role-based access control
- **Multi-Database Support** - MySQL, Oracle, SQL Server
- **GraphQL Subscriptions** - Real-time data updates
- **PostGIS Spatial Support** - Geographic data types and operations
- **Views & Materialized Views** - Database view support
- **Advanced Constraints** - Check, unique, and exclusion constraints

## 🌟 Enhanced Filtering System

Excalibase GraphQL now features a modern, object-based filtering system with enhanced PostgreSQL type support:

### Enhanced Type Filtering

**JSON/JSONB Operations:**
```graphql
{
  users(where: { 
    profile: { 
      hasKey: "preferences",
      path: ["settings", "theme"],
      contains: "{\"notifications\": true}"
    }
  }) { name profile }
}
```

**Array Operations:**
```graphql
{
  posts(where: {
    categories: { contains: "postgresql" },
    tags: { hasAny: ["development", "database"] }
  }) { title categories tags }
}
```

**Network Type Filtering:**
```graphql
{
  users(where: {
    ip_address: { like: "192.168.%" },
    last_login: { gte: "2023-01-01T00:00:00Z" }
  }) { name ip_address last_login }
}
```

**Complex Filtering:**
```graphql
{
  users(
    where: { 
      name: { startsWith: "John" },
      created_at: { gte: "2023-01-01T00:00:00Z" },
      profile: { hasKey: "active" }
    }
  ) { id name profile created_at }
}
```

**OR Operations with Enhanced Types:**
```graphql
{
  users(or: [
    { profile: { hasKey: "admin" } },
    { tags: { contains: "moderator" } },
    { ip_address: { like: "10.%" } }
  ]) { id name profile tags }
}
```

### Available Filter Operations

**All Data Types:**
- `eq`, `neq`, `isNull`, `isNotNull`, `in`, `notIn`

**String Operations:**
- `contains`, `startsWith`, `endsWith`, `like`, `ilike`

**Numeric Operations:**
- `gt`, `gte`, `lt`, `lte`

**JSON Operations (NEW):**
- `hasKey`, `hasKeys`, `contains`, `containedBy`, `path`, `pathText`

**Array Operations (NEW):**
- `contains`, `hasAny`, `hasAll`, `length`

**Date/Time Operations:**
- Supports multiple formats: `"2023-12-25"`, `"2023-12-25T14:30:00Z"`, ISO 8601 with timezones

### Legacy Support

The old syntax continues to work for backward compatibility:
```graphql
{
  users(
    name_contains: "john"      # Legacy syntax
    name_startsWith: "John"    # Still supported
    email_isNotNull: true
  ) { id name }
}
```

### 📚 Comprehensive Documentation

- **[Complete Filtering Guide](filtering.md)** - All operations, examples, and migration guides
- **[Test Coverage Documentation](testing.md)** - 42+ comprehensive test methods including enhanced types
- **Security**: SQL injection prevention with comprehensive security testing
- **Performance**: Optimized for large datasets (1000+ records) with sub-1s response times

### Pagination Options

**Offset-based pagination:**
```graphql
{
  users(limit: 20, offset: 40, orderBy: { id: ASC }) {
    id
    name
    profile
    tags
  }
}
```

**Cursor-based pagination (Relay specification):**
```graphql
{
  usersConnection(first: 20, after: "cursor123", orderBy: { id: ASC }) {
    edges {
      node {
        id
        name
        profile
        tags
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

## Configuration

### Basic Configuration

```yaml
# Database connection
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: ${DB_USERNAME:myuser}
    password: ${DB_PASSWORD:mypass}

# Schema settings  
app:
  allowed-schema: ${DATABASE_SCHEMA:public}
  database-type: postgres

# Server settings
server:
  port: ${SERVER_PORT:10000}

# Enhanced type support
app:
  enhanced-types:
    json-support: true           # Enable JSON/JSONB support
    array-support: true          # Enable array type support
    network-types: true          # Enable INET/CIDR/MACADDR support
```

### Development Configuration

```yaml
# Enable debug logging
logging:
  level:
    io.github.excalibase: DEBUG
    org.springframework.jdbc.core: DEBUG  # Show SQL queries

# Use virtual threads (Java 21+)
spring:
  threads:
    virtual:
      enabled: true

# TTL Cache for schema reflection
app:
  cache:
    schema-ttl-minutes: 60       # Cache schema for 1 hour
    enabled: true
```

## Architecture

The project follows a modular, database-agnostic design with enhanced type support:

**At Startup (Schema Generation & Wiring):**
```
                    ServiceLookup ───► Database-specific implementations
                         │
                         ▼
                   GraphqlConfig
                    │   │   │
       ┌────────────┘   │   └─────────────┐
       ▼                ▼                 ▼
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│  Database   │  │   Schema    │  │   Data      │
│  Reflector  │  │  Generator  │  │  Fetchers   │
│             │  │             │  │             │
│  Enhanced   │  │  Enhanced   │  │  Enhanced   │
│  Types      │  │  Types      │  │  Types      │
└─────────────┘  └─────────────┘  └─────────────┘
       │                ▼                 │
       │         ┌─────────────┐          │
       │         │  GraphQL    │          │
       └────────▶│   Schema    │◄─────────┘
                 │             │
                 │ JSON Scalar │
                 │ Array Types │
                 │ Filters     │
                 └─────────────┘
                       │
                       ▼
              ┌─────────────┐
              │  Mutators   │
              │(Mutations)  │
              │             │
              │ Enhanced    │
              │ Types       │
              └─────────────┘
```

**Key Components with Enhanced Type Support:**
- **Schema Reflector**: Introspects PostgreSQL metadata including enhanced types (startup only)
- **Schema Generator**: Creates GraphQL types with JSON scalars and array support (startup only)
- **Data Fetchers**: Handle query resolution with enhanced type conversion (runtime)
- **Mutators**: Process CRUD operations with type validation (runtime)
- **JSON Scalar**: Custom GraphQL scalar for JSON/JSONB handling
- **Filter System**: Enhanced filtering with type-specific operations
- **TTL Cache**: Performance optimization for large schemas

## Testing

Comprehensive test suite with enhanced type coverage (uses Testcontainers for real PostgreSQL testing):

```bash
# Run all tests (42+ comprehensive test methods)
mvn test

# Run with coverage report
mvn test jacoco:report

# Run specific enhanced type tests
mvn test -Dtest=GraphqlControllerTest
mvn test -Dtest=PostgresGraphQLSchemaGeneratorImplementTest

# Run performance tests with enhanced types
mvn test -Dtest=GraphqlPerformanceTest

# Run security tests
mvn test -Dtest=GraphqlSecurityTest
```

### Test Coverage Summary

- **Functional Tests**: 22+ methods including enhanced PostgreSQL types
- **Performance Tests**: 6+ methods with 1000+ record datasets
- **Security Tests**: 13+ methods covering SQL injection prevention
- **Enhanced Types**: Full coverage for JSON, arrays, datetime, network, binary types
- **Total Coverage**: **42+ comprehensive test methods**

## Current Limitations

- **PostgreSQL 60% complete**: Advanced features like views, constraints, PostGIS still in development
- **No authentication**: Built-in auth/authz coming soon
- **Single database**: MySQL, Oracle, SQL Server support planned
- **Basic error handling**: Some edge cases need improvement
- **Array operations**: Advanced array functions still being implemented

## Project Status

This project is in **active development** with significantly enhanced PostgreSQL support.

**What works exceptionally well:**
- ✅ Enhanced PostgreSQL types (JSON/JSONB, arrays, datetime, network, binary)
- ✅ Advanced filtering with type-specific operations
- ✅ Schema introspection with 60%+ PostgreSQL coverage
- ✅ GraphQL schema generation with custom scalars
- ✅ CRUD operations with enhanced type support
- ✅ Comprehensive test coverage (42+ tests)
- ✅ Production-ready performance and security

**What's coming next:**
- 🔄 Views and materialized views support
- 🔄 Advanced constraints (check, unique, exclusion)
- 🔄 PostGIS spatial types and operations
- 🔄 Multi-schema support
- 🔄 Authentication & authorization
- 🔄 Additional database support

## Contributing

This is currently a solo project, but contributions are welcome!

1. Check the [issues](https://github.com/excalibase/excalibase-graphql/issues) for open tasks
2. Fork the repository
3. Create a feature branch
4. Make your changes with tests
5. Submit a pull request

**Priority areas for contribution:**
- PostGIS spatial type support
- Views and materialized views
- Advanced constraint handling
- Additional database implementations
- Performance optimizations

## Getting Help

- **GitHub Issues**: Bug reports and feature requests
- **GitHub Discussions**: Questions and general discussion
- **Documentation**: Comprehensive guides in `/docs`

## License

Apache License 2.0 - see [LICENSE](https://github.com/excalibase/excalibase-graphql/blob/main/LICENSE) for details.

---

**⭐ Star the project** on GitHub if you find it useful!

**🚀 Recent Major Update**: Enhanced PostgreSQL support from 25% to 60% with JSON/JSONB, arrays, enhanced datetime, network types, and comprehensive test coverage (42+ tests)!
