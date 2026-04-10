CREATE SCHEMA rest_test;

-- Basic table for CRUD tests
CREATE TABLE rest_test.products (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    price NUMERIC(10,2) NOT NULL,
    category TEXT,
    in_stock BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT now()
);

INSERT INTO rest_test.products (name, price, category, in_stock) VALUES
    ('Widget', 9.99, 'tools', true),
    ('Gadget', 19.99, 'electronics', true),
    ('Gizmo', 29.99, 'electronics', false),
    ('Thingamajig', 4.99, 'tools', true),
    ('Doohickey', 14.99, 'gadgets', true);

-- Users + Orders for relationship tests
CREATE TABLE rest_test.users (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL
);

CREATE TABLE rest_test.orders (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES rest_test.users(id),
    product_id INTEGER REFERENCES rest_test.products(id),
    quantity INTEGER NOT NULL DEFAULT 1,
    total NUMERIC(10,2) NOT NULL,
    status TEXT DEFAULT 'pending'
);

INSERT INTO rest_test.users (name, email) VALUES
    ('Alice', 'alice@test.com'),
    ('Bob', 'bob@test.com');

INSERT INTO rest_test.orders (user_id, product_id, quantity, total, status) VALUES
    (1, 1, 2, 19.98, 'completed'),
    (1, 2, 1, 19.99, 'pending'),
    (2, 3, 3, 89.97, 'completed'),
    (2, 1, 1, 9.99, 'shipped');

-- JSONB table
CREATE TABLE rest_test.settings (
    id SERIAL PRIMARY KEY,
    key TEXT NOT NULL,
    value JSONB NOT NULL
);

INSERT INTO rest_test.settings (key, value) VALUES
    ('theme', '{"mode": "dark", "color": "blue"}'),
    ('notifications', '{"email": true, "push": false}');

-- Composite key table
CREATE TABLE rest_test.tags (
    entity_type TEXT NOT NULL,
    entity_id INTEGER NOT NULL,
    tag TEXT NOT NULL,
    PRIMARY KEY (entity_type, entity_id, tag)
);

INSERT INTO rest_test.tags VALUES
    ('product', 1, 'popular'),
    ('product', 1, 'sale'),
    ('product', 2, 'new'),
    ('user', 1, 'admin');

-- View
CREATE VIEW rest_test.expensive_products AS
    SELECT id, name, price FROM rest_test.products WHERE price > 15;

CREATE OR REPLACE FUNCTION rest_test.add_numbers(a integer, b integer)
RETURNS integer LANGUAGE sql IMMUTABLE AS $$
    SELECT a + b;
$$;

