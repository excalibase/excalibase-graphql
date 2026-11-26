# MySQL and SQLite

The same engine runs on MySQL or on a SQLite file. The permissions come from the same document, kept in a
file, as in the [Docker and compose guide](standalone-docker.md). Read that guide first for tokens, the console
and the checklist. This page shows what differs.

The examples are in
[`deploy/standalone`](https://github.com/excalibase/excalibase-graphql/tree/main/deploy/standalone):
`mysql/` and `sqlite/`. Each has a compose file, a `permissions.yaml` and an `init.sql`.

## MySQL

```bash
cd deploy/standalone/mysql
docker compose up -d
```

The engine serves `http://localhost:10000/my-app/graphql`. MySQL starts first, creates the `app` database
and the `todos` table from `init.sql`, and the engine waits until MySQL answers.

What is different from Postgres:

- **The schema is the database name.** The table key in the document is `app.todos`, and the GraphQL name is
  `appTodos` (`createAppTodos`, `appTodosConnection`). Ids of a `BIGINT` column come back as strings.
- **The settings.** `APP_DATABASE_TYPE: mysql`, the `jdbc:mysql:` URL, and
  `SPRING_DATASOURCE_DRIVER_CLASS_NAME: com.mysql.cj.jdbc.Driver`.
- **Rule source.** Use `file`, `url` or `s3`. The `db` source keeps its document in PostgreSQL, so it does not
  work with the `-mysql` image.
- **`init.sql` runs once.** MySQL runs it only when its data folder is empty. After that, change the schema with
  SQL. The engine notices the change within a few seconds. To start again from scratch,
  `docker compose down -v` removes the `mysql_data` volume.

## SQLite

```bash
cd deploy/standalone/sqlite
mkdir -p data
sqlite3 data/app.db < init.sql        # or: python3 -c "import sqlite3; sqlite3.connect('data/app.db').executescript(open('init.sql').read())"
docker compose up -d
```

There is no database server: the engine opens one file. The file must exist, with its tables, before the
engine starts.

- **One schema, `main`.** The table key is `main.todos` and the GraphQL name is `mainTodos`.
- **One replica.** A file has one writer.
- **No `-wal` surprises.** SQLite keeps recent writes in `app.db-wal` next to `app.db`. Never delete the
  `-wal` or `-shm` file by hand: writes that are not yet in `app.db` live in the `-wal` file, and they are lost
  with it. To copy or back up the database, stop the engine first, then copy the three files together, or use
  `sqlite3 data/app.db ".backup 'copy.db'"`.

### The data folder must be writable

The engine runs as user id 1000 inside the container. SQLite creates files next to the database, so the
**folder** must be writable by that user, not only the file. The compose file mounts `./data` at `/data`.

```bash
mkdir -p data && chown -R 1000:1000 data     # not needed when you are user 1000 yourself
```

If the folder is read-only, the engine still starts, but it cannot open the database. It then serves an empty
schema: every query fails with `Unknown field(s): mainTodos`, and `/actuator/health` answers 503 `DOWN`. The log has a
`Failed to load database schema` line with the cause.

## Health check

Both compose files turn on the readiness probe (`MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true"`) and use two
checks as the container health check:

```bash
docker compose exec engine sh -c 'curl -fsS http://localhost:8081/actuator/health/readiness && curl -fsS http://localhost:8081/actuator/health'
```

- **Readiness** is `UP` only when a valid permissions document is loaded. Without
  `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED`, this path answers 404.
- **`/actuator/health`** also checks the database. It answers 503 `DOWN` when the engine cannot open it, for
  example a read-only SQLite folder. Readiness alone stays `UP` in that case, so use both.

The management port (`8081`) is not published. These commands need `curl`, which the default images have. The
`-native` images are distroless (no `curl`, no shell), so probe port `8081` from outside the container for them.

## Which image

| Database | Image | Notes |
|---|---|---|
| MySQL | `excalibase/excalibase-graphql:<tag>-mysql` | Needs no `APP_DATABASE_TYPE` and no driver setting. |
| SQLite | `excalibase/excalibase-graphql:<tag>-sqlite` | Needs no `APP_DATABASE_TYPE` and no driver setting. |
| Any of them | `excalibase/excalibase-graphql:<tag>` | The all-in-one image. Set `APP_DATABASE_TYPE` and the driver. The examples use this one. |

`<tag>` is `main` or `main-<sha7>`. See [Which engine image?](engine-images.md) for the rest.
