# Excalibase GraphQL

[![CI](https://github.com/excalibase/excalibase-graphql/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/excalibase/excalibase-graphql/actions/workflows/ci.yml)
[![E2E Tests](https://github.com/excalibase/excalibase-graphql/actions/workflows/e2e.yml/badge.svg?branch=main)](https://github.com/excalibase/excalibase-graphql/actions/workflows/e2e.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15+-blue.svg)](https://www.postgresql.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.4+-orange.svg)](https://www.mysql.com/)
[![Docs](https://img.shields.io/badge/docs-excalibase.github.io-brightgreen.svg)](https://excalibase.github.io/excalibase-graphql/)

**Instant GraphQL + REST + NoSQL APIs on your existing Postgres or MySQL.** Introspects the schema at startup, serves three protocols from one process, streams realtime changes over WebSocket, and ships a 134 MB native binary.

📘 **[Full documentation →](https://excalibase.github.io/excalibase-graphql/)**

---

## Features

| | Feature | Docs |
|---|---|---|
| 🔄 | **Auto schema generation** — GraphQL types + REST endpoints from your tables, zero config | [GraphQL](docs/graphql/index.md) · [REST](docs/rest/index.md) |
| 📦 | **NoSQL document store** — JSONB-backed collections, expression indexes, Mongo-style DX | [NoSQL](docs/nosql/schema.md) |
| 🔍 | **Rich filtering** — `eq/neq/gt/lt/in`, regex, FTS, JSON paths, vectors, arrays — per your DB | [Filtering](docs/graphql/filtering.md) · [DB compat](docs/database-compatibility.md) |
| 🧭 | **Vector k-NN search** — pgvector-backed, cosine/L2/IP distance, HNSW indexes | [Search & Vector](docs/features/search-and-vector.md) |
| 📡 | **Realtime subscriptions** — WebSocket, backed by WAL via NATS CDC; one endpoint serves REST + NoSQL + GraphQL | [Realtime](docs/nosql/realtime.md) · [GraphQL subs](docs/features/subscriptions.md) |
| 🔐 | **Row-level security** — native Postgres RLS, enforced via `request.user_id` session var | [RLS](docs/features/user-context-rls.md) |
| 🗄️ | **Stored procedures** — `CALL proc(args)` as `callProcName` mutations with IN/OUT params | [Stored procedures](docs/features/stored-procedures.md) |
| 🔑 | **Composite keys, FK relations, views** — forward + reverse FK fields auto-wired | [GraphQL](docs/graphql/index.md) |
| 📄 | **Cursor pagination** — Relay-spec GraphQL connections + PostgREST-style REST + keyset for NoSQL | [Pagination](docs/nosql/pagination.md) |
| ✅ | **JSON Schema validation** — Draft 2020-12 on NoSQL inserts | [Validation](docs/nosql/validation.md) |
| 🏢 | **Multi-schema** — all schemas auto-discovered (except `pg_*`, `information_schema`, `nosql`) | [Multi-schema](docs/features/multi-schema.md) |
| ⚡ | **Native image** — GraalVM 25 AOT, ~134 MB binary, starts in <100 ms | [Install](docs/quickstart/index.md) |

## Quickstart

```bash
# 1. Start Postgres + API (pgvector-enabled)
docker compose up -d

# 2. GraphQL
curl -X POST http://localhost:10000/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ __schema { queryType { name } } }"}'

# 3. REST
curl 'http://localhost:10000/api/v1/rest/customer?limit=5'

# 4. NoSQL — declare a collection + insert
curl -X POST http://localhost:10000/api/v1/nosql -d '{
  "collections": { "users": { "indexes": [{"fields":["email"],"unique":true}] } }
}' -H 'Content-Type: application/json'

curl -X POST http://localhost:10000/api/v1/nosql/users -d '{
  "doc": {"email":"vu@acme.com","status":"active"}
}' -H 'Content-Type: application/json'
```

Full install guide (JVM, native image, K8s): **[docs/quickstart →](docs/quickstart/index.md)**

## The platform

This repo is one service in a larger stack. Each piece runs independently:

| Repo | Purpose |
|------|---------|
| **excalibase-graphql** *(this)* | GraphQL + REST + NoSQL gateway on your DB |
| [excalibase-auth](https://github.com/excalibase/excalibase-auth) | JWT/JWKS auth with email/password + API keys |
| [excalibase-watcher](https://github.com/excalibase/excalibase-watcher) | Postgres WAL → NATS CDC for realtime + cache invalidation |
| [excalibase-provisioning](https://github.com/excalibase/excalibase-provisioning) | Multi-tenant DB provisioning, Studio web UI, Deno edge functions |
| [excalibase-sdk-js](https://github.com/excalibase/excalibase-sdk-js) | TypeScript SDK (dual-protocol, auth, codegen) |

## Development

```bash
# Run the full test suite (unit + IT + E2E)
make e2e

# Build the native image (requires GraalVM 25)
make build-native

# Run the local dev stack with observability (Grafana, Prometheus, Tempo, Loki)
make up
```

See **[Testing](docs/testing.md)** for the full workflow and **[Contributing](CONTRIBUTING.md)** if you're opening a PR.

## Architecture (60 seconds)

- **Modules**: `starter` (shared SPI + CDC) · `postgres` + `mysql` (dialect impls) · `rest-api` · `nosql` · `graphql-api` (composes everything)
- **SqlDialect SPI**: each DB declares which operators it supports; schema is built dynamically from that. Adding a new DB = implementing one dialect interface
- **CDC path**: Watcher tails WAL → publishes to NATS JetStream → `NatsCDCService` → `SubscriptionService` (reactor sinks per table) → WebSocket out
- **NoSQL**: lives in the `nosql` Postgres schema; relational introspection filters it out. Same DB, same pool, two surfaces

## License

Apache 2.0 — see [LICENSE](LICENSE).
