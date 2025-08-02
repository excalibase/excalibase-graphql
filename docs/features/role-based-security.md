# Role-Based Security Feature Flag

## Overview

Excalibase GraphQL provides comprehensive role-based security through PostgreSQL's native Row Level Security (RLS) and Column Level Security (CLS) features. This functionality can be controlled via a feature flag for flexible deployment strategies.

## Feature Flag Configuration

### Basic Configuration

```yaml
app:
  security:
    role-based-schema: true  # Enable role-based schema filtering (default: true)
```

### Available Options

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `app.security.role-based-schema` | boolean | `true` | Enable/disable role-based schema filtering and RLS/CLS support |

## Behavior Modes

### ✅ **Enabled Mode** (Default: `role-based-schema: true`)

When enabled, Excalibase uses the **"Root + Filter"** approach:

1. **Schema Reflection**: Reflects the complete database schema once
2. **Role Privilege Query**: Queries PostgreSQL for role-specific privileges
3. **Schema Filtering**: Filters the schema in-memory based on role privileges
4. **GraphQL Generation**: Generates role-aware GraphQL schema

**Benefits:**
- ✅ **High Security**: Tables/columns are excluded from schema if role lacks privileges
- ✅ **Native PostgreSQL Security**: Leverages PostgreSQL RLS/CLS policies
- ✅ **Fine-grained Control**: Support for table-level and column-level permissions

**Request Flow:**
```http
POST /graphql
X-Database-Role: employee

{
  "query": "{ customer { id name salary } }"
}
```

Result: `salary` field excluded from schema if `employee` role lacks privilege.

### ⚠️ **Disabled Mode** (`role-based-schema: false`)

When disabled, Excalibase uses **full schema access**:

1. **Schema Reflection**: Uses complete database schema for all requests
2. **No Role Filtering**: All tables/columns visible in GraphQL schema regardless of role
3. **Database-Level Security**: PostgreSQL still enforces permissions at SQL execution time

**Use Cases:**
- 🚀 **Simple Deployments**: When role-based filtering is not needed
- 🔄 **Migration Period**: Gradual rollout of security features
- 🧪 **Testing**: Simplified testing scenarios
- 🚨 **Emergency Rollback**: Quick disable if issues occur

## Production Deployment Strategies

### Strategy 1: Immediate Rollout (Recommended)

```yaml
# production.yaml
app:
  security:
    role-based-schema: true  # Enable immediately
```

**Benefits:** Full security from day one

### Strategy 2: Gradual Rollout

```yaml
# Phase 1: Disable initially
app:
  security:
    role-based-schema: false

# Phase 2: Enable after PostgreSQL roles/policies are configured
app:
  security:
    role-based-schema: true
```

**Benefits:** Allows time to configure PostgreSQL security policies

### Strategy 3: Environment-Specific

```yaml
# application-dev.yaml
app:
  security:
    role-based-schema: false  # Simplified development

# application-prod.yaml
app:
  security:
    role-based-schema: true   # Full security in production
```

## Security Comparison

### Feature Flag Enabled (Secure)

```graphql
# Employee role request
{
  sensitive_customer {
    customer_id     # ✅ Accessible
    first_name      # ✅ Accessible  
    salary          # ❌ Field not in schema
    ssn             # ❌ Field not in schema
  }
}

# Response: Only accessible fields included
{
  "data": {
    "sensitive_customer": [
      { "customer_id": 1, "first_name": "John" }
    ]
  }
}
```

### Feature Flag Disabled (Less Secure)

```graphql
# Employee role request  
{
  sensitive_customer {
    customer_id     # ✅ Accessible
    first_name      # ✅ Accessible
    salary          # ⚠️ Field visible in schema
    ssn             # ⚠️ Field visible in schema
  }
}

# Response: PostgreSQL permission error at runtime
{
  "errors": [
    { "message": "permission denied for column salary" }
  ]
}
```

## Configuration Examples

### Example 1: High-Security Environment

```yaml
app:
  security:
    role-based-schema: true
  database-type: postgres
  cache:
    schema-ttl-minutes: 120  # 2-hour cache for better performance
```

### Example 2: Development Environment

```yaml
app:
  security:
    role-based-schema: false  # Simplified for development
  database-type: postgres
```

### Example 3: Staged Rollout

