-- Basic table for CRUD tests
CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    price NUMERIC(10,2) NOT NULL,
    category TEXT,
    in_stock BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT now()
);

INSERT INTO products (name, price, category, in_stock) VALUES
    ('Widget', 9.99, 'tools', true),
    ('Gadget', 19.99, 'electronics', true),
    ('Gizmo', 29.99, 'electronics', false),
    ('Thingamajig', 4.99, 'tools', true),
    ('Doohickey', 14.99, 'gadgets', true);

-- Users + Orders for relationship tests
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL
);

CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    product_id INTEGER REFERENCES products(id),
    quantity INTEGER NOT NULL DEFAULT 1,
    total NUMERIC(10,2) NOT NULL,
    status TEXT DEFAULT 'pending'
);

INSERT INTO users (name, email) VALUES
    ('Alice', 'alice@test.com'),
    ('Bob', 'bob@test.com');

INSERT INTO orders (user_id, product_id, quantity, total, status) VALUES
    (1, 1, 2, 19.98, 'completed'),
    (1, 2, 1, 19.99, 'pending'),
    (2, 3, 3, 89.97, 'completed'),
    (2, 1, 1, 9.99, 'shipped');

-- JSONB table
CREATE TABLE settings (
    id SERIAL PRIMARY KEY,
    key TEXT NOT NULL,
    value JSONB NOT NULL
);

INSERT INTO settings (key, value) VALUES
    ('theme', '{"mode": "dark", "color": "blue"}'),
    ('notifications', '{"email": true, "push": false}');

-- Composite key table
CREATE TABLE tags (
    entity_type TEXT NOT NULL,
    entity_id INTEGER NOT NULL,
    tag TEXT NOT NULL,
    PRIMARY KEY (entity_type, entity_id, tag)
);

INSERT INTO tags VALUES
    ('product', 1, 'popular'),
    ('product', 1, 'sale'),
    ('product', 2, 'new'),
    ('user', 1, 'admin');

-- View
CREATE VIEW expensive_products AS
    SELECT id, name, price FROM products WHERE price > 15;

CREATE OR REPLACE FUNCTION add_numbers(a integer, b integer)
RETURNS integer LANGUAGE sql IMMUTABLE AS $$
    SELECT a + b;
$$;
