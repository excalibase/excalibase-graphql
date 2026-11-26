# Docker and compose

One engine container, one Postgres, the permissions in a file. This is the simplest setup and the right one
for a single replica.

## Which rule source fits

Use the `file` source. The document is a plain file you can keep in git, edit by hand and check with
`validate`. The engine re-reads it every `METADATA_FILE_POLL_INTERVAL_MS` (default 5000).

Do not run two replicas against one writable file. Saves are checked inside one process only, so two replicas
can overwrite each other. For more than one replica use the [Kubernetes guide](standalone-kubernetes.md)
(`db` or `s3`).

## Files

Everything is in [`deploy/standalone`](https://github.com/excalibase/excalibase-graphql/tree/main/deploy/standalone):

| File | What it is |
|---|---|
| `docker-compose.file.yml` | Postgres and the engine with the `file` source. No console. |
| `docker-compose.console.yml` | An overlay that turns the console on. |
| `metadata/permissions.yaml` | The metadata document: the rules. Tokens are set in the compose file, not here. |
| `init.sql` | A sample `todos` table. |

## Run it

```bash
cd deploy/standalone
export APP_AUTH_SECRET=$(openssl rand -base64 48)
export DB_PASSWORD=$(openssl rand -hex 16)
docker compose -f docker-compose.file.yml up -d
```

The compose file refuses to start until `APP_AUTH_SECRET` (the token secret) and `DB_PASSWORD` are set, so no
example runs with a public secret. Keep the same `APP_AUTH_SECRET` in your shell or a `.env` file next to the
compose file: you need it to make tokens. The engine itself also refuses to start with a secret that contains
`change-me`.

The engine serves `http://localhost:10000/my-app/graphql`. The project id (`my-app`) is in the URL and must
equal `APP_PROJECT_ID` and the `projectId` in the document.

The compose file uses the `main` image tag. Set `ENGINE_IMAGE` to pin another tag.

### Minimal settings

```yaml
engine:
  environment:
    SERVER_PORT: 10000
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/app
    SPRING_DATASOURCE_USERNAME: app
    SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD:?set a database password}
    APP_DATABASE_TYPE: postgres
    APP_PROJECT_ID: my-app
    APP_AUTH_SECRET: ${APP_AUTH_SECRET:?set a random secret of 32 bytes or more, e.g. openssl rand -base64 48}
    APP_AUTH_AUDIENCE: my-app-api
    METADATA_SOURCE: file
    METADATA_FILE_PATH: /etc/excalibase
    MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true"
  volumes:
    - ./metadata:/etc/excalibase:ro
```

The passwords and the secret come from your environment, so none is public. Mount the folder, not the single
file, so an editor that replaces the file is still seen.

## Rules the engine always applies

- `auth`, `excalibase` and `excalibase_meta` are never served. Add more names with `APP_RESERVED_SCHEMAS`.
- A table with no rule is not reachable, except by the `service` role.
- With a missing or invalid document the engine stays up, answers 503, and reports not ready.

## Tokens

The engine checks tokens with its own settings (`APP_AUTH_*`), set in the compose file and not in the permissions
file. The example uses a shared secret (HS256):

```yaml
APP_AUTH_SECRET: ${APP_AUTH_SECRET:?set a random secret of 32 bytes or more, e.g. openssl rand -base64 48}
APP_AUTH_AUDIENCE: my-app-api
```

A token needs `aud` equal to `my-app-api`, a `role` claim and a `sub`. The rule for `todos` lets a `user` see
the rows whose `owner_id` equals `sub`. `APP_AUTH_CLAIMS_ROLE` and `APP_AUTH_CLAIMS_USER_ID` name other claims.

For Auth0, Cognito, Keycloak or another OIDC issuer, set an issuer instead of the secret. For Keycloak:

```yaml
APP_AUTH_ISSUER: https://keycloak.example.com/realms/my-realm
APP_AUTH_AUDIENCE: my-app-api
APP_AUTH_CLAIMS_ROLE: realm_access.roles
APP_AUTH_CLAIMS_DEFAULT_ROLE: user
```

Do not set `APP_SECURITY_AUTH_JWKS_URL` or `APP_SECURITY_AUTH_HMAC_SECRET` at the same time: the engine refuses to
start. The other providers and every `APP_AUTH_*` name are in the
[folder README](https://github.com/excalibase/excalibase-graphql/blob/main/deploy/standalone/README.md).

**Upgrading an older compose file.** Earlier examples set only `JWT_SECRET` and kept a `jwt:` section in
`permissions.yaml` (`secret: env:JWT_SECRET`). The new default file has no `jwt:` section. If you take the new file and
keep the old compose, the engine starts but nothing verifies a token: every token is rejected (it fails closed) and the
log says `no_token_verifier`. Set `APP_AUTH_SECRET` (the value `JWT_SECRET` had) and `APP_AUTH_AUDIENCE` (the audience
your tokens carry, `my-app-api` in the old file).

If you keep a document that still has a `jwt:` section, do not swap `JWT_SECRET` for `APP_AUTH_SECRET` in one step: the
engine then cannot read the document's secret, refuses the document and serves nothing. Go in this order and keep
`JWT_SECRET` until the end: (1) add `APP_AUTH_*` with the same values as the section, keeping `JWT_SECRET`; (2) restart
every replica and check for `metadata_jwt_identical` in the log; (3) remove `jwt:` from the document; (4) drop
`JWT_SECRET`. The Auth page below writes the lines for step 1.

Check the document before you start the engine. `validate` reads `APP_AUTH_*` from its environment the way the engine
does, so export them first to also see whether the engine has a token verifier:

```bash
java -jar excalibase-graphql-api.jar validate metadata/permissions.yaml --project-id my-app
```

## Try it: a token and your first queries

The engine checks every token, so you need one to try a query. In the sample setup a token is a JWT signed
(HS256) with `APP_AUTH_SECRET`, with the claims `aud` (`my-app-api`), `role` and `sub`. You can make one in the
console (the sample token form), or with this script. It needs only `bash` and `openssl`. It reads the secret
from the `APP_AUTH_SECRET` environment variable, so the secret never shows up in the process list or your shell history.
Use the same value you exported before `docker compose up` (`export APP_AUTH_SECRET=$(openssl rand -base64 48)`):

```bash
#!/usr/bin/env bash
# usage: APP_AUTH_SECRET=... ./mktoken.sh <audience> <user id> <role>
b64() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64)
payload=$(printf '{"sub":"%s","role":"%s","aud":"%s","exp":%s}' "$2" "$3" "$1" "$(( $(date +%s) + 3600 ))" | b64)
sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "${APP_AUTH_SECRET:?set APP_AUTH_SECRET}" -binary | b64)
printf '%s.%s.%s\n' "$header" "$payload" "$sig"
```

```bash
# APP_AUTH_SECRET is still exported from the step above
TOKEN=$(./mktoken.sh my-app-api alice user)
```

The token lasts one hour. The `sub` becomes the user id in the rules: the sample rule shows `alice` only the
rows whose `owner_id` is `alice`.

### Names carry the schema

A GraphQL name starts with the schema, then the table. The table `todos` in the Postgres schema `public` is
`publicTodos`. In MySQL the schema is the database name (`app`, so `appTodos`). In SQLite it is always `main`.

| What | Name for `public.todos` |
|---|---|
| Read rows | `publicTodos` |
| Read a page with a cursor | `publicTodosConnection` |
| Insert | `createPublicTodos` |
| Update | `updatePublicTodos` |
| Delete | `deletePublicTodos` |

The columns keep their own names, such as `owner_id`. Ask the engine for the list: send
`{ __schema { queryType { fields { name } } } }`.

```bash
q() { curl -s -X POST http://localhost:10000/my-app/graphql \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1"; echo; }

q '{"query":"{ publicTodos { id title done } }"}'
q '{"query":"{ publicTodos(where: {done: {eq: false}}, orderBy: {id: DESC}, limit: 5) { id title } }"}'
q '{"query":"{ publicTodosConnection(first: 2) { edges { node { id title } } pageInfo { hasNextPage } } }"}'
q '{"query":"mutation { createPublicTodos(input: {title: \"buy milk\"}) { id title done } }"}'
q '{"query":"mutation { updatePublicTodos(where: {id: {eq: 3}}, input: {done: true}) { id done } }"}'
q '{"query":"mutation { deletePublicTodos(where: {id: {eq: 3}}) { id } }"}'
```

The insert does not send `owner_id`. The rule sets it to the caller. The REST API reaches the same table,
without the schema in the name: `GET /my-app/api/v1/todos`.

### Reading an error

A bad token and an unknown project are refused before the query runs, with an HTTP error status. GraphQL errors
that come after that come back with HTTP status 200 and an `errors` list. Look at `message` and `extensions.code`.

| You see | It means |
|---|---|
| HTTP 401, `Invalid or expired token`, code `invalid_token` | The signature is wrong (another secret), or the token has expired. |
| HTTP 401, `Invalid or expired token`, code `aud_mismatch` | `aud` is not the `audience` in the document. |
| code `unknown_field`, `Unknown field(s): publicTodos. The request runs as role 'user', which has no permission rule for publicTodos…` | The role in the token has no rule for the table, or the table is not there. A request with no token runs as `anon`, so it gets this too; the message names the role. Check the `role` claim and the document. |
| HTTP 404, `Project not found`, code `project_not_found` | The project id in the URL is not `APP_PROJECT_ID`. |
| An empty list, no error | The rule's row filter hides every row from this user. |

## The console

The console is off in the base file. To turn it on:

```bash
mkdir -p secrets && openssl rand -hex 32 > secrets/admin-secret
docker compose -f docker-compose.file.yml -f docker-compose.console.yml up -d
```

Then open `http://localhost:10001/console` and paste the secret. The overlay:

- sets `EXCALIBASE_CONSOLE_ENABLED=true` and `EXCALIBASE_ADMIN_PORT=10001`;
- reads the secret from a file with `EXCALIBASE_ADMIN_SECRET_FILE`, through a compose secret;
- publishes the admin port as `127.0.0.1:10001`, so only this machine can reach it;
- mounts `./metadata` writable, because a save writes a new file next to `permissions.yaml`.

### The Auth page (optional)

Settings, then Auth, shows how the engine checks tokens: where the settings come from, the algorithm, audience and
claim names, and never the secret. You can paste a token to see why it passes or fails, and build the `APP_AUTH_*`
lines for a setup you read from a sample token. It is read-only: it does not change the engine's settings. Turn it on
with `CONSOLE_AUTH_PAGE_ENABLED=true` (it needs the console and is off by default). The token you paste is sent to the
engine on the admin port, checked, and dropped: it is not logged, stored or kept in the browser. "Test a token" is
limited per address (`EXCALIBASE_ADMIN_TOKEN_CHECK_LIMIT` per `EXCALIBASE_ADMIN_TOKEN_CHECK_WINDOW_SECONDS`, 30 per 60
seconds), on top of the admin secret.

The engine runs as uid 1000 in the container. The metadata folder must be writable by that user for saves to
work, and the secret file must be readable by it.

On a remote host, keep the loopback binding and reach the console through an SSH tunnel:

```bash
ssh -L 10001:127.0.0.1:10001 you@host
```

Never publish the admin port as `0.0.0.0` or put it behind a public reverse proxy. Stop the console when you
are done: run the base file again without the overlay.

## Health checks

The management port (`8081`) is inside the container network and is not published.

```bash
docker compose exec engine curl -fsS http://localhost:8081/actuator/health/readiness
```

The `curl` command works in the default images, which carry `curl`. The `-native` images are distroless: they have
no `curl` and no shell, so a command check inside the container cannot run. For them, probe port `8081` from
outside the container (your orchestrator's HTTP probe, a sidecar or the host), not with `docker compose exec`.

Readiness reports `UP` only when a valid document is loaded. The compose file uses this as the container
healthcheck. `/actuator/info` shows the version of the document being served.

## Before you go live

- Keep the admin port private. Turn the console off when you are not using it.
- Use a random admin secret of at least 32 characters, from a file.
- Keep the token secret and the database password out of git: export them or keep them in a `.env` file.
- Put TLS in front of port `10000`. The engine does not terminate TLS.

MySQL or a SQLite file instead of Postgres? See [MySQL and SQLite](standalone-mysql-sqlite.md).

The full checklist and the known limits are on the [overview page](standalone.md).
