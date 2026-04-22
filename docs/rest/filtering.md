# REST — Filtering

PostgREST-style filter operators work on every column of every table. The
shape is always `?<column>=<operator>.<value>`. Filters compose with an
implicit `AND`. Negate any filter by prefixing `not.`:
`?<column>=not.<operator>.<value>`.

!!! info "Which operators work on my database?"
    Operator availability varies by backend. See the **[database compatibility matrix](../database-compatibility.md)** for the canonical list — the same engine drives both REST and GraphQL, so the matrix applies to both.

For the GraphQL equivalent of every operator below, see
[GraphQL filtering](../graphql/filtering.md). For when to pick one protocol
over the other, see [Choose Your Protocol](../choose-your-protocol.md).

## Quickstart

```bash
# Eq
GET /api/v1/issues?status=eq.todo

# Range
GET /api/v1/issues?story_points=gte.5&story_points=lte.13

# In list
GET /api/v1/issues?status=in.(todo,in_progress,done)

# Null check
GET /api/v1/issues?parent_issue_id=is.null

# Text search
GET /api/v1/issues?description=plfts.payment

# Multi-filter (AND)
GET /api/v1/issues?status=eq.todo&priority=eq.high&story_points=gte.3
```

Every example below runs against a live stack — URL-encode special characters
(`{`, `}`, `(`, `)`, spaces, non-ASCII) with `encodeURIComponent` before
sending. Tomcat rejects raw reserved characters in query strings with a 400
before the request reaches the controller.

## Equality and comparison

| Op | SQL | Example |
|---|---|---|
| `eq` | `col = :v` | `?status=eq.todo` |
| `neq` | `col != :v` | `?status=neq.done` |
| `gt` | `col > :v` | `?story_points=gt.5` |
| `gte` | `col >= :v` | `?story_points=gte.5` |
| `lt` | `col < :v` | `?story_points=lt.13` |
| `lte` | `col <= :v` | `?story_points=lte.13` |

```bash
# Integer comparisons
GET /api/v1/issues?story_points=gte.5&story_points=lt.13

# String equality
GET /api/v1/customers?email=eq.alice@shop.com
```

## List membership

| Op | SQL | Example |
|---|---|---|
| `in.(v1,v2,...)` | `col IN (:v1, :v2, ...)` | `?status=in.(todo,done)` |
| `notin.(v1,v2,...)` | `col NOT IN (:v1, :v2, ...)` | `?id=notin.(1,2,3)` |

```bash
# Enum column
GET /api/v1/issues?status=in.(todo,done)

# Integer column
GET /api/v1/issues?id=in.(1,2,3)

# Compose with other filters
GET /api/v1/issues?status=in.(todo,done)&priority=eq.high
```

!!! note "Both `in` and `notin` coerce values through the column type"
    Int columns bind as `INT`, enum columns bind with a Postgres enum cast,
    text columns bind as `TEXT`. Values go through the same type-conversion
    path as single-value operators like `eq`.

## Null checks

| Op | SQL | Example |
|---|---|---|
| `is.null` | `col IS NULL` | `?parent_issue_id=is.null` |
| `is.notnull` | `col IS NOT NULL` | `?parent_issue_id=is.notnull` |
| `not.is.null` | `col IS NOT NULL` | `?parent_issue_id=not.is.null` |

```bash
GET /api/v1/issues?parent_issue_id=is.null       # top-level issues
GET /api/v1/issues?parent_issue_id=is.notnull    # child issues only
```

## Text operators

| Op | SQL | Example |
|---|---|---|
| `like` | `col LIKE :v` (use `*` for `%`) | `?title=like.*Setup*` |
| `ilike` | `col ILIKE :v` | `?title=ilike.*setup*` |
| `startswith` | `col LIKE 'prefix%'` | `?title=startswith.Setup` |
| `endswith` | `col LIKE '%suffix'` | `?title=endswith.auth` |
| `match` | `col ~ :v` (POSIX regex, case-sensitive) | `?title=match.%5ESetup` |
| `imatch` | `col ~* :v` (POSIX regex, case-insensitive) | `?title=imatch.%5Esetup` |

