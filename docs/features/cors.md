# CORS — per-project browser origins

The data plane is called straight from browsers (the SDK runs in browser JS),
so every project-scoped route answers CORS. It does so **per project**: each
project carries its own allowlist of origins, managed in provisioning and
enforced here on every surface — GraphQL, REST and the WebSocket upgrades.

> A project starts with **no origins**. Until its origins are added, a browser
> app calling `/{projectId}/graphql` is blocked (no `Access-Control-Allow-Origin`,
> preflight refused). Non-browser clients never send `Origin` and are unaffected.

## Where the allowlist lives

The list is a project setting in provisioning:

```bash
# Developer+ on the project's org
curl -sf -X PUT -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" \
  "https://<provisioning>/api/projects/<projectId>/cors" \
  -d '{"allowedOrigins": ["https://app.example.com", "http://localhost:5173"]}'
```

Entries must be absolute origins — `scheme://host[:port]`, no path, no
subdomain wildcard — exactly what the browser sends in `Origin`. The single
entry `"*"` allows every origin and has to be confirmed with
`"allowWildcard": true`. Provisioning exposes the list on
`GET /api/projects/{id}/info` as `corsAllowedOrigins`; that is what the
engine reads. See the provisioning docs (`docs/project-cors.md`) for the
full grammar.

## How a request's origin is decided

```
request  /{projectId}/graphql | /{projectId}/api/v1/... | WS upgrade on either
   │
   ├─ ProjectPath: project = leading path segment        (same rule as RLS)
   │      no project (health, actuator, legacy unscoped) ──► app.cors.allowed-origins
   │
   ├─ ProvisioningProjectCorsProvider.originsFor(project)
   │      cache hit (< app.cors.ttl-ms, default 30 s) ──► cached list
   │      cache miss ──► GET {app.cors.provisioning-url}/projects/{id}/info
   │            200 ──► corsAllowedOrigins, cached
   │            error, last-good cached ──► last-good list
   │            error, nothing cached  ──► DENY
   │
   └─ CorsConfiguration for that list (credentials off, GET/POST/PUT/PATCH/DELETE/OPTIONS)
          Origin on the list ──► Access-Control-Allow-Origin: <origin>
          "*"               ──► Access-Control-Allow-Origin: *
          not on the list / empty / DENY ──► 403 "Invalid CORS request", no CORS headers
```

The check runs on the Spring Security chain, ahead of authentication, so a
preflight never needs a token and the WebSocket handshake
(`/{projectId}/graphql`, `/{projectId}/api/v1/realtime`) is origin-checked
too — browsers do not apply CORS to WebSockets, so this server-side check is
what stops a foreign page from opening a subscription.

### Fail-closed rules

| Situation | Result |
|---|---|
| Project has origins, request `Origin` on the list | allowed, origin echoed |
| Project has origins, `Origin` not on the list | 403, no CORS headers |
| Project has no origins (new project, or list cleared) | 403 for any cross-origin browser request |
| Provisioning unreachable, list cached earlier | last-good list keeps being served |
| Provisioning unreachable or project unknown, nothing cached | denied — never the platform default, never `*` |
| No `Origin` header (curl, server SDKs, functions runtime) | not a CORS request; untouched |
| Route without a project (`/actuator/health`, legacy `/graphql`) | `app.cors.allowed-origins` (default `*`) |
| No `app.cors.provisioning-url` (standalone, no provisioning) | `app.cors.allowed-origins` everywhere, as before |

`Access-Control-Allow-Credentials` is never sent: auth is a Bearer token in
the `Authorization` header, not a cookie. That is also what makes honouring
`*` safe.

## Configuration

| Property | Env | Default | Meaning |
|---|---|---|---|
| `app.cors.allowed-origins` | `APP_CORS_ALLOWED_ORIGINS` | `*` | Platform default for routes without a project |
| `app.cors.provisioning-url` | `APP_CORS_PROVISIONING_URL` | `app.security.rls.policy-url` | Provisioning API base (`.../api`); empty = platform default everywhere |
| `app.cors.provisioning-pat` | `APP_CORS_PROVISIONING_PAT` | RLS policy PAT, then multi-tenant PAT | Service token used for `/info` |
| `app.cors.ttl-ms` | — | `30000` | Per-project cache TTL |

Changes made in provisioning reach the engine within one TTL; there is no
push. A browser app that used to rely on the old global `*` must have its
origin added to its project.

## SDK

The SDK cannot influence any of this: a browser sets `Origin` itself and the
SDK cannot override it. Add the origins your app is served from to the
project; localhost dev servers (`http://localhost:5173`) count as origins
too.
