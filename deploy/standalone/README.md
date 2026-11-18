# Running the engine on your own

The engine can run with only a Postgres database: no control plane, no Vault, no NATS. Permissions come
from a **metadata document** that you keep where you like. This folder holds a guide per way to keep it.
Today there are two: a file, and an HTTP(S) URL. More are added as they ship.

## The file source

> The compose and Kubernetes examples need an engine image that includes EXC-733 phase 1 (the
> `metadata.source` setting). The `latest` tag is the last release and may not have it, so the examples use
> the `main` tag (set `ENGINE_IMAGE` in compose to pin another). Use the first release that includes it once
> there is one.

1. `docker compose -f docker-compose.file.yml up`
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

1. `docker compose -f docker-compose.url.yml up` (it serves `metadata/permissions.yaml` from a small nginx).
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

## Tokens from Auth0, Cognito, Keycloak or any OIDC issuer

Put a `jwt:` section in the document. It applies only when `METADATA_SOURCE` is set; without it the engine
verifies tokens from its own settings (`APP_SECURITY_AUTH_*`), exactly as before. Do not set both: a document
with a `jwt:` section is refused (at start and on every reload) while `APP_SECURITY_AUTH_JWKS_URL` or
`..._HMAC_SECRET` is set. A
change to the section is applied together with the rules, after the keys were fetched; a failed fetch keeps
the last good version. An edit that leaves the section unchanged does not fetch again.

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
- `excalibase validate` checks the section without contacting the provider.

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
