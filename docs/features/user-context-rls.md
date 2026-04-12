# JWT-Based Row Level Security (RLS)

## Overview

Excalibase integrates PostgreSQL **Row Level Security (RLS)** with JWT authentication. When a valid JWT is present, Excalibase sets the user ID as a PostgreSQL session variable before executing queries, enabling per-user row filtering without separate database roles.

## How It Works

1. Client sends `Authorization: Bearer <jwt>` header
2. `JwtAuthFilter` verifies the JWT signature using the configured JWKS endpoint or public key
3. Excalibase sets: `SET LOCAL request.user_id = '<userId from JWT>'`
4. Your RLS policy reads `current_setting('request.user_id', true)` to filter rows
5. Session variable is scoped to the transaction — automatically cleared after each request

```
Client → Bearer JWT → JwtAuthFilter (verify) → SET LOCAL request.user_id → Query + RLS
```

## Configuration

### Enable JWT Verification

```yaml
# application.yaml
app:
  security:
    jwt-enabled: true
    auth:
      # Option A: JWKS endpoint (recommended — works with any OIDC provider)
      jwks-url: https://auth.example.com/.well-known/jwks.json

      # Option B: Inline public key PEM (local dev)
      # public-key: |
      #   -----BEGIN PUBLIC KEY-----
      #   MFkwEwYHKoZIzj0CAQY...
      #   -----END PUBLIC KEY-----

      # Option C: PEM file path (K8s Secret volume mount)
      # public-key-file: /run/secrets/jwt-public-key.pem
```

### Unauthenticated Requests

When `jwt-enabled: true`, requests without a JWT token are **allowed through** (no 401). The RLS policy at the database level controls what data is visible — a null `request.user_id` typically returns zero rows for protected tables.

Invalid tokens (malformed or wrong signature) return **401 Unauthorized**.

## Database Setup

### 1. Enable RLS on a Table

```sql
-- Use a non-superuser role for your app — superusers bypass RLS
CREATE ROLE app_user WITH LOGIN PASSWORD 'password';
GRANT SELECT, INSERT, UPDATE, DELETE ON orders TO app_user;

ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE ROW LEVEL SECURITY;  -- applies to table owner too
```

### 2. Create the RLS Policy

```sql
-- Users see only their own rows
CREATE POLICY user_isolation ON orders
  USING (user_id = current_setting('request.user_id', true));
```

`current_setting('request.user_id', true)` — the second argument `true` means return NULL (not an error) when the variable is not set, which handles the unauthenticated case.

### 3. Grant session variable permission

```sql
-- Allow app_user to read the session variable
GRANT SELECT ON pg_settings TO app_user;
```

## Example: GraphQL Query with RLS

```bash
# Login to get a token (excalibase-auth, Auth0, Keycloak, etc.)
TOKEN=$(curl -s -X POST https://auth.example.com/login \
  -d '{"email":"alice@example.com","password":"secret"}' | jq -r .accessToken)

# Query — sees only alice's orders (RLS filters by JWT userId)
curl -X POST http://localhost:10000/graphql \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"query":"{ orders { id product total } }"}'
```

## Example: REST Query with RLS

```bash
# GET filtered by JWT userId automatically via RLS
curl http://localhost:10000/api/v1/orders \
  -H "Authorization: Bearer $TOKEN"
```

## Multi-Table RLS

All tables with RLS policies automatically benefit from the JWT context. A single JWT sets the session variable once per request — all table accesses within that request see the same user context.

```sql
CREATE POLICY user_isolation ON orders
  USING (user_id = current_setting('request.user_id', true));

CREATE POLICY user_isolation ON payments
  USING (user_id = current_setting('request.user_id', true));

CREATE POLICY user_isolation ON notifications
  USING (user_id = current_setting('request.user_id', true));
```

```graphql
# Single request — all three tables filtered by JWT userId
{ orders { id product } payments { id amount } notifications { id message } }
```

## JWT Claims Available

The following claims are extracted from the JWT and available for RLS:

| Session Variable | JWT Claim | Example Value |
|-----------------|-----------|---------------|
| `request.user_id` | `userId` or `sub` | `"user-42"` |
| `request.project_id` | `projectId` | `"proj-abc"` |

```sql
-- Tenant isolation using projectId claim
CREATE POLICY tenant_isolation ON data
  USING (project_id = current_setting('request.project_id', true));
```

## Supported JWT Providers

Any OIDC-compatible provider that exposes a `/.well-known/jwks.json` (or equivalent) endpoint:

- **excalibase-auth** — lightweight companion auth service
- **Auth0** — `https://your-tenant.auth0.com/.well-known/jwks.json`
- **Keycloak** — `https://keycloak.example.com/realms/myrealm/protocol/openid-connect/certs`
- **Supabase** — configured via JWKS URL
- **Any OIDC provider** — point `auth.jwks-url` at the JWKS endpoint

## Security Notes

- Use a **non-superuser** database role for `app.datasource.username` — PostgreSQL superusers bypass RLS
- The JWT is verified cryptographically before the session variable is set — no client-controlled input reaches the DB
- Session variables are scoped to the transaction (`SET LOCAL`) — they cannot persist across requests in the connection pool
