# NoSQL — JSON Schema validation

Each collection can declare a JSON Schema (Draft 2020-12). Every `POST`
insert — single or bulk — is validated against the schema; failing writes
return HTTP 400 with structured issues and **no rows are inserted**.

## Declaring a schema

Inside the collection definition:

```json
POST /api/v1/nosql
{
  "collections": {
    "users": {
      "indexes": [ { "fields": ["email"], "unique": true } ],
      "schema": {
        "type": "object",
        "required": ["email"],
        "properties": {
          "email": { "type": "string", "format": "email" },
          "age":   { "type": "integer", "minimum": 0 }
        },
        "additionalProperties": false
      }
    }
  }
}
```

Any valid Draft 2020-12 schema works: `required`, `properties`, `pattern`,
`enum`, nested objects, `oneOf`, `anyOf`, `$ref` (internal only), etc.

## Error response

A violating insert:

```json
POST /api/v1/nosql/users
{ "doc": { "age": "not-a-number" } }
```

Returns `400 Bad Request`:

```json
{
  "error": "validation",
  "issues": [
    { "path": "/age",   "message": "...: string found, integer expected" },
    { "path": "",       "message": "required property 'email' not found" }
  ]
}
```

## Scope

- **Enforced on insert** (`POST /{coll}` — both `{doc:...}` and `{docs:[...]}`)
- **Not enforced on PATCH updates** — a `$set` patch doesn't carry the full
  document, so the schema can't be evaluated without reading the existing
  row. If you need that guarantee, do read-modify-write in the client and
  re-insert through a new `POST`.
- **Not enforced at the database level** — no `pg_jsonschema` extension. All
  writes must go through the API to be validated.

## Unregistering

Pass the collection again without a `schema` field (or with an empty object)
to remove the constraint:

```json
{
  "collections": {
    "users": { "indexes": [...] }
  }
}
```
