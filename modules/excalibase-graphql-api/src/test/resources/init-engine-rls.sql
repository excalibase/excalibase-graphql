-- Fixture for engine-driven (query-first) RLS, EXC-312.
--
-- Deliberately NO Postgres-native row-level security on this table: the
-- excalibase-rls engine must compose the WHERE that filters rows. If native
-- RLS were enabled the test couldn't distinguish engine filtering from DB
-- filtering. The connecting role is the container default superuser, so
-- without the engine every row is visible — which is exactly what makes the
-- filtering assertions meaningful.
--
-- A dedicated schema (not public) mirrors the existing JwtRlsIntegrationTest
-- so the table resolves to the same schema-prefixed GraphQL field shape:
-- rls_demo.docs → query field `rlsDemoDocs`, tableName key `rls_demo.docs`.

CREATE SCHEMA rls_demo;

CREATE TABLE rls_demo.docs (
    id        BIGINT PRIMARY KEY,
    owner_id  UUID  NOT NULL,
    title     TEXT  NOT NULL
);

INSERT INTO rls_demo.docs (id, owner_id, title) VALUES
    (1, '11111111-1111-1111-1111-111111111111', 'alice-1'),
    (2, '11111111-1111-1111-1111-111111111111', 'alice-2'),
    (3, '22222222-2222-2222-2222-222222222222', 'bob-1');

-- A writable sibling table for mutation RLS tests (UPDATE/DELETE/INSERT
-- WITH-CHECK), kept separate from `docs` so mutation state never perturbs the
-- exact row-count assertions in the read tests. id is caller-supplied (no
-- sequence) so inserts use explicit, collision-free ids.
CREATE TABLE rls_demo.notes (
    id        BIGINT PRIMARY KEY,
    owner_id  UUID  NOT NULL,
    title     TEXT  NOT NULL
);

INSERT INTO rls_demo.notes (id, owner_id, title) VALUES
    (1, '11111111-1111-1111-1111-111111111111', 'alice-note'),
    (2, '22222222-2222-2222-2222-222222222222', 'bob-note');

-- FK pair for nested-embed RLS (EXC-315): one shared shelf holds books owned
-- by different users. A policy on `book` (not `shelf`) must filter the embedded
-- books per caller, proving RLS reaches nested relations.
CREATE TABLE rls_demo.shelf (
    id   BIGINT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE rls_demo.book (
    id       BIGINT PRIMARY KEY,
    shelf_id BIGINT NOT NULL REFERENCES rls_demo.shelf(id),
    owner_id UUID  NOT NULL,
    title    TEXT  NOT NULL
);

INSERT INTO rls_demo.shelf (id, name) VALUES (1, 'main');
INSERT INTO rls_demo.book (id, shelf_id, owner_id, title) VALUES
    (10, 1, '11111111-1111-1111-1111-111111111111', 'alice-book-1'),
    (11, 1, '22222222-2222-2222-2222-222222222222', 'bob-book-1'),
    (12, 1, '11111111-1111-1111-1111-111111111111', 'alice-book-2');

-- NUMERIC + TIMESTAMPTZ table for type-precise bind tests (EXC-317): policies
-- compare against exact decimal and temporal values, so the bound RLS params
-- must carry BigDecimal / OffsetDateTime rather than lossy double / string.
CREATE TABLE rls_demo.ledger (
    id         BIGINT PRIMARY KEY,
    amount     NUMERIC(12,2) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL
);

INSERT INTO rls_demo.ledger (id, amount, created_at) VALUES
    (1, 100.00, now() - interval '10 days'),
    (2, 250.50, now()),
    (3,  99.99, now());
