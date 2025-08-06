# Sample GraphQL Queries for E2E Testing

This file contains sample GraphQL queries that demonstrate the capabilities of Excalibase GraphQL with enhanced PostgreSQL types. These queries work with the data created by `initdb.sql`.

## Basic Queries

### Get All Customers
```graphql
{
  customer {
    customer_id
    first_name
    last_name
    email
    active
  }
}
```

### Customer with Filtering
```graphql
{
  customer(where: { first_name: { eq: "MARY" } }) {
    customer_id
    first_name
    last_name
    email
  }
}
```

### OR Operations
```graphql
{
  customer(or: [
    { first_name: { eq: "MARY" } }, 
    { first_name: { eq: "JOHN" } }
  ]) {
    customer_id
    first_name
    last_name
  }
}
```

## Enhanced PostgreSQL Types

### JSON/JSONB Queries
```graphql
{
  enhanced_types {
    id
    name
    json_col
    jsonb_col
  }
}
```

### Array Types
```graphql
{
  enhanced_types {
    id
    name
    int_array
    text_array
  }
}
```

### Network Types
```graphql
{
  enhanced_types {
    id
    name
    inet_col
    cidr_col
    macaddr_col
  }
}
```

### DateTime Types
```graphql
{
  enhanced_types {
    id
    name
    timestamptz_col
    timetz_col
    interval_col
  }
}
```

### All Enhanced Types
```graphql
{
  enhanced_types {
    id
    name
    json_col
    jsonb_col
    int_array
    text_array
    timestamptz_col
    timetz_col
    interval_col
    numeric_col
    bytea_col
    inet_col
    cidr_col
    macaddr_col
    xml_col
    created_at
  }
}
```

## Relationships

### Orders with Customer Information
```graphql
{
  orders {
    order_id
    order_date
    total_amount
    status
    customer {
      customer_id
      first_name
      last_name
      email
    }
  }
}
```

### Customer with Their Orders
```graphql
{
  customer(where: { customer_id: { eq: 1 } }) {
    customer_id
    first_name
    last_name
    orders {
      order_id
      order_date
      total_amount
      status
    }
  }
}
```

## Deep Relationship Queries (5-6 Levels)

### 6-Level Deep Query: Audit Trail to Time Entries
```graphql
{
  audit_logs(where: { action: { eq: "UPDATE" } }, limit: 20) {
    id
    action
    table_name
    changed_by
    employees {
      first_name
      last_name
      department_id
      departments {
        name
        company_id
        companies {
          name
          industry
          projects {
            name
            status
            time_entries {
              hours_worked
              entry_date
            }
          }
        }
      }
    }
  }
}
```

### 6-Level Deep Query: Time Entries to Project Assignments
```graphql
{
  time_entries(where: { hours_worked: { gte: 8 } }, limit: 25) {
    id
    hours_worked
    entry_date
    projects {
      name
      budget
      company_id
      companies {
        name
        revenue
        departments {
          name
          budget
          employees {
            first_name
            salary
            project_assignments {
              role
              allocation_percentage
              hourly_rate
            }
          }
        }
      }
    }
  }
}
```

### 5-Level Deep Query: Financial Chain
```graphql
{
  invoices(where: { status: { eq: "PENDING" } }, limit: 30) {
    id
    invoice_number
    total_amount
    projects {
      name
      project_manager_id
      employees {
        first_name
        last_name
        departments {
          name
          budget
          companies {
            name
            industry
            revenue
          }
        }
      }
    }
  }
}
```

### Extreme Deep Traversal (5 Levels)
```graphql
{
  companies(where: { revenue: { gte: 1000000 } }, limit: 10) {
    name
    industry
    revenue
    departments {
      name
      budget
      employees {
        first_name
        salary
        projects {
          name
          budget
          time_entries {
            hours_worked
            entry_date
          }
        }
      }
    }
  }
}
```

## Views (Read-only)

### Active Customers View
```graphql
{
  active_customers {
    customer_id
    first_name
    last_name
    email
    create_date
  }
}
```

### Enhanced Types Summary View
```graphql
{
  enhanced_types_summary {
    id
    name
    json_name
    array_size
    created_date
  }
}
```

## Mutations

### Create Customer
```graphql
mutation {
  create_customer(input: {
    first_name: "NEW"
    last_name: "CUSTOMER"
    email: "new@example.com"
    active: true
  }) {
    customer_id
    first_name
    last_name
    email
    active
  }
}
```

### Update Customer
```graphql
mutation {
  update_customer(
    where: { customer_id: { eq: 1 } }
    input: { email: "updated@example.com" }
  ) {
    customer_id
    first_name
    last_name
    email
  }
}
```

## Advanced Filtering

### Date Range Filter
```graphql
{
  customer(where: {
    create_date: {
      gte: "2006-01-01"
      lt: "2008-01-01"
    }
  }) {
    customer_id
    first_name
    create_date
  }
}
```

### String Contains Filter
```graphql
{
  customer(where: {
    email: { contains: "@example.com" }
  }) {
    customer_id
    first_name
    email
  }
}
```

### IN Array Filter
```graphql
{
  customer(where: {
    customer_id: { in: [1, 2, 3] }
  }) {
    customer_id
    first_name
    last_name
  }
}
```

## Pagination

### Limit and Offset
```graphql
{
  customer(limit: 5, offset: 2) {
    customer_id
    first_name
    last_name
  }
}
```

### Ordering
```graphql
{
  customer(
    orderBy: { customer_id: ASC }
    limit: 5
  ) {
    customer_id
    first_name
    last_name
  }
}
```

### Connection-based Pagination
```graphql
{
  customerConnection(first: 3) {
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
  }
}
```

## Schema Introspection

### Available Types
```graphql
{
  __schema {
    types {
      name
      kind
    }
  }
}
```

### Query Type Fields
```graphql
{
  __schema {
    queryType {
      fields {
        name
        type {
          name
          kind
        }
      }
    }
  }
}
```

---

## Testing with Make Commands

You can test these queries using the Makefile after running the E2E setup:

```bash
# Start the E2E environment (keeps it running)
make dev

# Test built-in queries
make query-customers
make query-enhanced-types
make query-schema

# Or test with curl directly
curl -X POST http://localhost:10001/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ customer { customer_id first_name last_name } }"}'

# Test enhanced types
curl -X POST http://localhost:10001/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ enhanced_types { id name json_col int_array } }"}'

# Cleanup when done
make clean
``` 