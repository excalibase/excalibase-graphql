# Full-Text Search & Vector Search

Excalibase exposes two search capabilities that light up automatically when the
underlying database has the right columns and extensions:

- **Full-text search (FTS)** — built on Postgres `tsvector` columns, works on
  stock Postgres with zero extensions.
- **Vector k-NN search** — built on [pgvector](https://github.com/pgvector/pgvector)
  `vector(N)` columns, works on any Postgres image that ships the `vector`
  extension (e.g. `pgvector/pgvector:pg16`).

Both surfaces are exposed through **GraphQL** and **REST** from the same
schema — define the column once in SQL and both protocols can query it.

---

## Full-Text Search

### 1. Add a tsvector column to your table

FTS works against a `tsvector` column. The easiest setup is a `GENERATED
ALWAYS` column that Postgres maintains automatically from one or more text
columns, plus a GIN index for speed:

```sql
CREATE TABLE kanban.issues (
    id          SERIAL PRIMARY KEY,
    title       TEXT NOT NULL,
    description TEXT,
    -- ... other columns ...
    search_vec  tsvector GENERATED ALWAYS AS (
        to_tsvector('english', title || ' ' || coalesce(description, ''))
    ) STORED
);

CREATE INDEX issues_search_idx ON kanban.issues USING GIN(search_vec);
```

Excalibase detects the column on schema introspection and exposes it under a
`TsvectorFilterInput` with four operators.

### 2. GraphQL surface

Four operators live inside the column filter:

| Operator | Postgres function | Syntax accepted | Safe on bad input? |
|---|---|---|---|
| `search` | `plainto_tsquery` | Raw user text — any order, AND-joined, stems + drops stop words | yes |
| `webSearch` | `websearch_to_tsquery` | Google-style: `"phrase"`, `OR`, `-exclusion` | yes |
| `phraseSearch` | `phraseto_tsquery` | Words must be adjacent in the document in the given order | yes |
| `rawSearch` | `to_tsquery` | Raw tsquery: `foo & bar \| baz`, `!word`, `word:*` | **no — throws on malformed input** |

**Plain search** — default for a search box:

```graphql
{
  kanbanIssues(where: { search_vec: { search: "stripe payment" } }) {
    id
    title
  }
}
```

**Google-style search** — when users might type operators:

```graphql
# Alternation
{ kanbanIssues(where: { search_vec: { webSearch: "stripe OR benchmarks" } }) { id title } }

# Exclusion
{ kanbanIssues(where: { search_vec: { webSearch: "payment -stripe" } }) { id title } }

# Exact phrase
{ kanbanIssues(where: { search_vec: { webSearch: "\"webhook handler\"" } }) { id title } }

# Combined
{ kanbanIssues(where: { search_vec: { webSearch: "cat \"dog house\" OR rat -mouse" } }) { id title } }
```

**Phrase search** — words must appear adjacent in the document in the given order:

```graphql
# Matches "Stripe webhook handler" (adjacent, in order)
{ kanbanIssues(where: { search_vec: { phraseSearch: "webhook handler" } }) { id title } }

# Returns empty — wrong order
{ kanbanIssues(where: { search_vec: { phraseSearch: "handler webhook" } }) { id title } }
```

**Raw tsquery** — when you need full tsquery syntax (power-search expressions
generated server-side, saved searches, etc.). Throws on malformed input so
never route untrusted user text here:

```graphql
# AND
{ kanbanIssues(where: { search_vec: { rawSearch: "stripe & webhook" } }) { id title } }

# OR alternation
{ kanbanIssues(where: { search_vec: { rawSearch: "jwt | stripe" } }) { id title } }

# Prefix match — anything starting with "stri"
{ kanbanIssues(where: { search_vec: { rawSearch: "stri:*" } }) { id title } }
```

FTS composes with every other `where` predicate via implicit AND:

```graphql
{
  kanbanIssues(where: {
    search_vec: { search: "payment" },
    priority:   { eq: critical }
  }) { id title }
}
```

**Choosing between them:**

| Use case | Operator |
|---|---|
| Default search box (any user input) | `search` |
| User can type `"phrase"` / `OR` / `-exclude` | `webSearch` |
| "Words in this exact order" — quoted phrase only | `phraseSearch` |
| Server-generated tsquery (saved searches, power users) | `rawSearch` |

All four are dispatched through `SqlDialect.fullTextSearchSql` so no user
input is ever interpolated into SQL — everything goes through bind
parameters. Only `rawSearch` can throw at runtime, and only when the caller
feeds it invalid tsquery syntax.

### 3. REST surface (PostgREST-compatible)

REST exposes four PostgREST-standard operators:

| Operator | Postgres function | When to use |
|---|---|---|
| `plfts` | `plainto_tsquery` | Plain user text — default search box |
| `phfts` | `phraseto_tsquery` | Phrase search — words must be adjacent |
| `wfts` | `websearch_to_tsquery` | Google-style `"phrase"` / `OR` / `-` |
| `fts` | `to_tsquery` | Raw tsquery syntax — power users only, throws on bad input |

```bash
# Plain
GET /api/v1/issues?description=plfts.stripe

# Google-style
GET /api/v1/issues?description=wfts.stripe OR benchmarks

# Phrase (adjacent words)
GET /api/v1/issues?description=phfts.webhook handler

# Combines with other filters
GET /api/v1/issues?description=plfts.payment&priority=eq.critical
```

---

## Vector k-NN Search

### 1. Install pgvector and add a vector column

```sql
-- Requires pgvector (pgvector/pgvector:pg16 or equivalent)
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE kanban.issues (
    id        SERIAL PRIMARY KEY,
    title     TEXT NOT NULL,
    -- ... other columns ...
    embedding vector(3)   -- or vector(384) / vector(1536) / whatever your model uses
);
```

Excalibase detects `pg_extension` at introspection time. If pgvector isn't
installed, the `vector` argument silently disappears from the schema and no
bad SQL is ever generated — you get a clean degradation on non-pgvector
deployments.

### 2. GraphQL surface

The `vector` argument sits **next to `where`, `orderBy`, `limit`** on the
table query — not inside `where`. It's not a filter (it doesn't drop rows),
it's an ordering clause that replaces the query's `ORDER BY` with k-NN
similarity and clamps the result set with its own `limit`.

