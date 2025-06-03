# Excalibase GraphQL

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15+-blue.svg)](https://www.postgresql.org/)

## 🚀 Overview

Excalibase GraphQL is a powerful Spring Boot application that **automatically generates GraphQL schemas from your existing database tables**. It eliminates the need for manual schema definition and provides instant GraphQL APIs with advanced features like cursor-based pagination, relationship resolution, and comprehensive CRUD operations.

### ✨ Current Features
- **🔄 Automatic Schema Generation**: Creates GraphQL types from PostgreSQL tables
- **📊 Rich Querying**: Filtering, sorting, and pagination out of the box
- **🔗 Relationship Resolution**: Automatic foreign key relationship handling
- **🛠️ CRUD Operations**: Full create, read, update, delete support
- **📄 Cursor Pagination**: Relay-spec compatible connection queries
- **⚡ N+1 Prevention**: Built-in query optimization

### 🚧 Planned Features

- [ ] **Container image** - Docker support
- [ ] **CI/CD pipeline** - Github Ci/Jenkins integration
- [ ] **Schema Caching** - Performance optimization for large schemas
- [ ] **MySQL Support** - Complete MySQL database integration
- [ ] **Oracle Support** - Add Oracle database compatibility
- [ ] **SQL Server Support** - Microsoft SQL Server implementation
- [ ] **GraphQL Subscriptions** - Real-time data updates
- [ ] **Custom Directives** - Extended GraphQL functionality

## 📋 Quick Start

### Prerequisites

- Java 21+
- Maven 3.8+
- PostgreSQL 15+

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/excalibase/excalibase-graphql.git
   cd excalibase-graphql
   ```

2. **Configure your database**

   Edit `src/main/resources/application.yaml`:
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://localhost:5432/your_database
       username: your_username
       password: your_password
   
   app:
     allowed-schema: your_schema
     database-type: postgres
   ```

3. **Build and run**
   ```bash
   mvn clean install
   mvn spring-boot:run
   ```

4. **Access GraphQL endpoint**

   Your GraphQL endpoint will be available at: `http://localhost:10000/graphql`

## Example Usage

Given this database table:

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
    author_id INTEGER REFERENCES users(id),
    published_at TIMESTAMP
);
```

You can immediately query:

```graphql
# Get all users
query {
  users {
    id
    name
    email
  }
}

# Get posts with authors
query {
  posts {
    id
    title
    content
    users {  # Automatic relationship resolution
      name
      email
    }
  }
}

# Filtered query with pagination
query {
  users(
    name_contains: "john"
    limit: 10
    orderBy: { created_at: DESC }
  ) {
    id
    name
    email
    created_at
  }
}

# Cursor-based pagination
query {
  usersConnection(
    first: 10
    orderBy: { id: ASC }
  ) {
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
      endCursor
    }
    totalCount
  }
}
```

And perform mutations:

```graphql
# Create a user
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

# Update a user
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

# Create with relationships
mutation {
  createPostsWithRelations(input: {
    title: "Getting Started with GraphQL"
    content: "GraphQL is amazing..."
    users_connect: { id: 1 }  # Connect to existing user
  }) {
    id
    title
    users {
      name
    }
  }
}
```

## Project Structure

```
src/main/java/io/github/excalibase/
├── annotation/           # @ExcalibaseService for database-specific implementations
├── config/              # Spring configuration classes
├── constant/            # Constants and enums
├── controller/          # REST controllers (GraphQL endpoint)
├── exception/           # Custom exceptions
├── model/               # Data models (TableInfo, ColumnInfo, etc.)
├── schema/              # Core GraphQL schema handling
│   ├── fetcher/         # Data fetchers for queries
│   ├── generator/       # GraphQL schema generators
│   ├── mutator/         # Mutation resolvers
│   └── reflector/       # Database schema introspection
└── service/             # Business services and utilities

src/test/groovy/         # Spock-based tests with Testcontainers
```

## 🏗️ Architecture

The project uses a modular, database-agnostic design with two main phases:

**Startup Phase (Schema Generation & Wiring):**
```
                    ServiceLookup ───► Database Implementations  
                         │
                         ▼
                   GraphqlConfig ────────────────┐
                    │   │   │                   │
       ┌────────────┘   │   └─────────────┐     │
       ▼                ▼                 ▼     ▼
