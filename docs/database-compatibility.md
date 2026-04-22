# Database compatibility

Excalibase supports **PostgreSQL** as its primary target with full operator coverage, plus **MySQL** with a subset. This page lists every filter operator, which database supports it, and how it looks in each SQL dialect.

!!! info "Dynamic per-DB schema"
    The GraphQL schema is built from each database's `SqlDialect` implementation — operators that aren't supported on your DB **don't appear** in the generated schema. The matrix below is the canonical reference; your server's introspection is the live truth.

## Operator matrix

### Universal (all DBs)

| Operator | Meaning | Postgres | MySQL |
|----------|---------|----------|-------|
| `eq`         | Equals                  | ✅ | ✅ |
| `neq`        | Not equals              | ✅ | ✅ |
| `gt` / `gte` | Greater than (or eq)    | ✅ | ✅ |
| `lt` / `lte` | Less than (or eq)       | ✅ | ✅ |
| `in`         | Value in set            | ✅ | ✅ |
| `notIn`      | Value not in set        | ✅ | ✅ |
| `isNull`     | Field is NULL           | ✅ | ✅ |
| `isNotNull`  | Field is NOT NULL       | ✅ | ✅ |
| `like`       | SQL LIKE pattern        | ✅ | ✅ |
| `ilike`      | Case-insensitive LIKE   | ✅ | ✅ via `LOWER()` |
| `startsWith` | Prefix match            | ✅ | ✅ |
| `endsWith`   | Suffix match            | ✅ | ✅ |
| `contains`   | Substring               | ✅ | ✅ |

### Regex

| Operator | Postgres | MySQL |
|----------|----------|-------|
| `regex`   | `~`  (case-sensitive)    | ⚠️ **not yet wired** (MySQL uses `REGEXP BINARY`) |
| `iregex`  | `~*` (case-insensitive)  | ⚠️ **not yet wired** (MySQL uses `REGEXP`) |

### Full-text search

| Operator | Postgres column | Postgres function | MySQL |
|----------|----------------|-------------------|-------|
| `search`         | `tsvector` | `plainto_tsquery`       | ⚠️ planned (`MATCH ... AGAINST (:q IN NATURAL LANGUAGE MODE)`) |
| `webSearch`      | `tsvector` | `websearch_to_tsquery`  | ❌ no direct equivalent |
| `phraseSearch`   | `tsvector` | `phraseto_tsquery`      | ⚠️ planned (`MATCH ... AGAINST ('"phrase"' IN BOOLEAN MODE)`) |
| `rawSearch`      | `tsvector` | `to_tsquery`            | ⚠️ planned (`MATCH ... AGAINST (:q IN BOOLEAN MODE)`) |

REST operators: `fts` (raw), `plfts` (plain), `phfts` (phrase), `wfts` (websearch).

### Vector k-NN

| Operator | Postgres (pgvector) | MySQL |
|----------|---------------------|-------|
| `vector` (L2)     | `<->` + HNSW | ⚠️ MySQL 9+ `VECTOR` type planned |
| `vector` (COSINE) | `<=>` + HNSW | ⚠️ planned |
| `vector` (IP)     | `<#>` + HNSW | ⚠️ planned |

### JSON

| Operator | Postgres (jsonb) | MySQL (json) |
|----------|------------------|--------------|
| `contains`     | `@>`    | ⚠️ `JSON_CONTAINS(col, :v)` — not yet wired |
| `containedBy`  | `<@`    | ⚠️ `JSON_CONTAINS(:v, col)` — not yet wired |
| `hasKey`       | `?`     | ⚠️ `JSON_CONTAINS_PATH(col, 'one', :v)` — not yet wired |
| `hasAllKeys`   | `?&`    | ⚠️ `JSON_CONTAINS_PATH(col, 'all', ...)` — not yet wired |
| `hasAnyKeys`   | `?\|`   | ⚠️ `JSON_CONTAINS_PATH(col, 'one', ...)` — not yet wired |

### Array

