-- Fixture for variables placed inside inline mutation inputs and filters on MySQL (EXC-637).

CREATE TABLE tasks (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    title    VARCHAR(100) NOT NULL UNIQUE,
    author   VARCHAR(100),
    status   VARCHAR(10) NOT NULL DEFAULT 'todo',
    priority INT,
    meta     JSON,
    CONSTRAINT tasks_status_check CHECK (status IN ('todo', 'doing', 'done'))
);

INSERT INTO tasks (title, author, status) VALUES
    ('seed-update', 'alice', 'todo'),
    ('seed-unset', 'alice', 'todo'),
    ('seed-delete', 'carol', 'todo');
