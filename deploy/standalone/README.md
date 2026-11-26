# Running the engine on your own

The engine can run with only a Postgres database: no control plane, no Vault, no NATS. Permissions come
from a **metadata document** that you keep where you like. This folder holds a guide per way to keep it.
Today there are four: a file, an HTTP(S) URL, a database table, and an object in S3 or an S3-compatible store. More are added as they ship.

Step-by-step guides, with the admin console, the security checklist and the known limits, are in the docs:
[Docker and compose](../../docs/deploy/standalone-docker.md), [MySQL and SQLite](../../docs/deploy/standalone-mysql-sqlite.md) and [Kubernetes](../../docs/deploy/standalone-kubernetes.md).

## Which engine image?

The engine is published as one image per database next to the all-in-one image. Pick by the database you run; a
one-database image refuses to start on the other one and names the image to use.

| Image tag (`excalibase/excalibase-graphql:`) | Serves | Pick it when |
|---|---|---|
| `<tag>` | PostgreSQL, MySQL and SQLite | You run more than one, or the `db` source on a MySQL project. The default. |
| `<tag>-postgres` | PostgreSQL | You run PostgreSQL only (every example in this folder). |
| `<tag>-mysql` | MySQL | You run MySQL only. Not with `METADATA_SOURCE=db`, which keeps its document in PostgreSQL. |
| `<tag>-sqlite` | SQLite | You run a SQLite file only. Mount its folder at `/data`, writable by user id 1000. |
| `<tag>-native`, `<tag>-postgres-native`, `<tag>-mysql-native` | As above | The same images as a native binary: faster start, less memory. |

`<tag>` is `main` or `main-<sha7>` for a build of the main branch. More in the docs:
[Which engine image?](../../docs/deploy/engine-images.md)

## The file source

> The compose and Kubernetes examples need an engine image that includes EXC-733 phase 1 (the
> `metadata.source` setting). The `latest` tag is the last release and may not have it, so the examples use
> the `main` tag (set `ENGINE_IMAGE` in compose to pin another). Use the first release that includes it once
> there is one.

1. `export APP_AUTH_SECRET=$(openssl rand -base64 48) DB_PASSWORD=$(openssl rand -hex 16)`, then `docker compose -f docker-compose.file.yml up`. Compose refuses to start without them.
2. Open `http://localhost:10000/my-app/graphql` with a token whose `role` claim is `user`.
3. Edit `metadata/permissions.yaml`. The engine re-reads it every `METADATA_FILE_POLL_INTERVAL_MS`
   (default 5000) and swaps it in when the content changed.

### How it behaves

- **A bad edit changes nothing.** If the document is invalid or the file cannot be read, the engine keeps
  serving the last good version and logs `metadata_refused source=file ... error=...`.
- **No good document, no access.** If the file is missing or invalid at start, the engine stays up but
  refuses every request (503 `permissions_unavailable`), keeps polling, and starts serving once a valid file
  appears. It never falls back to "allow". It does not exit, so a fix needs no restart. While it has no good
  document its readiness probe reports not ready (`/actuator/health/readiness`, with
  `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true`), so Kubernetes does not route to it.
- **A rule for a table that no longer exists** is logged (`permission_invalid ... no such table`) and
  skipped. The rest of the document still applies.
- **A file caught half-written is not served.** A changed file is judged only when a following poll reads the
  same content, so a cut-off line cannot become a wider rule. A change takes two polls to apply.
- **Each replica reports the version it serves** at `/actuator/info` (`metadata.version`,
  `metadata.sourceVersion`, and `metadata.lastRefusal`, which is only a category such as `invalid_document`;
  the full reason is in the log) and as the metric `excalibase_metadata_version`.
- The document is limited to 1 MiB.

## The url source

The document is served by your own service (or a static host, a git raw URL, an object store's public or
presigned URL). The engine fetches it and polls. It is read-only: change the document where it is served.

1. Export `APP_AUTH_SECRET` and `DB_PASSWORD` as above, then `docker compose -f docker-compose.url.yml up` (it serves `metadata/permissions.yaml` from a small nginx).
2. Open `http://localhost:10000/my-app/graphql` with a token whose `role` claim is `user`.

