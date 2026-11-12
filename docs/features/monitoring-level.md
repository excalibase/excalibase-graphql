# Monitoring level

`excalibase.observability.level` (env `EXCALIBASE_OBSERVABILITY_LEVEL`) sets how much per-request monitoring the engine does. The default is `full`, which is the behaviour before this setting existed.

| Level | What runs | Cost |
|---|---|---|
| `full` (default) | every observation, span and metric | highest CPU per request |
| `lite` | HTTP server and Spring Security observations off; `http.server.requests` is recorded by a plain timer with the same tags (method, status, uri pattern, outcome, error, exception, tenant, org and project) and the in-flight `http.server.requests.active` gauge | much lower CPU per request; no HTTP server spans or Spring Security spans |
| `off` | HTTP server and Spring Security observations off, with no timer filter | lowest; no `http.server.requests` metric and no HTTP server spans; observations the engine makes itself, such as `graphql.request`, still emit their spans and timers |

Tracing still follows the remaining observations and exports only when an OTLP endpoint is set (`OTEL_EXPORTER_OTLP_ENDPOINT`). An unknown level stops startup with an error naming the allowed values; it never falls back. An explicit `management.observations.enable.*` setting overrides the level; with `management.observations.enable.http=true` the timer filter stays out, so a request is never counted twice.
