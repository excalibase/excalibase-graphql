-- Fixture for Postgres array columns over GraphQL and REST (EXC-638).

CREATE TABLE tasks (
    id     BIGSERIAL PRIMARY KEY,
    title  TEXT NOT NULL UNIQUE,
    labels TEXT[],
    scores INT[]
);

INSERT INTO tasks (title, labels, scores) VALUES
    ('seed-bug-ui', '{bug,ui}', '{1,2}'),
    ('seed-bug', '{bug}', '{3}'),
    ('seed-docs', '{docs}', '{}'),
    ('seed-update', '{old}', NULL),
    ('seed-rest-patch', '{old}', NULL);
