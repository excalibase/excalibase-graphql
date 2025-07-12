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
export DB_HOST=localhost
export DB_NAME=your_database
export DB_USERNAME=postgres
export DB_PASSWORD=your_password
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
     allowed-schema: public  # Your database schema
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

Given this database schema:

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE posts (
    id SERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    author_id INTEGER REFERENCES users(id)
);
```

You can immediately query:

```graphql
# Get all users
{
  users {
    id
    name
    email
    created_at
  }
}

# Get posts with authors (automatic relationship resolution)
{
  posts {
    id
    title
    content
    users {  # Foreign key relationship automatically resolved
      name
      email
    }
  }
}

# Filtered query with pagination
{
  users(
    name_contains: "john"
    limit: 10
    orderBy: { created_at: DESC }
  ) {
    id
    name
    email
  }
}
```

And perform mutations:

```graphql
# Create a new user
mutation {
  createUsers(input: {
    name: "Alice Johnson"
    email: "alice@example.com"
  }) {
    id
    name
    email
    created_at
  }
}

# Update existing user
mutation {
  updateUsers(input: {
    id: 1
    name: "Alice Smith"
  }) {
    id
    name
    email
  }
}

# Delete user
mutation {
  deleteUsers(id: 1)
}
```

## Key Features

### <span class="status-available">✅ Currently Available</span>

<div class="feature-grid">
<div class="feature-card">
<h3>🎯 Advanced Filtering</h3>
<p>Modern object-based filtering with <span class="test-badge functional">41+ tests</span> and 15+ operators including string matching, numeric comparisons, and date ranges.</p>
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
- **Schema Caching** - Improved performance for large schemas

## 🌟 Enhanced Filtering System

Excalibase GraphQL now features a modern, object-based filtering system that provides consistency with industry standards:

### Modern Object-Based Syntax

**New Syntax (Recommended):**
```graphql
{
  customer(where: { customer_id: { eq: 524 } }) {
    customer_id
    first_name
    last_name
  }
}
```

**Complex Filtering:**
```graphql
{
  users(
    where: { 
      name: { startsWith: "John" },
      age: { gte: 18, lt: 65 },
      active: { eq: true }
    }
  ) { id name age }
}
```

**OR Operations:**
```graphql
{
  users(or: [
    { name: { eq: "Alice" } },
    { email: { endsWith: "@admin.com" } }
  ]) { id name email }
}
```

### Available Filter Operations

**All Data Types:**
- `eq`, `neq`, `isNull`, `isNotNull`, `in`, `notIn`

**String Operations:**
- `contains`, `startsWith`, `endsWith`, `like`, `ilike`

**Numeric Operations:**
- `gt`, `gte`, `lt`, `lte`

**Date/Time Operations:**
- Supports multiple formats: `"2023-12-25"`, `"2023-12-25 14:30:00"`, ISO 8601

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
- **[Test Coverage Documentation](testing.md)** - 41+ comprehensive test methods
- **Security**: SQL injection prevention with comprehensive security testing
- **Performance**: Optimized for large datasets (1000+ records) with sub-1s response times

### Pagination Options

**Offset-based pagination:**
```graphql
{
  users(limit: 20, offset: 40, orderBy: { id: ASC }) {
    id
    name
    email
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
        email
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
```

## Architecture

The project follows a modular, database-agnostic design:

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
└─────────────┘  └─────────────┘  └─────────────┘
       │                ▼                 │
       │         ┌─────────────┐          │
       │         │  GraphQL    │          │
       └────────▶│   Schema    │◄─────────┘
                 └─────────────┘
                       │
                       ▼
              ┌─────────────┐
              │  Mutators   │
              │(Mutations)  │
              └─────────────┘
```

**At Runtime (Request Processing):**
```
HTTP Request ───► GraphQL Controller ───► GraphQL Engine ───► Data Fetchers/Mutators ───► Database
```

**Key Components:**
- **Schema Reflector**: Introspects PostgreSQL metadata (startup only)
- **Schema Generator**: Creates GraphQL types from database tables (startup only)
- **Data Fetchers**: Handle query resolution with optimizations (runtime)
- **Mutators**: Process CRUD operations (runtime)
- **Service Lookup**: Enables database-specific implementations
- **GraphqlConfig**: Central orchestrator that wires data fetchers and mutators to specific GraphQL fields for each table

## Testing

Run the test suite (uses Testcontainers for real PostgreSQL testing):

```bash
# Run all tests
mvn test

# Run with coverage report
mvn clean test jacoco:report

# Run specific test class
mvn test -Dtest=PostgresDatabaseDataFetcherImplementTest
```

## Current Limitations

- **PostgreSQL only**: MySQL, Oracle, SQL Server support planned
- **No authentication**: Built-in auth/authz coming soon
- **Docker available**: Use `docker-compose up -d` for easy setup
- **Basic error handling**: Some edge cases need improvement
- **Performance**: Not yet optimized for very large schemas

## Project Status

This project is in **active early development**. Core functionality works well, but many enterprise features are still being built.

**What works well:**
- PostgreSQL schema introspection
- GraphQL schema generation
- Basic queries and mutations
- Relationship resolution
- Pagination and filtering

**What's coming soon:**
- Docker support
- Authentication & authorization
- Additional database support
- CI/CD pipeline
- Performance optimizations

## Contributing

This is currently a solo project, but contributions are welcome!

1. Check the [issues](https://github.com/excalibase/excalibase-graphql/issues) for open tasks
2. Fork the repository
3. Create a feature branch
4. Make your changes with tests
5. Submit a pull request

## Getting Help

- **GitHub Issues**: Bug reports and feature requests
- **GitHub Discussions**: Questions and general discussion

## License

Apache License 2.0 - see [LICENSE](https://github.com/excalibase/excalibase-graphql/blob/main/LICENSE) for details.

---

**⭐ Star the project** on GitHub if you find it useful!Test edit: Sat Jul 12 23:50:29 +07 2025