```graphql
{
  kanbanIssues(vector: {
    column:   "embedding"
    near:     [0.0, 0.0, 1.0]
    distance: "L2"
    limit:    10
  }) {
    id
    title
  }
}
```

**Field reference:**

| Field | Type | Required | Description |
|---|---|---|---|
| `column` | `String` | yes | Name of the `vector(N)` column to search against |
| `near` | `[Float]` | yes | The query embedding — array of N floats matching the column's dimensionality |
| `distance` | `String` | no | `L2` (default) / `COSINE` / `IP`. Aliases: `EUCLIDEAN`, `INNER_PRODUCT` |
| `limit` | `Int` | no | Number of nearest neighbors to return, clamped to `app.max-rows` (30 by default) |

**Distance metrics:**

| Distance | pgvector operator | When to use |
|---|---|---|
| `L2` | `<->` | Euclidean distance — position matters. Best for normalized embeddings. |
| `COSINE` | `<=>` | Direction only, ignores magnitude. **Best default for neural embeddings** (OpenAI, Cohere, sentence-transformers). |
| `IP` | `<#>` | Negative dot product — largest product ranks first. Best when magnitude is meaningful. |

**Vector takes precedence over `orderBy`:**

```graphql
# id DESC would put id=15 first, but vector k-NN puts the nearest row first.
# The vector clause wins.
{
  kanbanIssues(
    vector:  { column: "embedding", near: [0.0, 0.0, 1.0], distance: "L2", limit: 3 }
    orderBy: { id: DESC }
  ) { id title }
}
```

**Vector combines with `where` filters** — WHERE drops rows, then vector
ranks the survivors:

```graphql
# Among high-priority issues, which are closest to the payment embedding?
{
  kanbanIssues(
    where:  { priority: { eq: "high" } }
    vector: { column: "embedding", near: [0.0, 0.0, 1.0], distance: "COSINE", limit: 5 }
  ) { id title priority }
}
```

### 3. REST surface

REST uses a new `vector` operator whose value is a URL-encoded JSON blob:

```bash
# URL shape (unencoded for readability):
GET /api/v1/issues?embedding=vector.{"near":[0,0,1],"distance":"L2","limit":3}

# What you actually send:
GET /api/v1/issues?embedding=vector.%7B%22near%22%3A%5B0%2C0%2C1%5D%2C%22distance%22%3A%22L2%22%2C%22limit%22%3A3%7D
```

**Why JSON in the query string?** Vector parameters don't fit the
`column=op.value` shape cleanly — the embedding itself is an array, the
distance is a separate choice, and the limit replaces the query's default
limit. JSON keeps everything in one self-contained operator value.

