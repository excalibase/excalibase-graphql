# Scripts Directory

This directory contains scripts and configuration files for end-to-end testing of Excalibase GraphQL.

## File Overview

### 📝 Database & Configuration
- **`initdb.sql`** - PostgreSQL initialization script with sample data
  - Creates customer, enhanced_types, and orders tables
  - Inserts sample data for comprehensive testing
  - Creates views and indexes for performance
  - Used automatically by docker-compose on startup

### 🧪 Testing Scripts
- **`e2e-test.sh`** - Core E2E test suite (25+ tests)
  - Tests schema introspection
  - Validates basic GraphQL operations
  - Tests enhanced PostgreSQL types
  - Validates relationships and views
  - Called by `make test-only` and `make start`

### 📖 Documentation
- **`sample-queries.md`** - GraphQL query examples
  - Demonstrates all supported operations
  - Shows enhanced PostgreSQL type usage
  - Provides curl examples for manual testing

### 🔧 Legacy Scripts (Now using Makefile)
- **`start-e2e.sh`** - Full E2E orchestration script
- **`start.sh`** - Simple start wrapper

> **Note**: We've migrated from bash scripts to using a `Makefile` for better workflow management. The core testing logic remains in `e2e-test.sh` but is now orchestrated through make commands.

## Usage

### Recommended (Make Commands)
```bash
# Run complete E2E test suite
make start

# Start development environment
make dev

# See all available commands
make help
```

### Direct Script Usage (Not Recommended)
```bash
# Only if you need to bypass the Makefile
./scripts/e2e-test.sh  # Requires services to be running
```

## Integration with Docker

The scripts work seamlessly with the docker-compose setup:

1. **`docker-compose.yml`** defines services (app + postgres)
2. **`initdb.sql`** initializes the database with sample data
3. **`e2e-test.sh`** validates the complete API
4. **`Makefile`** orchestrates the entire workflow

## Development Workflow

```bash
# Start development environment
make dev

# Run sample queries to test manually
make query-customers
make query-enhanced-types

# Run full test suite
make test-only

# Cleanup when done
make clean
```

This approach gives you full control over the E2E testing process while maintaining consistency and reliability. 