- **Version.** The hash of the body. Every poll is a plain full GET (the 1 MiB cap keeps it cheap); `ETag`
  and `304` are not used, because an ETag can stay the same while the body changes and a `304` could settle a
  body the engine has not read twice.
- **Same loop as the file source.** A timeout, a 5xx, a redirect to another host, an oversized body or a bad
  document keeps the last good version and logs `metadata_refused source=url ...`. Until the first good load
  every request is refused and the readiness probe says not ready. A changed version is applied when a
  following poll reads the same body again (one more poll, about `METADATA_URL_POLL_INTERVAL_MS`), so a
  document replaced mid-flight is not believed on first sight.
- **Safety.** `https` is required, except for `localhost`, loopback addresses, `*.svc` and `*.cluster.local`.
  Anything else over plain http, including a compose service name such as `http://documents/...`, needs
  `METADATA_URL_ALLOW_INSECURE=true`.
  The body is cut off at 1 MiB while it streams. Connect and read timeouts apply. A redirect is followed only
  within the same scheme, host and port.
- **Auth.** An optional bearer token: `METADATA_URL_TOKEN`, or `METADATA_URL_TOKEN_FILE` (re-read, so a
  mounted Secret can rotate). It is sent only to the configured host and never logged.

| Setting | Meaning |
|---|---|
| `METADATA_SOURCE=url` | Use the url source. |
| `METADATA_URL_LOCATION` | The URL. `{projectId}` is replaced by `APP_PROJECT_ID`. |
| `METADATA_URL_TOKEN` / `METADATA_URL_TOKEN_FILE` | Optional bearer token. |
| `METADATA_URL_ALLOW_INSECURE` | Accept plain http to an outside host. Default false. |
| `METADATA_URL_POLL_INTERVAL_MS` | Default 5000. |
| `METADATA_URL_CONNECT_TIMEOUT_MS` / `METADATA_URL_READ_TIMEOUT_MS` | Defaults 3000 and 10000. |

## The db source

The document is a row in a table, `excalibase_meta.metadata_documents(version, document, created_at)`. The
engine reads the row with the highest `version` and polls it. It lives in the served database, or in another
one when `METADATA_DATABASE_URL` is set (then the engine uses only that connection for it, with its own
`METADATA_DATABASE_USERNAME` and `METADATA_DATABASE_PASSWORD`). Postgres only. The console saves by inserting the next version; by hand,
do the same with an `INSERT` of the next version.

```sql
CREATE SCHEMA IF NOT EXISTS excalibase_meta;
CREATE TABLE IF NOT EXISTS excalibase_meta.metadata_documents (
  version bigint PRIMARY KEY, document text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
INSERT INTO excalibase_meta.metadata_documents (version, document) VALUES (1, $doc$ ...the YAML... $doc$);
```

- **The table is yours unless you opt in.** The engine creates the schema and table only with
  `METADATA_DB_MIGRATE=true`; otherwise it expects them to exist, and says so in the log when they do not.
- **Same rules as the other sources.** An invalid new row keeps the last good version and logs
  `metadata_refused source=db ...`. A changed row is believed on the poll after the one that saw it. With no
  table, no row or no database at start, every request is answered 503 and readiness reports not ready until a
  good row is read.
- **Never reachable through the API.** `excalibase_meta` is never served over GraphQL or REST. That does not
  stop a user's own view or function from reading the table, because every request runs as the application's
  database role and, by default, that same role reads the document (and, with `METADATA_DB_MIGRATE=true`, owns
  the table). So the role's grant cannot be revoked in the default mode. To close this, set
  `METADATA_DATABASE_URL` to the same database (or another one) with a separate, read-only
  `METADATA_DATABASE_USERNAME`, and give the application role no grant on `excalibase_meta`. We recommend this
  for anything beyond a trial.
- **A stuck read is cancelled on the server** after 10 seconds, so a locked table cannot pile up backends.
- **Credentials stay out of logs.** A failure is logged as a fixed sentence and the SQLState only.

| Setting | Meaning |
|---|---|
| `METADATA_SOURCE=db` | Use the db source. |
| `METADATA_DATABASE_URL` | JDBC URL of a separate metadata database. Default: the served database. |
| `METADATA_DATABASE_USERNAME` / `METADATA_DATABASE_PASSWORD` | Its credentials. |
| `METADATA_DB_MIGRATE` | Let the engine create the schema and table. Default false. |
| `METADATA_DB_POLL_INTERVAL_MS` | Default 5000. |
| `METADATA_DB_CONNECT_TIMEOUT_MS` | Default 3000. |

