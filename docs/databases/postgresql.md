# PostgreSQL

PostgreSQL runs every operator and feature the engine knows. Its schema is pinned by a snapshot test: building the
filter inputs from the dialect's declaration changes nothing a PostgreSQL client sees.

## Filter operators by column type

| Column | Operators |
|---|---|
| text | `eq` `neq` `like` `ilike` `contains` `startsWith` `endsWith` `regex` `iregex` `in` `notIn` `isNull` `isNotNull` |
| `tsvector` | `search` `webSearch` `phraseSearch` `rawSearch` |
| integer, float, date and time | `eq` `neq` `gt` `gte` `lt` `lte` `in` `notIn` `isNull` `isNotNull` |
| boolean | `eq` `neq` `isNull` `isNotNull` |
| `json`, `jsonb` | `eq` `neq` `contains` `containedBy` `hasKey` `hasKeys` `hasAnyKeys` `isNull` `isNotNull` |
| array | `eq` `neq` `contains` `containedBy` `overlaps` `hasAny` `hasAll` `isNull` `isNotNull` |
| enum | `eq` `neq` `in` `notIn` `isNull` `isNotNull` |

REST: `eq neq gt gte lt lte like ilike match imatch in notin is isdistinct isnotnull cs cd ov sl sr nxl nxr adj fts
plfts phfts wfts haskey jsoncontains contains jsoncontained containedin jsonpath jsonpathexists arraycontains
arrayhasany arrayhasall arraylength startswith endswith vector`.

## Features

All of: aggregates, upsert (`onConflict`), nested inserts, `distinctOn`, vector search, tracked functions,
subscriptions, arrays and composite types. See the [matrix](../database-compatibility.md#features).

Writes run as one statement with `RETURNING`.
