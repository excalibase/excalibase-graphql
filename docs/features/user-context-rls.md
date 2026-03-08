# User Context-Based RLS (Row Level Security)

## Overview

Excalibase now supports **Supabase-style RLS** where user context is automatically set as PostgreSQL session variables. This allows dynamic, per-user row filtering without creating separate database roles for each user.

## How It Works

1. **UserContextFilter** extracts user ID from request headers
2. Sets PostgreSQL session variable: `SET LOCAL request.user_id = 'user-123'`
3. RLS policies reference: `current_setting('request.user_id')`
4. Automatically clears context after request

## Configuration

### Enable User Context

```yaml
# application.yaml
app:
  security:
    user-context-enabled: true      # Default: true
    user-id-header: X-User-Id       # Default: X-User-Id
  database-type: postgres
```

### Disable User Context

```yaml
app:
  security:
    user-context-enabled: false     # Disable RLS user context
```

## PostgreSQL Setup

### 1. Create RLS Policy

```sql
-- Enable RLS on table
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;

-- Create policy using user context
CREATE POLICY user_orders_policy ON orders
FOR ALL
USING (user_id = current_setting('request.user_id', true));

-- Or for multi-tenant apps
CREATE POLICY tenant_policy ON customers
FOR ALL
USING (tenant_id = current_setting('request.jwt.tenant_id', true));
```

### 2. Grant Permissions

```sql
-- Grant permissions to public or authenticated role
GRANT SELECT, INSERT, UPDATE, DELETE ON orders TO PUBLIC;
```

**Note:** Everyone uses the same database connection. RLS policies handle row-level filtering based on user context.

## Usage Examples

### Basic Request

```bash
curl -X POST http://localhost:10000/graphql \
  -H "Content-Type: application/json" \
  -H "X-User-Id: user-123" \
  -d '{
    "query": "{ orders { id total } }"
  }'
```

**Result:** Only orders where `user_id = 'user-123'` are returned.

### With Additional Claims

```bash
curl -X POST http://localhost:10000/graphql \
  -H "Content-Type: application/json" \
  -H "X-User-Id: user-123" \
  -H "X-Claim-tenant_id: acme-corp" \
  -H "X-Claim-department_id: 5" \
  -d '{
    "query": "{ orders { id total } }"
  }'
```

**PostgreSQL Session Variables Set:**
- `request.user_id` = 'user-123'
- `request.jwt.tenant_id` = 'acme-corp'
- `request.jwt.department_id` = '5'

## Advanced RLS Policies

### User-Specific Access

```sql
CREATE POLICY user_policy ON orders
FOR ALL
USING (user_id = current_setting('request.user_id', true));
```

### Multi-Tenant with Department

```sql
CREATE POLICY tenant_dept_policy ON employees
FOR ALL
USING (
  tenant_id = current_setting('request.jwt.tenant_id', true)
  AND
  (
    department_id = current_setting('request.jwt.department_id', true)::int
    OR
    current_setting('request.jwt.role', true) = 'admin'
  )
);
```

### Manager Can See Team Members

```sql
CREATE POLICY manager_team_policy ON employees
FOR SELECT
USING (
  user_id = current_setting('request.user_id', true)
  OR
  manager_id = current_setting('request.user_id', true)
);
```

### Read-Only for Regular Users, Full Access for Admins

```sql
-- Read policy for everyone
CREATE POLICY read_policy ON documents
FOR SELECT
USING (
  tenant_id = current_setting('request.jwt.tenant_id', true)
);

-- Write policy only for admins
CREATE POLICY write_policy ON documents
FOR INSERT, UPDATE, DELETE
USING (
  tenant_id = current_setting('request.jwt.tenant_id', true)
  AND
  current_setting('request.jwt.role', true) = 'admin'
);
```

## Architecture

### Components

1. **IUserContextService** - Interface for setting user context (database-agnostic)
2. **PostgresUserContextService** - PostgreSQL implementation using `SET LOCAL`
3. **UserContextFilter** - Servlet filter that extracts headers and sets context
4. **ServiceLookup** - Resolves database-specific implementation

### Flow Diagram

```
HTTP Request
    ↓
UserContextFilter (extracts X-User-Id header)
    ↓
ServiceLookup → PostgresUserContextService
    ↓
SET LOCAL request.user_id = 'user-123'
SET LOCAL request.jwt.tenant_id = 'acme'
    ↓
GraphqlController → Execute Query
    ↓
PostgreSQL RLS Policy Applied
    ↓
Filtered Results
    ↓
UserContextFilter (clears context)
    ↓
HTTP Response
```

## Comparison with Traditional RLS

### Old Approach (Role-Based)

```sql
-- Must create role for each user ❌
CREATE ROLE user_123;
CREATE ROLE user_456;
-- ...thousands of roles

-- Set role per request
SET ROLE user_123;

-- Static policy
CREATE POLICY policy ON orders
FOR ALL TO user_123
USING (user_id = 123);
```

