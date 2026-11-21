# Run the engine on your own

The engine can run with only a Postgres database. There is no control plane, no Vault and no NATS. Your
permissions come from a **metadata document**: one YAML file that says who may read and write which table, and
how tokens are checked. You choose where that document lives.

Two guides cover the common setups:

- [Docker and compose](standalone-docker.md): one replica, the document in a file.
- [Kubernetes](standalone-kubernetes.md): several replicas, the document in the database (or in S3).

The sources, the settings and the token providers are listed in full in the
[deploy folder README](https://github.com/excalibase/excalibase-graphql/blob/main/deploy/standalone/README.md).
This page covers what is the same for every setup.

## Which engine image?

There is one image per database next to the all-in-one image: `-postgres`, `-mysql` and their `-native`
variants. See [Which engine image?](engine-images.md) to pick, and what a one-database image refuses.

## Which rule source to pick

| Source | Setting | Replicas | Saving from the console | Pick it when |
|---|---|---|---|---|
| `file` | `METADATA_SOURCE=file` | One | Yes, one replica only | One container, edited by hand or in git. |
| `url` | `METADATA_SOURCE=url` | Many | No, read-only | Your own service or a static host serves the document. |
| `db` | `METADATA_SOURCE=db` | Many | Yes | You already run Postgres and want one place for everything. |
| `s3` | `METADATA_SOURCE=s3` | Many | Yes, if the store checks `If-Match` | You keep config in S3, MinIO or R2. |

With several replicas use `db` or `s3`. Each replica polls the source on its own, so for one poll interval
they can serve different versions. `/actuator/info` on each replica shows the version it serves.

## What the engine never exposes

These rules need no setting and cannot be turned off.

- The schemas `auth`, `excalibase` and `excalibase_meta` are never served over GraphQL, REST, the OpenAPI
  document or subscriptions. This includes foreign-key links and functions that return rows of those tables.
  You can add more names to the list with `APP_RESERVED_SCHEMAS` (comma separated). You can never remove the
  built-in ones.
- A table with no rule in the document is not reachable by anyone except the `service` role.
- With no valid document the engine answers every request with 503, reports not ready, and keeps polling. It
  never falls back to "allow".
- `/_admin/**` is a 404 on every port except the admin port, and a 404 everywhere when the console is off.

One limit to know: a view or function that *you* create can still read `excalibase_meta`, because the engine
runs requests as its own database role. See "The db source" in the folder README for how to close that with a
separate read-only role.

## Schema changes

The engine notices DDL on its own. Every 5 seconds it runs one query over the database catalogs, and when a
table, column, key, view, type or function has changed it reflects the schema again. A table you create with
SQL shows up in GraphQL and REST a few seconds later, with no restart. Row writes, `ANALYZE` and vacuum change
nothing the check looks at.

| Setting | Meaning |
|---|---|
| `APP_SCHEMA_POLL_SECONDS` | Seconds between checks. Default `5`. `0` turns the check off, and then only a restart picks up DDL. |

The check runs only in this mode. With the platform's watcher and NATS, DDL events do the same job and nothing is polled.

## Tokens

Put a `jwt:` section in the metadata document to accept tokens from Auth0, Cognito, Keycloak or any OIDC issuer.
Without a `jwt:` section the engine uses `APP_SECURITY_AUTH_HMAC_SECRET` or `APP_SECURITY_AUTH_JWKS_URL`. Do not
set both: a document with a `jwt:` section is refused while either of those is set.

```yaml
jwt:
  issuer: https://tenant.eu.auth0.com/
  audience: https://api.my-app
  claims:
    role: https://my-app/role
```

Provider details (Auth0 trailing slash, Cognito ID tokens, Keycloak `realm_access.roles`) are in the folder
README.

## The admin console

The console is a page and a small API for editing the document. It is off by default.

| Setting | Meaning |
|---|---|
| `EXCALIBASE_CONSOLE_ENABLED` | `true` or `false`. Anything else refuses the start. Default `false`. |
| `EXCALIBASE_ADMIN_PORT` | The admin listener. Default `10001`. Must differ from the public and management ports. |
| `EXCALIBASE_ADMIN_SECRET` or `EXCALIBASE_ADMIN_SECRET_FILE` | Required when the console is on. At least 32 printable ASCII characters. Set one, not both. |
| `EXCALIBASE_ADMIN_MAX_FAILURES` | Wrong or missing secrets allowed per client address. Default `10`. |
| `EXCALIBASE_ADMIN_FAILURE_WINDOW_SECONDS` | How long an address stays blocked after that. Default `60`. |

- The console is at `/console` on the admin port only. The admin API is `/_admin/v1` on the same port, and
  needs the secret in the `X-Excalibase-Admin-Secret` header.
- The admin listener accepts at most 32 connections at once.
- Failed attempts are counted per TCP peer address. A blocked address gets `429` with `Retry-After`.
- Hosted mode (a policy URL or a provisioning URL is set) refuses to start with the console on.
- The engine refuses to start if the console is on and the secret is missing, too short, or the port clashes.

### Saving

`PUT /_admin/v1/metadata` needs an `If-Match` header with the version you started from. `GET /_admin/v1/metadata`
sends no `ETag` header. Use the `sourceVersion` field of its JSON body, in quotes. The `version` field is a
different number and gets a `409`.

```bash
SEC=$(cat secrets/admin-secret)
V=$(curl -s -H "X-Excalibase-Admin-Secret: $SEC" http://localhost:10001/_admin/v1/metadata \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["sourceVersion"])')
curl -X PUT -H "X-Excalibase-Admin-Secret: $SEC" -H "If-Match: \"$V\"" \
  --data-binary @permissions.yaml http://localhost:10001/_admin/v1/metadata
```

Without the header the answer is `428`. If someone else saved first, the answer is `409` with the
current version. A document that does not pass the engine's own checks is refused with `422` and nothing is
written. Each source writes in its own way:

| Source | How it saves |
|---|---|
| `file` | Writes a temporary file in the same folder, then renames it over `permissions.yaml`. The folder must be writable. Keeps a version history, see below. |
| `db` | Inserts a new row with version + 1. The primary key makes two racing saves conflict. Old rows stay as history. |
| `s3` | A conditional `PUT` with `If-Match`. Offered only after a start-up probe shows the store enforces it. |
| `url` | Not writable. Change the document where it is served. |

## Version history (file source)

Every save through the console or the admin API adds the text it replaces to `permissions.history`, next to
`permissions.yaml` (for another file name, next to it with `.history`). It is one file with one entry per line
(JSON Lines): the time of the save, the version hash and the whole previous text, comments included.

- **The last 20 versions are kept.** Older ones are dropped, and the file never grows past 20 times the 1 MiB
  document limit.
- **It holds old rules.** Old versions can show tables and roles you removed on purpose, and any literal secret
  you once put in the file. It gets the same permissions as `permissions.yaml`. Keep the folder private.
- **Hand edits are not recorded.** Only saves that go through the engine are. A file you edit yourself, or
  replace with a deploy, leaves no entry. Keep `permissions.yaml` in git; the history is a safety net for console
  saves, not a replacement for it.
- **To purge it, delete the file.** A literal secret you rotated out of `permissions.yaml` stays in the old
  versions until you delete `permissions.history` (and any `permissions.history.corrupt-*` beside it).
- **It never blocks a save.** If the history cannot be written, the save still goes ahead, the engine logs
  `metadata_history_not_written`, the answer carries `"historyRecorded": false` and the console says the version
  could not be added to History. A line in the file that is damaged is dropped on its own; a file that cannot be
  read is left untouched; a damaged one is kept as `permissions.history.corrupt-<time>` before a fresh one starts.
- **Do not name the rules file `*.history`.** It would share a file with its own history; the engine refuses to
  start.
- **Restore is a normal save.** The History tab shows each version against the current document and puts one
  back. The restored text is checked like any save (an old version that no longer passes is refused with the
  reason), it needs the version you started from, and the text it replaces goes into the history, so a restore can
  be undone. Over the API: `GET /_admin/v1/history`, `GET /_admin/v1/history/{id}` and
  `POST /_admin/v1/history/{id}/restore` with `If-Match`.
- **A read-only folder has no history.** A mounted ConfigMap cannot be saved to, so nothing is added.
- **`db`, `s3` and `url` keep none yet.** The console says history is available for the file source. The `db`
  source's old rows are the nearest thing today.

## Security checklist

- [ ] The admin port (`10001`) has no public route: no published port, no Service, no Ingress.
- [ ] The management port (`8081`) has no public route either.
- [ ] The admin secret is random, at least 32 characters, and comes from a file or a Secret object, not from a
      literal in a compose file or manifest.
- [ ] The console is off (`EXCALIBASE_CONSOLE_ENABLED=false`, or unset) whenever nobody is editing.
- [ ] The metadata database user has only what it needs. For reading and saving that is `SELECT` and `INSERT` on
      `excalibase_meta.metadata_documents`, plus `USAGE` on the schema. It needs no `UPDATE`, `DELETE` or
      `CREATE`, unless you set `METADATA_DB_MIGRATE=true`.
- [ ] S3 keys are scoped to one bucket and one key prefix. Reading needs `s3:GetObject`. Saving needs
      `s3:PutObject` and `s3:DeleteObject` as well, because the start-up probe writes and deletes a scratch object
      next to the document.
- [ ] The JWT secret or the issuer settings come from a Secret, not from the image.
- [ ] You ran `validate` on the document before deploying it.

## Known limits

- **File saves are not safe across replicas.** The check and the rename happen under a lock in one process.
  Two replicas sharing a writable file can overwrite each other. Use `db` or `s3` with more than one replica.
- **No IRSA or AWS role credentials yet.** The s3 source takes static keys only. On AWS, use an IAM user with a
  narrow policy, or serve the object through a presigned URL with the `url` source.
- **Saving from the grid or the sample-token form writes JSON and drops YAML comments and your own layout.** The
  console asks before such a save when the text it started from has comments or layout to lose: you confirm or
  cancel, and cancel writes nothing. The warning lists what the save changes. A save that gives a role an operation
  (red), rewrites a rule or changes the token settings (amber) always asks first, comments or not, and "Don't ask
  again" never hides that; a save that only removes access does not ask. Restore shows the same list. The text you replace stays in the history. To keep comments in the live file, edit it yourself
  and let the engine pick it up.
- **The explorer is not built yet.** The console has the rules grid, a sample token, the history of a file source
  and export.
- **A change takes time to apply.** The engine applies a change after the poll that saw it and one more poll that
  reads the same content, so about two poll intervals.
