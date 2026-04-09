CREATE SCHEMA tenant;

CREATE TABLE tenant.products (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    price NUMERIC(10,2) NOT NULL
);

INSERT INTO tenant.products (name, price) VALUES
    ('Widget A', 9.99),
    ('Gadget A', 19.99),
    ('Gizmo A', 29.99);
