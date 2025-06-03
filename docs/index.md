# Excalibase GraphQL

> Automatic GraphQL API generation from PostgreSQL database schemas

## Overview

Excalibase GraphQL is a Spring Boot application that automatically generates a complete GraphQL API from your existing PostgreSQL database. Simply point it at your database and get instant GraphQL queries and mutations with built-in pagination, filtering, and relationship resolution.

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.8+
- PostgreSQL 15+

### Installation

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

### ✅ Currently Available

- **Automatic Schema Generation**: GraphQL types created from database tables
- **Rich Querying**: Filtering, sorting, pagination out of the box
- **Relationship Resolution**: Foreign keys automatically become GraphQL relationships
- **CRUD Operations**: Create, read, update, delete mutations
- **Cursor Pagination**: Relay-spec compatible connections
- **N+1 Prevention**: Automatic query batching for relationships

### 🚧 In Development

- Docker support
- Authentication & authorization
- MySQL/Oracle database support
- CI/CD pipeline
- Performance optimizations

## Filtering & Pagination

### Available Filter Operators

**String fields:**
```graphql
{
  users(
    name_contains: "john"      # Contains substring
    name_startsWith: "John"    # Starts with
    name_endsWith: "Doe"       # Ends with
    email_isNotNull: true      # Not null check
  ) { id name }
}
```

**Numeric fields:**
```graphql
{
  posts(
    id_gte: 10                 # Greater than or equal
    id_lt: 100                 # Less than
    created_at_gt: "2024-01-01"
  ) { id title }
}
```

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
- **No Docker images**: Docker support in development
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

**⭐ Star the project** on GitHub if you find it useful!