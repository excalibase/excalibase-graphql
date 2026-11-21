# Database compatibility

Excalibase runs on **PostgreSQL** with every operator and feature, and on **MySQL 8.0 and 8.4** with everything
MySQL can do. This page is the universal reference: what each database runs, and what happens when you reach for
something it does not. Per-database detail is on the [PostgreSQL](databases/postgresql.md) and
[MySQL](databases/mysql.md) pages.

!!! info "The schema is built from what the database can do"
    Each database declares its filter operators per column type and its optional features. The GraphQL schema is
    generated from that declaration, so an operator or feature your database cannot run **does not appear** in
    its schema. Reach it anyway (REST, or a hand-written query) and the request is **refused with an error naming
    the database and the operator**. It is never answered with part of the request left out: a dropped condition
    would return every row. Your server's introspection is the live truth.

## Universal operators

Every database runs these, on every column type that has an order or text.

| GraphQL | REST | Meaning | PostgreSQL | MySQL |
|---|---|---|---|---|
| `eq` / `neq` | `eq` / `neq` | Equal / not equal | yes | yes (not on JSON, see below) |
| `gt` `gte` `lt` `lte` | `gt` `gte` `lt` `lte` | Comparison | yes | yes |
| `in` / `notIn` | `in` / `notin` | List membership | yes | yes |
| `isNull` / `isNotNull` | `is` / `isnotnull` | Null check | yes | yes |
| `like` | `like` | SQL LIKE | yes | yes |
| `ilike` | `ilike` | Case-insensitive LIKE | yes | yes, as `LOWER(col) LIKE LOWER(:p)` |
| `startsWith` `endsWith` `contains` | `startswith` `endswith` | Literal prefix, suffix, substring | yes | yes |

## Operators that differ

### Regex

| GraphQL | REST | PostgreSQL | MySQL |
|---|---|---|---|
| `regex` | `match` | `col ~ :p` | `REGEXP_LIKE(col, :p, 'c')`, case-sensitive |
| `iregex` | `imatch` | `col ~* :p` | `REGEXP_LIKE(col, :p, 'i')`, case-insensitive |

The pattern dialects differ (PostgreSQL POSIX, MySQL ICU). Keep patterns to the common subset when a project
may move between them.

### Full-text search

| GraphQL | REST | PostgreSQL (`tsvector` column) | MySQL (text column) |
|---|---|---|---|
| `search` | `plfts` | `plainto_tsquery` | `MATCH ... AGAINST (:q IN BOOLEAN MODE)`, every word required |
| `phraseSearch` | `phfts` | `phraseto_tsquery` | boolean mode phrase `"a b"` |
| `rawSearch` | `fts` | `to_tsquery` | boolean mode syntax, as written |
| `webSearch` | `wfts` | `websearch_to_tsquery` | **not available**: MySQL has no equivalent syntax |

MySQL searches a text column only when it has a `FULLTEXT` index **on that column alone** (`MATCH(col)` needs an
index on exactly that column list). Other columns get no search operators in the schema, and a search on one is
refused with "needs a FULLTEXT index on this column alone".

### JSON

| GraphQL | REST | PostgreSQL (`jsonb`) | MySQL (`json`) |
|---|---|---|---|
| `contains` | `cs` / `jsoncontains` | `col @> :v` | `JSON_CONTAINS(col, :v)` |
| `containedBy` | `cd` / `jsoncontained` | `col <@ :v` | `JSON_CONTAINS(:v, col)` |
| `hasKey` | `haskey` | `jsonb_exists` | `JSON_CONTAINS_PATH(col, 'one', path)` |
| `hasKeys` | | `jsonb_exists_all` | `JSON_CONTAINS_PATH(col, 'all', paths)` |
| `hasAnyKeys` | | `jsonb_exists_any` | `JSON_CONTAINS_PATH(col, 'one', paths)` |
| `eq` / `neq` | | yes | **not available**: MySQL compares JSON values by type rules a bound text value cannot meet; use `contains` and `containedBy` |
| `path` / `pathText`, REST `jsonpath` | | yes | not available |

