# excalibase-graphql

Automatic GraphQL API generation from PostgreSQL and MySQL databases.

## TL;DR

```bash
helm install my-release ./helm/excalibase-graphql \
  --set database.url=jdbc:postgresql://postgres:5432/mydb \
  --set database.username=app_user \
  --set database.password=secret
```

## Multi-Tenant Mode

For per-project database routing via JWT + vault credentials:

```bash
helm install my-release ./helm/excalibase-graphql \
  --set app.security.jwtEnabled=true \
  --set app.security.provisioningUrl=https://vault.internal/api \
  --set app.security.provisioningPat=my-vault-pat \
  --set app.allowedSchema=public
```

In multi-tenant mode, `database.url` is optional. If omitted, the app starts with no default database and routes all requests via JWT claims to tenant-specific databases.

## Parameters

### Image

| Parameter | Description | Default |
|-----------|-------------|---------|
| `image.repository` | Container image repository | `excalibase/excalibase-graphql` |
| `image.tag` | Container image tag | `latest` |
| `image.pullPolicy` | Image pull policy | `IfNotPresent` |
| `imagePullSecrets` | Image pull secrets | `[]` |

### Database

| Parameter | Description | Default |
|-----------|-------------|---------|
| `database.url` | JDBC URL (auto-detects postgres/mysql from prefix). Leave empty for multi-tenant only mode | `""` |
| `database.type` | Override auto-detection (`postgres` or `mysql`) | `""` |
| `database.username` | Database username (ignored if `existingSecret` set) | `""` |
| `database.password` | Database password (ignored if `existingSecret` set) | `""` |
| `database.existingSecret` | Name of existing Secret with DB credentials | `""` |
| `database.existingSecretUsernameKey` | Key in existing Secret for username | `username` |
| `database.existingSecretPasswordKey` | Key in existing Secret for password | `password` |

### Application

| Parameter | Description | Default |
|-----------|-------------|---------|
| `app.allowedSchema` | Comma-separated database schemas to expose via GraphQL | `public` |
| `app.port` | Server port (must match `service.port`) | `10000` |
| `app.cache.schemaTtlMinutes` | TTL for schema introspection cache and public key cache (minutes) | `30` |

### Security / JWT / Multi-Tenant

| Parameter | Description | Default |
|-----------|-------------|---------|
| `app.security.userContextEnabled` | Enable user context extraction for RLS | `true` |
| `app.security.userIdHeader` | HTTP header name for user ID (legacy, pre-JWT) | `X-User-Id` |
| `app.security.jwtEnabled` | Enable JWT verification + multi-tenant datasource routing | `false` |
| `app.security.provisioningUrl` | Vault/provisioning service base URL (e.g. `https://vault.internal/api`) | `""` |
| `app.security.provisioningPat` | PAT for vault API authentication (use `existingProvisioningSecret` in production) | `""` |
| `app.security.existingProvisioningSecret` | Name of existing Secret containing the provisioning PAT | `""` |
| `app.security.existingProvisioningPatKey` | Key in existing Secret for provisioning PAT | `provisioning-pat` |

### NATS CDC (Change Data Capture)

| Parameter | Description | Default |
|-----------|-------------|---------|
| `app.nats.enabled` | Enable NATS subscription for CDC events (from excalibase-watcher) | `false` |
| `app.nats.url` | NATS server URL | `nats://nats:4222` |
| `app.nats.streamName` | NATS JetStream stream name | `CDC` |
| `app.nats.subjectPrefix` | NATS subject prefix for CDC events | `cdc` |

### HikariCP Connection Pool

Pool size formula: `(target_db_CPU x 2 + 1) / replicas`. With multi-tenant routing, each tenant database gets its own pool.

| Parameter | Description | Default |
|-----------|-------------|---------|
| `hikari.maximumPoolSize` | Max connections per datasource | `10` |
| `hikari.minimumIdle` | Min idle connections per datasource | `3` |
| `hikari.connectionTimeout` | Connection timeout (ms) | `10000` |
| `hikari.idleTimeout` | Idle connection timeout (ms) | `300000` |
| `hikari.maxLifetime` | Max connection lifetime (ms) | `900000` |

### Tomcat

| Parameter | Description | Default |
|-----------|-------------|---------|
| `tomcat.threadsMax` | Max Tomcat threads (carrier threads with Virtual Threads) | `200` |
| `tomcat.threadsMinSpare` | Min spare threads | `10` |
| `tomcat.maxConnections` | Max connections | `8192` |
| `tomcat.acceptCount` | Accept queue size | `100` |
| `tomcat.connectionTimeout` | Connection timeout (ms) | `20000` |

### JVM

| Parameter | Description | Default |
|-----------|-------------|---------|
| `jvm.opts` | JVM options (Generational ZGC recommended for Java 25) | `-XX:+UseZGC -XX:+ZGenerational -XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0` |
| `virtualThreads.enabled` | Enable Java 25 Virtual Threads | `true` |

### Resources & Scaling

| Parameter | Description | Default |
|-----------|-------------|---------|
| `resources.limits.cpu` | CPU limit | `500m` |
| `resources.limits.memory` | Memory limit | `1Gi` |
| `resources.requests.cpu` | CPU request | `250m` |
| `resources.requests.memory` | Memory request | `512Mi` |
| `replicaCount` | Number of replicas | `1` |
| `autoscaling.enabled` | Enable HPA | `false` |
| `autoscaling.minReplicas` | Min replicas | `1` |
| `autoscaling.maxReplicas` | Max replicas | `5` |
| `autoscaling.targetCPUUtilizationPercentage` | Target CPU utilization | `70` |

### Networking

| Parameter | Description | Default |
|-----------|-------------|---------|
| `service.type` | Service type | `ClusterIP` |
| `service.port` | Service port | `10000` |
| `ingress.enabled` | Enable ingress | `false` |
| `ingress.className` | Ingress class name | `""` |
| `ingress.hosts` | Ingress hosts | `[{host: excalibase.local, paths: [{path: /, pathType: Prefix}]}]` |
| `ingress.tls` | Ingress TLS configuration | `[]` |

### Probes

| Parameter | Description | Default |
|-----------|-------------|---------|
| `livenessProbe.initialDelaySeconds` | Liveness probe initial delay | `30` |
| `livenessProbe.periodSeconds` | Liveness probe period | `15` |
| `readinessProbe.initialDelaySeconds` | Readiness probe initial delay | `20` |
| `readinessProbe.periodSeconds` | Readiness probe period | `10` |

### Extra Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `extraEnv` | Additional environment variables | `[]` |
| `extraVolumes` | Additional volumes | `[]` |
| `extraVolumeMounts` | Additional volume mounts | `[]` |
| `podAnnotations` | Pod annotations | `{}` |
| `podLabels` | Pod labels | `{}` |
| `nodeSelector` | Node selector | `{}` |
| `tolerations` | Tolerations | `[]` |
| `affinity` | Affinity rules | `{}` |

## Vault API Contract

When `jwtEnabled: true`, the app fetches credentials from:

```
GET {provisioningUrl}/vault/secrets/projects/{orgSlug}/{projectName}/credentials/excalibase_app
Authorization: Bearer {provisioningPat}
```

Expected response:
```json
{
  "host": "db-host.svc.cluster.local",
  "port": "5432",
  "database": "app",
  "username": "excalibase_app",
  "password": "..."
}
```

Public key for JWT verification is fetched from:
```
GET {provisioningUrl}/vault/pki/public-key
```
