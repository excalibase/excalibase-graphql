-- MySQL fixture for engine-driven RLS + CLS (dialect-aware backtick quoting).
-- No native MySQL row security exists, so any filtering observed proves the
-- engine composed the WHERE with backtick-quoted identifiers. owner is a plain
-- string (FieldType.STRING) to keep the test off UUID/temporal binding paths.

CREATE TABLE rls_docs (
    id     INT PRIMARY KEY,
    owner  VARCHAR(64) NOT NULL,
    secret VARCHAR(64) NOT NULL,
    title  VARCHAR(64) NOT NULL
);

INSERT INTO rls_docs (id, owner, secret, title) VALUES
    (1, 'alice', 'a-secret-1', 'alice-doc-1'),
    (2, 'alice', 'a-secret-2', 'alice-doc-2'),
    (3, 'bob',   'b-secret-1', 'bob-doc-1');

-- Second table, used by the exposure tests: a caller granted rls_docs only must
-- not see this one at all.
CREATE TABLE rls_notes (
    id   INT PRIMARY KEY,
    body VARCHAR(64) NOT NULL
);

INSERT INTO rls_notes (id, body) VALUES (1, 'note-1');
