-- Fixture for tracked functions (docs/features/permissions.md §6): set-returning, single-row,
-- session-argument, data-changing and SECURITY DEFINER functions over one owned table.

CREATE TABLE authors (
    id   INT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE notes (
    id        INT PRIMARY KEY,
    owner_id  TEXT NOT NULL,
    body      TEXT NOT NULL,
    secret    TEXT,
    author_id INT REFERENCES authors (id)
);

INSERT INTO authors (id, name) VALUES (1, 'ann'), (2, 'ben');

INSERT INTO notes (id, owner_id, body, secret, author_id) VALUES
    (1, 'u-1', 'alpha one',   's1', 1),
    (2, 'u-2', 'alpha two',   's2', 2),
    (3, 'u-1', 'beta three',  's3', 2);

CREATE FUNCTION search_notes(p_query text, p_limit int DEFAULT 100) RETURNS SETOF notes
    LANGUAGE sql STABLE AS $$ SELECT * FROM notes WHERE body LIKE p_query ORDER BY id LIMIT p_limit $$;

CREATE FUNCTION my_notes(session jsonb) RETURNS SETOF notes
    LANGUAGE sql STABLE AS $$ SELECT * FROM notes WHERE owner_id = session ->> 'x-excalibase-user-id' $$;

CREATE FUNCTION add_note(p_id int, p_body text, session jsonb) RETURNS SETOF notes
    LANGUAGE sql VOLATILE AS $$
        INSERT INTO notes (id, owner_id, body, secret)
        VALUES (p_id, session ->> 'x-excalibase-user-id', p_body, 'new') RETURNING * $$;

-- Writes one row for the caller and one for somebody else, and returns both.
CREATE FUNCTION add_note_pair(p_id int) RETURNS SETOF notes
    LANGUAGE sql VOLATILE AS $$
        INSERT INTO notes (id, owner_id, body) VALUES (p_id, 'u-1', 'mine'), (p_id + 1, 'u-2', 'theirs')
        RETURNING * $$;

CREATE FUNCTION first_note() RETURNS notes
    LANGUAGE sql STABLE SECURITY DEFINER AS $$ SELECT * FROM notes ORDER BY id LIMIT 1 $$;

CREATE FUNCTION no_note() RETURNS notes
    LANGUAGE sql STABLE AS $$ SELECT * FROM notes WHERE false $$;

CREATE FUNCTION untracked_notes() RETURNS SETOF notes
    LANGUAGE sql STABLE AS $$ SELECT * FROM notes $$;
