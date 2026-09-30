-- Fixture for insert without select: a contact form anon may write to but never read.

CREATE TABLE messages (
    id     SERIAL PRIMARY KEY,
    email  TEXT NOT NULL,
    body   TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'unknown'
);

INSERT INTO messages (email, body, source) VALUES ('seed@test.com', 'already here', 'seed');
