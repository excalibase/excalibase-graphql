CREATE SCHEMA tenant;

CREATE TABLE tenant.items (
    id SERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    quantity INTEGER NOT NULL
);

INSERT INTO tenant.items (title, quantity) VALUES
    ('Item X', 10),
    ('Item Y', 20);
