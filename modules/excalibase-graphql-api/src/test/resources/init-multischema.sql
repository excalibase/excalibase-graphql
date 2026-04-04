-- Multi-schema test: two schemas with cross-schema FK, enums, views, procs

CREATE SCHEMA IF NOT EXISTS schema_a;
CREATE SCHEMA IF NOT EXISTS schema_b;

-- schema_a: users table + enum + view + stored proc
CREATE TYPE schema_a.status_type AS ENUM ('ACTIVE', 'INACTIVE');

CREATE TABLE schema_a.users (
    user_id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    status schema_a.status_type DEFAULT 'ACTIVE'
);

CREATE VIEW schema_a.active_users AS
    SELECT * FROM schema_a.users WHERE status = 'ACTIVE';

-- Mock extension view (should be excluded from introspection)
CREATE VIEW schema_a.pg_stat_statements AS SELECT 1 AS dummy;

CREATE OR REPLACE PROCEDURE schema_a.reset_user(IN p_id INTEGER)
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE schema_a.users SET status = 'ACTIVE' WHERE user_id = p_id;
END;
$$;

-- schema_b: orders table with FK to schema_a.users
CREATE TABLE schema_b.orders (
    order_id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES schema_a.users(user_id),
    amount NUMERIC(10,2) NOT NULL
);

-- Seed data
INSERT INTO schema_a.users (name, status) VALUES ('Alice', 'ACTIVE'), ('Bob', 'INACTIVE');
INSERT INTO schema_b.orders (user_id, amount) VALUES (1, 99.99), (1, 50.00), (2, 75.50);
