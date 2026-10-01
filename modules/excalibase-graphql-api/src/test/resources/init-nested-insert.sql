-- Fixture for nested inserts: customers ← orders ← order_items ← item_notes.

CREATE TABLE customers (
    id   SERIAL PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE orders (
    id          SERIAL PRIMARY KEY,
    customer_id INT REFERENCES customers (id),
    status      TEXT NOT NULL DEFAULT 'new',
    note        TEXT,
    source      TEXT NOT NULL DEFAULT 'unknown'
);

CREATE TABLE order_items (
    id       SERIAL PRIMARY KEY,
    order_id INT NOT NULL REFERENCES orders (id),
    sku      TEXT NOT NULL,
    qty      INT NOT NULL,
    hidden   BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE item_notes (
    id      SERIAL PRIMARY KEY,
    item_id INT NOT NULL REFERENCES order_items (id),
    body    TEXT NOT NULL
);

-- The native row-security oracle runs the same inserts as this role.
CREATE ROLE oracle_role NOLOGIN;
GRANT USAGE ON SCHEMA public TO oracle_role;
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO oracle_role;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO oracle_role;