**Use `encodeURIComponent` in JavaScript** — Tomcat rejects raw `{`, `}`,
`[`, `]` in URLs with a 400 before the request reaches the controller:

```javascript
const vector = JSON.stringify({ near: [0.0, 0.0, 1.0], distance: "L2", limit: 3 });
const url = `/api/v1/issues?embedding=vector.${encodeURIComponent(vector)}`;
```

Vector composes with the rest of the REST query string:

```bash
# k-NN restricted to priority=high
GET /api/v1/issues?embedding=vector.{...}&priority=eq.high

# k-NN with a select projection
GET /api/v1/issues?select=id,title&embedding=vector.{...}
```

---

## Multi-schema deployments

In multi-tenant mode (`APP_SCHEMAS=shopify,kanban,clinic`), each tenant has
its own schema and Excalibase flattens them into one GraphQL surface with
`{schema}{Table}` naming. Extensions are **global to the database**, so if
`CREATE EXTENSION vector` runs on the shared database, every tenant's vector
column is queryable. Text search works the same way — there are no
per-tenant capability differences.

Schema changes (adding a `tsvector` column, installing pgvector, etc.)
propagate to the GraphQL schema within the cache TTL
(`app.cache.schema-ttl-minutes`, default 30 min). Restart the graphql
service for immediate reload in dev.

---

## Backend support

| Backend | FTS (`search` / `webSearch`) | Vector (`vector`) |
|---|---|---|
| **PostgreSQL (core)** | ✅ stock | ❌ needs pgvector extension |
| **pgvector/pgvector:pg16** | ✅ stock | ✅ |
| **MySQL** | ❌ not wired yet | ❌ not applicable |

MySQL support for `MATCH ... AGAINST` and MySQL 9+ `VECTOR` type is on the
roadmap but not shipped — the `SqlDialect` abstraction is ready to accept
both; only the `MysqlDialect` implementations are missing.

---

## Security notes

- Every user-supplied query string is **bind-parameterized**, never
  interpolated. FTS injection attempts are safely tokenized by
  `plainto_tsquery` / `websearch_to_tsquery` and the table stays intact.
- The `search` / `webSearch` / `vector` GraphQL operators are only attached
  to columns whose introspected type matches (`tsvector` for FTS, any
  `vector` column for k-NN). You cannot accidentally apply them to a
  mismatched column — they simply don't appear in the schema.
- Vector search has no built-in result-count ceiling beyond `app.max-rows`
  (default 30). If you expect larger result sets, raise that config value
  deliberately rather than bypassing the limit parameter.

---

## Performance tips

**FTS:**
- GIN-index your `tsvector` column — `CREATE INDEX ... USING GIN(col)`. Query
  planners rely on this for anything above ~10k rows.
- Prefer `GENERATED ALWAYS AS ... STORED` columns over application-level
  updates. The DB keeps the `tsvector` in sync and the index stays current.
- Use the `english` (or language-appropriate) text search config for
  stemming — `to_tsvector('english', ...)`. Without a language, Postgres
  falls back to `simple` which doesn't stem.

**Vector:**
- For datasets over ~10k rows, create an HNSW index:
  `CREATE INDEX ON tbl USING hnsw (embedding vector_cosine_ops);`
  (use `vector_l2_ops` / `vector_ip_ops` to match your distance metric).
- The `limit` field is clamped to `app.max-rows`. If you need top-100 results,
  raise `app.max-rows` in config — the server-side clamp is a safety limit,
  not a hard cap.
- Use `COSINE` for most neural embedding models. The others are niche.

---

## Example: end-to-end kanban demo

The `e2e/study-cases` stack ships a working FTS + vector demo on the kanban
tenant. Spin it up with:

```bash
make study-cases-up
```

Then explore:

```graphql
# Search for stripe-related issues (FTS)
{
  kanbanIssues(where: { search_vec: { search: "stripe" } }) {
    id title description
  }
}
```

```graphql
# Find the 3 issues most semantically similar to "payment" (vector k-NN)
{
  kanbanIssues(vector: {
    column: "embedding"
    near: [0.0, 0.0, 1.0]
    distance: "L2"
    limit: 3
  }) {
    id title
  }
}
```

The kanban schema seeds 15 issues with hand-picked 3-D embeddings that
cluster by category (auth / filters / payments), so the above query reliably
returns the payment cluster. Replace the 3-D vector with a real embedding
from your model of choice for production use.
