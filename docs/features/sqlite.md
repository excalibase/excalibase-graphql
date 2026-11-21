# SQLite (tiny projects)

For a small project that wants neither Postgres nor a database server: the engine serves a SQLite **file**.
GraphQL and REST both work, and the permissions are the same ones Postgres and MySQL use.

## Run it

```yaml
spring:
  datasource:
    url: jdbc:sqlite:/data/app.db      # no username, no password
    driver-class-name: org.sqlite.JDBC
app:
  database-type: sqlite
  project-id: my-app
```

Or with environment variables: `SPRING_DATASOURCE_URL=jdbc:sqlite:/data/app.db`,
`SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.sqlite.JDBC`, `APP_DATABASE_TYPE=sqlite`.

The slim image `excalibase/excalibase-graphql:<tag>-sqlite` carries only this database; mount the file's directory at
`/data`.

The file must already exist with its tables; the engine reflects them with `PRAGMA` (tables, views, columns,
declared types, primary keys, foreign keys and unique indexes). Its one schema is `main`, so a table `todos` is
`mainTodos` in GraphQL (`createMainTodos`, `mainTodosConnection`) and `/api/v1/todos` in REST.

## Standalone example

[`deploy/standalone/sqlite`](https://github.com/excalibase/excalibase-graphql/tree/main/deploy/standalone/sqlite)
holds a compose file, a `permissions.yaml` and an `init.sql`. It uses the `file` metadata source, as the
[standalone guide](../deploy/standalone-docker.md) describes. Table keys in the document are `main.<table>`.

```bash
cd deploy/standalone/sqlite && mkdir -p data && sqlite3 data/app.db < init.sql
docker compose up -d     # http://localhost:10000/my-app/graphql
```

## Permissions

SQLite has no roles and no row-level security, so the engine enforces them itself, the way it does for MySQL:
each rule is compiled into the SQL of the request. Reads carry the select filter, UPDATE and DELETE carry it too,
and every written row is read back by key and must pass the insert or update check, or the whole write rolls back.
The compiled filters are tested against native Postgres row security: the same rule keeps the same rows.

- A table with no rule is not reachable by anyone but the `service` role, as on every database.
- A check that reads the table it writes is refused, not judged after the write (the same limit as MySQL).
- A permission that cannot be compiled for SQLite refuses the request or the start, never serves unfiltered rows.

Declared types decide how a rule compares values. Timestamps and dates are ISO text (the engine writes
`yyyy-MM-ddTHH:mm:ss.SSSSSSZ`, in UTC, and compares that text; a value SQLite wrote itself, such as
`DEFAULT CURRENT_TIMESTAMP`'s `yyyy-MM-dd HH:mm:ss`, is read in that form too, in rules and in filters, at the cost of
an index on the column); a column declared with no type, or a type the
engine does not know (BLOB, for one), can only be tested for NULL in a rule. SQLite has no enum type, so an
enum-like column is text and orders as text.

## Column types

| Declared | Answered as |
|---|---|
| `INTEGER`, `INT`, `SMALLINT` | number |
| `BIGINT`, `NUMERIC`, `DECIMAL` | string of every digit |
| `REAL`, `FLOAT`, `DOUBLE` | number |
| `BOOLEAN` | `true` / `false` (stored 0/1) |
| `TEXT`, `VARCHAR`, `CHAR` | string |
| `JSON` | JSON value |
| `DATE`, `DATETIME`, `TIMESTAMP`, `TIMESTAMPTZ` | ISO 8601 string |
| `BLOB` | `\x` hex |
| `UUID` | string |

## Filters

`eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `in`, `notIn`, `like`, `ilike`, `startsWith`, `endsWith`, `contains`
(text), `isNull`, `isNotNull`; REST `eq neq gt gte lt lte like ilike in notin is isnotnull startswith endswith`.
`like` is case sensitive and `ilike` folds ASCII case (SQLite's own `LOWER`); a backslash escapes `%` and `_`, as on Postgres. Nothing else appears in the
schema: no regular expressions, no full-text search, no JSON or array operators. A REST request for one is
refused by name.

## Limits

- **One writer.** Every connection runs in WAL mode with a 10 second busy timeout. A write takes the write lock
  before its first statement, so concurrent writes queue instead of failing with `SQLITE_BUSY`. A read takes no lock,
  so it never waits for a writer and a writer never waits for it. Run one engine replica per file.
- **Durability.** Commits are synced with `synchronous=NORMAL`: after a power loss (not a crash of the engine) the
  last commits may be lost, the file is never corrupted. Set `SQLITE_SYNCHRONOUS=FULL` to sync every commit, at the
  cost of write speed.
- **The file must exist.** The engine opens it read and write and never creates it; a wrong path is an error at
  start, not a new empty database.
- **Native image.** The JDBC driver loads a native library: run Java with `--enable-native-access=ALL-UNNAMED`, and
  on a host where `/tmp` is mounted `noexec` set `-Dorg.sqlite.tmpdir=<a directory that allows exec>`.
- **No realtime.** A file has no change feed. The schema has no `Subscription` type and a subscription on the
  REST or GraphQL websocket is refused with a message saying so. Poll the data instead.
- **A file on disk.** Put it on a local disk or volume, not a network share; back it up with `sqlite3 .backup`.
- **Writes are keyed.** Rows are written and read back by primary key, as on MySQL, so a table with no primary
  key and no unique index of NOT NULL columns cannot return its written rows. `INTEGER PRIMARY KEY` is
  generated by SQLite. `onConflict` works on GraphQL (name `PRIMARY` or a unique index) and on REST (`Prefer: resolution=merge-duplicates`).
- **No multi-tenant mode, no functions, no `DISTINCT ON`.**
- **JVM and native image.** The driver (`org.xerial:sqlite-jdbc`, Apache-2.0) bundles its native libraries and
  ships a GraalVM feature that includes them. The engine's native config registers the SQLite classes, but a
  native image of the all-in-one app has not been built with it yet: treat SQLite as **JVM only** until it has.
