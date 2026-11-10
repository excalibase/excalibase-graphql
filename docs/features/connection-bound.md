# Connection bound

The engine limits open connections to what the CPUs it has can serve. Past the limit, clients wait in the kernel accept queue instead of being parsed and parked, so the admitted ones keep their speed and memory stays flat.

| Setting (env) | Default | Meaning |
|---|---|---|
| `app.overload.enabled` (`APP_OVERLOAD_ENABLED`) | `true` | `false` keeps Tomcat's own defaults |
| `app.overload.max-connections-per-cpu` (`APP_OVERLOAD_MAX_CONNECTIONS_PER_CPU`) | `256` | open connections per CPU, websockets included |
| `app.overload.accept-count-per-cpu` (`APP_OVERLOAD_ACCEPT_COUNT_PER_CPU`) | `256` | accept queue per CPU |
| `app.overload.max-keep-alive-requests` (`APP_OVERLOAD_MAX_KEEP_ALIVE_REQUESTS`) | `20` | requests before the server closes a keep-alive connection |
| `app.overload.keep-alive-timeout-ms` (`APP_OVERLOAD_KEEP_ALIVE_TIMEOUT_MS`) | `5000` | idle time before a connection gives its slot back |

The effective values and the CPU count are logged once at startup. `server.tomcat.max-connections` and `server.tomcat.accept-count` no longer have an effect while the bound is enabled; use the per-CPU settings.

## Health and metrics port

Actuator (`/actuator/health`, `/actuator/metrics`) is served on its own port, `management.server.port` (`MANAGEMENT_SERVER_PORT`, default `8081`), which the bound does not apply to. Probes therefore keep answering when the main port is full. This port has no authentication: publish it to the kubelet or loopback only, never through an ingress or a public port mapping.

The bound is visible as `tomcat.connections.config.max` and `tomcat.connections.current` on that port.
