# Monitoring level

`excalibase.observability.level` (env `EXCALIBASE_OBSERVABILITY_LEVEL`) sets how much per-request monitoring the engine does. The default is `full`, which is the behaviour before this setting existed.

| Level | What runs | Cost |
|---|---|---|
| `full` (default) | every observation, span and metric | highest CPU per request |
| `lite` | HTTP server and Spring Security observations off; `http.server.requests` is recorded by a plain timer with the same tags (method, status, uri pattern, outcome, error, exception, tenant, org and project) | much lower CPU per request; no HTTP server spans or Spring Security spans |
| `off` | no request observations: HTTP, Spring Security, JDBC and Tomcat | lowest; no `http.server.requests` metric and no request spans |

Tracing still follows the remaining observations and exports only when an OTLP endpoint is set (`OTEL_EXPORTER_OTLP_ENDPOINT`). An unknown level stops startup with an error naming the allowed values; it never falls back. An explicit `management.observations.enable.*` setting overrides the level.
