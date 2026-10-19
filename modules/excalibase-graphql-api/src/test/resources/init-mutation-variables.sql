-- Fixture for variables placed inside inline mutation inputs and filters (EXC-637).

CREATE TABLE projects (
    id   BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE tasks (
    id         BIGSERIAL PRIMARY KEY,
    project_id BIGINT REFERENCES projects (id),
    title      TEXT NOT NULL UNIQUE,
    author     TEXT,
    status     TEXT NOT NULL DEFAULT 'todo' CHECK (status IN ('todo', 'doing', 'done')),
    priority   INT,
    meta       JSONB
);

INSERT INTO projects (name) VALUES ('seed');
INSERT INTO tasks (project_id, title, author, status, meta) VALUES
    (1, 'seed-update', 'alice', 'todo', '{"tag": "seed"}'),
    (1, 'seed-collection', 'alice', 'todo', NULL),
    (1, 'seed-upsert', 'alice', 'todo', NULL),
    (1, 'seed-unset', 'alice', 'todo', NULL),
    (1, 'seed-delete', 'carol', 'todo', '{"tag": "gone"}');
