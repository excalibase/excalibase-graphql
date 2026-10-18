# Excalibase GraphQL

[![CI](https://github.com/excalibase/excalibase-graphql/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/excalibase/excalibase-graphql/actions/workflows/ci.yml)
[![E2E Tests](https://github.com/excalibase/excalibase-graphql/actions/workflows/e2e.yml/badge.svg?branch=main)](https://github.com/excalibase/excalibase-graphql/actions/workflows/e2e.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15+-blue.svg)](https://www.postgresql.org/)
[![MySQL](https://img.shields.io/badge/MySQL-8.4+-orange.svg)](https://www.mysql.com/)
[![Docs](https://img.shields.io/badge/docs-excalibase.github.io-brightgreen.svg)](https://excalibase.github.io/excalibase-graphql/)

**Instant GraphQL + REST APIs on your existing Postgres or MySQL.** Introspects the schema at startup, compiles each GraphQL request into one SQL statement, enforces per-role permissions in the engine (the same rules on MySQL, which has no row-level security), streams realtime changes over WebSocket, and ships a 134 MB native binary.

This is the API engine of [Excalibase](https://github.com/excalibase/excalibase), an open-source platform as a service: databases, generated APIs, permissions, end-user auth, functions and app hosting in one project. Hosted beta at [excalibase.io](https://excalibase.io).

📘 **[Full documentation →](https://excalibase.github.io/excalibase-graphql/)**

---

## Features

| | Feature | Docs |
|---|---|---|
| 🔄 | **Auto schema generation** — GraphQL types + REST endpoints from your tables, zero config | [GraphQL](docs/graphql/index.md) · [REST](docs/rest/index.md) |
| 🔍 | **Rich filtering** — `eq/neq/gt/lt/in`, regex, FTS, JSON paths, vectors, arrays — per your DB | [Filtering](docs/graphql/filtering.md) · [DB compat](docs/database-compatibility.md) |
| 🧭 | **Vector k-NN search** — pgvector-backed, cosine/L2/IP distance, HNSW indexes | [Search & Vector](docs/features/search-and-vector.md) |
| 📡 | **Realtime subscriptions** — WebSocket, backed by WAL (Postgres) or binlog (MySQL) via NATS CDC, filtered by the subscriber's permissions | [Subscriptions](docs/features/subscriptions.md) |
| 🔐 | **API permissions** — Hasura-style, one object per table, role and operation: rows, columns and write checks | [Permissions](docs/features/permissions.md) |
| 🗄️ | **Tracked functions** — Postgres functions returning table rows, per-role, results filtered by the return table's permissions | [Functions](docs/features/functions.md) |
| 🔑 | **Composite keys, FK relations, views** — forward + reverse FK fields auto-wired | [GraphQL](docs/graphql/index.md) |
| 📄 | **Cursor pagination** — Relay-spec GraphQL connections + PostgREST-style REST | [GraphQL](docs/graphql/index.md) · [REST](docs/rest/index.md) |
| 🏢 | **Multi-schema** — all schemas auto-discovered (except `pg_*` and `information_schema`) | [Multi-schema](docs/features/multi-schema.md) |
| ⚡ | **Native image** — GraalVM 25 AOT, ~134 MB binary, starts in <100 ms | [Install](docs/quickstart/index.md) |
| 📦 | **TypeScript SDK** — [`@excalibase/sdk`](https://www.npmjs.com/package/@excalibase/sdk): auth + GraphQL + REST in one client, codegen types | [SDK](docs/sdk.md) |

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

```

Full install guide (JVM, native image, K8s): **[docs/quickstart →](docs/quickstart/index.md)**

## The platform

This repo is one service in a larger stack. Each piece runs independently:

| Repo | Purpose |
|------|---------|
| **excalibase-graphql** *(this)* | GraphQL + REST engine on your DB |
| [excalibase-auth](https://github.com/excalibase/excalibase-auth) | JWT/JWKS auth with email/password + API keys |
| [excalibase-watcher](https://github.com/excalibase/excalibase-watcher) | Postgres WAL / MySQL binlog → NATS CDC for realtime + cache invalidation |
| [excalibase](https://github.com/excalibase/excalibase) | Control plane and Studio: projects, provisioning, backups, functions, app hosting |
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

See **[Testing](docs/testing.md)** for the full workflow and **[Contributing](docs/CONTRIBUTING.md)** if you're opening a PR.

## Architecture (60 seconds)

- **Modules**: `starter` (shared SPI + CDC) · `postgres` + `mysql` (dialect impls) · `rest-api` · `rls-core` + `rls-jdbc` (permissions) · `graphql-api` (composes everything)
- **SqlDialect SPI**: each DB declares which operators it supports; schema is built dynamically from that. Adding a new DB = implementing one dialect interface
- **CDC path**: Watcher tails WAL → publishes to NATS JetStream → `NatsCDCService` → `SubscriptionService` (reactor sinks per table) → WebSocket out
- **Permissions**: per role, table and operation (row filters, column lists, write checks), compiled into the same SQL statement; checked on MySQL against native Postgres RLS by a differential test suite

## License

Apache 2.0 — see [LICENSE](LICENSE).
