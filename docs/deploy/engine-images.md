# Which engine image?

The engine ships as one image per database next to the all-in-one image. A one-database image carries only that
database's plugin and driver, so it is smaller and has less code to patch. Pick by the database you run.

| Image tag | Serves | Pick it when |
|---|---|---|
| `excalibase/excalibase-graphql:<tag>` | PostgreSQL and MySQL | You run both, you switch databases often, or you use the `db` rule source on a MySQL project. The default. |
| `excalibase/excalibase-graphql:<tag>-postgres` | PostgreSQL | You run PostgreSQL only. |
| `excalibase/excalibase-graphql:<tag>-mysql` | MySQL | You run MySQL only. |
| `excalibase/excalibase-graphql:<tag>-native` | PostgreSQL and MySQL | The all-in-one image, as a native binary: faster start, less memory. |
| `excalibase/excalibase-graphql:<tag>-postgres-native` | PostgreSQL | The PostgreSQL image, as a native binary. |
| `excalibase/excalibase-graphql:<tag>-mysql-native` | MySQL | The MySQL image, as a native binary. |

`<tag>` is `main` or `main-<sha7>` for a build of the main branch. The all-in-one image keeps its existing tags.
The `-postgres` and `-mysql` images are published with every main build. The native one-database images are built
nightly, so a recent commit may not have its `-postgres-native` or `-mysql-native` tag yet.

## What a one-database image does

- It needs no `APP_DATABASE_TYPE` and no `SPRING_DATASOURCE_DRIVER_CLASS_NAME`; the image sets both for its database.
- Asked for a database it does not carry, it **refuses to start** and names the image to use:

  ```text
  Refusing to start with APP_DATABASE_TYPE=mysql. This engine build serves only postgres, not mysql.
  Use the mysql image (excalibase/excalibase-graphql:<tag>-mysql) or the all-in-one image excalibase/excalibase-graphql:<tag>.
  ```

  A `jdbc:postgresql:` or `jdbc:mysql:` URL of the other database is refused the same way.
- In multi-tenant mode there is no default database, so the engine starts. A project whose database is the
  other one is refused when it is first served: the client gets `503 project_unavailable` with no detail, no
  connection pool is built, the refusal is remembered for a minute, and the image to use is in the engine's log.
- `METADATA_SOURCE=db` keeps its document in PostgreSQL. A `-mysql` image has no PostgreSQL driver, so it
  refuses to start with that setting. Use the `file`, `url` or `s3` source with it, or the all-in-one image.

The images are otherwise identical: the same ports, the same settings, the same API.