| Operator | Postgres (`type[]`) | MySQL |
|----------|---------------------|-------|
| `contains`     | `@>` | ❌ not applicable (MySQL has no native array type) |
| `overlap`      | `&&` | ❌ |

### Network types

| Operator | Postgres (`inet`/`cidr`) | MySQL |
|----------|--------------------------|-------|
| `containsIp`     | `>>`   | ❌ no native INET/CIDR type |
| `containedByIp`  | `<<`   | ❌ |

### Date / datetime

| Operator | Postgres | MySQL |
|----------|----------|-------|
| `eq/neq/gt/gte/lt/lte` | ✅ all timestamp/date types | ✅ |
| `isToday` / `isYesterday` | ⚠️ helper, computed server-side | ⚠️ same |
| Format: `YYYY-MM-DD`, `YYYY-MM-DDTHH:MM:SSZ`, `YYYY-MM-DD HH:MM:SS` | ✅ | ✅ |

### Enums

| | Postgres | MySQL |
|---|----------|-------|
| Native enum columns | ✅ custom `CREATE TYPE ... AS ENUM` | ✅ `ENUM(...)` column |
| GraphQL representation | Enum type (uppercase values) | Enum type (uppercase values) |
| Filter | `eq/neq/in/notIn` on enum value | same |

## Side-by-side SQL

=== "Equality (eq)"

    Both dialects use parameterized `=`.

    ```sql
    -- Postgres
    WHERE "status" = :value

    -- MySQL
    WHERE `status` = :value
    ```

=== "Case-insensitive LIKE (ilike)"

    Postgres has a native operator; MySQL uses `LOWER()` on both sides.

    ```sql
    -- Postgres
    WHERE "email" ILIKE :pattern

    -- MySQL
    WHERE LOWER(`email`) LIKE LOWER(:pattern)
    ```

=== "Full-text search (search)"

    ```sql
    -- Postgres (requires tsvector column, indexed by GIN)
    WHERE "search_vec" @@ plainto_tsquery('english', :query)

    -- MySQL (requires FULLTEXT index) — planned
    WHERE MATCH(`title`, `body`) AGAINST(:query IN NATURAL LANGUAGE MODE)
    ```

=== "Vector k-NN (cosine)"

    ```sql
    -- Postgres (pgvector) — active
    ORDER BY "embedding" <=> :query::vector
    LIMIT :topK

    -- MySQL 9+ (VECTOR) — planned
    ORDER BY DISTANCE(`embedding`, :query, 'COSINE')
    LIMIT :topK
    ```

=== "JSON contains"

    ```sql
    -- Postgres (jsonb)
    WHERE "metadata" @> :jsonFragment::jsonb

    -- MySQL (json) — planned
    WHERE JSON_CONTAINS(`metadata`, :jsonFragment)
    ```

## Not-yet-wired operator behavior

MySQL operators marked ⚠️ above currently fail silently — the `WHERE` clause is dropped and the query returns all rows. **This is being fixed** in two steps:

1. **Short-term safety net**: change `SqlDialect` defaults to throw `UnsupportedOperationException` — fail loud instead of wrong results.
2. **Full implementation**: add the missing `MysqlDialect` methods for regex, FTS, JSON, and vector.

Until that lands, the generated MySQL schema will not expose operators the dialect doesn't support. Check your generated schema (GraphQL introspection or `excalibase-codegen`) for the authoritative list on your DB.

## Adding a new database

Each database implements one `SqlDialect` interface. A new DB = one class declaring:

- Identifier quoting (`"foo"` vs `` `foo` ``)
- Supported operators per column type (via `supportedFilterOps()`)
- SQL fragment for each operator (`regexSql`, `fullTextSearchSql`, `jsonPredicateSql`, etc.)

The framework composes the schema from whatever the dialect says it supports. No touching `IntrospectionHandler` or `FilterBuilder`.

See `modules/excalibase-graphql-postgres/.../PostgresDialect.java` and `modules/excalibase-graphql-mysql/.../MysqlDialect.java` for reference implementations.