┌─────────────┐  ┌─────────────┐  ┌─────────────┐ ┌─────────────┐
│  Database   │  │   Schema    │  │   Data      │ │  Mutators   │
│  Reflector  │  │  Generator  │  │  Fetchers   │ │             │
└─────────────┘  └─────────────┘  └─────────────┘ └─────────────┘
       │                │                 │             │
       └────────────────┼─────────────────┼─────────────┘
                        ▼                 │
                 ┌─────────────┐          │
                 │  GraphQL    │◄─────────┘
                 │   Schema    │  
                 └─────────────┘
```

**Runtime Phase (Request Processing):**
```
HTTP Request ───► GraphQL Controller ───► GraphQL Engine ───► Resolvers ───► Database
```

**Key Components:**
- **Schema Reflector**: Introspects database metadata (startup)
- **Schema Generator**: Creates GraphQL types from database tables (startup)
- **Data Fetchers**: Handle query resolution with optimizations (runtime)
- **Mutators**: Process CRUD operations (runtime)
- **Service Lookup**: Enables pluggable database-specific implementations
- **GraphqlConfig**: Central orchestrator that wires data fetchers and mutators to specific GraphQL field coordinates for each table

## Configuration Options

### Database Configuration
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: ${DB_USERNAME:myuser}
    password: ${DB_PASSWORD:mypass}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5

app:
  allowed-schema: ${DATABASE_SCHEMA:public}
  database-type: postgres
```

### Server Configuration
```yaml
server:
  port: ${SERVER_PORT:10000}

spring:
  threads:
    virtual:
      enabled: true  # Java 21 virtual threads
```

### Logging

```yaml
logging:
  level:
    io.github.excalibase: DEBUG
    org.springframework.jdbc.core: DEBUG  # Show SQL queries
```

## 🧪 Testing

The project includes comprehensive tests using Spock and Testcontainers:

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=PostgresDatabaseDataFetcherImplementTest

# Run with coverage
mvn clean test jacoco:report
```

Tests automatically spin up real PostgreSQL containers for integration testing.

## Supported Database Types

| Database | Status | Version |
|----------|--------|---------|
| PostgreSQL | ✅ Supported | 15+ |
| MySQL | 🚧 Planned | - |
| Oracle | 🚧 Planned | - |
| SQL Server | 🚧 Planned | - |

## Filtering & Querying

### Available Operators

**String fields:**
- `field_contains`, `field_startsWith`, `field_endsWith`
- `field_isNull`, `field_isNotNull`

**Numeric fields:**
- `field_gt`, `field_gte`, `field_lt`, `field_lte`

**All fields:**
- Direct equality: `field: value`

### Pagination

**Offset-based:**
```graphql
query {
  users(limit: 20, offset: 40, orderBy: { id: ASC }) {
    id
    name
  }
}
```

**Cursor-based (Relay spec):**
```graphql
query {
  usersConnection(first: 20, after: "cursor", orderBy: { id: ASC }) {
    edges { node { id name } cursor }
    pageInfo { hasNextPage endCursor }
  }
}
```

## 🤝 Contributing

This is a solo project in early development, but contributions are welcome!

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Make your changes and add tests
4. Ensure tests pass: `mvn test`
5. Commit: `git commit -m 'Add amazing feature'`
6. Push: `git push origin feature/amazing-feature`
7. Open a Pull Request

## Known Limitations

- PostgreSQL only (for now)
- No authentication/authorization (planned)
- Limited error handling in some edge cases
- No Docker images yet
- Performance maybe not optimized for very large schemas


## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 🙋‍♂️ Support

- **Issues**: [GitHub Issues](https://github.com/excalibase/excalibase-graphql/issues)
- **Discussions**: [GitHub Discussions](https://github.com/excalibase/excalibase-graphql/discussions)

## ⭐ Show Your Support

If you find this project useful, please consider giving it a star ⭐ on GitHub!

---
Made with ❤️ by the [Excalibase Team](https://github.com/excalibase)