### Arrays, vectors and network types

| Operator | PostgreSQL | MySQL |
|---|---|---|
| Array `contains`, `containedBy`, `overlaps`, `hasAny`, `hasAll`, `length` | yes (`type[]` columns) | not available: no array type |
| Vector k-NN (`vector` argument, REST `vector`) | yes, with pgvector | not available: refused |
| Network (`inet`, `cidr`) | yes | not available: no such type |

## Features

| Feature | PostgreSQL | MySQL | If the database cannot |
|---|---|---|---|
| List, filter, order, offset pagination | yes | yes | |
| Cursor connections (`first`, `after`, `totalCount`) | yes | yes | |
| Aggregates (`count`, `sum`, `avg`, `min`, `max`) | yes | yes, same shape | field absent, query refused |
| Foreign-key relationships, both directions | yes | yes | |
| Views (read-only types) | yes | yes | |
| `create`, `createMany`, `update`, `delete` | yes | yes | |
| Rows returned by writes | `RETURNING` in one statement | read back by primary key in the same transaction | |
| Upsert: GraphQL `onConflict`, REST `on_conflict` | `ON CONFLICT ... DO UPDATE` | insert-or-update of the one row holding the key (see [MySQL](databases/mysql.md#upsert)) | `onConflict` refused |
| Nested inserts through relationships | yes | not available: absent from the schema | field absent |
| `distinctOn` | yes (`DISTINCT ON`) | not available: refused | refused |
| Tracked functions | yes | not available: routines are never exposed | |
| Enums | `CREATE TYPE ... AS ENUM` | `ENUM` columns | |
| Arrays, composite types | yes | not available | |
| Permissions (roles, row filters, checks, presets) | yes, plus database RLS | yes, compiled into every statement | |
| Realtime subscriptions | yes, from the watcher's logical replication stream | not supported yet: not in the schema, refused at the WebSocket | |
| REST (PostgREST-style) | yes | yes, same operators as above | refused with the database's name |

## REST parity

REST takes the operators of the table above under their PostgREST names, and refuses what the database cannot run:
`wfts`, `jsonpath`, `ov`, `sl`, `sr`, `nxl`, `nxr`, `adj`, `arraycontains`, `arrayhasany`, `arrayhasall`,
`arraylength` and `vector` answer `400` on MySQL with a message naming MySQL and the operator.

## Fail loud, never drop

The same rule covers everything a request can name:

* a filter operator the database does not run: refused, naming the operator and the column;
* a feature it does not run (`distinctOn`, `vector`, `onConflict`, aggregates): refused, naming the database;
* an unknown operator or argument: refused, never ignored.

## Adding a new database

A database is a plugin registered as `io.github.excalibase.SqlEngineProvider`. It implements `SqlDialect` and
declares what it runs:

1. **Identifier quoting and SQL shapes**: `quoteIdentifier`, `buildObject`, `aggregateArray`, `onConflict` and the
   rest of the abstract methods.
2. **Filter operators**: override `filterCapabilities()`. Start from `FilterCapabilities.PORTABLE` and add the
   operators the database runs per column kind; implement the SQL for each in `regexSql`, `fullTextSearchSql`,
   `jsonPredicateSql`. The where-input types in the schema list exactly the declared operators.
3. **Features**: override `features()` with the `DialectFeature`s it runs (`AGGREGATES`, `UPSERT`, `DISTINCT_ON`,
   `SUBSCRIPTIONS`, `VECTOR_SEARCH`). The default is none.
4. **Mutations**: a `MutationCompiler` names the arguments each mutation takes and whether inserts may nest.

A dialect that declares no features gets none of them (fail-closed), including aggregates: a third-party
dialect written before `features()` existed must now list what it runs.

Nothing needs to change in the introspection handler or the filter builder. See `PostgresDialect` and
`MysqlDialect` for reference implementations.
