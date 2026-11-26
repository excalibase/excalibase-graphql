# MongoDB and DocumentDB

The engine serves a MongoDB or DocumentDB database through the same GraphQL endpoint and the same permission file as the
SQL databases. Set `APP_DATABASE_TYPE=mongodb` (or `documentdb`). The database type is the opt-in: nothing changes for
a deployment that does not set it.

This is the first wired slice. It is read only.

| | Served now | Not yet (later phases) |
|---|---|---|
| GraphQL | list, get by id, filters, ordering, paging, introspection | writes, relationships, aggregates, subscriptions |
| Permissions | row filters and column lists, per role, from `permissions.yaml` | `_not`, nested groups, relationships, `_exists`, per-role row limits |
| REST | refused | REST for documents |
| Console | not served | collection browser |
| Roles | every role the document names; `anon` and unnamed roles read nothing | the `service` role (refused, not bypassed) |

## Start

```bash
APP_DATABASE_TYPE=mongodb
APP_PROJECT_ID=shop
APP_MONGODB_URI=mongodb://user:pass@mongo:27017/?authSource=admin
APP_MONGODB_DATABASE=shop
APP_MONGODB_COLLECTIONS_FILE=/etc/excalibase/collections.yaml
APP_SECURITY_JWT_ENABLED=true
METADATA_SOURCE=file
METADATA_FILE_PATH=/etc/excalibase/permissions.yaml
```

There is no open mode: without a permission document the engine refuses to start. A document that holds a rule the engine
cannot enforce exactly also refuses to start (and, on a reload, answers `503 permissions_unavailable` until it is fixed).

## Collections are declared

Documents have no schema, and sampling them would change the API whenever the data changed. Declare each collection:

```yaml
collections:
  - name: orders
    id: objectId          # objectId (default), string, int or long
    fields:
      owner: string
      status: string
      total: int           # string, objectId, int, long, double, boolean, datetime
      paid: boolean
      placedAt: datetime
```

The GraphQL field `id` is the document's `_id`. A stored value that is not the declared type is hidden, never guessed.

## Permissions

The same file as the SQL engines. A table is named `<database>.<collection>`, with the database set in
`APP_MONGODB_DATABASE`:

```yaml
format: 1
projectId: shop
tables:
  - table: shop.orders
    role: user
    select:
      filter: {"owner": {"_eq": "X-Excalibase-User-Id"}}
      columns: ["id", "owner", "status", "total"]
```

What is accepted: a comparison, or one flat `_and` or `_or` of comparisons, on a declared field. Operators `_eq _neq
_gt _gte _lt _lte _in _nin _like _nlike _is_null`. Values are literals of the field's declared type, or a session
variable (`X-Excalibase-*`) for every operator except `_in` and `_nin`. A column list must include `id`; fields left out
are removed from that role's schema. A collection no role may select reads as empty for everyone.

Refused when the document loads, never ignored: `_not`, a nested `_and`/`_or`, relationship filters, `_exists`,
session-variable checks, a per-role row `limit`, a literal of the wrong type, a literal written as a claim (`{{...}}`),
an `_in` literal holding a comma, and a table or column that is not declared. Insert, update, delete and function
permissions grant nothing yet, because there is no write surface; the engine logs how many it saw.

### How a rule becomes a filter

The engine never builds a MongoDB filter from request or document text. A rule becomes a typed predicate, the
permission filter is the first stage of every aggregation pipeline, and the request's own `where` is a later stage, so
it can narrow but not widen. Operators come from a closed list; `$where`, `$function` and `$expr` are never emitted, and
a value such as `"$where"` is only a value. SQL semantics are kept: a comparison against a missing or null field does not
match, and `_neq` does not return documents without the field.

A request without the session variable a filter needs is refused, and returns no rows.

## Known limits

- One project per engine. A MongoDB or DocumentDB engine refuses to start in multi-tenant mode (a provisioning URL), and
  answers `404 project_not_found` for any project but `APP_PROJECT_ID`. Per-project document databases are phase 4.
- `service` tokens are refused on a MongoDB project (`403 service_role_unsupported`); there is no bypass.
- Introspection shows every declared collection and its field names to every role. A column a role may not read is removed
  from that role's schema, but a collection a role has no permission on is still listed (with all its fields) and reads
  as empty. Hiding such collections per role is phase 2.
- Realtime and `graphql-ws` subscriptions answer `surface_not_served`.
- `/health` pings MongoDB: a wrong or unreachable URI reports the engine down. The start logs a warning instead of
  refusing, so an engine can start before its database does.
- The native-image build has not been tried with the MongoDB driver; use the JVM image.
- Reloading the permission document replaces the engine; a request in flight at that moment can fail once.
