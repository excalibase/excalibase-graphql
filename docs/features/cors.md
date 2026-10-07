# CORS — per-project browser origins

The data plane is called straight from browsers (the SDK runs in browser JS),
so every project-scoped route answers CORS. It does so **per project**: each
project carries its own allowlist of origins, managed in provisioning and
enforced here on every surface — GraphQL, REST and the WebSocket upgrades.

> A project starts with **no origins**. Until its origins are added, a browser
> app calling `/{projectId}/graphql` is blocked (no `Access-Control-Allow-Origin`,
> preflight refused). Non-browser clients never send `Origin` and are unaffected.

## The rule (same on GraphQL, REST, auth and functions)

| Request | Origin listed (or `*`) | Origin not listed |
|---|---|---|
| Preflight (`OPTIONS` + `Access-Control-Request-Method`) | 2xx, grant headers | **403, no CORS headers** — the browser blocks the real call |
| Actual request (`GET`/`POST`/...) | served, `Access-Control-Allow-Origin` echoed | **served**, no `Access-Control-Allow-Origin` — a browser page cannot read it |
| WebSocket upgrade, `http`/`https` origin | 101 | **403** (browsers skip CORS on WebSockets, so the server checks) |
| WebSocket upgrade, no `Origin` or a native scheme (`capacitor://`, `ionic://`, `tauri://`, `app://`, `file://`, `null`) | 101 | 101 |

Every response to a request with `Origin` carries `Vary: Origin`.

### Why the server does not refuse a request for its Origin

These APIs authenticate with a bearer token in `Authorization`, never a
cookie. A foreign web page therefore has no ambient credential to ride: it
can only send what it already holds. CORS is the browser's protection — it
stops a page on an unlisted origin from **reading** the response (and, for
anything beyond a simple request, from sending it at all, because the
preflight is refused). Refusing the actual request on the server as well adds
no protection — any non-browser client can omit or forge `Origin` — but it
does break callers that are not web pages:

* native apps — Capacitor (`capacitor://localhost`, `ionic://localhost`),
  Tauri (`tauri://localhost`), Electron pages loaded from disk (`Origin: null`);
* server-side proxies and SSR backends that forward the browser's `Origin`.

The WebSocket upgrade is the exception: browsers apply no CORS to it, so the
handshake checks `Origin` itself. Only web origins (`http`/`https`) are held
to the allowlist; a native-app scheme or `null` is not a page an attacker can
send a visitor to, and the session still has to authenticate with its token.

If a route ever authenticates with a cookie, this reasoning no longer holds:
such a route must refuse unlisted origins on the server (CSRF), as
provisioning does for the Studio session cookie.

## Native apps (Capacitor, Ionic, Tauri, Electron)

A native app's WebView is still a browser: `fetch` with an `Authorization`
header is preflighted, so the app's origin must be on the project's list.

| Runtime | Origin the WebView sends | Add to the allowlist |
|---|---|---|
| Capacitor, iOS | `capacitor://localhost` | `capacitor://localhost` |
| Capacitor, Android | `https://localhost` (or `http://localhost`) | that exact origin |
| Ionic (legacy WebView) | `ionic://localhost` | `ionic://localhost` |
| Tauri v2, macOS/Linux | `tauri://localhost` | `tauri://localhost` |
| Tauri v2, Windows | `http://tauri.localhost` (`https://` if `useHttpsScheme`) | that exact origin |
| Electron with a custom protocol | `app://<host>` (whatever you register) | that origin |
| Electron `loadFile` / any `file://` page | `null` | cannot be listed — register a custom protocol, or use `*` |

`null` cannot be listed because every sandboxed iframe and `data:` page on
the web also sends `null`; list a real scheme instead. Add origins with
`add_cors_origin` over MCP, or `PUT /api/projects/{id}/cors` (below).

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
          not on the list / empty / DENY ──► preflight: 403, no CORS headers
                                             actual request: served, no CORS headers
```

The check runs on the Spring Security chain, ahead of authentication, so a
preflight never needs a token (`BrowserEnforcedCorsProcessor`). The WebSocket
handshake (`/{projectId}/graphql`, `/{projectId}/api/v1/realtime`) is
origin-checked by `WebSocketOriginInterceptor` against the same per-project
list — browsers do not apply CORS to WebSockets, so this server-side check is
what stops a foreign web page from opening a subscription.

### Fail-closed rules

| Situation | Result |
|---|---|
| Project has origins, request `Origin` on the list | allowed, origin echoed |
| Project has origins, `Origin` not on the list | preflight 403 and WS upgrade 403; actual request served without CORS headers |
| Project has no origins (new project, or list cleared) | same as "not on the list" for every origin |
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
