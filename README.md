# Excalibase GraphQL

[![CI](https://github.com/excalibase/excalibase-graphql/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/excalibase/excalibase-graphql/actions/workflows/ci.yml)
[![E2E Tests](https://github.com/excalibase/excalibase-graphql/actions/workflows/e2e.yml/badge.svg?branch=main)](https://github.com/excalibase/excalibase-graphql/actions/workflows/e2e.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.java.net/)
[![Micronaut](https://img.shields.io/badge/Micronaut-4.10-brightgreen.svg)](https://micronaut.io)
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
| 🚀 | **Run it on your own** — one Postgres, permissions in a file, database or S3, an admin console on a private port | [Deploy guides](docs/deploy/standalone.md) |
| 🗄️ | **Tracked functions** — Postgres functions returning table rows, per-role, results filtered by the return table's permissions | [Functions](docs/features/functions.md) |
| 🔑 | **Composite keys, FK relations, views** — forward + reverse FK fields auto-wired | [GraphQL](docs/graphql/index.md) |
| 📄 | **Cursor pagination** — Relay-spec GraphQL connections + PostgREST-style REST | [GraphQL](docs/graphql/index.md) · [REST](docs/rest/index.md) |
| 🏢 | **Multi-schema** — all schemas auto-discovered (except `pg_*` and `information_schema`) | [Multi-schema](docs/features/multi-schema.md) |
| ⚡ | **Native image** — GraalVM 25 AOT, ~134 MB binary, starts in <100 ms | [Install](docs/quickstart/index.md) |
| 📦 | **TypeScript SDK** — [`@excalibase/sdk`](https://www.npmjs.com/package/@excalibase/sdk): auth + GraphQL + REST in one client, codegen types | [SDK](docs/sdk.md) |

## Quickstart

```bash
# 1. Start Postgres (with sample data), the engine, auth and a mock control plane
docker compose up -d
until curl -s -X POST http://localhost:10000/e2e-test/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ hanaCustomer(limit: 1) { customer_id } }"}' | grep -q customer_id; do sleep 3; done

# 2. GraphQL: routes are per project; the sample project is `e2e-test`, whose
#    permissions (served by the mock control plane) let anonymous callers read `hana.customer`
curl -s -X POST http://localhost:10000/e2e-test/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ hanaCustomer(limit: 2) { customer_id first_name last_name } }"}'
echo

# 3. REST: the same table and the same permissions
curl -s 'http://localhost:10000/e2e-test/api/v1/customer?limit=2' -H 'Accept-Profile: hana'
echo
```

A table with no permission for a role is invisible to it: try `/e2e-test/api/v1/rls_team_orders` (404).
Permissions per role, table and operation are described in [Permissions](docs/features/permissions.md).

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

## Why Micronaut

The engine runs on Micronaut. It used to run on Spring Boot, and three things were wrong for a thin application that mostly turns a request into one SQL statement:

- **Slow startup.** The engine took 17.5 s to be ready on one CPU.
- **Heavy for what it does.** About twice the memory for the same traffic.
- **A slow cold minute and a lower ceiling.** It needed about 60 s after the start before it served 800 rps, and its clean limit was lower.

The measurement is REST by id only, 1 CPU / 1 GiB, real Postgres, 3 runs, median [min-max]. Micronaut JVM is the shipped build; native is shown for reference. Each run has three phases, and figures are only comparable inside a phase: ready time (container start to the first correct answer), the cold minute (six 10 s chunks at 800 rps offered, right after ready), and the warm ladder (20 s steps at 1200, 1600, 2000, 2600 and 3200 rps). A ladder step is clean when the served rate is at least 99% of the offered rate and no check fails.

Phase 1, ready time:

| | Spring Boot | Micronaut JVM | Micronaut native |
|---|---|---|---|
| Ready time | 17.5 s [17.5-17.6] | 7.6 s [7.5-7.6] | 0.5 s [0.4-0.5] |

Phase 2, cold minute (800 rps offered), served rps / p95 in ms:

| Chunk | Spring Boot | Micronaut JVM | Micronaut native |
|---|---|---|---|
| 1 (0-10 s) | 85 [84-86] / 16122 [15930-16165] | 361 [353-366] / 2312 [2275-2322] | 800 / 1.3 [1.3-2.0] |
| 2 | 186 [180-194] / 1366 [1340-10304] | 794 [792-800] / 268 [170-288] | 800 / 2.1 [2.0-2.1] |
| 3 | 304 [281-310] / 793 [733-838] | 800 / 44 [37-46] | 800 / 2.7 [2.6-2.8] |
| 4 | 417 [405-420] / 945 [887-1021] | 800 / 14 [13-16] | 800 / 2.8 [2.6-2.8] |
| 5 | 452 [448-512] / 606 [390-1206] | 800 / 5.6 [4.9-6.7] | 800 / 2.7 [2.6-2.9] |
| 6 (50-60 s) | 793 [523-800] / 223 [170-333] | 800 / 2.0 [1.7-3.0] | 800 / 2.7 [2.5-2.8] |

Phase 3, warm ladder:

| | Spring Boot | Micronaut JVM | Micronaut native |
|---|---|---|---|
| Highest clean step | 2000 rps [1600-2000] | 3200 rps [3200-3200] (top of the ladder) | 2000 rps [2000-2000] |
| p95 at 1600 rps | 19.8 ms [12.3-27.5] | 1.5 ms [1.5-1.9] | 4.1 ms [4.1-4.2] |
| p99 at 1600 rps | 82 ms [53-93] | 5.2 ms [4.5-5.6] | 13.4 ms [13.3-13.5] |
| Memory (RSS) after the cold minute | 379 MiB [377-404] | 199 MiB [198-206] | 27.6 MiB [27.6-28.2] |

1600 rps is the highest step every run of every build passed cleanly. CPU was not recorded in a usable form and GraphQL was not measured, so neither is reported. The harness and the raw run files are in the private [excalibase/benchmarking](https://github.com/excalibase/benchmarking) repo, folder `spring-vs-micronaut/`, with a script that recomputes these tables.

**Staying on Spring:** the last Spring Boot release is tagged `spring-v1.6.3`, and the Spring code lives on the `spring-1.x` branch.

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
