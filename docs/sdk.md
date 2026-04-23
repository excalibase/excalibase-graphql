# SDK — @excalibase/sdk

!!! info "Pre-release"
    `@excalibase/sdk` v0.1 is in active development. Install from GitHub for
    now; npm publish is scheduled with the next platform release.

Official TypeScript client for Excalibase. Single package covers **auth**,
**GraphQL**, **REST**, and **NoSQL** surfaces with typed helpers and a
persistent refresh-token session.

[:octicons-mark-github-16: excalibase/excalibase-sdk-js](https://github.com/excalibase/excalibase-sdk-js){ .md-button .md-button--primary }

## Why a first-party SDK

- **Auth done right** — password login, refresh-before-expiry, OAuth2 `/token`
  flow, API keys. No manual JWT wrangling.
- **One client for three protocols** — pick GraphQL, REST, or NoSQL per call;
  same auth, same headers, same error types.
- **Typed search / vector** — `search`, `webSearch`, `vectorSearch` are
  first-class; autocomplete on the operator shape.
- **Works in the browser and Node** (≥18). ESM + CJS. Sub-10 KB gzipped core.
- **Codegen** — `excalibase-codegen` points at your running server and emits
  TypeScript types for every table. Your client code becomes schema-aware.

## Install

```bash
npm install @excalibase/sdk graphql-request
```

## Create a client

```ts
import { createClient, localStorageAdapter } from "@excalibase/sdk";

const db = createClient({
  url: "https://api.example.com",
  projectId: "acme/prod",
  publishableKey: "esk_pub_...",
  storage: localStorageAdapter(),  // or memoryStorageAdapter() for SSR
  autoRefreshToken: true,
});
```

## Auth

```ts
await db.auth.signUp({ email, password, fullName });
await db.auth.signInWithPassword({ email, password });
await db.auth.signOut();

db.auth.onAuthStateChange((event, session) => { /* ... */ });
```

Supports API key exchange, OAuth2-style `/token`, and automatic refresh
before JWT expiry. See the [SDK README][sdk] for the full surface.

## GraphQL

```ts
const issues = await db.graphql.query<{ kanbanIssues: Issue[] }>(`
  {
    kanbanIssues(
      where: { status: { eq: "open" }, search_vec: { search: "stripe" } },
      limit: 10
    ) { id title priority }
  }
`);

await db.graphql.mutation<{ createKanbanIssue: Issue }>(`
  mutation ($input: CreateKanbanIssueInput!) {
    createKanbanIssue(input: $input) { id }
  }
`, { input: { title: "..." } });
```

Vector k-NN:

```ts
const nearby = await db.graphql.query(`
  {
    kanbanIssues(
      vector: { column: "embedding", near: [0.1, 0.2, 0.3], distance: "COSINE", limit: 5 }
    ) { id title }
  }
`);
```

## REST

```ts
// GET /api/v1/rest/customer?status=eq.active&limit=10
const customers = await db.rest.get("customer", {
  query: { status: "eq.active", limit: 10 },
});

// POST
await db.rest.post("customer", { body: { name: "Vu", status: "active" } });
```

## NoSQL

One-time schema sync (typically at deploy time):

```ts
await db.nosql.init({
  collections: {
    users: {
      fields: { email: "string", status: "string" },
      indexes: [
        { fields: ["email"], unique: true },
        { fields: ["status"] },
      ],
    },
    articles: {
      fields: {},
      indexes: [],
      search: "body",
    },
    docs: {
      fields: {},
      indexes: [],
      vector: { field: "embedding", dimensions: 1536 },
    },
  },
});
```

Then use collections anywhere in your app:

```ts
const users = db.collection("users");

await users.insertOne({ email: "vu@acme.com", status: "active" });
const active = await users.find({ status: "active" }, { limit: 20 });

// Full-text search
const hits = await db.collection("articles").search("tsvector tsquery", { limit: 10 });

// Vector k-NN
const nearest = await db.collection("docs").vectorSearch([0.1, 0.2, 0.3], { topK: 5 });
```

See [NoSQL pagination](nosql/pagination.md) for cursor mode, and
[NoSQL validation](nosql/validation.md) for JSON Schema.

## Codegen

Point `excalibase-codegen` at a running server and regenerate types whenever
your schema changes:

```bash
npx excalibase-codegen \
  --url http://localhost:10000 \
  --out src/generated/db.ts
```

Your generated `Database` type is the single source of truth for what
operators and fields are available on your specific DB. The SDK's
`createClient<Database>` picks up the generics and every query / filter is
type-checked at compile time.

## Errors

```ts
import { AuthError, NetworkError, ExcalibaseError } from "@excalibase/sdk";

try {
  await db.graphql.query("{ bogus }");
} catch (e) {
  if (e instanceof AuthError) { /* 401/403 */ }
  else if (e instanceof NetworkError) { /* transport failure */ }
  else if (e instanceof ExcalibaseError) { /* server error payload */ }
}
```

## Realtime

WebSocket-based subscriptions land in a future SDK release — today the
server exposes `/api/v1/realtime` and GraphQL subscriptions, and any
`graphql-ws` or browser `WebSocket` client works. SDK-level
`db.realtime.channel(...).on(...)` helpers are on the roadmap.

See **[Realtime protocol](nosql/realtime.md)** for the wire format meanwhile.

## Roadmap

- [ ] `db.realtime` helpers (mirrors Supabase's `.channel().on()` shape)
- [ ] Python SDK
- [ ] Dart SDK (Flutter-first)
- [ ] Typed filter builder variants per DB (PostgreSQL-only ops gated by `Database` generic)

## Links

- **GitHub**: [excalibase/excalibase-sdk-js][sdk]
- **npm** *(pending)*: `@excalibase/sdk`
- **Issues / feedback**: [GitHub Issues](https://github.com/excalibase/excalibase-sdk-js/issues)

[sdk]: https://github.com/excalibase/excalibase-sdk-js
