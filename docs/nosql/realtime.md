# NoSQL — Realtime (WebSocket)

The unified realtime endpoint streams live insert/update/delete events to
connected clients. One endpoint serves **both** NoSQL collections and
relational (REST) tables — the client picks the source.

## Connect

```
GET /api/v1/realtime    (with Upgrade: websocket)
```

No subprotocol negotiation — send/receive plain JSON frames.

## Protocol

### Subscribe

```json
{
  "type": "subscribe",
  "id": "sub-1",
  "source": "nosql",
  "collection": "orders",
  "filter": { "status": "active" }
}
```

| Field        | Required | Notes |
|--------------|----------|-------|
| `type`       | yes | Must be `"subscribe"` |
| `id`         | yes | Client-chosen subscription id (uuid or any string) |
| `source`     | yes | `"nosql"` or `"rest"` |
| `collection` | yes | Collection or table name |
| `schema`     | no  | Only relevant for `source=rest`; defaults to `public` |
| `filter`     | no  | Object of field/value equality pairs; empty = subscribe to all |

### Event

For every matching insert/update/delete:

```json
{
  "type": "next",
  "id": "sub-1",
  "op": "insert",
  "doc": { "id": "...", "status": "active", ... }
}
```

`op` is one of `insert`, `update`, `delete`. DDL and heartbeat events are
filtered out server-side.

### Complete

```json
{ "type": "complete", "id": "sub-1" }
```

Releases the subscription on the server. Closing the socket also releases
every subscription for that session.

### Error

Server-side errors during subscribe:

```json
{ "type": "error", "id": "sub-1", "message": "source must be 'rest' or 'nosql'" }
```

## Filter matching

V1 supports equality on a single top-level field of the document. Complex
filters (`$gt`, `$in`, nested paths, etc.) are planned but not yet wired.

Multiple equality fields can be combined:

```json
{ "filter": { "status": "active", "region": "asia" } }
```

Events must match **all** fields to be delivered.

## Backing pipeline

```
Postgres WAL
      ↓
excalibase-watcher (logical replication)
      ↓
NATS JetStream
      ↓
NatsCDCService → SubscriptionService (Reactor sinks per schema_table key)
      ↓
RealtimeWebSocketHandler
      ↓
You
```

The watcher must be configured to replicate the schemas you care about
(`nosql` for NoSQL, `public` and friends for REST). If an event doesn't
reach your subscription, check watcher logs first.

## Testing

The handler takes events straight from `SubscriptionService.publish(…)` — any
integration test that can post a `CDCEvent` to the sink proves the full
WebSocket path without needing a real watcher running.
