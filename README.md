# Excalibase GraphQL
[![CI](https://github.com/excalibase/excalibase-graphql/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/excalibase/excalibase-graphql/actions/workflows/ci.yml)
[![E2E Tests](https://github.com/excalibase/excalibase-graphql/actions/workflows/e2e.yml/badge.svg?branch=main)](https://github.com/excalibase/excalibase-graphql/actions/workflows/e2e.yml)
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
- **🎯 Custom PostgreSQL Types**: Full support for custom enum and composite types with automatic GraphQL mapping
- **📄 Enhanced PostgreSQL Data Types**: JSON/JSONB, arrays, network types (INET, CIDR), enhanced datetime, binary, and XML support  
- **🔗 Relationship Resolution**: Automatic foreign key relationship handling
- **🛠️ CRUD Operations**: Full create, read, update, delete support
- **📄 Cursor Pagination**: Relay-spec compatible connection queries
- **⚡ N+1 Prevention**: Built-in query optimization
- **🔧 OR Operations**: Complex logical conditions with nested filtering
- **🐳 Docker Support**: Container images with Docker Compose setup
- **🔄 CI/CD Pipeline**: GitHub Actions integration with automated testing

### 🚧 Planned Features

- [ ] **Schema Caching** - Performance optimization for large schemas
- [ ] **MySQL Support** - Complete MySQL database integration
- [ ] **Oracle Support** - Add Oracle database compatibility
- [ ] **SQL Server Support** - Microsoft SQL Server implementation
- [ ] **GraphQL Subscriptions** - Real-time data updates
- [ ] **Custom Directives** - Extended GraphQL functionality
- [ ] **Authentication/Authorization** - Role-based access control

## 📋 Quick Start

### Prerequisites

- Java 21+
- Maven 3.8+
- PostgreSQL 15+

### Installation

#### Option 1: Docker (Recommended)

1. **Clone the repository**
   ```bash
   git clone https://github.com/excalibase/excalibase-graphql.git
   cd excalibase-graphql
   ```

2. **Configure your database**

   Edit `docker-compose.yml` or set environment variables:
   ```yaml
   environment:
     - DB_HOST=your_postgres_host
     - DB_PORT=5432
     - DB_NAME=your_database
     - DB_USERNAME=your_username
     - DB_PASSWORD=your_password
     - DATABASE_SCHEMA=your_schema
   ```

3. **Run with Docker Compose**
   ```bash
   docker-compose up -d
   ```

4. **Access GraphQL endpoint**

   Your GraphQL endpoint will be available at: `http://localhost:10000/graphql`

#### Option 2: Local Development

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

## 🐳 Docker Support

### Docker Compose Setup

The project includes a `docker-compose.yml` file for easy setup:

```yaml
version: '3.8'
services:
  app:
    build: .
    ports:
      - "10000:10000"
    environment:
      - DB_HOST=postgres
      - DB_PORT=5432
      - DB_NAME=your_database
      - DB_USERNAME=your_username
      - DB_PASSWORD=your_password
      - DATABASE_SCHEMA=your_schema
    depends_on:
      - postgres
  
  postgres:
    image: postgres:15-alpine
    environment:
      - POSTGRES_DB=your_database
      - POSTGRES_USER=your_username
      - POSTGRES_PASSWORD=your_password
    ports:
      - "5432:5432"
```

### Available Docker Commands

**Production Setup (Easy for Users):**
```bash
# Start all services
docker-compose up -d

# View logs
docker-compose logs -f excalibase-app

# Stop all services
docker-compose down
```

**E2E Testing Setup:**
```bash
# Use test environment (different ports to avoid conflicts)
docker-compose -f scripts/docker-compose.test.yml up -d

# Or use Makefile for complete testing workflow
make e2e           # Complete e2e test (build + test + cleanup)
make dev           # Start services for development
make test-only     # Run tests against running services
make clean         # Stop and cleanup
```

**Development:**
```bash
# Rebuild and restart
docker-compose up -d --build

# Run tests in container (production setup)
docker-compose exec excalibase-app mvn test

# Run tests in container (test setup)
docker-compose -f scripts/docker-compose.test.yml exec app mvn test

# Access application shell
docker-compose exec excalibase-app /bin/bash
```

### Environment Variables

Configure the application using environment variables:

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `SPRING_DATASOURCE_URL` | JDBC connection URL | `jdbc:postgresql://localhost:5432/hana` | `jdbc:postgresql://postgres:5432/hana` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `hana001` | `hana001` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | `password123` | `password123` |
| `APP_ALLOWED_SCHEMA` | Database schema to introspect | `hana` | `hana` |
| `APP_DATABASE_TYPE` | Database type | `postgres` | `postgres` |
| `SERVER_PORT` | Application port | `10000` | `10000` |

**Legacy Environment Variables (Docker Compose):**
- `DB_HOST`, `DB_PORT`, `DB_NAME` - Still supported for backward compatibility

## 🧪 End-to-End Testing

Excalibase GraphQL includes a comprehensive E2E testing suite that validates the complete GraphQL API using Docker Compose with unique ports to avoid conflicts.

### Quick Start E2E Testing

```bash
# Run complete E2E test suite (builds, starts services, runs tests, cleans up)
make e2e

# Start development environment (keeps services running)
make dev

# Run only tests (against already running services)
make test-only

# See all available commands
make help
```

> **Note**: We use a `Makefile` for streamlined development workflow. All e2e testing operations are handled through `make` commands for consistency and ease of use.

### E2E Test Configuration

The E2E setup uses unique ports to avoid conflicts:
- **GraphQL API**: `http://localhost:10001/graphql` (unique port 10001)
- **PostgreSQL**: `localhost:5433` (unique port 5433)

### E2E Test Coverage

The test suite includes **25+ comprehensive tests** covering:

#### **Schema & Introspection**
- ✅ GraphQL schema introspection
- ✅ Query and Mutation type validation
- ✅ Field availability verification

#### **Basic GraphQL Operations**
- ✅ Query all customers
- ✅ Filtering with WHERE clauses
- ✅ OR operations with multiple conditions
- ✅ Pagination (limit, offset)
- ✅ Ordering (ASC/DESC)

#### **Enhanced PostgreSQL Types** 🆕
- ✅ JSON/JSONB column operations
- ✅ Array types (INTEGER[], TEXT[])
- ✅ Enhanced datetime (TIMESTAMPTZ, TIMETZ, INTERVAL)
- ✅ Network types (INET, CIDR, MACADDR)
- ✅ Binary and XML types

#### **Relationships & Views**
- ✅ Foreign key relationship traversal
- ⚠️ **Important**: Include foreign key fields in queries for relationships to work
- ✅ PostgreSQL views (read-only queries)
- ✅ Materialized views
- ✅ Complex relationship queries

#### **CRUD Operations**
- ✅ Create mutations
- ✅ Update mutations
- ✅ Data validation

#### **Advanced Features**
- ✅ Cursor-based pagination (connections)
- ✅ Complex filtering (date ranges, string operations)
- ✅ Boolean and array filters
- ✅ Error handling validation
- ✅ Performance testing (< 1000ms response times)

### Sample Data & Schema

The application includes comprehensive sample data in the `hana` schema:

#### **Demo Tables (Great for Documentation & Learning)**
```sql
-- Blog-style schema for demos and learning
users: 3 sample users (john_doe, jane_smith, bob_wilson)
posts: 4 sample blog posts with rich content
comments: 4 sample comments showing relationships
```

#### **Test Tables (Comprehensive Testing Coverage)**
```sql
-- Customer table (12 records)
customer: MARY SMITH, PATRICIA JOHNSON, etc.

-- Enhanced types table (3 records with full PostgreSQL type coverage)
enhanced_types: JSON objects, arrays, network addresses, XML documents

-- Orders table (5 records with foreign key relationships)
orders: Realistic order data linking to customers
```

#### **Views & Advanced Features**
```sql
-- Read-only views
active_customers: Filtered view of active customers
enhanced_types_summary: Materialized view with JSON extraction
posts_with_authors: Blog posts joined with author information
```

### Available Make Commands

```bash
# Main commands
make e2e            # Complete e2e test (build + test + cleanup)
make dev            # Start services for development (no cleanup)
make test-only      # Run tests against running services
make clean          # Stop services and cleanup

# Development workflow
make test-quick     # Quick test (skip build)
make restart        # Restart services
make rebuild        # Full rebuild and restart

# Monitoring and debugging
make logs           # Show all service logs
make logs-app       # Show application logs only
make status         # Show service status

# Database operations
make db-shell       # Connect to PostgreSQL shell
make db-reset       # Reset database with fresh data

# Sample queries (both demo and test data)
make query-users           # Query demo users table
make query-posts           # Query demo posts with relationships
make query-customers       # Query test customer data
make query-enhanced-types  # Query enhanced PostgreSQL types
make query-schema          # Query GraphQL schema introspection
```

### Manual Testing After E2E

If you use `make dev`, you can manually explore the API:

```bash
# Visit GraphQL endpoint in browser
make open-api

# Test with built-in demo queries
make query-users            # Simple user data
make query-posts            # Blog posts with relationships

# Test with comprehensive test data  
make query-customers        # Customer data with advanced filtering
make query-enhanced-types   # PostgreSQL advanced types (JSON, arrays, etc.)

# Or test with curl directly
curl -X POST http://localhost:10001/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ users { id username email first_name } }"}'

# Cleanup when done
make clean
```

### Sample GraphQL Queries

**Demo Data Queries (Great for Learning):**
```graphql
# Query blog users
{
  users {
    id
    username
    email
    first_name
    last_name
  }
}

# Query posts with author relationships
{
  posts {
    id
    title
    published
    author_id
    users {
      username
      first_name
      last_name
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

**Test Data Queries (Advanced Features):**
```graphql
# Query customers with filtering
{
  customer(where: { active: { eq: true } }) {
    customer_id
    first_name
    last_name
    email
  }
}

# Query enhanced PostgreSQL types
{
  enhanced_types {
    id
    name
    json_col
    jsonb_col
    int_array
    text_array
    timestamptz_col
    inet_col
  }
}

# Query with relationships
{
  orders {
    order_id
    total_amount
    customer_id
    customer {
      first_name
      last_name
      email
    }
  }
}
```

### Dependencies

The E2E tests require:
- **Make** (usually pre-installed on macOS/Linux)
- **Docker** and **Docker Compose**
- **curl** (for HTTP requests)
- **jq** (for JSON processing)
- **Maven** (for building the application)

Use `make check-deps` to verify all dependencies are installed, or `make install-deps` on macOS to auto-install missing tools.

## 🔄 CI/CD Pipeline

### GitHub Actions Integration

The project includes automated CI/CD with GitHub Actions:

#### **Build & Test Pipeline**
- ✅ **Automated Testing**: Runs all 41+ test methods on every push
- ✅ **Multi-Java Support**: Tests against Java 17, 21
- ✅ **PostgreSQL Integration**: Uses PostgreSQL service for integration tests
- ✅ **Security Scanning**: Automated dependency vulnerability checks
- ✅ **Code Coverage**: Generates and reports test coverage metrics

#### **Docker Pipeline**
- ✅ **Container Building**: Builds Docker images for each release
- ✅ **Multi-architecture**: Supports AMD64 and ARM64 architectures
- ✅ **Registry Publishing**: Pushes images to container registries
- ✅ **Automated Tagging**: Tags images with version and latest

#### **Quality Gates**
- ✅ **All tests must pass** before merge
- ✅ **Code coverage above 90%**
- ✅ **Security scans pass**
- ✅ **Docker builds successfully**

### Pipeline Configuration

The CI/CD pipeline runs on:
- **Push to main**: Full pipeline with deployment
- **Pull requests**: Build and test validation
- **Release tags**: Docker image publishing
- **Scheduled**: Nightly security scans

## 🌟 Enhanced Filtering System

Excalibase GraphQL features a modern, object-based filtering system that provides consistency with industry standards and PostgREST-style APIs:

### 🎯 **Key Advantages**

- **🗓️ Intelligent Date Handling**: Supports multiple date formats (`2023-12-25`, `2023-12-25 14:30:00`, ISO 8601) with automatic type conversion
- **🔧 Rich Operator Support**: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `in`, `notIn`, `isNull`, `isNotNull`, `contains`, `startsWith`, `endsWith`, `like`, `ilike`
- **🔗 Complex Logical Operations**: OR conditions with nested filtering
- **⚡ Type-Safe**: Dedicated filter types for String, Int, Float, Boolean, and DateTime
- **🔄 Backward Compatible**: Legacy filter syntax still supported
- **📊 Production Ready**: 95% feature completeness with comprehensive test coverage (41+ test methods)
- **🛡️ Security Focused**: SQL injection prevention with comprehensive security testing
- **⚡ Performance Optimized**: Tested for large datasets (1000+ records) with sub-1s response times

### 💡 **Modern Syntax Examples**

**New Object-Based Syntax (Recommended):**
```graphql
{ 
  customer(where: { customer_id: { eq: 524 } }) {
    customer_id
    first_name
    last_name
  }
}
```

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

**Combined WHERE and OR:**
```graphql
{
  customer(
    where: { active: { eq: true } }
    or: [
      { customer_id: { lt: 10 } },
      { customer_id: { gt: 600 } }
    ]
  ) { customer_id first_name last_name active }
}
```

### 📚 **Comprehensive Documentation**

For detailed filtering documentation, examples, and migration guides, see:
- **[GraphQL Filtering Guide](docs/filtering.md)** - Complete filtering reference
- **[Test Coverage Documentation](docs/testing.md)** - Comprehensive test suite overview

## Example Usage

Given this database table with custom PostgreSQL types:

```sql
-- Custom PostgreSQL enum and composite types
CREATE TYPE user_status AS ENUM ('active', 'inactive', 'suspended');
CREATE TYPE address AS (
    street TEXT,
    city TEXT,
    state TEXT,
    zip_code TEXT
);

CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) UNIQUE,
    status user_status DEFAULT 'active',
    address address,
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
# Get all users with custom types
query {
  users {
    id
    name
    email
    status          # PostgreSQL enum type
    address {       # PostgreSQL composite type
      street
      city
      state
      zip_code
    }
  }
}

# Get posts with authors
query {
  posts {
    id
    title
    content
    user_id      # Foreign key required for relationship
    users {      # Automatic relationship resolution
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
# Create a user with custom types
mutation {
  createUsers(input: {
    name: "Alice Johnson"
    email: "alice@example.com"
    status: active
    address: {
      street: "123 Main St"
      city: "New York"
      state: "NY"
      zip_code: "10001"
    }
  }) {
    id
    name
    email
    status
    address {
      street
      city
      state
    }
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
    user_id    # Foreign key field
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

🚀 **Getting Started:**
1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Make your changes and add tests
4. Ensure tests pass: `mvn test`
5. Commit: `git commit -m 'Add amazing feature'`
6. Push: `git push origin feature/amazing-feature`
7. Open a Pull Request

**Current Priority**: Complete PostgreSQL support and enhance GraphQL API features

## Known Limitations

- PostgreSQL only (for now)
- No authentication/authorization (planned)
- Limited error handling in some edge cases
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