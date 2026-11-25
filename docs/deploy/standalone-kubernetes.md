# Kubernetes

Several replicas, the permissions in the database, the console reached only by port-forward.

## Which rule source fits

Use `db` or `s3`. Every replica polls the same source and reads the same document, and the console can save
with a version check that works across replicas. A ConfigMap with the `file` source also works for read-only
use (see `kubernetes/configmap-file-source.yaml`), but the console cannot save to a ConfigMap, and a change
takes a minute or more to reach the pods.

| | `db` | `s3` |
|---|---|---|
| Fits when | You already have Postgres and do not want another service. | You keep config in S3, MinIO or R2. |
| Needs | A table `excalibase_meta.metadata_documents`. | A bucket and one object. |
| Saving | Inserts a new row. History stays in the table. | A conditional `PUT`. Only if the store enforces `If-Match`. |
| Credentials | A database user. | Static access keys. No IRSA yet. |

This guide uses `db`. The manifest is
[`deploy/standalone/kubernetes/db-source.yaml`](https://github.com/excalibase/excalibase-graphql/blob/main/deploy/standalone/kubernetes/db-source.yaml).

## Prepare the database

The engine reads and saves the document in `excalibase_meta.metadata_documents`. Run the SQL below as a database
admin, not as the application user. This is the recommended setup, because the engine's own user then has no grant on
the schema it serves from.

```sql
CREATE SCHEMA IF NOT EXISTS excalibase_meta;
CREATE TABLE IF NOT EXISTS excalibase_meta.metadata_documents (
  version bigint PRIMARY KEY,
  document text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now());

CREATE ROLE excalibase_meta_rw LOGIN PASSWORD '...';
GRANT USAGE ON SCHEMA excalibase_meta TO excalibase_meta_rw;
GRANT SELECT, INSERT ON excalibase_meta.metadata_documents TO excalibase_meta_rw;

INSERT INTO excalibase_meta.metadata_documents (version, document) VALUES (1, $doc$
format: 1
projectId: my-app
jwt:
  secret: env:JWT_SECRET
  algorithms: [HS256]
  audience: my-app-api
  claims: {role: role, userId: sub}
tables:
  - table: public.todos
    role: user
    select:
      filter: {"owner_id": {"_eq": "X-Excalibase-User-Id"}}
      columns: "*"
$doc$);
```

`SELECT` and `INSERT` are enough, including for saves from the console. A save adds a row with the next
version; it never updates or deletes one.

The manifest already points the engine at this user through `METADATA_DATABASE_URL`, `METADATA_DATABASE_USERNAME`
and `METADATA_DATABASE_PASSWORD`, so the application user needs no grant on `excalibase_meta`.

If you would rather let the engine create the table, set `METADATA_DB_MIGRATE=true`. Then the engine's user
needs `CREATE` on the database, and it owns the table.

## Create the Secret

```bash
kubectl create secret generic excalibase-engine \
  --from-literal=database-url='jdbc:postgresql://postgres.example:5432/app' \
  --from-literal=database-username=app \
  --from-literal=database-password='...' \
  --from-literal=metadata-username=excalibase_meta_rw \
  --from-literal=metadata-password='...' \
  --from-literal=jwt-secret="$(openssl rand -hex 32)" \
  --from-literal=admin-secret="$(openssl rand -hex 32)"
```

Keep these values out of git. If you use a secrets operator, create a Secret with the same keys.

## Deploy

```bash
kubectl apply -f db-source.yaml
```

The manifest has a Deployment (2 replicas), a Service and a NetworkPolicy. The settings that matter:

| Setting | Value |
|---|---|
| `METADATA_SOURCE` | `db` |
| `METADATA_DB_POLL_INTERVAL_MS` | `5000` |
| `EXCALIBASE_CONSOLE_ENABLED` | `true` (set `false` when you are not editing) |
| `EXCALIBASE_ADMIN_PORT` | `10001` |
| `EXCALIBASE_ADMIN_SECRET_FILE` | A path in the mounted Secret. |
| `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED` | `true`, so the probes below exist. |

The Service exposes port 10000 only. There is no Service, Ingress or LoadBalancer for `10001` or `8081`.
Point your Ingress at the `excalibase-engine` Service for the public API, and nothing else.

The NetworkPolicy allows ingress to port 10000 only. It has no `from`, so any source can reach that port and
nothing needs allowing. It has an effect only when your CNI enforces NetworkPolicy.

## Tokens

The example document reads `JWT_SECRET` from the Secret. For Auth0, Cognito, Keycloak or OIDC, save a document
whose `jwt:` section has an `issuer` and remove `JWT_SECRET` from the Deployment:

```yaml
jwt:
  issuer: https://tenant.eu.auth0.com/     # keep the trailing slash for Auth0
  audience: https://api.my-app
  claims:
    role: https://my-app/role
```

Provider notes:

- **Cognito.** Use the ID token. The access token has no `aud`, which the engine refuses. Groups arrive in
  `cognito:groups`.
- **Keycloak.** Roles are in `realm_access.roles`. Set a `defaultRole`.
- Do not set `APP_SECURITY_AUTH_JWKS_URL` or `APP_SECURITY_AUTH_HMAC_SECRET` together with a `jwt:` section.

## Reach the console

```bash
kubectl port-forward deploy/excalibase-engine 10001:10001
```

Open `http://localhost:10001/console` and paste the admin secret:

```bash
kubectl get secret excalibase-engine -o jsonpath='{.data.admin-secret}' | base64 -d
```

`port-forward` picks one pod. That is fine for the `db` source, because the save goes to the database and
every replica reads it. A VPN into the cluster network also works, with a Service of type ClusterIP that you
create for the admin port and never expose outside the VPN. Do not put the admin port behind a public Ingress.
The secret and the per-address limit are a second line of defence, not the first.

When you are done, set `EXCALIBASE_CONSOLE_ENABLED` to `false` and roll the Deployment.

## Health checks

| Probe | Path | Port |
|---|---|---|
| Readiness | `/actuator/health/readiness` | `8081` |
| Liveness | `/actuator/health/liveness` | `8081` |

Readiness reports not ready while no valid document is loaded, so the pod gets no traffic and does not crash
loop. Each replica reports the version it serves at `/actuator/info`:

```bash
kubectl exec deploy/excalibase-engine -- curl -s http://localhost:8081/actuator/info
```

After a save, replicas can differ for about two poll intervals. Compare `metadata.version` across pods.

## Using s3 instead

Replace the `db` settings with:

```yaml
- {name: METADATA_SOURCE, value: s3}
- {name: METADATA_S3_BUCKET, value: my-bucket}
- {name: METADATA_S3_KEY, value: excalibase/my-app/permissions.yaml}
- {name: METADATA_S3_REGION, value: us-east-1}
# MinIO or R2 only:
# - {name: METADATA_S3_ENDPOINT, value: "https://<account>.r2.cloudflarestorage.com"}
- name: METADATA_S3_ACCESS_KEY_ID_FILE
  value: /run/secrets/excalibase/s3-access-key-id
- name: METADATA_S3_SECRET_ACCESS_KEY_FILE
  value: /run/secrets/excalibase/s3-secret-access-key
```

Mount the two keys from the Secret next to the admin secret. Static keys only: IRSA and EKS pod identity are
not supported yet. Scope the key to one bucket and one prefix. It needs `s3:GetObject` to read, and
`s3:PutObject` and `s3:DeleteObject` as well to save, because the start-up probe writes a scratch object next
to the document and deletes it. If the store does not enforce `If-Match`, the source stays read-only and the
console cannot save to it.

The full checklist and the known limits are on the [overview page](standalone.md).
