-- Multi-schema test: two schemas in the same database with cross-schema FK

CREATE SCHEMA IF NOT EXISTS schema_a;
CREATE SCHEMA IF NOT EXISTS schema_b;

-- schema_a: users table
CREATE TABLE schema_a.users (
    user_id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100)
);

-- schema_b: orders table with FK to schema_a.users
CREATE TABLE schema_b.orders (
    order_id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES schema_a.users(user_id),
    amount NUMERIC(10,2) NOT NULL
);

-- Seed data
INSERT INTO schema_a.users (name, email) VALUES
    ('Alice', 'alice@test.com'),
    ('Bob', 'bob@test.com');

INSERT INTO schema_b.orders (user_id, amount) VALUES
    (1, 99.99),
    (1, 50.00),
    (2, 75.50);
