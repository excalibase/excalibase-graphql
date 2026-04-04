# Installation Guide

## Prerequisites

- Docker and Docker Compose
- (Local dev only) Java 21+, Maven 3.8+

---

## Option 1: Docker (Recommended)

### PostgreSQL Stack

```bash
git clone https://github.com/excalibase/excalibase-graphql.git
cd excalibase-graphql
docker-compose up -d
```

API available at **http://localhost:10000/graphql**

### MySQL Stack

```bash
docker-compose -f docker-compose.mysql.yml up -d
```

API available at **http://localhost:10001/graphql**

### Check Status

```bash
docker-compose ps
docker-compose logs -f excalibase-app
```

---

## What's Included

Both stacks start with sample data so you can query immediately.

### PostgreSQL (`docker-compose.yml`)

- **Image:** `postgres:15-alpine` on port 5432
- **Database/Schema:** `hana`
- **Credentials:** `hana001` / `password123`

Sample tables:
- `users`, `posts`, `comments`, `tasks` — blog-style demo data with custom enum/composite types
- `customer`, `orders`, `products`, `order_items` — e-commerce data with FK relationships
- `enhanced_types` — JSON/JSONB, arrays, INET, CIDR, MACADDR, BYTEA, XML, TIMESTAMPTZ
- `wallets` — for stored procedure testing
- `rls_orders` — Row-Level Security demo table

Views: `active_customers`, `posts_with_authors`, `enhanced_types_summary` (materialized)

Stored procedures: `get_customer_order_count`, `transfer_funds`

### MySQL (`docker-compose.mysql.yml`)

- **Image:** `mysql:8.4` on port 3306
- **Database:** `excalibase`
- **Credentials:** `excalibase` / `password123`

Sample tables: `customer`, `orders`, `product`, `task` (ENUM columns), `product_detail` (JSON columns), `wallets`

Views: `active_customers`, `orders_summary`, `high_value_orders`

Stored procedures: `get_customer_order_count`, `transfer_funds`

---

## Sample Queries

### Basic Query

```graphql
{
  hanaUsers {
    id
    username
    email
    role
  }
}
```

### With Filtering and Relationships

```graphql
{
  hanaPosts(where: { published: { eq: true } }, orderBy: { created_at: ASC }) {
    id
    title
    author_id
    hanaUsers {
      username
      first_name
    }
  }
}
```

### Custom Enum and Composite Types (PostgreSQL)

```graphql
{
  hanaUsers {
    id
    username
    role             # user_role enum: admin, moderator, user, guest
    shipping_address # address composite type
    contact          # contact_info composite type
  }
}
```

### Aggregate

```graphql
{
  hanaOrdersAggregate {
    count
    sum { total_amount }
    avg { total_amount }
  }
}
```

### Stored Procedure

```graphql
mutation {
  callHanaTransferFunds(p_from_wallet_id: 1, p_to_wallet_id: 2, p_amount: 100.00)
}
```

---

## Option 2: Local Development

1. **Configure database** in `modules/excalibase-graphql-api/src/main/resources/application.yaml`:

    ```yaml
    spring:
      datasource:
        url: jdbc:postgresql://localhost:5432/hana
        username: hana001
        password: password123

    app:
      schemas: hana
      database-type: postgres   # or: mysql

    server:
      port: 10000
    ```

2. **Build and run:**

    ```bash
    mvn clean compile
    mvn spring-boot:run -pl modules/excalibase-graphql-api
    ```

---

## Option 3: Native Binary (GraalVM)

Pull the pre-built native image for minimal startup time (~50ms) and memory (~80MB):

```bash
docker pull excalibase/excalibase-graphql:native
```

Or build locally (requires GraalVM 21):

```bash
JAVA_HOME=~/.sdkman/candidates/java/21.0.2-graalce
mvn -Pnative package -DskipTests -pl modules/excalibase-graphql-api -am
```

---

## Common Commands

```bash
# Stop everything
docker-compose down

# Reset data (removes volumes)
docker-compose down -v && docker-compose up -d

# View logs
docker-compose logs -f excalibase-app

# Connect to PostgreSQL directly
docker-compose exec postgres psql -U hana001 -d hana

# Connect to MySQL directly
docker-compose -f docker-compose.mysql.yml exec mysql mysql -u excalibase -ppassword123 excalibase
```

---

## Troubleshooting

**App won't start** — check that the database container is healthy before the app starts:
```bash
docker-compose ps   # both containers should show "healthy" or "Up"
```

**Empty GraphQL schema** — the app builds the schema from the database at startup. If the DB wasn't ready, restart the app:
```bash
docker-compose restart excalibase-app
```

**Port conflict** — edit the host port mapping in `docker-compose.yml`:
```yaml
ports:
  - "9000:10000"   # change 9000 to any free port
```

---

## Next Steps

- [API Reference →](api/index.md) — full schema documentation
- [Filtering →](filtering.md) — all filter operators with examples
- [MySQL Support →](features/mysql.md) — MySQL-specific guide
- [Stored Procedures →](features/stored-procedures.md) — calling procedures from GraphQL
- [Subscriptions →](features/subscriptions.md) — real-time CDC subscriptions (PostgreSQL)