**Problems:**
- Doesn't scale (thousands of database roles)
- Complex management
- Role explosion

### New Approach (User Context)

```sql
-- Just one policy for all users ✅
CREATE POLICY user_policy ON orders
FOR ALL
USING (user_id = current_setting('request.user_id', true));
```

**Benefits:**
- ✅ Single policy for all users
- ✅ No per-user roles needed
- ✅ Dynamic filtering
- ✅ Scales to millions of users

## Testing RLS Policies

### Test in psql

```sql
-- Simulate user context
SET LOCAL request.user_id = 'user-123';
SET LOCAL request.jwt.tenant_id = 'acme';

-- Test query
SELECT * FROM orders;

-- Should only return user-123's orders
```

### Test in Application

```bash
# User A sees only their orders
curl -H "X-User-Id: user-a" \
  -d '{"query": "{ orders { id } }"}' \
  http://localhost:10000/graphql
# Returns: [{"id": 1}, {"id": 2}]

# User B sees different orders
curl -H "X-User-Id: user-b" \
  -d '{"query": "{ orders { id } }"}' \
  http://localhost:10000/graphql
# Returns: [{"id": 3}, {"id": 4}]
```

## Security Best Practices

### ✅ DO

1. **Always use RLS policies** - Don't rely solely on application logic
2. **Use `current_setting(..., true)`** - The `true` parameter prevents errors if variable not set
3. **Test policies thoroughly** - Verify users can't access others' data
4. **Use HTTPS** - Protect user ID in transit
5. **Validate user ID** - Verify JWT/session before setting X-User-Id header

### ❌ DON'T

1. **Don't trust client-provided X-User-Id directly** - Verify authentication first
2. **Don't forget to enable RLS** - `ALTER TABLE ... ENABLE ROW LEVEL SECURITY`
3. **Don't create overly permissive policies** - Start restrictive, then loosen
4. **Don't skip testing** - Test edge cases (null user_id, missing claims, etc.)

## Migration Guide

### From Old Role-Based Approach

```sql
-- 1. Drop old per-user roles
DROP POLICY IF EXISTS user_123_policy ON orders;
DROP ROLE IF EXISTS user_123;
-- Repeat for all users...

-- 2. Create new user context policy
CREATE POLICY user_context_policy ON orders
FOR ALL
USING (user_id = current_setting('request.user_id', true));

-- 3. Grant to PUBLIC (RLS handles filtering)
GRANT SELECT, INSERT, UPDATE, DELETE ON orders TO PUBLIC;
```

### Enable in Application

```yaml
# application.yaml
app:
  security:
    user-context-enabled: true
    user-id-header: X-User-Id
```

### Update Clients

```diff
- X-Database-Role: user_123
+ X-User-Id: user-123
```

## Future: JWT Support

JWT support will be added in a future release:

```bash
# Future feature
curl -H "Authorization: Bearer eyJhbGc..." \
  -d '{"query": "{ orders { id } }"}' \
  http://localhost:10000/graphql
```

For now, use a middleware/API gateway to:
1. Validate JWT
2. Extract `sub` claim
3. Forward as `X-User-Id` header

## Troubleshooting

### Issue: No rows returned

**Cause:** User context not set or RLS policy too restrictive

**Debug:**
```sql
-- Check if user context is set
SHOW request.user_id;

-- Temporarily disable RLS to verify data exists
ALTER TABLE orders DISABLE ROW LEVEL SECURITY;
SELECT * FROM orders;
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
```

### Issue: Permission denied

**Cause:** Missing GRANT permissions

**Fix:**
```sql
GRANT SELECT, INSERT, UPDATE, DELETE ON orders TO PUBLIC;
```

### Issue: Filter not working

**Cause:** RLS not enabled on table

**Fix:**
```sql
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
```

### Issue: current_setting not found

**Cause:** Using `current_setting(...)` without `true` parameter

**Fix:**
```sql
-- ❌ Will error if not set
current_setting('request.user_id')

-- ✅ Returns NULL if not set
current_setting('request.user_id', true)
```

## Performance

User context RLS is **very fast**:
- Session variables: ~0.1ms overhead
- RLS policy evaluation: Happens in PostgreSQL (index-optimized)
- No additional queries needed
- Connection pooling works normally

## Examples

See example RLS policies in:
- `modules/excalibase-graphql-api/src/test/groovy/io/github/excalibase/controller/GraphqlRlsClsTest.groovy`
- `docs/examples/rls-policies.sql` (coming soon)

## References

- [PostgreSQL RLS Documentation](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- [Supabase RLS Guide](https://supabase.com/docs/guides/database/postgres/row-level-security)
- [PostgreSQL Session Variables](https://www.postgresql.org/docs/current/functions-admin.html#FUNCTIONS-ADMIN-SET)