## The s3 source

The document is one object in AWS S3 or an S3-compatible store (MinIO, Cloudflare R2, and other stores that speak
the S3 API). The engine reads it with a signed GET and polls. The console can save to it when the store enforces `If-Match` (the engine checks this at start with a scratch object, so the key also needs `s3:PutObject` and `s3:DeleteObject`). You can also change the object yourself, for
example `aws s3 cp permissions.yaml s3://my-bucket/permissions.yaml`, or `mc cp` for MinIO.

- **How it talks to S3.** One SigV4-signed `GET` of one object over the JDK's HTTP client. There is no AWS SDK, so
  the image does not grow and the native image is unaffected. The signer is checked against the example AWS
  publishes and against a real MinIO in the tests.
- **Version.** The hash of the body, as for the url source (an ETag is not trusted to change exactly when the bytes
  do). Every poll is a full GET, capped at 1 MiB, so it costs one request per poll (about $0.20 a month at the
  default 5 s on S3; inside R2's free tier).
- **Same loop as the other sources.** A missing bucket or object, a 403, a timeout, an oversized object or a bad
  document keeps the last good version and logs `metadata_refused source=s3 ...`. Until the first good load every
  request is refused and the readiness probe says not ready. A changed version is applied when a following poll
  reads the same body again.
- **Bounded.** Connect, headers and the whole body are under timeouts, and the body is cut off at 1 MiB.
  Redirects are not followed, so the signature goes only to the configured endpoint.
- **Credentials.** Static keys only: `METADATA_S3_ACCESS_KEY_ID` and `METADATA_S3_SECRET_ACCESS_KEY`, or the
  standard `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN`. Each also has a `..._FILE` form
  for a mounted secret, which is re-read on every poll so a rotation needs no restart. The whole set comes from one
  place: if any `METADATA_S3_*` key setting is present only those are used, otherwise only the `AWS_*` ones (so an
  ambient `AWS_SESSION_TOKEN` never rides on your static keys). They are never logged, and
  the store's error text is never read, because it can name the access key. Give the key read access to that one
  object (`s3:GetObject`) and nothing else.
- **Not supported yet:** web-identity (IRSA, EKS pod identity), instance-profile and `~/.aws` profile
  credentials. On AWS use an IAM user's key with a read-only policy, or put the object behind a presigned URL and
  use the url source.
- **Safety.** `https` is required for an endpoint outside this machine or cluster (loopback, `*.svc`,
  `*.cluster.local`); anything else over plain http needs `METADATA_S3_ALLOW_INSECURE=true`.

| Setting | Meaning |
|---|---|
| `METADATA_SOURCE=s3` | Use the s3 source. |
| `METADATA_S3_BUCKET`, `METADATA_S3_KEY` | The object. `{projectId}` in the key is replaced by `APP_PROJECT_ID`. |
| `METADATA_S3_ENDPOINT` | Scheme, host and optional port of an S3-compatible store. Blank means AWS. |
| `METADATA_S3_REGION` | Default `us-east-1`. R2 uses `auto`. |
| `METADATA_S3_PATH_STYLE` | `true` or `false`. Default: path style for a custom endpoint, virtual-hosted for AWS. |
| `METADATA_S3_ACCESS_KEY_ID`, `METADATA_S3_SECRET_ACCESS_KEY`, `METADATA_S3_SESSION_TOKEN` | Keys; the AWS names work too. |
| `METADATA_S3_ALLOW_INSECURE` | Accept plain http to an outside host. Default false. |
| `METADATA_S3_POLL_INTERVAL_MS` | Default 5000. |
| `METADATA_S3_CONNECT_TIMEOUT_MS` / `METADATA_S3_READ_TIMEOUT_MS` | Defaults 3000 and 10000. |

## Tokens from Auth0, Cognito, Keycloak or any OIDC issuer

**Preferred: set the engine's own token settings (`APP_AUTH_*`).** They are checked with the same rules as the
`jwt:` section below and cannot be changed by whoever can edit the permissions document.

| Variable | Meaning |
|---|---|
| `APP_AUTH_ISSUER`, `APP_AUTH_JWKS_URL` | issuer (alone: OIDC discovery) and key set URL |
| `APP_AUTH_SECRET` or `APP_AUTH_SECRET_FILE` | a shared secret (at least 32 bytes), as a value or a file; not both |
| `APP_AUTH_AUDIENCE`, `APP_AUTH_ALGORITHMS` | comma lists; audience is required |
| `APP_AUTH_CLOCK_SKEW_SECONDS` | 0 to 300 |
| `APP_AUTH_CLAIMS_ROLE`, `APP_AUTH_CLAIMS_DEFAULT_ROLE`, `APP_AUTH_CLAIMS_USER_ID` | claim paths |
| `APP_AUTH_CLAIMS_EXTRA` | `name=path,name=path` |

Any other `APP_AUTH_*` name stops start-up. They are refused in hosted mode, with `JWT_ENABLED=false`, and together
with `APP_SECURITY_AUTH_*`. A document `jwt:` beside them is refused unless it says exactly the same (every field and
the same key bytes): to move without a gap, set `APP_AUTH_*` to the section's values, restart every replica, check
the logs say `metadata_jwt_identical`, then remove `jwt:` from the document. Removing it before every replica
runs the new version leaves the old replicas with no verifier, so every token is rejected there. With no
verifier at all the engine starts, warns `no_token_verifier`, and rejects every token.

The `jwt:` section below still works when no engine settings are set, and is deprecated. The default
`metadata/permissions.yaml` no longer has one, and the compose and Kubernetes examples set `APP_AUTH_*`.

### Upgrading an older compose file or manifest

An older example set only `JWT_SECRET` and kept a `jwt:` section in `permissions.yaml` that read it
(`secret: env:JWT_SECRET`). Two cases:

- **You take the new default file (no `jwt:`) and keep the old compose file.** The engine **starts but rejects every
  token**: with no `APP_AUTH_*` and no `jwt:` there is no verifier, so it fails closed and logs `no_token_verifier`
  (`validate` and the console's save say the same when the document has rules for a role other than `anon`). Set
  `APP_AUTH_SECRET` (the value `JWT_SECRET` had, 32 bytes or more) and `APP_AUTH_AUDIENCE` (the audience your tokens
  carry, `my-app-api` in the old file).
- **You keep a document that still has `jwt:`.** Do not swap `JWT_SECRET` for `APP_AUTH_SECRET` in one step: the
  document's `env:JWT_SECRET` then cannot be read, the engine refuses the document, and it serves nothing. Go in this
  order, and keep `JWT_SECRET` until the last step:
  1. Add `APP_AUTH_*` with exactly the values of the `jwt:` section (every field: secret bytes, issuer, audience,
     algorithms, clock skew and the claim names; the Auth page's snippet writes them), and keep `JWT_SECRET`.
  2. Restart every replica and check the logs say `metadata_jwt_identical`.
  3. Remove `jwt:` from the document.
  4. Only then drop `JWT_SECRET`.

### The Auth page

With the console on, `CONSOLE_AUTH_PAGE_ENABLED=true` adds Settings, then Auth: a read-only view of how the engine
checks tokens (the source of the settings, algorithm, audience and the two claim names; never the secret), a "Test a
token" box that says why a token passes or fails, and a generator for the `APP_AUTH_*` lines and the Kubernetes `env:`
block. It does not change the engine's settings. The pasted token is sent to the engine over the admin port, checked
and dropped: not logged, stored or kept in the browser. The calls are `GET /_admin/v1/auth` and
`POST /_admin/v1/auth/check`; the second is limited per address, apart from the admin secret's own limit
(`EXCALIBASE_ADMIN_TOKEN_CHECK_LIMIT` per `EXCALIBASE_ADMIN_TOKEN_CHECK_WINDOW_SECONDS`, default 30 per 60). Off by
default; without the console the setting refuses start.

Put a `jwt:` section in the document. It applies only when `METADATA_SOURCE` is set; without it the engine
verifies tokens from its own settings (`APP_SECURITY_AUTH_*`), exactly as before. Do not set both: a document
with a `jwt:` section is refused (at start and on every reload) while `APP_SECURITY_AUTH_JWKS_URL` or
`..._HMAC_SECRET` is set. A
change to the section is applied together with the rules, after the keys were fetched; a failed fetch keeps
the last good version. An edit that leaves the section unchanged does not fetch again. A rotated secret file is re-read when the `jwt:` section changes.

```yaml
jwt:
  issuer: https://tenant.eu.auth0.com/        # checked against iss; alone, it finds the keys by OIDC discovery
  jwksUrl: https://tenant.eu.auth0.com/.well-known/jwks.json   # optional; needs issuer
  audience: https://api.my-app                # a string or a list; required
  algorithms: [RS256]                         # default RS256 and ES256; never none
  clockSkew: 30                               # seconds, 0 to 300, default 30
  claims:
    role: https://my-app/role                 # a dotted path; a string, or a list of roles
    defaultRole: user                         # the active role when role is a list
    userId: sub                               # default sub; fills X-Excalibase-User-Id
    extra:                                    # each becomes X-Excalibase-<name> in rules
      org-id: https://my-app/org_id
```

- **Auth0.** `issuer` is `https://<tenant>.<region>.auth0.com/` (keep the trailing slash), `audience` is the API
  identifier. Add the role to the token with an Action as a namespaced claim and point `claims.role` at it.
- **Cognito.** `issuer` is `https://cognito-idp.<region>.amazonaws.com/<userPoolId>`. An **ID token** carries
  `aud` (the app client id), so use it as `audience`; an access token has no `aud`, which the engine refuses.
  Groups arrive in `cognito:groups`: `claims.role: cognito:groups` with a `defaultRole`.
- **Keycloak.** `issuer` is `https://<host>/realms/<realm>`. Roles are in `realm_access.roles`:
  `claims.role: realm_access.roles` with a `defaultRole`. Entries that are not role names (lower-case letters,
  digits and `_`) are ignored.
- **A shared secret** (HS256) is only ever a reference: `secret: env:JWT_SECRET` or `secret: file:/run/secrets/jwt`
  (at least 32 bytes), with no `jwksUrl`. A literal secret in the document is refused.

How it behaves:

- **Algorithms** come from the document, never from the token. `none` is refused, and HMAC is refused with a
  key set, so a token signed with the public key as an HMAC secret cannot pass.
- **Keys** are fetched over `https` (plain `http` only to `localhost`, loopback, `*.svc` and `*.cluster.local`)
  with timeouts and a 64 KiB cap, never following a redirect, cached, and refetched when a token names an unknown `kid`, at most once every
  10 seconds. A failed refetch keeps the keys held. A token naming a `kid` the provider does not publish is
  refused. RSA keys under 2048 bits are ignored.
- **Roles.** When the role claim is a list, the caller may act as any role in it and `defaultRole` is the
  active one; `X-Excalibase-Role` may pick another role from the list and nothing outside it. The `service` role is
  never taken from an outside token.
- **Session variables.** Only the caller's id and the `extra` names are exposed to rules; other claims
  of the token are not, so a provider's claim cannot stand in for a variable nobody mapped.
- `excalibase validate` checks the section without contacting the provider. It does read a `secret: env:`/`file:`
  reference, so export that variable (or have the file) where you run it.

### Check a document before you deploy it

```
java -jar excalibase-graphql-api.jar validate metadata/permissions.yaml [--project-id my-app]
```

Exit code 0 means valid, 1 invalid (the message names the path of the first error), 2 bad usage or an
unreadable file. It runs the same parser the engine runs at load. Put it in CI. An editor can complete and
check the structure from `docs/schemas/metadata-v1.json`.

### Kubernetes

See `kubernetes/configmap-file-source.yaml`. Mount the folder, not a `subPath` (a `subPath` mount never
sees updates). The file is read-only there, so edit the ConfigMap and apply it.

### Settings

| Setting | Meaning |
|---|---|
| `METADATA_SOURCE=file` | Use the file source. Unset keeps the engine's previous behaviour. |
| `METADATA_FILE_PATH` | The file, or a folder holding `permissions.yaml`. |
| `METADATA_FILE_POLL_INTERVAL_MS` | How often the content hash is checked. Default 5000. |
| `APP_PROJECT_ID` | Required; the document's `projectId` must equal it. |

`METADATA_SOURCE` cannot be combined with `APP_SECURITY_RLS_POLICY_URL`.