```bash
# LIKE with wildcards — use * instead of %
GET /api/v1/issues?title=like.*Setup*

# Case-insensitive prefix search
GET /api/v1/issues?title=ilike.setup*

# POSIX regex (^Setup anchor)
GET /api/v1/issues?title=match.%5ESetup      # url-encoded ^
```

!!! warning "`^`, `{`, `}`, `[`, `]` must be url-encoded"
    Tomcat rejects unencoded reserved characters in query strings with a
    `400 Bad Request` before the controller sees them. In JS use
    `encodeURIComponent(value)`.

## JSON operators (jsonb)

| Op | SQL | Example |
|---|---|---|
| `haskey` | `col ? :key` (via `jsonb_exists`) | `?address=haskey.city` |
| `jsoncontains` / `cs` | `col @> :v::jsonb` | `?address=cs.%7B%22city%22%3A%22NY%22%7D` |
| `jsoncontained` / `cd` | `col <@ :v::jsonb` | `?metadata=cd.%7B%22brand%22%3A%22Apple%22%7D` |
| `jsonpath` | `jsonb_path_exists(col, :v::jsonpath)` | `?metadata=jsonpath.%24.brand` |
| `jsonpathexists` | alias for `jsonpath` | `?metadata=jsonpathexists.%24.price` |

```bash
# Key exists
GET /api/v1/customers?address=haskey.city

# Containment — value is a URL-encoded JSON blob
# {"city":"New York"} → %7B%22city%22%3A%22New%20York%22%7D
GET /api/v1/customers?address=cs.%7B%22city%22%3A%22New%20York%22%7D

# Jsonpath existence
GET /api/v1/products?metadata=jsonpath.%24.brand
```

!!! note "`jsonpath` uses the function form, not the `@?` operator"
    Postgres's `@?` operator contains a `?` character which clashes with
    Spring's `NamedParameterJdbcTemplate` placeholder parser. The compiler
    emits `jsonb_path_exists(col, :v::jsonpath)` instead — same semantics,
    no parser collision.

## Array operators (for `text[]`, `int[]`, etc.)

| Op | SQL | Example |
|---|---|---|
| `arraycontains` / `arrayhasall` | `col @> :v` | `?tags=arraycontains.%7Bnew,featured%7D` |
| `arrayhasany` / `ov` | `col && :v` | `?tags=arrayhasany.%7Bnew,clearance%7D` |
| `arraylength` | `array_length(col, 1) = :n` | `?tags=arraylength.3` |

```bash
# Contains all listed tags
GET /api/v1/patients?allergies=arraycontains.%7Bpenicillin,aspirin%7D

# Has any of the listed tags
GET /api/v1/patients?allergies=arrayhasany.%7Bpenicillin,aspirin%7D

# Exact array length
GET /api/v1/patients?allergies=arraylength.2
```

!!! note "Array literal syntax"
    Use Postgres array literal form `{a,b,c}` — **not** `[a,b,c]`. Element
    coercion happens through the column type, so `text[]` columns bind
    text elements and `int[]` columns bind integers.

## Full-text search (tsvector columns)

| Op | Postgres function | Use when |
|---|---|---|
| `plfts` | `plainto_tsquery` | Default search box — raw user input, always safe |
| `wfts` | `websearch_to_tsquery` | Users may type `"phrase"` / `OR` / `-exclude` |
| `phfts` | `phraseto_tsquery` | Words must be adjacent in the document |
| `fts` | `to_tsquery` | Raw tsquery syntax (`foo & bar \| baz`), throws on bad input |

