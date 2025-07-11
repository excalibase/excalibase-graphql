# Excalibase GraphQL
[![Tests](https://github.com/excalibase/excalibase-graphql/actions/workflows/build-and-push.yml/badge.svg?branch=main)](https://github.com/excalibase/excalibase-graphql/actions/workflows/build-and-push.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15+-blue.svg)](https://www.postgresql.org/)

## 🚀 Overview

Excalibase GraphQL is a powerful Spring Boot application that **automatically generates GraphQL schemas from your existing database tables**. It eliminates the need for manual schema definition and provides instant GraphQL APIs with advanced features like cursor-based pagination, relationship resolution, and comprehensive CRUD operations.

### ✨ Current Features
- **🔄 Automatic Schema Generation**: Creates GraphQL types from PostgreSQL tables
- **📊 Rich Querying**: Filtering, sorting, and pagination out of the box
- **🗓️ Enhanced Date/Time Filtering**: Comprehensive date and timestamp operations with multiple format support
- **🔍 Advanced Filter Types**: StringFilter, IntFilter, FloatFilter, BooleanFilter, DateTimeFilter with operators like eq, neq, gt, gte, lt, lte, in, notIn, isNull, isNotNull
- **🔗 Relationship Resolution**: Automatic foreign key relationship handling
- **🛠️ CRUD Operations**: Full create, read, update, delete support
- **📄 Cursor Pagination**: Relay-spec compatible connection queries
- **⚡ N+1 Prevention**: Built-in query optimization
- **🔧 OR Operations**: Complex logical conditions with nested filtering

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

## 🌟 Enhanced Filtering System

Excalibase GraphQL features a comprehensive filtering system that goes beyond basic GraphQL capabilities:

### 🎯 **Key Advantages**

- **🗓️ Intelligent Date Handling**: Supports multiple date formats (`2023-12-25`, `2023-12-25 14:30:00`, ISO 8601) with automatic type conversion
- **🔧 Rich Operator Support**: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `in`, `notIn`, `isNull`, `isNotNull`, `contains`, `startsWith`, `endsWith`, `like`, `ilike`
- **🔗 Complex Logical Operations**: OR conditions with nested filtering
- **⚡ Type-Safe**: Dedicated filter types for String, Int, Float, Boolean, and DateTime
- **🔄 Backward Compatible**: Legacy filter syntax still supported
- **📊 Production Ready**: 95% feature completeness with comprehensive test coverage

### 💡 **Quick Examples**

**Date Range Filtering:**
```graphql
{ 
  users(where: { created_at: { gte: "2023-01-01", lt: "2024-01-01" } }) 
  { id name created_at } 
}
```

**Complex OR Conditions:**
```graphql
{ 
  users(or: [
    { name: { startsWith: "John" } }, 
    { email: { endsWith: "@admin.com" } }
  ]) { id name email } 
}
```

**Array Operations:**
```graphql
{ 
  users(where: { id: { in: [1, 2, 3, 4, 5] } }) 
  { id name } 
}
```

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

# Enhanced filtering with new filter types
query {
  users(
    where: {
      name: { startsWith: "John" }
      email: { isNotNull: true }
      id: { in: [1, 2, 3, 4, 5] }
      created_at: { 
        gte: "2023-01-01",
        lt: "2024-01-01" 
      }
    }
  ) {
    id
    name
    email
    created_at
  }
}

# OR operations with enhanced filters
query {
  users(
    or: [
      { name: { eq: "Alice" } },
      { name: { eq: "Bob" } },
      { email: { endsWith: "@example.com" } }
    ]
  ) {
    id
    name
    email
  }
}

# Complex date/timestamp filtering
query {
  posts(
    where: {
      published_at: { 
        gte: "2023-12-01 00:00:00",
        lt: "2023-12-31 23:59:59"
      }
      title: { contains: "GraphQL" }
    }
  ) {
    id
    title
    published_at
    users {
      name
    }
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

The project includes comprehensive tests using Spock, Spring Boot MockMvc, and Testcontainers:

```bash
# Run all tests
mvn test

# Run specific test classes
mvn test -Dtest=PostgresDatabaseDataFetcherImplementTest
mvn test -Dtest=GraphqlControllerTest

# Run enhanced filtering tests
mvn test -Dtest=DateTimeFilteringIntegrationTest

# Run with coverage
mvn clean test jacoco:report
```

### Test Coverage

**✅ Enhanced Date/Time Filtering Tests:**
- Date equality, range, and comparison operations
- Timestamp filtering with multiple format support
- Cross-precision operations (date-only on timestamp columns)
- OR operations with complex nested conditions
- IN/NOT IN array operations
- Null/Not null checks
- Error handling for invalid date formats

**✅ Controller Tests (MockMvc):**
- GraphQL schema introspection validation
- All filter types (StringFilter, IntFilter, DateTimeFilter, etc.)
- Connection queries with enhanced filtering
- Legacy filter syntax backward compatibility
- Real PostgreSQL database integration

**✅ Integration Tests:**
- Automatic PostgreSQL container spin-up
- Real database schema and data testing
- Performance and edge case validation

### Test Configuration

Tests use the same PostgreSQL configuration as the main application:
- Database: `hana` on `localhost:5432`
- Test server runs on port `10001`
- Full feature parity with production environment

## Supported Database Types

| Database | Status | Version |
|----------|--------|---------|
| PostgreSQL | ✅ Supported | 15+ |
| MySQL | 🚧 Planned | - |
| Oracle | 🚧 Planned | - |
| SQL Server | 🚧 Planned | - |

## 🔍 Enhanced Filtering & Querying

### Filter Types

Excalibase GraphQL provides comprehensive filter types for all data types:

#### **StringFilter**
```graphql
{
  users(where: { 
    name: { 
      eq: "John",           # Exact match
      neq: "Jane",          # Not equal
      contains: "oh",       # Contains substring
      startsWith: "J",      # Starts with
      endsWith: "n",        # Ends with
      like: "J%n",          # SQL LIKE pattern
      ilike: "j%N",         # Case-insensitive LIKE
      isNull: false,        # Null check
      isNotNull: true,      # Not null check
      in: ["John", "Jane"], # In array
      notIn: ["Bob"]        # Not in array
    }
  }) { id name }
}
```

#### **IntFilter / FloatFilter**
```graphql
{
  users(where: { 
    age: { 
      eq: 25,               # Equal
      neq: 30,              # Not equal
      gt: 18,               # Greater than
      gte: 21,              # Greater than or equal
      lt: 65,               # Less than
      lte: 64,              # Less than or equal
      in: [25, 30, 35],     # In array
      notIn: [40, 45],      # Not in array
      isNull: false         # Null checks
    }
  }) { id name age }
}
```

#### **DateTimeFilter**
Supports multiple date formats with intelligent type conversion:

```graphql
{
  users(where: { 
    created_at: { 
      eq: "2023-12-25",                    # Date only
      gte: "2023-01-01 00:00:00",          # Timestamp
      lt: "2023-12-31T23:59:59.999Z",      # ISO format
      in: ["2023-01-01", "2023-06-01"]     # Date array
    }
  }) { id name created_at }
}
```

**Supported Date Formats:**
- `"2023-12-25"` (yyyy-MM-dd)
- `"2023-12-25 14:30:00"` (yyyy-MM-dd HH:mm:ss)
- `"2023-12-25 14:30:00.123"` (with milliseconds)
- `"2023-12-25T14:30:00Z"` (ISO 8601)

#### **BooleanFilter**
```graphql
{
  users(where: { 
    is_active: { 
      eq: true,
      isNull: false
    }
  }) { id name is_active }
}
```

### OR Operations

Combine multiple conditions with logical OR:

```graphql
{
  users(
    or: [
      { name: { startsWith: "John" } },
      { email: { endsWith: "@admin.com" } },
      { age: { gte: 65 } }
    ]
  ) { id name email age }
}
```

### Legacy Filter Support

For backward compatibility, the old filter syntax is still supported:

```graphql
{
  users(filter: "name='John' AND age>25") {
    id name age
  }
}
```

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