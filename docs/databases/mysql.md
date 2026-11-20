# MySQL

MySQL 8.0 and 8.4 are tested on every change. See [MySQL Support](../features/mysql.md) for setup, column types
and permissions; this page lists what the filters and writes do.

## Filter operators by column type

| Column | Operators |
|---|---|
| text | `eq` `neq` `like` `ilike` `contains` `startsWith` `endsWith` `regex` `iregex` `in` `notIn` `isNull` `isNotNull` |
| text with its own `FULLTEXT` index | the above, plus `search` `phraseSearch` `rawSearch` |
| integer, float, date and time | `eq` `neq` `gt` `gte` `lt` `lte` `in` `notIn` `isNull` `isNotNull` |
| boolean | `eq` `neq` `isNull` `isNotNull` |
| `json` | `contains` `containedBy` `hasKey` `hasKeys` `hasAnyKeys` `isNull` `isNotNull` |
| `enum` | `eq` `neq` `in` `notIn` `isNull` `isNotNull` |

REST: `eq neq gt gte lt lte like ilike match imatch in notin is isdistinct isnotnull startswith endswith fts plfts
phfts haskey jsoncontains contains cs jsoncontained containedin cd`.

Not available, so absent from the schema and refused when reached: `webSearch` (no MySQL equivalent), JSON `eq` and
`neq`, JSON `path`, arrays, vector search, network types, `distinctOn`, nested inserts, tracked functions.

### Regex

`regex` and `iregex` compile to `REGEXP_LIKE(col, :pattern, 'c')` and `'i'`: the match type is explicit, so case
follows the operator, not the column's collation.

### Full-text search

```graphql
{ article(where: { body: { search: "quick fox" } }) { id } }
```

`search` requires every word (as PostgreSQL's `plainto_tsquery` does) and leaves out the words InnoDB never indexes
(its default stop words and anything shorter than three characters). `phraseSearch` keeps the words in order.
`rawSearch` takes MySQL boolean-mode syntax (`+fox -dog "brown fox" fo*`).

The column needs a `FULLTEXT` index on **that column alone**. A column covered only by a wider index
(`FULLTEXT (title, body)`) is not searchable by itself, so it gets no search operators; searching it is refused
with the reason.

### JSON

```graphql
{ product(where: { attributes: { contains: { color: "red" } } }) { id } }
{ product(where: { attributes: { hasKeys: ["color", "size"] } }) { id } }
```

Keys are quoted into the JSON path, so a key holding dots or spaces is a literal key.

## Writes

MySQL has no `RETURNING`. A write runs as several statements in one transaction, and the rows it wrote are read
back by primary key. A table needs a primary key for that; an insert must send it or let `AUTO_INCREMENT` generate
it.

### Upsert

```graphql
mutation {
  createCountry(
    input: { code: "VN", name: "Viet Nam" }
    onConflict: { constraint: "PRIMARY", update_columns: ["name"] }
  ) { code name }
}
```

`constraint` is `PRIMARY` or the name of a unique index. The write inserts when no row holds the sent key and
otherwise updates **that row alone** with the values sent for `update_columns`. It does not use
`ON DUPLICATE KEY UPDATE`, which would update whichever row any unique key collides with. If the insert collides
on a different unique key, it fails as a unique violation and changes nothing.

Differences from PostgreSQL's `ON CONFLICT`:

* every `update_columns` column must be in `input` (PostgreSQL would take its default); otherwise the request is refused;
* every column of the constraint must be sent, else the write is a plain insert;
* an unknown constraint name is refused, naming the unique indexes it could have been.

With permissions, an upsert needs both insert and update rights, the update permission's columns, presets and row
filter decide which existing rows may be overwritten (a row the filter hides is left as it is), and every written
row must pass the insert and update checks.

REST's `on_conflict` and `Prefer: resolution=merge-duplicates` do the same.

## Aggregates

```graphql
{ ordersAggregate { count sum { total } avg { total } min { total } max { total } } }
```

The shape is the same as PostgreSQL's: nested per column.
