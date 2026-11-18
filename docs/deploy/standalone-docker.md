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
| `metadata/permissions.yaml` | The metadata document, with rules and a `jwt:` section. |
| `init.sql` | A sample `todos` table. |

## Run it

```bash
cd deploy/standalone
docker compose -f docker-compose.file.yml up -d
```

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
    SPRING_DATASOURCE_PASSWORD: change-me
    APP_DATABASE_TYPE: postgres
    APP_PROJECT_ID: my-app
    JWT_SECRET: change-me-to-a-long-random-secret-of-32-bytes-or-more
    METADATA_SOURCE: file
    METADATA_FILE_PATH: /etc/excalibase
    MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true"
  volumes:
    - ./metadata:/etc/excalibase:ro
```

Change the passwords and the secret before you use this for anything real. Mount the folder, not the single
file, so an editor that replaces the file is still seen.

## Rules the engine always applies

- `auth`, `excalibase` and `excalibase_meta` are never served. Add more names with `APP_RESERVED_SCHEMAS`.
- A table with no rule is not reachable, except by the `service` role.
- With a missing or invalid document the engine stays up, answers 503, and reports not ready.

## Tokens

The sample document reads a shared secret (HS256) from `JWT_SECRET`:

```yaml
jwt:
  secret: env:JWT_SECRET
  algorithms: [HS256]
  audience: my-app-api
  claims:
    role: role
    userId: sub
```

A token needs `aud` equal to `my-app-api`, a `role` claim and a `sub`. The rule for `todos` lets a `user` see
the rows whose `owner_id` equals `sub`.

For Auth0, Cognito, Keycloak or another OIDC issuer, replace the section with one that has an `issuer`. For
Keycloak:

```yaml
jwt:
  issuer: https://keycloak.example.com/realms/my-realm
  audience: my-app-api
  claims:
    role: realm_access.roles
    defaultRole: user
```

Then remove `JWT_SECRET`. Do not set `APP_SECURITY_AUTH_JWKS_URL` or `APP_SECURITY_AUTH_HMAC_SECRET` at the
same time: the document is refused while either is set. The other providers are in the
[folder README](https://github.com/excalibase/excalibase-graphql/blob/main/deploy/standalone/README.md).

Check the document before you start the engine:

```bash
java -jar excalibase-graphql-api.jar validate metadata/permissions.yaml --project-id my-app
```

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

Readiness reports `UP` only when a valid document is loaded. The compose file uses this as the container
healthcheck. `/actuator/info` shows the version of the document being served.

## Before you go live

- Keep the admin port private. Turn the console off when you are not using it.
- Use a random admin secret of at least 32 characters, from a file.
- Replace every `change-me` value.
- Put TLS in front of port `10000`. The engine does not terminate TLS.

The full checklist and the known limits are on the [overview page](standalone.md).