```yaml
# application.yaml
app:
  security:
    role-based-schema: ${ROLE_BASED_SECURITY:true}  # Environment variable override
```

```bash
# Disable temporarily
export ROLE_BASED_SECURITY=false

# Enable when ready
export ROLE_BASED_SECURITY=true
```

## Monitoring and Troubleshooting

### Enable Debug Logging

```yaml
logging:
  level:
    io.github.excalibase: debug
```

### Debug Output

**Feature Flag Enabled:**
```
DEBUG Role 'employee' has access to 3/10 tables
DEBUG Schema filtering for role 'employee': 3/10 tables accessible
```

**Feature Flag Disabled:**
```
DEBUG Role-based schema filtering disabled - role 'employee' using full schema: 10 tables
DEBUG Default role using full schema: 10 tables
```

### Common Issues

#### Issue: Performance Degradation
**Symptom:** Slow GraphQL responses
**Solution:** 
```yaml
app:
  cache:
    schema-ttl-minutes: 120  # Increase cache duration
  security:
    role-based-schema: true   # Keep enabled for security
```

#### Issue: Missing Tables/Fields
**Symptom:** Expected tables not in GraphQL schema
**Solution:** Check PostgreSQL role privileges:
```sql
-- Check role privileges
SELECT * FROM information_schema.table_privileges 
WHERE grantee = 'your_role';

-- Check column privileges  
SELECT * FROM information_schema.column_privileges 
WHERE grantee = 'your_role';
```

#### Issue: Emergency Disable
**Symptom:** Security feature causing production issues
**Solution:** Quick disable via feature flag:
```yaml
app:
  security:
    role-based-schema: false  # Immediate disable
```

## Migration Guide

### From No Security to Role-Based Security

1. **Prepare PostgreSQL:**
   ```sql
   -- Create roles
   CREATE ROLE employee;
   CREATE ROLE manager;
   
   -- Grant appropriate privileges
   GRANT SELECT ON customer TO employee;
   GRANT ALL ON customer TO manager;
   
   -- Enable RLS
   ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
   
   -- Create policies
   CREATE POLICY employee_policy ON customer 
   FOR SELECT TO employee 
   USING (department_id = current_setting('app.user_dept')::int);
   ```

2. **Enable Feature Flag:**
   ```yaml
   app:
     security:
       role-based-schema: true
   ```

3. **Test Thoroughly:**
   ```bash
   # Test with different roles
   curl -H "X-Database-Role: employee" \
        -d '{"query": "{ customer { id name } }"}' \
        http://localhost:10000/graphql
   ```

### Rollback Plan

If issues occur, immediately disable:

```yaml
app:
  security:
    role-based-schema: false
```

This preserves all functionality while disabling role-based filtering.

## Best Practices

### ✅ **Recommended Practices**

1. **Keep Enabled in Production:**
   ```yaml
   app:
     security:
       role-based-schema: true  # Always enable for security
   ```

2. **Configure Appropriate Cache TTL:**
   ```yaml
   app:
     cache:
       schema-ttl-minutes: 120  # Balance performance vs freshness
   ```

3. **Use Environment Variables:**
   ```yaml
   app:
     security:
       role-based-schema: ${ROLE_BASED_SECURITY:true}
   ```

4. **Monitor Performance:**
   ```yaml
   logging:
     level:
       io.github.excalibase.service: debug  # Monitor cache hits/misses
   ```

### ❌ **Anti-Patterns**

1. **Don't Disable in Production Without Reason:**
   ```yaml
   # ❌ Reduces security
   app:
     security:
       role-based-schema: false
   ```

2. **Don't Set Very Short Cache TTL:**
   ```yaml
   # ❌ Poor performance
   app:
     cache:
       schema-ttl-minutes: 1
   ```

3. **Don't Forget to Configure PostgreSQL Security:**
   - Always set up proper roles, privileges, and RLS policies
   - Feature flag only controls schema filtering, not database security

## Conclusion

The role-based security feature flag provides flexible control over Excalibase's advanced security features while maintaining high performance and backward compatibility. Enable it for maximum security, or disable it for simplified deployments during migration periods.

For questions or issues, refer to the [comprehensive RLS/CLS testing documentation](../../tests/GraphqlRlsClsTest.md) and [security best practices guide](./security-best-practices.md). 