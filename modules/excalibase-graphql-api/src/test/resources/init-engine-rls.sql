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