```bash
GET /api/v1/issues?search_vec=plfts.stripe
GET /api/v1/issues?search_vec=wfts.stripe%20OR%20benchmarks
GET /api/v1/issues?search_vec=phfts.webhook%20handler
GET /api/v1/issues?search_vec=fts.jwt%20%26%20auth
```

See the [Full-Text & Vector Search guide](../features/search-and-vector.md)
for the full tsvector setup (generated columns, GIN indexes) and how to
pick between the variants.

## Vector k-NN (pgvector columns)

The `vector` operator replaces the query's `ORDER BY` and `LIMIT` with a k-NN
similarity search. The value is a URL-encoded JSON blob:

```bash
# URL-encoded shape (readable form):
GET /api/v1/issues?embedding=vector.{"near":[0.1,0.2,0.3],"distance":"COSINE","limit":5}
```

```javascript
// From JavaScript — encode the JSON blob
const vec = { near: [0.1, 0.2, 0.3], distance: "COSINE", limit: 5 };
const url = `/api/v1/issues?embedding=vector.${encodeURIComponent(JSON.stringify(vec))}`;
```

**Field reference:**

| Field | Required | Description |
|---|---|---|
| `near` | yes | Query embedding (array of floats matching column dimensionality) |
| `distance` | no | `L2` (default) / `COSINE` / `IP`. Aliases: `EUCLIDEAN`, `INNER_PRODUCT` |
| `limit` | no | Result count, clamped to `app.max-rows` (default 30) |

Vector combines with other filters — they run as `WHERE` predicates, then
the k-NN clause orders the survivors:

```bash
GET /api/v1/issues?status=eq.todo&embedding=vector.{"near":[0.1,0.2,0.3],"limit":5}
```

See the [Full-Text & Vector Search guide](../features/search-and-vector.md)
for distance metric tradeoffs, index tuning (HNSW), and REST vs GraphQL
syntax differences.

## Composing filters (AND / OR)

**AND** (default): every filter in the query string is ANDed together.

```bash
GET /api/v1/issues?status=eq.todo&priority=eq.high&story_points=gte.5
# WHERE status = 'todo' AND priority = 'high' AND story_points >= 5
```

**OR** is supported via PostgREST-style logical trees on the `or` parameter:

```bash
GET /api/v1/issues?or=(status.eq.todo,priority.eq.critical)
# WHERE (status = 'todo' OR priority = 'critical')
```

Nest OR inside AND:

```bash
GET /api/v1/issues?project_id=eq.1&or=(status.eq.todo,status.eq.in_progress)
# WHERE project_id = 1 AND (status = 'todo' OR status = 'in_progress')
```

Nesting parentheses deeper is allowed.

## Ordering

```bash
GET /api/v1/issues?order=id.desc
GET /api/v1/issues?order=priority.desc,created_at.asc
GET /api/v1/issues?order=story_points.desc.nullslast
```

Supported suffixes: `.asc`, `.desc`, `.nullsfirst`, `.nullslast`.

## Compose with pagination + projection

```bash
GET /api/v1/issues\
?select=id,title,status\
&status=in.(todo,in_progress)\
&order=id.desc\
&limit=20\
&offset=0
```

For `Content-Range` headers + `Prefer: count=exact` total counts, see
[Pagination](pagination.md).

## What's NOT supported in REST

- **GraphQL enum filter narrowing** — REST values are always strings, so
  invalid enum values produce Postgres errors instead of schema-validation
  errors. Prefer GraphQL when client-side type safety matters.
- **Nested projection with FK traversal** — REST returns flat rows. For
  nested shapes use GraphQL or call REST multiple times.
- **Aggregate functions in one call** — GraphQL's `<Table>Aggregate { count sum avg min max }`
  has no direct REST equivalent; the closest is `Prefer: count=exact` for
  total row count only.
- **WebSocket subscriptions** — GraphQL only (see
  [Real-Time Subscriptions](../features/subscriptions.md)).
